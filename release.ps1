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

# PowerShell 5.1 在 $ErrorActionPreference="Stop" 下会把原生命令写到 stderr 的任何一行
# （git 的进度提示、curl 的告警、javac 的过时警告）当成终止错误，脚本会在中途静默中断，
# 后面的 Copy-Item 和 update.json 生成全部不执行，release 目录就停留在上一次的旧产物。
# 所以所有原生命令都走下面的 helper：临时降级为 Continue，只按退出码判定成败。
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [switch]$PassThruOutput   # 需要捕获 stdout 时打开
    )
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($PassThruOutput) {
            $text = & $FilePath @Arguments 2>$null
            $code = $LASTEXITCODE
            return [pscustomobject]@{ ExitCode = $code; Text = ($text -join "`n") }
        } else {
            & $FilePath @Arguments 2>$null | Out-Null
            return $LASTEXITCODE
        }
    } finally {
        $ErrorActionPreference = $old
    }
}

# git 一定会往 stderr 写进度，直接调用会触发 NativeCommandError，所以重定向到文件后看退出码
function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$GitArgs)
    $logDir = Join-Path $root "HMCL\build"
    New-Item -ItemType Directory -Force $logDir | Out-Null
    $o = Join-Path $logDir "release-git.log"
    $e = Join-Path $logDir "release-git.err.log"
    $p = Start-Process -FilePath "git" -ArgumentList $GitArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $o -RedirectStandardError $e
    Get-Content $e -ErrorAction SilentlyContinue | ForEach-Object { if ($_ -notmatch '^\s*$') { Write-Host $_ } }
    return $p.ExitCode
}

if ($Version -notmatch '^\d+(\.\d+)+$') { throw "版本号要像 1.1 或 1.2.3 这样" }
$tag = "v$Version"

# 1. 改版本号
$propFile = Join-Path $root "config\project.properties"
$props = [IO.File]::ReadAllText($propFile)
$props = [regex]::Replace($props, '(?m)^versionRoot=.*$', "versionRoot=$Version")
[IO.File]::WriteAllText($propFile, $props, (New-Object Text.UTF8Encoding($false)))
Write-Host "版本号已改成 $Version"

# 2. 编译（会用 fsl-secrets.properties 里的 signature.key 签名）
# gradle 会把 javac 警告写到 stderr，直接 & 调用时在 $ErrorActionPreference="Stop" 下会被当成
# 终止错误，脚本在编译后就中断（Copy-Item、update.json 都不会执行）。所以把两个流都重定向到
# 日志文件，只按退出码判断成败。
$buildLogDir = Join-Path $root "HMCL\build"
New-Item -ItemType Directory -Force $buildLogDir | Out-Null
$buildOut = Join-Path $buildLogDir "release-build.log"
$buildErr = Join-Path $buildLogDir "release-build.err.log"
$build = Start-Process -FilePath (Join-Path $root "gradlew.bat") `
    -ArgumentList "--no-daemon", ":HMCL:makeExecutables" `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
if ($build.ExitCode -ne 0) {
    Write-Host "编译失败，日志尾部："
    Get-Content $buildOut -Tail 30 -ErrorAction SilentlyContinue | Write-Host
    Get-Content $buildErr -Tail 30 -ErrorAction SilentlyContinue | Write-Host
    throw "编译失败"
}

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
$out = Join-Path $root "HMCL\build\release"
New-Item -ItemType Directory -Force $out | Out-Null

# jarsha1 必须等于这个 jar 的真实 SHA-1。历史上这里被写成全 0，
# 启动器下载完做校验直接抛 ChecksumMismatchException（expected: 000…, actual: 真实值）。
$sha1 = (Get-FileHash -Path $jar -Algorithm SHA1).Hash.ToLower()
if ($sha1 -notmatch '^[0-9a-f]{40}$' -or $sha1 -eq ('0' * 40)) {
    throw "jar 的 SHA-1 计算异常：'$sha1'（$jar）"
}
Write-Host "jar SHA-1 = $sha1"

$update = [ordered]@{
    version   = $Version
    jar       = "https://gitee.com/$repo/releases/download/$tag/FSL-$Version.jar"
    mirrors   = @("https://github.com/$repo/releases/download/$tag/FSL-$Version.jar")
    jarsha1   = $sha1
    force     = [bool]$Force
    changelog = ($Notes -replace '`n', "`n")
    page      = "https://gitee.com/$repo/releases/tag/$tag"
}
$json = Join-Path $out "update.json"
[IO.File]::WriteAllText($json, ($update | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))

