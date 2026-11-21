#include <windows.h>

#include <algorithm>
#include <format>
#include <initializer_list>
#include <regex>
#include <string>
#include <vector>

#include "archive.h"
#include "bootstrap.h"
#include "config.h"
#include "debug.h"
#include "download.h"
#include "i18n.h"
#include "path.h"
#include "platform.h"
#include "progress.h"

namespace {
// std::vformat only accepts lvalue arguments, so a tiny positional replacement
// helper keeps the i18n templates readable at the call sites.
std::wstring HLFillTemplate(const wchar_t *pattern, std::initializer_list<std::wstring> values) {
  std::wstring result = pattern;
  for (const auto &value : values) {
    const size_t placeholder = result.find(L"{}");
    if (placeholder == std::wstring::npos) {
      break;
    }
    result.replace(placeholder, 2, value);
  }
  return result;
}


constexpr int kPreferredJavaFeatureVersion = 21;
constexpr unsigned long long kEstimatedDownloadSizeMb = 48;
constexpr LPCWSTR kDownloadUrlEnvVar = L"FSL_JAVA_DOWNLOAD_URL";
// Set to 1 to skip the confirmation dialog (unattended installations / tests).
constexpr LPCWSTR kAssumeYesEnvVar = L"FSL_JAVA_ASSUME_YES";

struct HLDownloadSource {
  std::wstring url;
  std::wstring label;
  // Mirrors are expected to be fast, so a mirror that trickles bytes is
  // abandoned and the next candidate is tried. The generic fallbacks keep
  // running instead: any working link beats no runtime at all.
  bool strictSpeed = false;
};

std::wstring HLGetBundledJavaDirectoryName(HLArchitecture arch) {
  switch (arch) {
    case HLArchitecture::ARM64:
      return L"jre-arm64";
    case HLArchitecture::X86_64:
      return L"jre-x64";
    case HLArchitecture::X86:
      return L"jre-x86";
    default:
      return L"jre-x64";
  }
}

std::wstring HLGetAdoptiumUrl(int featureVersion, LPCWSTR platform) {
  return std::format(
      L"https://api.adoptium.net/v3/binary/latest/{}/ga/windows/{}/jre/hotspot/normal/eclipse?project=jdk",
      featureVersion, platform);
}

std::vector<HLDownloadSource> HLBuildDownloadSources(HLArchitecture arch) {
  if (const auto overrideUrl = HLGetEnvVar(kDownloadUrlEnvVar);
      overrideUrl.has_value() && !overrideUrl.value().empty()) {
    HLDebugLog(std::format(L"Using {}: {}", kDownloadUrlEnvVar, overrideUrl.value()));
    return {{overrideUrl.value(), L"Java runtime"}};
  }

  std::vector<HLDownloadSource> sources;

  // Mainland China mirrors are tried first: the Adoptium API and the Microsoft
  // aka.ms links redirect to GitHub / Azure CDN endpoints that are frequently
  // unreachable there, which is why they stay as fallbacks below.
  // Every mirror that answers contributes a source, so a single mirror being
  // out of sync (a listing that points at a file it has not fetched yet) only
  // costs one fast 404 instead of falling through to the slow generic sources.
  for (const auto &mirrorUrl : HLResolveMirrorJavaUrls(arch)) {
    HLDebugLog(std::format(L"Resolved mirror Java runtime: {}", mirrorUrl));
    sources.push_back({mirrorUrl, L"Adoptium mirror", true});
  }

  const auto addAdoptium = [&sources](int featureVersion, LPCWSTR platform) {
    sources.push_back({HLGetAdoptiumUrl(featureVersion, platform),
                       std::format(L"Temurin JRE {}", featureVersion)});
  };

  switch (arch) {
    case HLArchitecture::ARM64:
      // aka.ms is served from an Azure CDN edge that answers from mainland
      // China in the hundreds of KB/s, while api.adoptium.net usually stalls
      // until it times out, so the CDN link comes first.
      sources.push_back({L"https://aka.ms/download-jdk/microsoft-jdk-21-windows-aarch64.zip",
                         L"Microsoft Build of OpenJDK 21"});
      addAdoptium(kPreferredJavaFeatureVersion, L"aarch64");
      addAdoptium(HMCL_EXPECTED_JAVA_MAJOR_VERSION, L"aarch64");
      break;
    case HLArchitecture::X86_64:
      sources.push_back(
          {L"https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip", L"Microsoft Build of OpenJDK 21"});
      addAdoptium(kPreferredJavaFeatureVersion, L"x64");
      addAdoptium(HMCL_EXPECTED_JAVA_MAJOR_VERSION, L"x64");
      break;
    case HLArchitecture::X86:
    default:
      // Temurin is the only vendor still publishing 32-bit Windows runtimes.
      addAdoptium(kPreferredJavaFeatureVersion, L"x86");
      addAdoptium(HMCL_EXPECTED_JAVA_MAJOR_VERSION, L"x86");
      break;
  }

  return sources;
}

bool HLIsJavaRuntimeReady(const HLPath &home, LPCWSTR javaExecutableName) {
  return (home / L"bin" / javaExecutableName).IsRegularFile();
}

bool HLIsZipArchive(const HLPath &path) {
  HANDLE file =
      CreateFileW(path.path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    return false;
  }

  unsigned char header[4]{};
  DWORD read = 0;
  const bool ok = ReadFile(file, header, sizeof(header), &read, nullptr) && read == sizeof(header) &&
                  header[0] == 'P' && header[1] == 'K';
  CloseHandle(file);
  return ok;
}

// The archives published by Temurin and Microsoft contain a single top level
// directory (jdk-21.0.5+11-jre, jdk-21.0.5.11-hotspot, ...). Step into it so the
// runtime ends up directly below <workdir>/jre-<arch>.
HLPath HLResolveArchiveRoot(const HLPath &staging) {
  std::vector<std::wstring> entries;
  if (!HLListDirectory(staging, entries) || entries.size() != 1) {
    return staging;
  }

  const HLPath candidate = staging / entries.front();
  const DWORD attributes = GetFileAttributesW(candidate.path.c_str());
  if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY)) {
    return staging;
  }

  return candidate;
}

