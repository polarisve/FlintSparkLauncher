# FSL 一键发版：改版本号 -> 编译并签名 -> 生成 update.json -> 发布到 Gitee 和 GitHub Releases
# 启动器优先从 Gitee 检查和下载更新，Gitee 不通再用 GitHub
# 用法（在 FSL 目录里）:
#   powershell -ExecutionPolicy Bypass -File release.ps1 -Version 1.1 -Notes "修复了xxx`n新增了xxx"
# GitHub 用 git 保存的登录（不需要 gh）；Gitee 用 fsl-secrets.properties 里的 gitee.token
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$Force,      # 加上 -Force 就是强制更新
    [switch]$NoUpload    # 只编译和生成文件，不上传
)

$ErrorActionPreference = "Stop"
# curl/git 输出的是 UTF-8，不设置的话中文会被当成 GBK 解析
[Console]::OutputEncoding = [Text.Encoding]::UTF8
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
    jar       = "https://gitee.com/$repo/releases/download/$tag/FSL-$Version.jar"
    mirrors   = @("https://github.com/$repo/releases/download/$tag/FSL-$Version.jar")
    jarsha1   = $sha1
    force     = [bool]$Force
    changelog = ($Notes -replace '`n', "`n")
    page      = "https://gitee.com/$repo/releases/tag/$tag"
}
$out = Join-Path $root "HMCL\build\release"
New-Item -ItemType Directory -Force $out | Out-Null
$json = Join-Path $out "update.json"
[IO.File]::WriteAllText($json, ($update | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
Copy-Item $jar, $exe $out -Force
Write-Host "已生成 $out"

if ($NoUpload) { Write-Host "没有上传（-NoUpload）"; exit 0 }

# 4. 先发 Gitee（国内用户主要从这里更新）
$secrets = Get-Content (Join-Path $root "fsl-secrets.properties") -Encoding UTF8
$giteeToken = (($secrets | Where-Object { $_ -like "gitee.token=*" }) -replace '^gitee\.token=', '').Trim()
if (-not $giteeToken) { throw "fsl-secrets.properties 里没有 gitee.token" }
# 先把代码和 tag 推到 Gitee，发布要基于这个 tag
git push gitee main
if ($LASTEXITCODE -ne 0) { throw "推送到 Gitee 失败" }
$giteeApi = "https://gitee.com/api/v5/repos/$repo"
$giteeBodyFile = Join-Path $out "gitee-body.json"
$giteeBody = [ordered]@{ access_token = $giteeToken; tag_name = $tag; target_commitish = "main"; name = "FSL $Version"; body = $update.changelog; prerelease = $false }
[IO.File]::WriteAllText($giteeBodyFile, ($giteeBody | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
$giteeRel = curl.exe -s -m 60 -X POST -H "Content-Type: application/json;charset=UTF-8" --data-binary "@$giteeBodyFile" "$giteeApi/releases" | ConvertFrom-Json
Remove-Item $giteeBodyFile
if (-not $giteeRel.id) { throw "Gitee 创建发布失败：$($giteeRel.message)（版本 $tag 可能已经发过了）" }
foreach ($name in @("FSL-$Version.jar", "update.json", "FSL-$Version.exe")) {
    $file = Join-Path $out $name
    $asset = curl.exe -s -m 1800 -X POST -F "access_token=$giteeToken" -F "file=@$file" "$giteeApi/releases/$($giteeRel.id)/attach_files" | ConvertFrom-Json
    if ($asset.name -ne $name) { throw "Gitee 上传 $name 失败：$($asset.message)" }
    Write-Host "Gitee 已上传 $name"
}
Write-Host "Gitee 发布完成：https://gitee.com/$repo/releases/tag/$tag"

# 5. 再发 GitHub Releases，并标为最新版本（Gitee 不通时的备用源）
# 用 Windows 自带的 curl.exe 和 git 保存的 GitHub 登录（gh 在这台电脑上连 API 会断）
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill
$token = ($cred | Where-Object { $_ -like "password=*" }) -replace '^password=', ''
if (-not $token) { throw "找不到 GitHub 登录，先用 git push 登录一次" }
$api = "https://api.github.com/repos/$repo"
$auth = "Authorization: Bearer $token"

$bodyFile = Join-Path $out "body.json"
$body = [ordered]@{ tag_name = $tag; target_commitish = "main"; name = "FSL $Version"; body = $update.changelog; make_latest = "true" }
[IO.File]::WriteAllText($bodyFile, ($body | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
$created = curl.exe -s -m 60 -X POST -H $auth -H "Content-Type: application/json" --data-binary "@$bodyFile" "$api/releases" | ConvertFrom-Json
Remove-Item $bodyFile
if (-not $created.id) { throw "创建发布失败：$($created.message)（版本 $tag 可能已经发过了）" }

foreach ($name in @("FSL-$Version.exe", "FSL-$Version.jar", "update.json")) {
    $file = Join-Path $out $name
    $asset = curl.exe -s -m 900 -X POST -H $auth -H "Content-Type: application/octet-stream" --data-binary "@$file" `
        "https://uploads.github.com/repos/$repo/releases/$($created.id)/assets?name=$name" | ConvertFrom-Json
    if ($asset.state -ne "uploaded") { throw "上传 $name 失败：$($asset.message)" }
    Write-Host "GitHub 已上传 $name"
}
Write-Host "GitHub 发布完成：https://github.com/$repo/releases/tag/$tag"
