# FSL 一键发版：改版本号 -> 编译并签名 -> 生成 update.json -> 发布到 GitHub Releases
# 用法（在 FSL 目录里）:
#   powershell -ExecutionPolicy Bypass -File release.ps1 -Version 1.1 -Notes "修复了xxx`n新增了xxx"
# 第一次用之前要先登录 GitHub:  gh auth login
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$Force,      # 加上 -Force 就是强制更新
    [switch]$NoUpload    # 只编译和生成文件，不上传
)

$ErrorActionPreference = "Stop"
$repo = "polarisve/FlintSparkLauncher"
$root = $PSScriptRoot
Set-Location $root

if ($Version -notmatch '^\d+(\.\d+)+$') { throw "版本号要像 1.1 或 1.2.3 这样" }
$tag = "v$Version"

# 1. 改版本号
$propFile = Join-Path $root "config\project.properties"
$props = [IO.File]::ReadAllText($propFile)
$props = [regex]::Replace($props, '(?m)^versionRoot=.*$', "versionRoot=$Version")
[IO.File]::WriteAllText($propFile, $props, (New-Object Text.UTF8Encoding($false)))
Write-Host "版本号已改成 $Version"

# 2. 编译（会用 fsl-secrets.properties 里的 signature.key 签名）
& "$root\gradlew.bat" --no-daemon -q :HMCL:makeExecutables
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

$libs = Join-Path $root "HMCL\build\libs"
$jar = Join-Path $libs "FSL-$Version.jar"
$exe = Join-Path $libs "FSL-$Version.exe"
if (-not (Test-Path $jar) -or -not (Test-Path $exe)) { throw "没找到 $jar 或 $exe" }

# 确认 jar 里有签名，没签名的话别人的启动器会拒绝这个更新
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
$signed = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/hmcl_signature" }
$zip.Dispose()
if (-not $signed) { throw "jar 没有签名，检查 fsl-secrets.properties 里的 signature.key" }

# 3. 生成 update.json（启动器读的就是这个文件）
$sha = [Security.Cryptography.SHA1]::Create()
$sha1 = -join ($sha.ComputeHash([IO.File]::ReadAllBytes($jar)) | ForEach-Object { $_.ToString("x2") })
$update = [ordered]@{
    version   = $Version
    jar       = "https://github.com/$repo/releases/download/$tag/FSL-$Version.jar"
    jarsha1   = $sha1
    force     = [bool]$Force
    changelog = ($Notes -replace '`n', "`n")
    page      = "https://github.com/$repo/releases/tag/$tag"
}
$out = Join-Path $root "HMCL\build\release"
New-Item -ItemType Directory -Force $out | Out-Null
$json = Join-Path $out "update.json"
[IO.File]::WriteAllText($json, ($update | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
Copy-Item $jar, $exe $out -Force
Write-Host "已生成 $out"

if ($NoUpload) { Write-Host "没有上传（-NoUpload）"; exit 0 }

# 4. 上传到 GitHub Releases，并标为最新版本
$notesFile = Join-Path $out "notes.md"
[IO.File]::WriteAllText($notesFile, $update.changelog, (New-Object Text.UTF8Encoding($false)))
gh release create $tag "$out\FSL-$Version.exe" "$out\FSL-$Version.jar" $json `
    --repo $repo --title "FSL $Version" --notes-file $notesFile --latest
if ($LASTEXITCODE -ne 0) { throw "上传失败（没登录就先运行 gh auth login）" }
Write-Host "发布完成：https://github.com/$repo/releases/tag/$tag"
