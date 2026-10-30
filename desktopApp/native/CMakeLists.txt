# The Automix analyser, built for the desktop.
#
# Deliberately the same sources the Android build compiles: `native/analyzer`
# is already platform-neutral C++17 with no dependency beyond the standard
# library, and `app/src/main/cpp/jni` is plain JNI with nothing Android about
# it. Reusing both verbatim is what keeps the desktop's tempo, beat grid and
# mel front end bit-identical to the source of truth rather than a second
# implementation that drifts.
#
# The JNI symbol names encode the Java package, which is why the desktop's
# analysis classes live in `com.music.bitchord.playback.smart` exactly as
# Android's do.

cmake_minimum_required(VERSION 3.16)

if(POLICY CMP0091)
    cmake_policy(SET CMP0091 NEW)
endif()

project(bitchord_analysis LANGUAGES CXX)

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(bitchord_analysis SHARED
    ${REPO_ROOT}/native/analyzer/audio_analysis.cpp
    ${REPO_ROOT}/native/analyzer/tempo_analysis.cpp
    ${REPO_ROOT}/native/analyzer/resampler.cpp
    ${REPO_ROOT}/native/analyzer/mel_spectrogram.cpp
    ${REPO_ROOT}/native/analyzer/vocal_spectrogram.cpp
    ${REPO_ROOT}/app/src/main/cpp/jni/analysis_jni.cpp
    ${REPO_ROOT}/app/src/main/cpp/jni/mel_jni.cpp
    ${REPO_ROOT}/app/src/main/cpp/jni/vocal_jni.cpp
)

target_include_directories(bitchord_analysis PRIVATE
    ${REPO_ROOT}/native
    ${REPO_ROOT}/app/src/main/cpp
)

# JNI headers come from the JDK running the build rather than from find_package,
# which on a JDK with no JAVA_HOME set reports a stale system install.
if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_analysis PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/linux
        $ENV{JAVA_HOME}/include/win32
        $ENV{JAVA_HOME}/include/darwin
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_analysis PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Cross-building for Windows, the one case where the JDK running the build has
# no header for the platform being built: `jni.h` is shared, `jni_md.h` is not.
# Listed after the JDK's own paths so a real Windows JDK always wins.
if(CMAKE_SYSTEM_NAME STREQUAL "Windows" AND NOT CMAKE_HOST_WIN32)
    target_include_directories(bitchord_analysis PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/win32)
endif()

target_compile_options(bitchord_analysis PRIVATE -O2)

# `System.mapLibraryName` asks for `bitchord_analysis.dll` on Windows, with no
# prefix — but MinGW's CMake defaults to the Unix `lib` one, so a cross-built
# library came out as `libbitchord_analysis.dll` and the loader never found it.
# Set on the target rather than in the toolchain file, because `project()`
# reinitialises CMAKE_SHARED_LIBRARY_PREFIX per language and overwrites
# anything the toolchain set beforehand.
if(WIN32)
    set_target_properties(bitchord_analysis PROPERTIES PREFIX "")
endif()

# The app unpacks this DLL into its private runtime directory and loads it from
# there, where MinGW's own libstdc++/libgcc/winpthread DLLs are not. libgcc and
# libstdc++ stay statically linked for that reason. The bare -static flag is
# deliberately NOT used: on MSYS2 UCRT GCC 16 it forces a static CRT whose
# libmingwex references __mingw_fe_dfl_env with no definition, so the link of
# BOTH DLLs fails (proven 26 Oct 2026). The CRT stays dynamic (system UCRT,
# present on Windows 10/11); if a built DLL still imports a MinGW runtime DLL
# (check with objdump -p), ship that DLL beside it instead of re-adding -static.
if(MINGW)
    target_link_options(bitchord_analysis PRIVATE -static-libgcc -static-libstdc++)
    # Static winpthread: this MUST live in target_link_libraries (late, after
    # the objects) — an early -lwinpthread is skipped by ld before the objects
    # that need it are seen, and the DLL keeps importing libwinpthread-1.dll.
    target_link_libraries(bitchord_analysis PRIVATE -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic)
endif()

# The Windows media controls, which are WinRT. MSVC builds them with C++/WinRT;
# MinGW has no C++/WinRT, so it builds smtc_abi.cpp — the same exports written
# against the raw ABI headers — whenever its headers carry the Media namespace.
include(CheckIncludeFileCXX)
if(MINGW)
    check_include_file_cxx(systemmediatransportcontrolsinterop.h BITCHORD_HAVE_SMTC_INTEROP)
    # The same guard smtc_abi.cpp defines: MinGW's windows.foundation.h does not compile without it.
    set(CMAKE_REQUIRED_DEFINITIONS -D____FIReference_1_BYTE_INTERFACE_DEFINED__)
    check_include_file_cxx(windows.media.h BITCHORD_HAVE_WINRT_MEDIA)
    unset(CMAKE_REQUIRED_DEFINITIONS)
endif()
if(MINGW AND BITCHORD_HAVE_SMTC_INTEROP AND BITCHORD_HAVE_WINRT_MEDIA)
    add_library(bitchord_smtc SHARED ${CMAKE_CURRENT_SOURCE_DIR}/smtc/smtc_abi.cpp)
    set_target_properties(bitchord_smtc PROPERTIES PREFIX "")
    if(DEFINED ENV{JAVA_HOME})
        target_include_directories(bitchord_smtc PRIVATE $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/win32)
    else()
        find_package(JNI REQUIRED)
        target_include_directories(bitchord_smtc PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    if(NOT CMAKE_HOST_WIN32)
        target_include_directories(bitchord_smtc PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/win32)
    endif()
    target_compile_options(bitchord_smtc PRIVATE -O2)
    target_link_libraries(bitchord_smtc PRIVATE runtimeobject ole32 user32 -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic)
    # Loaded from the app's private runtime directory, where MinGW's own DLLs are not.
    # Same rule as bitchord_analysis above: libgcc/libstdc++ static, bare -static
    # omitted (it breaks the link on MSYS2 UCRT GCC 16: __mingw_fe_dfl_env).
    target_link_options(bitchord_smtc PRIVATE -static-libgcc -static-libstdc++)
elseif(MSVC)
    add_library(bitchord_smtc SHARED ${CMAKE_CURRENT_SOURCE_DIR}/smtc/smtc_jni.cpp)
    # C++20 rather than the 17 the analyser uses: under 17, C++/WinRT reaches for
    # <experimental/coroutine>, and current MSVC refuses to compile that header
    # at all. C++20 gives it the standard <coroutine> instead.
    set_target_properties(bitchord_smtc PROPERTIES PREFIX "" CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
    if(DEFINED ENV{JAVA_HOME})
        target_include_directories(bitchord_smtc PRIVATE $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/win32)
    else()
        find_package(JNI REQUIRED)
        target_include_directories(bitchord_smtc PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    # The WinRT umbrella import library.
    target_link_libraries(bitchord_smtc PRIVATE windowsapp gdi32)
endif()