bool HLInstallDirectory(const HLPath &source, const HLPath &target) {
  const std::wstring backupPath = target.path + L".old";
  HLRemoveDirectoryRecursive(HLPath{backupPath});

  const bool targetExists = GetFileAttributesW(target.path.c_str()) != INVALID_FILE_ATTRIBUTES;
  if (targetExists && !MoveFileExW(target.path.c_str(), backupPath.c_str(), 0)) {
    HLDebugLog(std::format(L"Failed to move the previous runtime aside, removing it instead: {}",
                           target.path));
    if (!HLRemoveDirectoryRecursive(target)) {
      return false;
    }
  }

  if (!MoveFileExW(source.path.c_str(), target.path.c_str(), 0)) {
    HLDebugLog(std::format(L"Failed to install the runtime to {} (error {})", target.path, GetLastError()));
    if (targetExists) {
      MoveFileExW(backupPath.c_str(), target.path.c_str(), 0);
    }
    return false;
  }

  HLRemoveDirectoryRecursive(HLPath{backupPath});
  return true;
}

bool HLRestartSelf(const HLPath &workdir) {
  std::wstring commandLine = GetCommandLineW();

  STARTUPINFOW startupInfo{};
  startupInfo.cb = sizeof(startupInfo);
  PROCESS_INFORMATION processInfo{};

  const BOOL created = CreateProcessW(nullptr, commandLine.data(), nullptr, nullptr, FALSE, 0, nullptr,
                                      workdir.path.c_str(), &startupInfo, &processInfo);
  if (!created) {
    HLDebugLog(std::format(L"Failed to restart the launcher (error {})", GetLastError()));
    return false;
  }

  CloseHandle(processInfo.hThread);
  CloseHandle(processInfo.hProcess);
  HLDebugLog(std::format(L"Restarted the launcher after installing the Java runtime: {}", commandLine));
  return true;
}

}  // namespace

