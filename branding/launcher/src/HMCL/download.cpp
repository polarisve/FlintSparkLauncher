#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <format>
#include <string>
#include <vector>

#include "config.h"
#include "debug.h"
#include "download.h"
#include "progress.h"

namespace {

constexpr DWORD kReadBufferSize = 128 * 1024;

// A mirror that cannot reach a usable throughput is abandoned quickly: the
// launcher must not spend minutes on a CDN edge that trickles bytes to a
// mainland China connection when another mirror can serve the same archive in
// seconds. 512 KiB per window is roughly 100 KiB/s, well below what any healthy
// mainland mirror delivers and far above a trickling foreign CDN.
constexpr ULONGLONG kStallWindowMs = 5000;
constexpr unsigned long long kStallWindowMinBytes = 512 * 1024;
constexpr int kMaxRedirects = 6;

struct HLParsedUrl {
  std::wstring host;
  std::wstring target;
  INTERNET_PORT port = 0;
  bool secure = false;
  bool defaultPort = true;
};

std::wstring HLFormatErrorMessage(DWORD code) {
  wchar_t *buffer = nullptr;
  DWORD size = FormatMessageW(
      FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, code,
      MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT), reinterpret_cast<LPWSTR>(&buffer), 0, nullptr);

  std::wstring message;
  if (size != 0 && buffer != nullptr) {
    message.assign(buffer, size);
    while (!message.empty() && (message.back() == L'\n' || message.back() == L'\r' || message.back() == L' ')) {
      message.pop_back();
    }
  }
  if (buffer != nullptr) {
    LocalFree(buffer);
  }

  if (message.empty()) {
    message = std::format(L"Win32 error {}", code);
  } else {
    message += std::format(L" (Win32 error {})", code);
  }
  return message;
}

bool HLParseUrl(const std::wstring &url, HLParsedUrl &result) {
  URL_COMPONENTS components{};
  components.dwStructSize = sizeof(components);

  wchar_t host[256]{};
  wchar_t path[8192]{};

  components.lpszHostName = host;
  components.dwHostNameLength = static_cast<DWORD>(std::size(host));
  components.lpszUrlPath = path;
  components.dwUrlPathLength = static_cast<DWORD>(std::size(path));

  if (!WinHttpCrackUrl(url.c_str(), static_cast<DWORD>(url.size()), 0, &components)) {
    return false;
  }

  result.host.assign(host, components.dwHostNameLength);
  result.target.assign(path, components.dwUrlPathLength);
  result.port = components.nPort;
  result.secure = components.nScheme == INTERNET_SCHEME_HTTPS;
  result.defaultPort = (result.secure && result.port == INTERNET_DEFAULT_HTTPS_PORT) ||
                       (!result.secure && result.port == INTERNET_DEFAULT_HTTP_PORT);
  return !result.host.empty();
}

std::wstring HLResolveRedirect(const HLParsedUrl &current, const std::wstring &location) {
  if (location.rfind(L"http://", 0) == 0 || location.rfind(L"https://", 0) == 0) {
    return location;
  }

  const std::wstring scheme = current.secure ? L"https" : L"http";

  if (location.rfind(L"//", 0) == 0) {
    return scheme + L":" + location;
  }

  std::wstring prefix = scheme + L"://" + current.host;
  if (!current.defaultPort) {
    prefix += L":" + std::to_wstring(current.port);
  }

  if (!location.empty() && location.front() == L'/') {
    return prefix + location;
  }

  const auto slash = current.target.find_last_of(L'/');
  if (slash == std::wstring::npos) {
    return prefix + L"/" + location;
  }
  return prefix + current.target.substr(0, slash + 1) + location;
}

std::wstring HLQueryHeaderString(HINTERNET request, DWORD query) {
  DWORD size = 0;
  if (!WinHttpQueryHeaders(request, query, WINHTTP_HEADER_NAME_BY_INDEX, WINHTTP_NO_OUTPUT_BUFFER, &size,
                           WINHTTP_NO_HEADER_INDEX) &&
      GetLastError() != ERROR_INSUFFICIENT_BUFFER) {
    return {};
  }

  if (size == 0) {
    return {};
  }

  std::wstring value(size / sizeof(wchar_t) + 1, L'\0');
  if (!WinHttpQueryHeaders(request, query, WINHTTP_HEADER_NAME_BY_INDEX, value.data(), &size,
                           WINHTTP_NO_HEADER_INDEX)) {
    return {};
  }

  while (!value.empty() && value.back() == L'\0') {
    value.pop_back();
  }
  return value;
}

