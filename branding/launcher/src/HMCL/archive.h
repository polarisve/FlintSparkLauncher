#pragma once

#include <functional>
#include <string>
#include <vector>

#include "path.h"

// Extracts every entry of a zip archive into destDir while preserving the
// directory layout. progress receives (completedEntries, totalEntries).
bool HLZipExtract(const HLPath &zipFile, const HLPath &destDir, std::wstring &error,
                  const std::function<void(size_t, size_t)> &progress = {});

// Recursively deletes dir. A missing directory counts as success.
bool HLRemoveDirectoryRecursive(const HLPath &dir);

// Lists the entry names directly contained in dir (without sub directory walk).
bool HLListDirectory(const HLPath &dir, std::vector<std::wstring> &names);

// Creates dir and all its parents when they do not exist yet.
bool HLCreateDirectories(const std::wstring &dir);