HLJavaBootstrapOutcome HLBootstrapJavaRuntime(const HLPath &workdir, HLArchitecture arch,
                                             LPCWSTR javaExecutableName) {
  const auto i18n = HLI18N::Instance();

  const std::wstring directoryName = HLGetBundledJavaDirectoryName(arch);
  const HLPath targetHome = workdir / directoryName;
  if (HLIsJavaRuntimeReady(targetHome, javaExecutableName)) {
    HLDebugLog(std::format(L"A bundled Java runtime already exists: {}", targetHome.path));
    return HLJavaBootstrapOutcome::Failed;
  }

  HLDebugLog(std::format(L"No Java runtime found, preparing to download one into {}", targetHome.path));

  const std::wstring prompt =
      HLFillTemplate(i18n.javaDownloadPrompt, {std::to_wstring(HMCL_EXPECTED_JAVA_MAJOR_VERSION), std::to_wstring(kEstimatedDownloadSizeMb), targetHome.path});
  bool assumeYes = false;
  if (const auto value = HLGetEnvVar(kAssumeYesEnvVar); value.has_value()) {
    assumeYes = value.value() == L"1" || value.value() == L"true" || value.value() == L"yes";
  }

  if (!assumeYes &&
      MessageBoxW(nullptr, prompt.c_str(), i18n.javaDownloadTitle,
                  MB_ICONQUESTION | MB_YESNO | MB_DEFBUTTON1) != IDYES) {
    HLDebugLog(L"The user declined to download a Java runtime");
    return HLJavaBootstrapOutcome::Cancelled;
  }

  const HRESULT comResult = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);

  const HLPath archivePath = workdir / L"jre-download.zip";
  const HLPath stagingPath = workdir / L".jre-staging";

  const auto finish = [comResult]() {
    if (SUCCEEDED(comResult)) {
      CoUninitialize();
    }
  };

  HLRemoveDirectoryRecursive(stagingPath);
  DeleteFileW(archivePath.path.c_str());

  const auto sources = HLBuildDownloadSources(arch);
  std::wstring lastError;

  HLDownloadResult download;
  bool downloaded = false;
  for (const auto &source : sources) {
    HLDebugLog(std::format(L"Downloading {} from {}", source.label, source.url));
    const HLDownloadOptions options{.title = i18n.javaDownloadTitle,
                                    .cancelMessage = i18n.javaDownloadCancelMessage,
                                    .fileLabel = source.label,
                                    .failOnSlowNetwork = source.strictSpeed};
    download = HLDownloadFile(source.url, archivePath, options);
    if (download.status == HLDownloadStatus::Succeeded && HLIsZipArchive(archivePath)) {
      downloaded = true;
      break;
    }
    if (download.status == HLDownloadStatus::Cancelled) {
      finish();
      return HLJavaBootstrapOutcome::Cancelled;
    }

    lastError = download.error;
    if (download.status == HLDownloadStatus::Succeeded) {
      lastError = L"The downloaded file is not a zip archive";
    }
    HLDebugLog(std::format(L"Download failed: {}", lastError));
    DeleteFileW(archivePath.path.c_str());
  }

  if (!downloaded) {
    finish();
    if (lastError.empty()) {
      lastError = L"No download source available";
    }
    const std::wstring message =
        HLFillTemplate(i18n.javaDownloadFailed, {lastError});
    MessageBoxW(nullptr, message.c_str(), i18n.javaDownloadTitle, MB_ICONERROR | MB_OK);
    return HLJavaBootstrapOutcome::Failed;
  }

  HLProgressDialog dialog;
  dialog.Start(i18n.javaDownloadTitle, i18n.javaDownloadCancelMessage);
  dialog.SetLine(i18n.javaDownloadConnecting);

  std::wstring extractError;
  const bool extracted = HLZipExtract(archivePath, stagingPath, extractError, [&dialog, &i18n](size_t completed, size_t total) {
    if (total != 0) {
      dialog.SetProgress(completed, total);
    }
    dialog.SetLine(HLFillTemplate(i18n.javaDownloadExtracting, {std::to_wstring(completed), std::to_wstring(total)}));
  });
  dialog.Stop();

  if (!extracted) {
    finish();
    HLRemoveDirectoryRecursive(stagingPath);
    DeleteFileW(archivePath.path.c_str());
    HLDebugLog(L"Failed to extract the Java runtime: " + extractError);
    const std::wstring message = HLFillTemplate(i18n.javaDownloadFailed, {extractError});
    MessageBoxW(nullptr, message.c_str(), i18n.javaDownloadTitle, MB_ICONERROR | MB_OK);
    return HLJavaBootstrapOutcome::Failed;
  }

  const HLPath archiveRoot = HLResolveArchiveRoot(stagingPath);
  if (!HLIsJavaRuntimeReady(archiveRoot, javaExecutableName)) {
    finish();
    HLRemoveDirectoryRecursive(stagingPath);
    DeleteFileW(archivePath.path.c_str());
    const std::wstring detail = std::format(L"The archive does not contain bin\\{}", javaExecutableName);
    HLDebugLog(L"Failed to install the Java runtime: " + detail);
    const std::wstring message = HLFillTemplate(i18n.javaDownloadFailed, {detail});
    MessageBoxW(nullptr, message.c_str(), i18n.javaDownloadTitle, MB_ICONERROR | MB_OK);
    return HLJavaBootstrapOutcome::Failed;
  }

  if (!HLInstallDirectory(archiveRoot, targetHome)) {
    finish();
    HLRemoveDirectoryRecursive(stagingPath);
    DeleteFileW(archivePath.path.c_str());
    const std::wstring detail = L"Failed to move the unpacked runtime to " + targetHome.path;
    const std::wstring message = HLFillTemplate(i18n.javaDownloadFailed, {detail});
    MessageBoxW(nullptr, message.c_str(), i18n.javaDownloadTitle, MB_ICONERROR | MB_OK);
    return HLJavaBootstrapOutcome::Failed;
  }

  HLRemoveDirectoryRecursive(stagingPath);
  DeleteFileW(archivePath.path.c_str());
  finish();

  HLDebugLog(std::format(L"Java runtime installed to {}", targetHome.path));

  if (!HLRestartSelf(workdir)) {
    const std::wstring detail = L"The launcher could not restart itself. Please start it again manually.";
    const std::wstring message = HLFillTemplate(i18n.javaDownloadFailed, {detail});
    MessageBoxW(nullptr, message.c_str(), i18n.javaDownloadTitle, MB_ICONERROR | MB_OK);
    return HLJavaBootstrapOutcome::Failed;
  }

  return HLJavaBootstrapOutcome::Restarted;
}

