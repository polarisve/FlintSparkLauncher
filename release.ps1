# FSL 一键发版：改版本号 -> 编译并签名 -> 生成 update.json -> 发布到 Gitee 和 GitHub Releases
# 两边同时上传；启动器优先从 Gitee 检查和下载更新，Gitee 不通再用 GitHub
# 用法（在 FSL 目录里）:
#   powershell -ExecutionPolicy Bypass -File release.ps1 -Version 1.1 -Notes "修复了xxx`n新增了xxx"
# 发版会自动额外生成 FSL-<版本>-win-x64.zip（exe + jre-x64 内置运行时 + 预置 JavaFX），
# 没装 Java 的电脑解压后双击 zip 里的 exe 即可启动；不想出整包时加 -SkipBundle，
# jlink 找不到合适 JDK 时用 -JdkHome 'C:\Program Files\Java\jdk-21' 指定（必须是带 jmods 的完整 JDK）。
# GitHub 用 git 保存的登录（不需要 gh）；Gitee 用 fsl-secrets.properties 里的 gitee.token
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = "",
    [switch]$Force,      # 加上 -Force 就是强制更新
    [switch]$NoUpload,   # 只编译和生成文件，不上传
    [string]$JdkHome = "",                    # 构建内置运行时用的 JDK（必须含 jmods）；留空自动探测 JAVA_HOME/PATH
    [string]$BundleExeName = "Flint Spark Launcher.exe",  # 整包里 exe 的显示名（解压后双击的就是它）
    [switch]$SkipBundle  # 跳过内置运行时/预置 JavaFX/整包 zip（回到只发 jar+exe 的旧流程）
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

# ============================================================
# 内置运行时 / 预置 JavaFX / 整包 zip
# 目的：让最终分发包在没有安装任何 Java 的 Windows 上解压即可双击启动
#   - 启动器桩会按顺序找 Java：HMCL_JAVA_HOME -> JAVA_HOME -> PATH -> 同目录 jre-arm64/jre-x64/jre-x86
#     所以把 jlink 产物放到 exe 同级的 jre-x64\bin\javaw.exe 就能被自动识别
#   - JavaFX 走 SelfDependencyPatcher 的本地缓存：<exe目录>\.fsl\dependencies\windows-x86_64\openjfx
#     文件名必须与 openjfx-dependencies.json 里的 artifactId-version-classifier.jar 完全一致，且 SHA-1 要对得上
#     （对不上会重新联网下载，纯离线机器就会报 Missing JavaFX environment）
# ============================================================
$script:JreDirName = "jre-x64"
# 模块集：java.desktop（JavaFX 补丁用到的 Swing 进度框/Headless 检测）、java.management、
# jdk.unsupported（Unsafe）、jdk.crypto.ec（HTTPS ECDHE 握手）、jdk.localedata（中日韩排序）、
# jdk.charsets（GBK 解压/日志）、jdk.zipfs（jar 文件系统）、java.logging/java.naming/java.xml/java.net.http
$script:BundleModules = @(
    "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.xml", "java.scripting",
    "jdk.crypto.ec", "jdk.localedata", "jdk.unsupported", "jdk.zipfs", "jdk.charsets"
)
$script:OpenJFXRepos = @(
    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
    "https://repo1.maven.org/maven2"
)

function Resolve-BuildJdk {
    param([string]$Explicit)
    $cands = @()
    if ($Explicit) { $cands += $Explicit }
    if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) { $cands += (Split-Path (Split-Path $javaCmd.Source -Parent) -Parent) }
    foreach ($c in $cands) {
        if (-not $c) { continue }
        $c = $c.TrimEnd('\')
        $jlink = Join-Path $c "bin\jlink.exe"
        $jmods = Join-Path $c "jmods"
        if ((Test-Path $jlink) -and (Test-Path $jmods)) { return $c }
    }
    throw "找不到带 jmods 的完整 JDK（jlink 需要）。请用 -JdkHome 指定，例如 -JdkHome 'C:\Program Files\Java\jdk-21'"
}

