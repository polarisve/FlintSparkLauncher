# Builds the Flint Spark Launcher stub (branding/launcher/HMCLauncher.exe).
#
# The stub is a fork of the HMCL launcher stub (GPLv3) with branding changes and
# an automatic Java runtime download. It is compiled as a 32-bit binary by
# default so a single stub runs on x86, x64 and arm64 Windows; the architecture
# detection at runtime is performed by IsWow64Process2.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Arch x64
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install
#
# -Install copies the freshly built stub over branding/launcher/HMCLauncher.exe,
# which is the file embedded into the jar by HMCL/build.gradle.kts.

param(
    [ValidateSet('x86', 'x64', 'arm64')]
    [string]$Arch = 'x86',
    [switch]$Install,
    [string]$BuildDir = (Join-Path $env:TEMP 'fsl-launcher-build')
)

$ErrorActionPreference = 'Stop'

$src = $PSScriptRoot
$stubTarget = Join-Path (Split-Path $src -Parent) 'HMCLauncher.exe'

function Find-VcVars {
    $candidates = @(
        'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat',
        'C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $installPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($installPath) {
            $candidate = Join-Path $installPath 'VC\Auxiliary\Build\vcvarsall.bat'
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }

    throw 'Visual Studio C++ build tools were not found. Install "Desktop development with C++" (VS Build Tools).'
}

$vcvars = Find-VcVars

Write-Host "Source : $src"
Write-Host "Build  : $BuildDir"
Write-Host "VCVars : $vcvars"

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BuildDir 'obj') | Out-Null

# --- Generate config.h and the resource script from the templates ---
$version = '1.1.0.0'
$versionParts = $version.Split('.')

$configTemplate = Get-Content -LiteralPath (Join-Path $src 'HMCL\config.h.in') -Raw
$config = $configTemplate.Replace('@PROJECT_VERSION@', $version).
    Replace('@HMCL_EXPECTED_JAVA_MAJOR_VERSION@', '17')
Set-Content -LiteralPath (Join-Path $BuildDir 'config.h') -Value $config -Encoding UTF8 -NoNewline

$rcTemplate = Get-Content -LiteralPath (Join-Path $src 'HMCL\HMCL.rc.in') -Raw
$rc = $rcTemplate.Replace('@PROJECT_VERSION_MAJOR@', $versionParts[0]).
    Replace('@PROJECT_VERSION_MINOR@', $versionParts[1]).
    Replace('@PROJECT_VERSION_PATCH@', $versionParts[2]).
    Replace('@PROJECT_VERSION_TWEAK@', $versionParts[3]).
    Replace('@PROJECT_VERSION@', $version)
Set-Content -LiteralPath (Join-Path $BuildDir 'HMCL.rc') -Value $rc -Encoding UTF8 -NoNewline

Copy-Item -LiteralPath (Join-Path $src 'fsl.ico') -Destination (Join-Path $BuildDir 'fsl.ico') -Force

# --- Compile ---
$sources = @(
    'HMCL\main.cpp',
    'HMCL\platform.cpp',
    'HMCL\i18n.cpp',
    'HMCL\java.cpp',
    'HMCL\path.cpp',
    'HMCL\debug.cpp',
    'HMCL\progress.cpp',
    'HMCL\download.cpp',
    'HMCL\archive.cpp',
    'HMCL\bootstrap.cpp'
)

$clFlags = '/nologo /utf-8 /W4 /MT /std:c++20 /EHsc /O2 /DNDEBUG /DUNICODE /D_UNICODE ' +
    '/DWINVER=0x0601 /D_WIN32_WINNT=0x0601 ' +
    "/I`"$BuildDir`" /I`"$src\third_party\miniz`" /Fo`"$BuildDir\obj\\`""

$linkLibs = 'shlwapi.lib version.lib winhttp.lib ole32.lib shell32.lib user32.lib advapi32.lib comctl32.lib'

$batch = @"
@echo off
call "$vcvars" $Arch >nul || exit /b 1
rc /nologo /fo "$BuildDir\HMCL.res" /I "$BuildDir" "$BuildDir\HMCL.rc" || exit /b 1
cl $clFlags /c $($sources -join ' ') || exit /b 1
cl /nologo /utf-8 /W0 /MT /c "$src\third_party\miniz\miniz.c" /Fo"$BuildDir\obj\miniz.obj" || exit /b 1
link /nologo /SUBSYSTEM:WINDOWS /ENTRY:wWinMainCRTStartup /INCREMENTAL:NO /OUT:"$BuildDir\HMCLauncher.exe" "$BuildDir\obj\*.obj" "$BuildDir\HMCL.res" $linkLibs || exit /b 1
"@

$batchPath = Join-Path $BuildDir 'build.bat'
Set-Content -LiteralPath $batchPath -Value $batch -Encoding ASCII

Push-Location $src
try {
    & cmd.exe /c $batchPath
    if ($LASTEXITCODE -ne 0) {
        throw "Compilation failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$exe = Join-Path $BuildDir 'HMCLauncher.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    throw 'HMCLauncher.exe was not produced.'
}

$size = (Get-Item -LiteralPath $exe).Length
Write-Host "Built  : $exe ($size bytes)"

if ($Install) {
    Copy-Item -LiteralPath $exe -Destination $stubTarget -Force
    Write-Host "Installed: $stubTarget"
}
