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
  // Abandon the transfer when it stays far below a usable throughput. Used for
  // the mainland mirrors, which are expected to be fast and have replacements;
  // the generic fallbacks leave it off so a slow but working link still wins.
  bool failOnSlowNetwork = false;
};

// Downloads url into destFile (overwriting it), showing a native progress
// dialog. The partial file is removed when the download fails or is cancelled.
HLDownloadResult HLDownloadFile(const std::wstring &url, const HLPath &destFile, const HLDownloadOptions &options);

// Fetches a small text resource (used to resolve mirror download links).
bool HLHttpGetText(const std::wstring &url, std::wstring &body, std::wstring &error);
