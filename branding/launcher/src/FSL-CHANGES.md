# Flint Spark Launcher Windows 启动器桩（改动记录）

本目录是 Flint Spark Launcher（以下简称 FSL）使用的 Windows 启动器桩源码，基于
[HMCL-dev/HMCLauncher](https://github.com/HMCL-dev/HMCLauncher) 修改而来
（上游提交 `da0e0c3b4f01fa0edbaf575d9631f093daf70cd1`）。

上游项目使用 GPLv3 授权，并附有 GPLv3 第 7 条附加条款。本目录按同一授权公开发布，
便于分发 FSL 的二进制时履行源码提供义务。

## 与上游的差异

1. 品牌**: 版本资源中的公司名、产品名、文件描述、内部名与版权信息改为 FSL 品牌
   （`HMCL.rc.in`），产品版本号改为 `1.1.0.0` 以区分上游原版。
2. 缺少 Java 时的行为**: 上游在找不到 Java 17+ 时会用浏览器打开官网下载页；FSL 改为
   自行完成下载与安装：
   - 依次尝试国内镜像（清华 Adoptium 镜像，按目录清单里最新的 JRE 包）与上游官方源
     （Temurin JRE 21 → Temurin JRE 17 → Microsoft Build of OpenJDK 21），
     x86 平台只有 Temurin 提供 32 位运行时；
   - 下载到 `jre-download.zip`（支持 3xx 跳转、断点续传、取消后清理残片）；
   - 解压到 `.jre-staging` 并跳过归档顶层目录，校验 `bin\javaw.exe` 存在；
   - 移动到 exe 同目录的 `jre-x64` / `jre-arm64` / `jre-x86`（旧目录先备份为
     `<target>.old`，失败可回滚）；
   - 重新启动自身，随后走既有的"内置 JRE"查找分支进入主界面。
   下载失败或用户取消时才回退到上游的浏览器下载页行为。
3. 下载源可通过环境变量 `FSL_JAVA_DOWNLOAD_URL` 强制指定，便于测试与自建镜像。
4. 若 exe 同目录已存在 `jre-<arch>`，则完全不触发下载，查找顺序与上游一致：
   `HMCL_JAVA_HOME` → 内置 `jre-*` → `JAVA_HOME` → `%APPDATA%\.hmcl\java` → `PATH`
   → Program Files 厂商目录 → 注册表 `JavaSoft`，要求 Java 17 及以上。

## 新增 / 修改文件

| 文件 | 说明 |
| --- | --- |
| `HMCL/bootstrap.cpp` / `bootstrap.h` | 下载、安装、重启自身的总流程与下载源选择 |
| `HMCL/download.cpp` / `download.h` | WinHTTP 下载（跳转、断点续传、取消清理）与文本资源获取 |
| `HMCL/archive.cpp` / `archive.h` | 基于 miniz 的 zip 解压（UTF-16 路径、路径穿越防护）、递归删除与目录遍历 |
| `HMCL/progress.cpp` / `progress.h` | 原生 `IProgressDialog` 进度窗口 |
| `HMCL/i18n.cpp` / `i18n.h` | 新增下载相关的中英文文案（简体 / 英文） |
| `HMCL/main.cpp` | 无 Java 分支先尝试自动下载安装，失败后回退上游的下载页行为 |
| `third_party/miniz` | miniz 3.0.2（MIT），仅用于解压 zip |

其余文件与上游保持一致。

## 构建

```powershell
# 默认 x86（与现有 12MB exe 的桩架构一致），产物落在 %TEMP%\fsl-launcher-build
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 安装到 branding\launcher\HMCLauncher.exe（gradle 打包时会把它放进 assets）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
```

脚本需要已安装 Visual Studio Build Tools（含 MSVC 与 Windows SDK）。也可以直接用
CMake：`cmake -A Win32 -B build && cmake --build build --config Release`。

## 与 Java 侧的衔接

`HMCL/src/main/java/org/jackhuang/hmcl/upgrade/ExecutableHeaderHelper.java` 在打包时
把 `assets/HMCLauncher.exe` 追加到 jar 前面，形成最终的单文件 exe；因此本桩的产物大小
变化只会影响 exe 头部体积（新增约 230 KB），不影响产物形态。
