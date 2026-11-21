#pragma once

#include <windows.h>

struct HLI18N {
  static HLI18N Instance();

  // Error Messages
  LPCWSTR errorSelfPath;
  LPCWSTR errorInvalidHMCLJavaHome;
  LPCWSTR errorJavaNotFound;

  // Java runtime bootstrap (FSL). Strings holding {} placeholders are formatted
  // with std::format: prompt takes (version, size in MB, target directory) and
  // failed takes the failure detail.
  LPCWSTR javaDownloadTitle;
  LPCWSTR javaDownloadPrompt;
  LPCWSTR javaDownloadConnecting;
  LPCWSTR javaDownloadCancelMessage;
  LPCWSTR javaDownloadExtracting;
  LPCWSTR javaDownloadFailed;
};