# 回读校验：写盘被跳过、编码错、字段丢空都在这一步暴露，不让坏文件流到上传
$check = [IO.File]::ReadAllText($json, [Text.Encoding]::UTF8) | ConvertFrom-Json
if ($check.version -ne $Version) { throw "update.json 回读校验失败：version=$($check.version)，期望 $Version" }
if ($check.jarsha1 -ne $sha1) { throw "update.json 回读校验失败：jarsha1=$($check.jarsha1)，期望 $sha1" }
if (([string]$check.jarsha1).Length -ne 40) { throw "update.json 回读校验失败：jarsha1 长度异常" }

# 产物同步：先删同名旧文件再复制（避免旧文件被占用时静默保留），复制后按哈希核对
foreach ($f in @($jar, $exe)) {
    $dest = Join-Path $out ([IO.Path]::GetFileName($f))
    if (Test-Path $dest) { Remove-Item $dest -Force }
    Copy-Item $f $dest -Force
    if ((Get-FileHash $f -Algorithm SHA256).Hash -ne (Get-FileHash $dest -Algorithm SHA256).Hash) {
        throw "复制后校验不一致：$dest"
    }
}
Write-Host "已生成 $out（update.json、FSL-$Version.jar、FSL-$Version.exe）"

if ($NoUpload) { Write-Host "没有上传（-NoUpload）"; exit 0 }

# 4. Gitee 和 GitHub 同时发布（两个后台任务并行上传，不分先后）
$secrets = Get-Content (Join-Path $root "fsl-secrets.properties") -Encoding UTF8
$giteeToken = (($secrets | Where-Object { $_ -like "gitee.token=*" }) -replace '^gitee\.token=', '').Trim()
if (-not $giteeToken) { throw "fsl-secrets.properties 里没有 gitee.token" }
# GitHub 用 git 保存的登录（gh 在这台电脑上连 API 会断）
# credential fill 走管道，仍是原生命令，临时降级 ErrorActionPreference 以免 stderr 提示中断脚本
$credText = $null
$oldEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $credText = ("protocol=https`nhost=github.com`n`n" | git credential fill 2>$null) -join "`n"
} finally {
    $ErrorActionPreference = $oldEap
}
$githubToken = (($credText -split "`r?`n") | Where-Object { $_ -like "password=*" }) -replace '^password=', ''
if (-not $githubToken) { throw "找不到 GitHub 登录，先用 git push 登录一次" }
# 直连 GitHub 很慢，SakuraCat 代理（127.0.0.1:12450）能连上 GitHub 就让 GitHub 走代理
# 只看端口开着不够：代理有时连 GitHub 会握手失败，所以先真的请求一次 GitHub
$githubProxy = ""
$probe = Invoke-Native -FilePath "curl.exe" -PassThruOutput -Arguments @(
    "-s", "-m", "15", "-x", "http://127.0.0.1:12450", "-o", "NUL", "-w", "%{http_code}",
    "https://api.github.com/zen")
$probeCode = ([string]$probe.Text).Trim()
if ($probeCode -eq "200") { $githubProxy = "http://127.0.0.1:12450" } else { Write-Host "代理连不上 GitHub（$probeCode），GitHub 改为直连" }
# 发布是基于 main 打 tag 的，先把代码推到两边
if ((Invoke-Git -GitArgs @("push", "gitee", "main")) -ne 0) { throw "推送到 Gitee 失败" }
if ($githubProxy) {
    if ((Invoke-Git -GitArgs @("-c", "http.proxy=$githubProxy", "push", "origin", "main")) -ne 0) { throw "推送到 GitHub 失败" }
} else {
    if ((Invoke-Git -GitArgs @("push", "origin", "main")) -ne 0) { throw "推送到 GitHub 失败" }
}

# 两边共用的参数；curl 结果写进文件再按 UTF-8 读，避免后台任务里中文乱码
# 发布页说明 = 更新说明 + 下载说明（误报怎么办、jar 怎么用）；启动器更新弹窗只显示 update.json 里的更新说明
$helpFile = Join-Path $root "release-download-help.md"
$releaseBody = $update.changelog
if (Test-Path $helpFile) { $releaseBody += "`n" + ([IO.File]::ReadAllText($helpFile, [Text.Encoding]::UTF8) -replace '\{version\}', $Version) }
$jobArgs = @($repo, $tag, $Version, $out, $releaseBody)

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
