#include <windows.h>

#include <algorithm>
#include <string>
#include <vector>

#include "archive.h"
#include "miniz.h"

namespace {

std::wstring HLUtf8ToWide(const char *text) {
  if (text == nullptr || *text == '\0') {
    return {};
  }

  const int length = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
  if (length <= 1) {
    return {};
  }

  std::wstring result(static_cast<size_t>(length), L'\0');
  if (MultiByteToWideChar(CP_UTF8, 0, text, -1, result.data(), length) <= 0) {
    return {};
  }
  result.resize(static_cast<size_t>(length - 1));
  return result;
}

// Rejects entries that would escape destDir once joined.
bool HLIsSafeEntryName(const std::wstring &name) {
  if (name.empty()) {
    return false;
  }

  if (name.size() >= 2 && name[1] == L':' && ((name[0] >= L'A' && name[0] <= L'Z') || (name[0] >= L'a' && name[0] <= L'z'))) {
    return false;
  }

  if (name.front() == L'\\' || name.front() == L'/') {
    return false;
  }

  std::wstring normalized = name;
  for (auto &ch : normalized) {
    if (ch == L'/') {
      ch = L'\\';
    }
  }

  size_t start = 0;
  while (start <= normalized.size()) {
    const auto end = normalized.find(L'\\', start);
    const std::wstring component =
        normalized.substr(start, end == std::wstring::npos ? std::wstring::npos : end - start);
    if (component == L"..") {
      return false;
    }
    if (end == std::wstring::npos) {
      break;
    }
    start = end + 1;
  }

  return true;
}

std::wstring HLNormalizeSeparators(const std::wstring &name) {
  std::wstring normalized = name;
  for (auto &ch : normalized) {
    if (ch == L'/') {
      ch = L'\\';
    }
  }
  return normalized;
}

// miniz only exposes narrow path helpers, which are not reliable for non ASCII
// install paths on Windows, so archives are loaded into memory and written with
// the wide Win32 APIs instead.
bool HLReadFileBytes(const std::wstring &path, std::vector<unsigned char> &data) {
  HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                            FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    return false;
  }

  LARGE_INTEGER size{};
  if (!GetFileSizeEx(file, &size) || size.QuadPart <= 0) {
    CloseHandle(file);
    return false;
  }

  data.resize(static_cast<size_t>(size.QuadPart));

  size_t offset = 0;
  while (offset < data.size()) {
    DWORD read = 0;
    const DWORD chunk = static_cast<DWORD>(std::min<size_t>(data.size() - offset, 1u << 24));
    if (!ReadFile(file, data.data() + offset, chunk, &read, nullptr) || read == 0) {
      CloseHandle(file);
      return false;
    }
    offset += read;
  }

  CloseHandle(file);
  return true;
}

bool HLWriteFileBytes(const std::wstring &path, const void *contents, size_t size) {
  HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    return false;
  }

  const auto *bytes = static_cast<const unsigned char *>(contents);
  size_t offset = 0;
  while (offset < size) {
    DWORD written = 0;
    const DWORD chunk = static_cast<DWORD>(std::min<size_t>(size - offset, 1u << 24));
    if (!WriteFile(file, bytes + offset, chunk, &written, nullptr) || written == 0) {
      CloseHandle(file);
      return false;
    }
    offset += written;
  }

  CloseHandle(file);
  return true;
}

}  // namespace

bool HLCreateDirectories(const std::wstring &dir) {
  if (dir.empty()) {
    return false;
  }

  const std::wstring normalized = HLNormalizeSeparators(dir);
  for (size_t pos = normalized.find(L'\\', 3); pos != std::wstring::npos; pos = normalized.find(L'\\', pos + 1)) {
    const std::wstring prefix = normalized.substr(0, pos);
    if (!CreateDirectoryW(prefix.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) {
      return false;
    }
  }

  if (!CreateDirectoryW(normalized.c_str(), nullptr)) {
    const DWORD error = GetLastError();
    if (error != ERROR_ALREADY_EXISTS) {
      return false;
    }
    const DWORD attributes = GetFileAttributesW(normalized.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY)) {
      return false;
    }
  }

  return true;
}

