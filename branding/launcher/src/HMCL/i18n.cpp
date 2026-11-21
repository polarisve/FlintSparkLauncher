#include <windows.h>

#include "i18n.h"

#include "config.h"

HLI18N HLI18N::Instance() {
  HLI18N i18n = {.errorSelfPath = L"Failed to get the exe path.",
                 .errorInvalidHMCLJavaHome =
                     L"The Java path specified by HMCL_JAVA_HOME is invalid. Please update it to a valid Java "
                     "installation path or remove this environment variable.",
                 .errorJavaNotFound =
                     L"HMCL requires Java " HMCL_EXPECTED_JAVA_MAJOR_VERSION_STR " or later to run.\n"
                     L"Click 'OK' to open the Java download page.\n"
                     L"Please restart Flint Spark Launcher after installing Java.",
                 .javaDownloadTitle = L"Flint Spark Launcher - Download Java Runtime",
                 .javaDownloadPrompt =
                     L"No Java {} or later was found on this computer.\n\n"
                     L"Download a Java runtime (about {} MB) and install it to:\n{}\n\n"
                     L"Flint Spark Launcher will start automatically when the download is complete.",
                 .javaDownloadConnecting = L"Connecting to the download server...",
                 .javaDownloadCancelMessage = L"Cancelling the download...",
                 .javaDownloadExtracting = L"Extracting the Java runtime... ({} / {})",
                 .javaDownloadFailed = L"Failed to download or install the Java runtime.\n\n{}"};

  const auto language = GetUserDefaultUILanguage();
  if (language == 2052) {  // zh-CN
    i18n.errorSelfPath = L"获取程序路径失败。";
    i18n.errorInvalidHMCLJavaHome = L"HMCL_JAVA_HOME 所指向的 Java 路径无效，请更新或删除该变量。\n";
    i18n.errorJavaNotFound =
        L"HMCL 需要 Java " HMCL_EXPECTED_JAVA_MAJOR_VERSION_STR " 或更高版本才能运行。\n"
        L"点击“确定”打开 Java 下载页面。\n"
        L"请在安装 Java 完成后重新启动 Flint Spark Launcher。";
    i18n.javaDownloadTitle = L"Flint Spark Launcher - 下载 Java 运行时";
    i18n.javaDownloadPrompt =
        L"未在本机找到 Java {} 或更高版本。\n\n"
        L"是否现在下载 Java 运行时（约 {} MB）并安装到：\n{}\n\n"
        L"下载完成后会自动启动 Flint Spark Launcher。";
    i18n.javaDownloadConnecting = L"正在连接下载服务器…";
    i18n.javaDownloadCancelMessage = L"正在取消下载…";
    i18n.javaDownloadExtracting = L"正在解压 Java 运行时…（{} / {}）";
    i18n.javaDownloadFailed = L"下载或安装 Java 运行时失败。\n\n{}";
  }
  return i18n;
}