namespace {

// Compares Adoptium archive names by their numeric version components, e.g.
// OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip -> {21, 0, 13, 9}.
std::vector<int> HLParseVersionKey(const std::wstring &fileName) {
  std::vector<int> key;
  const size_t marker = fileName.find(L"hotspot_");
  if (marker == std::wstring::npos) {
    return key;
  }

  std::wstring digits;
  for (size_t index = marker + 8; index < fileName.size(); ++index) {
    const wchar_t ch = fileName[index];
    if (ch >= L'0' && ch <= L'9') {
      digits.push_back(ch);
    } else if (!digits.empty()) {
      key.push_back(std::stoi(digits));
      digits.clear();
    }
  }
  if (!digits.empty()) {
    key.push_back(std::stoi(digits));
  }
  return key;
}

bool HLIsNewerVersionKey(const std::vector<int> &lhs, const std::vector<int> &rhs) {
  const size_t size = std::min<size_t>(lhs.size(), rhs.size());
  for (size_t index = 0; index < size; ++index) {
    if (lhs[index] != rhs[index]) {
      return lhs[index] > rhs[index];
    }
  }
  return lhs.size() > rhs.size();
}

LPCWSTR HLGetAdoptiumPlatformName(HLArchitecture arch) {
  switch (arch) {
    case HLArchitecture::ARM64:
      return L"aarch64";
    case HLArchitecture::X86:
      return L"x86";
    case HLArchitecture::X86_64:
    default:
      return L"x64";
  }
}

}  // namespace

std::vector<std::wstring> HLResolveMirrorJavaUrls(HLArchitecture arch) {
  // Ordered by measured throughput from mainland China. A mirror that cannot
  // be listed is skipped silently: the caller still has the generic sources.
  static constexpr LPCWSTR kMirrorBases[] = {
      L"https://mirrors.tuna.tsinghua.edu.cn/Adoptium/",
      L"https://mirrors.cernet.edu.cn/adoptium/",
  };

  const std::wstring pattern = std::wstring(L"OpenJDK[0-9]+U-jre_") + HLGetAdoptiumPlatformName(arch) +
                               L"_windows_hotspot_[0-9._]+\\.zip";
  const std::wregex matcher(pattern);

  std::vector<std::wstring> urls;
  for (LPCWSTR mirrorBase : kMirrorBases) {
    const std::wstring base = std::wstring(mirrorBase) + std::to_wstring(kPreferredJavaFeatureVersion) + L"/jre/" +
                              HLGetAdoptiumPlatformName(arch) + L"/windows/";

    std::wstring page;
    std::wstring error;
    if (!HLHttpGetText(base, page, error)) {
      HLDebugLog(std::format(L"Skipping mirror {}: {}", base, error));
      continue;
    }

    std::wstring bestName;
    std::vector<int> bestKey;
    for (std::wsregex_iterator it(page.begin(), page.end(), matcher); it != std::wsregex_iterator(); ++it) {
      const std::wstring candidate = it->str();
      const auto key = HLParseVersionKey(candidate);
      if (bestName.empty() || HLIsNewerVersionKey(key, bestKey)) {
        bestName = candidate;
        bestKey = key;
      }
    }

    if (bestName.empty()) {
      HLDebugLog(L"No JRE archive found in " + base);
      continue;
    }

    urls.push_back(base + bestName);
  }

  return urls;
}

bool HLResolveMirrorJavaUrl(HLArchitecture arch, std::wstring &url, std::wstring &error) {
  url.clear();
  error.clear();

  const std::vector<std::wstring> urls = HLResolveMirrorJavaUrls(arch);
  if (urls.empty()) {
    error = L"No JRE archive found on any mirror";
    return false;
  }

  url = urls.front();
  return true;
}
