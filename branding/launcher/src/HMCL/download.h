#pragma once

#include <string>

#include "path.h"

enum class HLDownloadStatus {
  Succeeded,
  Cancelled,
  Failed,
};

struct HLDownloadResult {
  HLDownloadStatus status = HLDownloadStatus::Failed;
  std::wstring error;
};

struct HLDownloadOptions {
  std::wstring title;          // Progress dialog title
  std::wstring cancelMessage;  // Message shown after the user cancels
  std::wstring fileLabel;      // File name displayed in the dialog
};

// Downloads url into destFile (overwriting it), showing a native progress
// dialog. The partial file is removed when the download fails or is cancelled.
HLDownloadResult HLDownloadFile(const std::wstring &url, const HLPath &destFile, const HLDownloadOptions &options);

// Fetches a small text resource (used to resolve mirror download links).
bool HLHttpGetText(const std::wstring &url, std::wstring &body, std::wstring &error);
