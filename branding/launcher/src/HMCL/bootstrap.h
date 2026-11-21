#pragma once

#include <windows.h>

#include <string>
#include <vector>

#include "path.h"
#include "platform.h"

enum class HLJavaBootstrapOutcome {
  Restarted,  // A Java runtime was installed and the launcher restarted itself
  Cancelled,  // The user declined the download
  Failed,     // Download or installation failed
};

// Makes sure a usable Java runtime exists in <workdir>/jre-<arch>. If it is
// missing, the runtime is downloaded from the internet, unpacked next to the
// launcher and the launcher restarts itself so the regular lookup order finds
// the freshly installed runtime.
HLJavaBootstrapOutcome HLBootstrapJavaRuntime(const HLPath &workdir, HLArchitecture arch, LPCWSTR javaExecutableName);

// Resolves the newest JRE archive for arch from every mainland friendly
// mirror that answers, ordered fastest first. The result is empty when no
// mirror listing can be used, in which case the generic sources are tried.
std::vector<std::wstring> HLResolveMirrorJavaUrls(HLArchitecture arch);

// Resolves the newest JRE archive for arch from a mainland friendly mirror.
// Returns false and fills error when the mirror listing cannot be used.
bool HLResolveMirrorJavaUrl(HLArchitecture arch, std::wstring &url, std::wstring &error);
