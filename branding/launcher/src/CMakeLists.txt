cmake_minimum_required(VERSION 3.16)
project(HMCLauncher
        LANGUAGES C CXX
        VERSION 1.1.0.0
)

# The launcher stub is derived from https://github.com/HMCL-dev/HMCLauncher
# (GPLv3). Flint Spark Launcher changed the branding and added the automatic
# Java runtime download; see UPSTREAM-README.md and LICENSE in this directory.
# The target keeps the name HMCLauncher because the Gradle build and
# release.ps1 expect branding/launcher/HMCLauncher.exe as the launcher stub.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(CMAKE_WIN32_EXECUTABLE ON)

set(HMCL_EXPECTED_JAVA_MAJOR_VERSION 17)

add_compile_definitions(UNICODE _UNICODE)
add_compile_definitions(WINVER=0x0601 _WIN32_WINNT=0x0601) # Windows 7

if (MSVC)
    add_compile_options(/utf-8 /W4 /MT)
    add_link_options(/ENTRY:wWinMainCRTStartup)
else ()
    add_compile_options(-municode -Wall -Wextra -Wpedantic)
    add_link_options(-municode -static)
endif ()

include_directories(${CMAKE_CURRENT_BINARY_DIR})
include_directories(third_party/miniz)

configure_file(fsl.ico ${CMAKE_CURRENT_BINARY_DIR}/fsl.ico COPYONLY)
configure_file(HMCL/HMCL.rc.in ${CMAKE_CURRENT_BINARY_DIR}/HMCL.rc @ONLY)
configure_file(HMCL/config.h.in ${CMAKE_CURRENT_BINARY_DIR}/config.h @ONLY)

add_executable(HMCLauncher WIN32
        ${CMAKE_CURRENT_BINARY_DIR}/HMCL.rc
        HMCL/main.cpp
        HMCL/platform.cpp HMCL/platform.h
        HMCL/i18n.cpp HMCL/i18n.h
        HMCL/java.cpp HMCL/java.h
        HMCL/path.cpp HMCL/path.h
        HMCL/debug.cpp HMCL/debug.h
        HMCL/progress.cpp HMCL/progress.h
        HMCL/download.cpp HMCL/download.h
        HMCL/archive.cpp HMCL/archive.h
        HMCL/bootstrap.cpp HMCL/bootstrap.h
        third_party/miniz/miniz.c
)

# miniz is third party code, keep its warnings quiet.
if (MSVC)
    set_source_files_properties(third_party/miniz/miniz.c PROPERTIES COMPILE_OPTIONS "/W0")
else ()
    set_source_files_properties(third_party/miniz/miniz.c PROPERTIES COMPILE_OPTIONS "-w")
endif ()

target_link_libraries(HMCLauncher PRIVATE shlwapi version winhttp ole32 shell32 user32 advapi32 comctl32)
