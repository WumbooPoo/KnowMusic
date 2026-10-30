cmake_minimum_required(VERSION 3.16)

project(bitchord_window LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

add_library(bitchord_window SHARED window_jni.cpp)
set_target_properties(bitchord_window PROPERTIES PREFIX "")

if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_window PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_window PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_link_libraries(bitchord_window PRIVATE user32 dwmapi ole32 uuid -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic)

if(MINGW)
    # The desktop app loads this DLL from its private runtime directory. Gradle daemons and
    # installed builds do not inherit the developer toolchain's bin directory, so leaving MinGW's
    # libwinpthread dynamic makes the bridge silently unavailable even though the DLL itself was
    # packaged correctly. Bare -static is deliberately NOT used here: on MSYS2 UCRT GCC 16 it
    # forces a static CRT whose libmingwex references __mingw_fe_dfl_env with no definition,
    # so the link fails (proven 26 Oct 2026 for all three native targets). libgcc/libstdc++
    # stay static; winpthread is static-linked via the -Bstatic pair above, which must sit
    # in the link libraries (late, after the objects) or ld skips the archive.
    target_link_options(bitchord_window PRIVATE -static-libgcc -static-libstdc++)
endif()