DWORD HLQueryContentLength(HINTERNET request) {
  DWORD length = 0;
  DWORD size = sizeof(length);
  if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                           WINHTTP_HEADER_NAME_BY_INDEX, &length, &size, WINHTTP_NO_HEADER_INDEX)) {
    return 0;
  }
  return length;
}

void HLRemoveFileQuietly(const HLPath &path) {
  if (!path.path.empty()) {
    DeleteFileW(path.path.c_str());
  }
}

}  // namespace

HLDownloadResult HLDownloadFile(const std::wstring &url, const HLPath &destFile, const HLDownloadOptions &options) {
  HLDownloadResult result;

  HINTERNET session = WinHttpOpen(L"FlintSparkLauncher/" HMCL_LAUNCHER_VERSION, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (session == nullptr) {
    result.error = L"WinHttpOpen: " + HLFormatErrorMessage(GetLastError());
    return result;
  }
  // Connect/response timeouts are deliberately short: an unreachable CDN
  // must fail fast so that the next source is tried instead of stalling the
  // launcher for minutes. The receive timeout only limits idle gaps, so a slow
  // but live transfer is still allowed to finish.
  WinHttpSetTimeouts(session, 8000, 8000, 20000, 30000);

  HLProgressDialog dialog;
  dialog.Start(options.title, options.cancelMessage);
  dialog.SetLine(options.fileLabel);

  HINTERNET connection = nullptr;
  HINTERNET request = nullptr;
  HANDLE file = INVALID_HANDLE_VALUE;

  auto cleanup = [&]() {
    if (file != INVALID_HANDLE_VALUE) {
      CloseHandle(file);
      file = INVALID_HANDLE_VALUE;
    }
    if (request != nullptr) {
      WinHttpCloseHandle(request);
      request = nullptr;
    }
    if (connection != nullptr) {
      WinHttpCloseHandle(connection);
      connection = nullptr;
    }
  };

  std::wstring currentUrl = url;
  DWORD contentLength = 0;
  bool connected = false;

  for (int hop = 0; hop <= kMaxRedirects && !connected; ++hop) {
    HLParsedUrl parsed;
    if (!HLParseUrl(currentUrl, parsed)) {
      result.error = L"Invalid URL: " + currentUrl;
      break;
    }

    connection = WinHttpConnect(session, parsed.host.c_str(), parsed.port, 0);
    if (connection == nullptr) {
      result.error = L"WinHttpConnect: " + HLFormatErrorMessage(GetLastError());
      break;
    }

    request = WinHttpOpenRequest(connection, L"GET", parsed.target.c_str(), nullptr, WINHTTP_NO_REFERER,
                                 WINHTTP_DEFAULT_ACCEPT_TYPES, parsed.secure ? WINHTTP_FLAG_SECURE : 0);
    if (request == nullptr) {
      result.error = L"WinHttpOpenRequest: " + HLFormatErrorMessage(GetLastError());
      break;
    }

    if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(request, nullptr)) {
      result.error = L"WinHttp request failed: " + HLFormatErrorMessage(GetLastError());
      break;
    }

    DWORD statusCode = 0;
    DWORD statusSize = sizeof(statusCode);
    if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                             WINHTTP_HEADER_NAME_BY_INDEX, &statusCode, &statusSize, WINHTTP_NO_HEADER_INDEX)) {
      result.error = L"WinHttpQueryHeaders: " + HLFormatErrorMessage(GetLastError());
      break;
    }

    if (statusCode >= 300 && statusCode < 400 && statusCode != 304) {
      const std::wstring location = HLQueryHeaderString(request, WINHTTP_QUERY_LOCATION);
      if (location.empty()) {
        result.error = std::format(L"HTTP {} without a Location header", statusCode);
        break;
      }
      HLDebugLog(std::format(L"Java download redirected (HTTP {}) to {}", statusCode, location));
      cleanup();
      currentUrl = HLResolveRedirect(parsed, location);
      continue;
    }

    if (statusCode != 200 && statusCode != 206) {
      result.error = std::format(L"HTTP status {}", statusCode);
      break;
    }

    contentLength = HLQueryContentLength(request);
    connected = true;
  }

  if (!connected) {
    cleanup();
    dialog.Stop();
    if (result.error.empty()) {
      result.error = L"Too many redirections";
    }
    HLDebugLog(L"Java download failed: " + result.error);
    return result;
  }

  file = CreateFileW(destFile.path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    result.error = L"Failed to create " + destFile.path + L": " + HLFormatErrorMessage(GetLastError());
    cleanup();
    dialog.Stop();
    HLDebugLog(L"Java download failed: " + result.error);
    return result;
  }

  std::vector<BYTE> buffer(kReadBufferSize);
  unsigned long long downloaded = 0;
  bool cancelled = false;
  bool failed = false;
  ULONGLONG lastUpdate = 0;
  ULONGLONG stallTick = GetTickCount64();
  unsigned long long stallBytes = 0;

  for (;;) {
    DWORD read = 0;
    if (!WinHttpReadData(request, buffer.data(), static_cast<DWORD>(buffer.size()), &read)) {
      const DWORD error = GetLastError();
      if (error != ERROR_HANDLE_EOF) {
        result.error = L"WinHttpReadData: " + HLFormatErrorMessage(error);
        failed = true;
      }
      break;
    }

    if (read == 0) {
      break;
    }

    DWORD written = 0;
    if (!WriteFile(file, buffer.data(), read, &written, nullptr) || written != read) {
      result.error = L"Failed to write " + destFile.path + L": " + HLFormatErrorMessage(GetLastError());
      failed = true;
      break;
    }

    downloaded += written;

    const ULONGLONG now = GetTickCount64();
    if (now - lastUpdate >= 100 || downloaded == contentLength) {
      lastUpdate = now;
      if (contentLength != 0) {
        dialog.SetProgress(downloaded, contentLength);
      } else {
        dialog.SetLine(std::format(L"{}  ({} MB)", options.fileLabel, downloaded / (1024 * 1024)));
      }
      if (dialog.HasUserCancelled()) {
        cancelled = true;
        break;
      }
    }

    if (options.failOnSlowNetwork && now - stallTick >= kStallWindowMs &&
        (contentLength == 0 || downloaded < contentLength)) {
      const unsigned long long windowBytes = downloaded - stallBytes;
      if (windowBytes < kStallWindowMinBytes) {
        result.error = std::format(L"Download too slow ({} KB in {} s)", windowBytes / 1024, kStallWindowMs / 1000);
        failed = true;
        break;
      }
      stallTick = now;
      stallBytes = downloaded;
    }
  }

  cleanup();
  dialog.Stop();

  if (cancelled) {
    HLRemoveFileQuietly(destFile);
    HLDebugLog(L"Java download cancelled by the user");
    result.status = HLDownloadStatus::Cancelled;
    return result;
  }

  if (failed) {
    HLRemoveFileQuietly(destFile);
    HLDebugLog(L"Java download failed: " + result.error);
    return result;
  }

  if (downloaded == 0) {
    HLRemoveFileQuietly(destFile);
    result.error = L"The downloaded file is empty";
    HLDebugLog(L"Java download failed: " + result.error);
    return result;
  }

  if (contentLength != 0 && downloaded != contentLength) {
    HLRemoveFileQuietly(destFile);
    result.error = std::format(L"Incomplete download ({} of {} bytes)", downloaded, contentLength);
    HLDebugLog(L"Java download failed: " + result.error);
    return result;
  }

  HLDebugLog(std::format(L"Downloaded {} bytes from {}", downloaded, url));
  result.status = HLDownloadStatus::Succeeded;
  return result;
}

