# FSL 一键发版：改版本号 -> 编译并签名 -> 生成 update.json -> 发布到 Gitee 和 GitHub Releases
# 两边同时上传；启动器优先从 Gitee 检查和下载更新，Gitee 不通再用 GitHub
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

# 4. Gitee 和 GitHub 同时发布（两个后台任务并行上传，不分先后）
$secrets = Get-Content (Join-Path $root "fsl-secrets.properties") -Encoding UTF8
$giteeToken = (($secrets | Where-Object { $_ -like "gitee.token=*" }) -replace '^gitee\.token=', '').Trim()
if (-not $giteeToken) { throw "fsl-secrets.properties 里没有 gitee.token" }
# GitHub 用 git 保存的登录（gh 在这台电脑上连 API 会断）
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill
$githubToken = ($cred | Where-Object { $_ -like "password=*" }) -replace '^password=', ''
if (-not $githubToken) { throw "找不到 GitHub 登录，先用 git push 登录一次" }
# 直连 GitHub 很慢，SakuraCat 代理（127.0.0.1:12450）开着就让 GitHub 走代理
$githubProxy = ""
$probe = New-Object Net.Sockets.TcpClient
try { $probe.Connect("127.0.0.1", 12450); $githubProxy = "http://127.0.0.1:12450" } catch { Write-Host "代理 12450 没开，GitHub 直连（可能很慢）" } finally { $probe.Close() }
# 发布是基于 main 打 tag 的，先把代码推到两边
git push gitee main
if ($LASTEXITCODE -ne 0) { throw "推送到 Gitee 失败" }
if ($githubProxy) { git -c http.proxy=$githubProxy push origin main } else { git push origin main }
if ($LASTEXITCODE -ne 0) { throw "推送到 GitHub 失败" }

# 两边共用的参数；curl 结果写进文件再按 UTF-8 读，避免后台任务里中文乱码
$jobArgs = @($repo, $tag, $Version, $out, $update.changelog)

$giteeJob = Start-Job -Name "Gitee" -ArgumentList ($jobArgs + $giteeToken) -ScriptBlock {
    param($repo, $tag, $Version, $out, $changelog, $token)
    $ErrorActionPreference = "Stop"
    function Read-Json($f) { $j = Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json; Remove-Item $f; $j }
    $api = "https://gitee.com/api/v5/repos/$repo"
    $bodyFile = Join-Path $out "gitee-body.json"
    $resp = Join-Path $out "gitee-resp.json"
    $body = [ordered]@{ access_token = $token; tag_name = $tag; target_commitish = "main"; name = "FSL $Version"; body = $changelog; prerelease = $false }
    [IO.File]::WriteAllText($bodyFile, ($body | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    curl.exe -s -m 60 -o $resp -X POST -H "Content-Type: application/json;charset=UTF-8" --data-binary "@$bodyFile" "$api/releases"
    Remove-Item $bodyFile
    $rel = Read-Json $resp
    if (-not $rel.id) { throw "Gitee 创建发布失败：$($rel.message)（版本 $tag 可能已经发过了）" }
    foreach ($name in @("FSL-$Version.jar", "update.json", "FSL-$Version.exe")) {
        $file = Join-Path $out $name
        curl.exe -s -m 1800 -o $resp -X POST -F "access_token=$token" -F "file=@$file" "$api/releases/$($rel.id)/attach_files"
        $asset = Read-Json $resp
        if ($asset.name -ne $name) { throw "Gitee 上传 $name 失败：$($asset.message)" }
        "Gitee 已上传 $name"
    }
    "Gitee 发布完成：https://gitee.com/$repo/releases/tag/$tag"
}

$githubJob = Start-Job -Name "GitHub" -ArgumentList ($jobArgs + $githubToken + $githubProxy) -ScriptBlock {
    param($repo, $tag, $Version, $out, $changelog, $token, $proxy)
    $ErrorActionPreference = "Stop"
    $px = if ($proxy) { @("-x", $proxy) } else { @() }
    function Read-Json($f) { $j = Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json; Remove-Item $f; $j }
    $api = "https://api.github.com/repos/$repo"
    $auth = "Authorization: Bearer $token"
    $bodyFile = Join-Path $out "github-body.json"
    $resp = Join-Path $out "github-resp.json"
    $body = [ordered]@{ tag_name = $tag; target_commitish = "main"; name = "FSL $Version"; body = $changelog; make_latest = "true" }
    [IO.File]::WriteAllText($bodyFile, ($body | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    curl.exe -s -m 60 @px -o $resp -X POST -H $auth -H "Content-Type: application/json" --data-binary "@$bodyFile" "$api/releases"
    Remove-Item $bodyFile
    $rel = Read-Json $resp
    if (-not $rel.id) { throw "GitHub 创建发布失败：$($rel.message)（版本 $tag 可能已经发过了）" }
    foreach ($name in @("FSL-$Version.jar", "update.json", "FSL-$Version.exe")) {
        $file = Join-Path $out $name
        curl.exe -s -m 1800 @px -o $resp -X POST -H $auth -H "Content-Type: application/octet-stream" --data-binary "@$file" `
            "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=$name"
        $asset = Read-Json $resp
        if ($asset.state -ne "uploaded") { throw "GitHub 上传 $name 失败：$($asset.message)" }
        "GitHub 已上传 $name"
    }
    "GitHub 发布完成：https://github.com/$repo/releases/tag/$tag"
}

Write-Host "Gitee 和 GitHub 正在同时上传……"
Wait-Job $giteeJob, $githubJob | Out-Null
$failed = @()
foreach ($job in @($giteeJob, $githubJob)) {
    Receive-Job $job -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
    if ($job.State -ne "Completed") {
        $reason = $job.ChildJobs[0].JobStateInfo.Reason
        Write-Host "$($job.Name) 失败：$($reason.Message)" -ForegroundColor Red
        $failed += $job.Name
    }
    Remove-Job $job
}
# 两边都发成功才算发完；有一边失败要修好补发
if ($failed) { throw "没有发完：$($failed -join '、') 失败，两边都成功才算发布完成" }
Write-Host "两边都发布完成"