bool HLRemoveDirectoryRecursive(const HLPath &dir) {
  const std::wstring pattern = dir.path + L"\\*";

  WIN32_FIND_DATAW data{};
  HANDLE find = FindFirstFileW(pattern.c_str(), &data);
  if (find != INVALID_HANDLE_VALUE) {
    do {
      const std::wstring name = data.cFileName;
      if (name == L"." || name == L"..") {
        continue;
      }

      const std::wstring child = dir.path + L"\\" + name;
      if (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
        HLRemoveDirectoryRecursive(HLPath{child});
      } else {
        SetFileAttributesW(child.c_str(), FILE_ATTRIBUTE_NORMAL);
        DeleteFileW(child.c_str());
      }
    } while (FindNextFileW(find, &data));
    FindClose(find);
  }

  if (!RemoveDirectoryW(dir.path.c_str())) {
    const DWORD error = GetLastError();
    if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) {
      return false;
    }
  }

  return true;
}

bool HLListDirectory(const HLPath &dir, std::vector<std::wstring> &names) {
  names.clear();

  const std::wstring pattern = dir.path + L"\\*";
  WIN32_FIND_DATAW data{};
  HANDLE find = FindFirstFileW(pattern.c_str(), &data);
  if (find == INVALID_HANDLE_VALUE) {
    return false;
  }

  do {
    const std::wstring name = data.cFileName;
    if (name == L"." || name == L"..") {
      continue;
    }
    names.push_back(name);
  } while (FindNextFileW(find, &data));

  FindClose(find);
  return true;
}

bool HLZipExtract(const HLPath &zipFile, const HLPath &destDir, std::wstring &error,
                  const std::function<void(size_t, size_t)> &progress) {
  std::vector<unsigned char> archiveBytes;
  if (!HLReadFileBytes(zipFile.path, archiveBytes)) {
    error = L"Failed to read " + zipFile.path;
    return false;
  }

  mz_zip_archive archive{};
  if (!mz_zip_reader_init_mem(&archive, archiveBytes.data(), archiveBytes.size(), 0)) {
    error = L"Failed to open " + zipFile.path;
    return false;
  }

  bool ok = true;
  const mz_uint total = mz_zip_reader_get_num_files(&archive);

  for (mz_uint index = 0; index < total; ++index) {
    mz_zip_archive_file_stat stat{};
    if (!mz_zip_reader_file_stat(&archive, index, &stat)) {
      error = L"Failed to read a directory entry from " + zipFile.path;
      ok = false;
      break;
    }

    const std::wstring entryName = HLUtf8ToWide(stat.m_filename);
    if (!HLIsSafeEntryName(entryName)) {
      if (progress) {
        progress(static_cast<size_t>(index) + 1, static_cast<size_t>(total));
      }
      continue;
    }

    const std::wstring relative = HLNormalizeSeparators(entryName);
    const std::wstring target = destDir.path + L"\\" + relative;

    if (stat.m_is_directory) {
      if (!HLCreateDirectories(target)) {
        error = L"Failed to create directory " + target;
        ok = false;
        break;
      }
    } else {
      const auto slash = target.find_last_of(L'\\');
      if (slash != std::wstring::npos && !HLCreateDirectories(target.substr(0, slash))) {
        error = L"Failed to create directory for " + target;
        ok = false;
        break;
      }
      std::vector<unsigned char> contents(static_cast<size_t>(stat.m_uncomp_size));
      if (stat.m_uncomp_size > 0) {
        if (!mz_zip_reader_extract_to_mem(&archive, index, contents.data(), contents.size(), 0)) {
          error = L"Failed to extract " + target;
          ok = false;
          break;
        }
      }
      if (!HLWriteFileBytes(target, contents.data(), contents.size())) {
        error = L"Failed to write " + target;
        ok = false;
        break;
      }
    }

    if (progress) {
      progress(static_cast<size_t>(index) + 1, static_cast<size_t>(total));
    }
  }

  mz_zip_reader_end(&archive);
  return ok;
}