namespace {
// Redirects must be followed explicitly: WinHTTP defaults to refusing them, and the mirror
// listings answer 302 when the requested path is not spelled the way the index advertises.
bool HLEnableRedirects(HINTERNET request, std::wstring &error) {
  DWORD policy = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
  if (!WinHttpSetOption(request, WINHTTP_OPTION_REDIRECT_POLICY, &policy, sizeof(policy))) {
    error = L"WinHttpSetOption(redirect): " + HLFormatErrorMessage(GetLastError());
    return false;
  }
  return true;
}
}  // namespace

bool HLHttpGetText(const std::wstring &url, std::wstring &body, std::wstring &error) {
  body.clear();

  const std::wstring &narrow = url;
  URL_COMPONENTS components{};
  components.dwStructSize = sizeof(components);
  components.dwSchemeLength = static_cast<DWORD>(-1);
  components.dwHostNameLength = static_cast<DWORD>(-1);
  components.dwUrlPathLength = static_cast<DWORD>(-1);
  components.dwExtraInfoLength = static_cast<DWORD>(-1);
  if (!WinHttpCrackUrl(narrow.c_str(), 0, 0, &components)) {
    error = L"WinHttpCrackUrl: " + HLFormatErrorMessage(GetLastError());
    return false;
  }

  const std::wstring host(components.lpszHostName, components.dwHostNameLength);
  std::wstring target(components.lpszUrlPath, components.dwUrlPathLength);
  if (components.dwExtraInfoLength > 0) {
    target.append(components.lpszExtraInfo, components.dwExtraInfoLength);
  }

  HINTERNET session = WinHttpOpen(L"FlintSparkLauncher/" HMCL_LAUNCHER_VERSION, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (session == nullptr) {
    error = L"WinHttpOpen: " + HLFormatErrorMessage(GetLastError());
    return false;
  }
  // Connect/response timeouts are deliberately short: an unreachable CDN
  // must fail fast so that the next source is tried instead of stalling the
  // launcher for minutes. The receive timeout only limits idle gaps, so a slow
  // but live transfer is still allowed to finish.
  WinHttpSetTimeouts(session, 8000, 8000, 20000, 30000);

  bool ok = false;
  HINTERNET connection = WinHttpConnect(session, host.c_str(), components.nPort, 0);
  if (connection == nullptr) {
    error = L"WinHttpConnect: " + HLFormatErrorMessage(GetLastError());
  } else {
    const DWORD flags = components.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0;
    HINTERNET request =
        WinHttpOpenRequest(connection, L"GET", target.c_str(), nullptr, WINHTTP_NO_REFERER,
                           WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (request == nullptr) {
      error = L"WinHttpOpenRequest: " + HLFormatErrorMessage(GetLastError());
    } else if (!HLEnableRedirects(request, error)) {
      // A mirror listing (or a CDN URL) may answer with a redirect to the canonical spelling
      // of the path; the text helper must follow it or the mirror looks unreachable.
      // error already carries the reason.
    } else if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
               !WinHttpReceiveResponse(request, nullptr)) {
      error = L"WinHttp request failed: " + HLFormatErrorMessage(GetLastError());
    } else {
      DWORD status = 0;
      DWORD statusSize = sizeof(status);
      if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                               WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize, WINHTTP_NO_HEADER_INDEX)) {
        error = L"WinHttpQueryHeaders: " + HLFormatErrorMessage(GetLastError());
      } else if (status != 200) {
        error = L"HTTP status " + std::to_wstring(status);
      } else {
        ok = true;
        std::string buffer;
        while (true) {
          DWORD available = 0;
          if (!WinHttpQueryDataAvailable(request, &available)) {
            error = L"WinHttpQueryDataAvailable: " + HLFormatErrorMessage(GetLastError());
            ok = false;
            break;
          }
          if (available == 0) {
            break;
          }
          const size_t offset = buffer.size();
          buffer.resize(offset + available);
          DWORD read = 0;
          if (!WinHttpReadData(request, buffer.data() + offset, available, &read)) {
            error = L"WinHttpReadData: " + HLFormatErrorMessage(GetLastError());
            ok = false;
            break;
          }
          buffer.resize(offset + read);
          if (buffer.size() > (1u << 20)) {
            error = L"Response too large";
            ok = false;
            break;
          }
        }
        if (ok) {
          const int wideLength = MultiByteToWideChar(CP_UTF8, 0, buffer.data(), static_cast<int>(buffer.size()), nullptr, 0);
          if (wideLength > 0) {
            std::wstring wide(static_cast<size_t>(wideLength), L'\0');
            MultiByteToWideChar(CP_UTF8, 0, buffer.data(), static_cast<int>(buffer.size()), wide.data(), wideLength);
            body.swap(wide);
          }
        }
      }
      WinHttpCloseHandle(request);
    }
    WinHttpCloseHandle(connection);
  }

  WinHttpCloseHandle(session);
  return ok;
}