function New-BundledRuntime {
    param([string]$Jdk, [string]$Destination)
    $jlink = Join-Path $Jdk "bin\jlink.exe"
    if (Test-Path $Destination) {
        # 只允许清理 HMCL\build 下的运行时目录，避免误删别处
        $buildRoot = (Join-Path $root "HMCL\build").TrimEnd('\')
        $fullPath = $Destination.TrimEnd('\')
        if (-not $fullPath.StartsWith($buildRoot + "\", [StringComparison]::OrdinalIgnoreCase)) {
            throw "拒绝清理非预期路径：$Destination"
        }
        Remove-Item $Destination -Recurse -Force
    }
    $jlinkArgs = @(
        "--add-modules", ($script:BundleModules -join ","),
        "--include-locales=en,zh",
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--compress=zip-6",
        "--output", $Destination
    )
    $code = Invoke-Native -FilePath $jlink -Arguments $jlinkArgs
    if ($code -ne 0) { throw "jlink 构建运行时失败（ExitCode=$code）" }
    $javaw = Join-Path $Destination "bin\javaw.exe"
    if (-not (Test-Path $javaw)) { throw "jlink 产物里没有 bin\javaw.exe：$Destination" }
    $releaseFile = Join-Path $Destination "release"
    $verMatch = [regex]::Match((Get-Content $releaseFile -Raw), 'JAVA_VERSION="([^"]+)"')
    $ver = if ($verMatch.Success) { $verMatch.Groups[1].Value } else { "unknown" }
    Write-Host "内置运行时已生成：$Destination（Java $ver）"
    return $ver
}

function Install-BundledJavaFX {
    param([string]$JreDestination, [string]$DependencyRoot)

    $jsonPath = Join-Path $root "HMCL\src\main\resources\assets\openjfx-dependencies.json"
    if (-not (Test-Path $jsonPath)) { throw "找不到 $jsonPath" }

    # 运行时 >= 23 用 modern 分支，否则 classic（与 SelfDependencyPatcher.readDependencies 的判定保持一致）
    $releaseFile = Join-Path $JreDestination "release"
    $match = [regex]::Match((Get-Content $releaseFile -Raw), 'JAVA_VERSION="(\d+)')
    $feature = if ($match.Success) { [int]$match.Groups[1].Value } else { 0 }
    $flavor = if ($feature -ge 23) { "modern" } else { "classic" }

    $all = [IO.File]::ReadAllText($jsonPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $platform = $all.'windows-x86_64'
    if (-not $platform) { throw "openjfx-dependencies.json 里没有 windows-x86_64" }
    $list = $platform.$flavor
    if (-not $list) { throw "openjfx-dependencies.json 里没有 windows-x86_64/$flavor（运行时 Java $feature）" }

    $cache = Join-Path $root "HMCL\build\cache\openjfx"
    New-Item -ItemType Directory -Force $cache | Out-Null
    # 清掉上一次遗留的 jar（换 JDK 大版本后会残留另一套 modern/classic），避免整包体积虚高
    if (Test-Path $DependencyRoot) {
        Get-ChildItem $DependencyRoot -Filter *.jar -File | Remove-Item -Force
    }
    New-Item -ItemType Directory -Force $DependencyRoot | Out-Null

    foreach ($dep in $list) {
        $fileName = "$($dep.artifactId)-$($dep.version)-$($dep.classifier).jar"
        $want = ([string]$dep.sha1).ToLower()
        $cached = Join-Path $cache $fileName
        $cachedOk = (Test-Path $cached) -and ((Get-FileHash $cached -Algorithm SHA1).Hash.ToLower() -eq $want)
        if (-not $cachedOk) {
            $relative = ($dep.groupId -replace '\.', '/') + "/$($dep.artifactId)/$($dep.version)/$fileName"
            $ok = $false
            foreach ($repoBase in $script:OpenJFXRepos) {
                $url = "$repoBase/$relative"
                $code = Invoke-Native -FilePath "curl.exe" -Arguments @("-sS", "-L", "-m", "300", "-o", $cached, $url)
                if ($code -eq 0 -and (Test-Path $cached) -and ((Get-FileHash $cached -Algorithm SHA1).Hash.ToLower() -eq $want)) { $ok = $true; break }
            }
            if (-not $ok) { throw "下载 JavaFX 依赖失败或 SHA-1 不一致：$fileName（期望 $want）" }
        }
        Copy-Item $cached (Join-Path $DependencyRoot $fileName) -Force
    }

    # 落地自校验：文件名 + SHA-1 都要和 json 一致，否则运行时会重新联网下载
    foreach ($dep in $list) {
        $fileName = "$($dep.artifactId)-$($dep.version)-$($dep.classifier).jar"
        $target = Join-Path $DependencyRoot $fileName
        if (-not (Test-Path $target)) { throw "预置 JavaFX 缺失：$fileName" }
        $actual = (Get-FileHash $target -Algorithm SHA1).Hash.ToLower()
        if ($actual -ne ([string]$dep.sha1).ToLower()) { throw "预置 JavaFX SHA-1 不一致：$fileName（$actual）" }
    }
    Write-Host "已预置 $($list.Count) 个 JavaFX 依赖（$flavor）到 $DependencyRoot"
    return $list.Count
}

function New-ReleaseBundle {
    param(
        [string]$ExeSource,
        [string]$ExeName,
        [string]$ReleaseDir,
        [string]$StageDir,
        [string]$ZipPath
    )
    $buildRoot = Join-Path $root "HMCL\build"
    foreach ($p in @($StageDir)) {
        if ((Split-Path $p -Parent).TrimEnd('\') -ne $buildRoot.TrimEnd('\')) { throw "拒绝清理非预期路径：$p" }
    }
    if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
    New-Item -ItemType Directory -Force $StageDir | Out-Null

    Copy-Item $ExeSource (Join-Path $StageDir $ExeName) -Force
    Copy-Item (Join-Path $ReleaseDir $script:JreDirName) (Join-Path $StageDir $script:JreDirName) -Recurse -Force
    # 只带预置 JavaFX：.fsl 下还有 config\accounts.json、logs、state 等运行时数据，
    # 整个目录复制会把账号/日志打进公开发布包，这里按白名单精确拷贝
    $depSource = Join-Path $ReleaseDir ".fsl\dependencies\windows-x86_64\openjfx"
    if (-not (Test-Path $depSource)) { throw "发布目录缺少预置 JavaFX：$depSource" }
    $depTarget = Join-Path $StageDir ".fsl\dependencies\windows-x86_64\openjfx"
    New-Item -ItemType Directory -Force $depTarget | Out-Null
    Copy-Item (Join-Path $depSource "*.jar") $depTarget -Force

    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($StageDir, $ZipPath, [IO.Compression.CompressionLevel]::Optimal, $false)

    # 整包内容校验：缺运行时或预置依赖的 zip 一旦发出去，用户双击就是"找不到 Java"/"缺少 JavaFX"
    $archive = [IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName -replace '\\', '/' })
        foreach ($need in @($ExeName, "$($script:JreDirName)/bin/javaw.exe")) {
            if ($names -notcontains $need) { throw "整包缺少 $need" }
        }
        $fxCount = @($names | Where-Object { $_ -like ".fsl/dependencies/windows-x86_64/openjfx/javafx-*.jar" }).Count
        if ($fxCount -lt 3) { throw "整包预置 JavaFX 只有 $fxCount 个，期望至少 3 个" }
        $leaked = @($names | Where-Object { $_ -like ".fsl/config/*" -or $_ -like ".fsl/logs/*" -or $_ -like ".fsl/state/*" -or $_ -like ".fsl/cache/*" -or $_ -like ".fsl/private/*" })
        if ($leaked.Count -gt 0) { throw "整包里混入了运行时数据（可能含账号/日志），已中止：$($leaked -join ', ')" }
    } finally {
        $archive.Dispose()
    }
    return (Get-Item $ZipPath).Length
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

# 2.5 内置运行时 + 预置 JavaFX + 整包 zip
# 产物直接落在 HMCL\build\release：exe 与 jre-x64 同级，.fsl\dependencies 内置 JavaFX，解压即用
$out = Join-Path $root "HMCL\build\release"
New-Item -ItemType Directory -Force $out | Out-Null
$zipPath = $null
$zipName = $null
$zipSha256 = $null
$zipSize = 0
$runtimeVersion = $null
if (-not $SkipBundle) {
    $buildJdk = Resolve-BuildJdk -Explicit $JdkHome
    Write-Host "用 $buildJdk 构建内置运行时（jlink）……"
    $jreDir = Join-Path $out $script:JreDirName
    $runtimeVersion = New-BundledRuntime -Jdk $buildJdk -Destination $jreDir

    $depRoot = Join-Path $out ".fsl\dependencies\windows-x86_64\openjfx"
    Install-BundledJavaFX -JreDestination $jreDir -DependencyRoot $depRoot | Out-Null

    $zipName = "FSL-$Version-win-x64.zip"
    $zipPath = Join-Path $out $zipName
    $stage = Join-Path $root "HMCL\build\bundle-stage"
    $zipSize = New-ReleaseBundle -ExeSource $exe -ExeName $BundleExeName -ReleaseDir $out -StageDir $stage -ZipPath $zipPath
    $zipSha256 = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()
    Write-Host ("整包已生成：$zipPath（{0:N1} MB，exe + {1} + 预置 JavaFX）" -f ($zipSize / 1MB), $script:JreDirName)
    Write-Host "整包 SHA-256 = $zipSha256"
}

# 3. 生成 update.json（启动器读的就是这个文件）
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
# 整包（exe + jre-x64 + 预置 JavaFX）照常上传到 GitHub 发布页，但 update.json 不再写 bundle 字段
# （2026-11-28 用户决定：所有版本的 update.json 都不带整包下载链接）。
# 启动器自更新只认 jar/mirrors/jarsha1，去掉 bundle 不影响更新。
$json = Join-Path $out "update.json"
[IO.File]::WriteAllText($json, ($update | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))

# 回读校验：写盘被跳过、编码错、字段丢空都在这一步暴露，不让坏文件流到上传
$check = [IO.File]::ReadAllText($json, [Text.Encoding]::UTF8) | ConvertFrom-Json
if ($check.version -ne $Version) { throw "update.json 回读校验失败：version=$($check.version)，期望 $Version" }
if ($check.jarsha1 -ne $sha1) { throw "update.json 回读校验失败：jarsha1=$($check.jarsha1)，期望 $sha1" }
if (([string]$check.jarsha1).Length -ne 40) { throw "update.json 回读校验失败：jarsha1 长度异常" }
if ($check.PSObject.Properties.Name -contains "bundle") { throw "update.json 回读校验失败：不应包含 bundle 字段" }
if ($zipPath -and -not (Test-Path $zipPath)) { throw "整包不存在：$zipPath" }

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
if ($zipPath) {
    Write-Host ("已生成整包 {0}（{1:N1} MB）：exe + {2} + 预置 JavaFX，无 Java 机器解压双击即可启动" -f $zipName, ($zipSize / 1MB), $script:JreDirName)
} else {
    Write-Host "已跳过内置运行时与整包（-SkipBundle）"
}

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
# 整包说明放最前面：新用户第一眼要看到"解压双击就能用、不需要先装 Java"
if ($zipPath) {
    $bundleHelp = "### 全新安装（电脑没装 Java 也能用）`n" +
        "1. 下载 FSL-$Version-win-x64.zip`n" +
        "2. 解压到任意目录（不要把 exe 单独拖出来，$($script:JreDirName) 必须和它同级）`n" +
        "3. 双击 $BundleExeName`n" +
        "整包内含 Win x64 精简运行时（Java $runtimeVersion）和预置的 JavaFX，首次启动不需要联网补依赖。`n`n"
    $releaseBody = $bundleHelp + $releaseBody
}
$jobArgs = @($repo, $tag, $Version, $out, $releaseBody, $(if ($zipName) { $zipName } else { "" }))

$giteeJob = Start-Job -Name "Gitee" -ArgumentList ($jobArgs + $giteeToken) -ScriptBlock {
    param($repo, $tag, $Version, $out, $changelog, $zipName, $token)
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
    $assets = @("FSL-$Version.jar", "update.json", "FSL-$Version.exe")
    # Gitee 不传整包 zip：附件配额只有 1GB，整包只发 GitHub
    foreach ($name in $assets) {
        $file = Join-Path $out $name
        curl.exe -s -m 1800 -o $resp -X POST -F "access_token=$token" -F "file=@$file" "$api/releases/$($rel.id)/attach_files"
        $asset = Read-Json $resp
        if ($asset.name -ne $name) { throw "Gitee 上传 $name 失败：$($asset.message)" }
        "Gitee 已上传 $name"
    }
    "Gitee 发布完成：https://gitee.com/$repo/releases/tag/$tag"
}

$githubJob = Start-Job -Name "GitHub" -ArgumentList ($jobArgs + $githubToken + $githubProxy) -ScriptBlock {
    param($repo, $tag, $Version, $out, $changelog, $zipName, $token, $proxy)
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
    $assets = @("FSL-$Version.jar", "update.json", "FSL-$Version.exe")
    if ($zipName) { $assets += $zipName }
    foreach ($name in $assets) {
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
