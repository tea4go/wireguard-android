# SPDX-License-Identifier: Apache-2.0
#
# Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.

cmake_minimum_required(VERSION 3.4.1)
project("WireGuard")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_link_options(LINKER:--build-id=none)
add_compile_options(-Wall -Werror)

add_executable(libwg-quick.so wireguard-tools/src/wg-quick/android.c ndk-compat/compat.c)
target_compile_options(libwg-quick.so PUBLIC -std=gnu11 -include ${CMAKE_CURRENT_SOURCE_DIR}/ndk-compat/compat.h -DWG_PACKAGE_NAME=\"${ANDROID_PACKAGE_NAME}\")
target_link_libraries(libwg-quick.so -ldl)

file(GLOB WG_SOURCES wireguard-tools/src/*.c ndk-compat/compat.c)
add_executable(libwg.so ${WG_SOURCES})
target_include_directories(libwg.so PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/wireguard-tools/src/uapi/linux/" "${CMAKE_CURRENT_SOURCE_DIR}/wireguard-tools/src/")
target_compile_options(libwg.so PUBLIC -std=gnu11 -include ${CMAKE_CURRENT_SOURCE_DIR}/ndk-compat/compat.h -DRUNSTATEDIR=\"/data/data/${ANDROID_PACKAGE_NAME}/cache\")

if(CMAKE_HOST_WIN32)
    # libwg-go's Makefile requires flock(1) and downloads a Linux-hosted Go
    # toolchain, neither of which works on Windows. Invoke a native Windows Go
    # toolchain instead (provisioned by scripts/build-win.ps1, exposed via
    # WG_GO_EXE/WG_GOROOT).
    if(NOT DEFINED ENV{WG_GO_EXE} OR NOT EXISTS "$ENV{WG_GO_EXE}")
        message(FATAL_ERROR "WG_GO_EXE is not set or does not point to a Go executable. Run scripts/build-win.ps1 so the Windows Go toolchain is provisioned.")
    endif()
    if(NOT DEFINED ENV{WG_GOROOT} OR NOT EXISTS "$ENV{WG_GOROOT}")
        message(FATAL_ERROR "WG_GOROOT is not set or does not point to a Go root. Run scripts/build-win.ps1 so the Windows Go toolchain is provisioned.")
    endif()

    if(CMAKE_ANDROID_ARCH_ABI)
        set(WG_ANDROID_ABI "${CMAKE_ANDROID_ARCH_ABI}")
    else()
        set(WG_ANDROID_ABI "${ANDROID_ABI}")
    endif()

    if(WG_ANDROID_ABI STREQUAL "arm64-v8a")
        set(WG_GOARCH arm64)
    elseif(WG_ANDROID_ABI STREQUAL "armeabi-v7a")
        set(WG_GOARCH arm)
    elseif(WG_ANDROID_ABI STREQUAL "x86")
        set(WG_GOARCH 386)
    elseif(WG_ANDROID_ABI STREQUAL "x86_64")
        set(WG_GOARCH amd64)
    else()
        message(FATAL_ERROR "Unsupported ABI for wireguard-go: ${WG_ANDROID_ABI}")
    endif()

    # Mirror the Makefile: keep CMake's flags, but drop -mthumb (invalid for
    # arm64/x86) and pin the build-id to none so the shipped .so stays stable.
    string(REGEX REPLACE "-mthumb" "-marm" WG_C_flags "${CMAKE_C_FLAGS}")
    string(REGEX REPLACE "-Wl,--build-id=[^ ]*" "-Wl,--build-id=none" WG_LDFLAGS "${CMAKE_SHARED_LINKER_FLAGS}")

    add_custom_target(libwg-go.so
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go"
        COMMENT "Building wireguard-go"
        VERBATIM
        COMMAND "${CMAKE_COMMAND}" -E env
            "GOROOT=$ENV{WG_GOROOT}"
            "GOOS=android"
            "GOARCH=${WG_GOARCH}"
            "CGO_ENABLED=1"
            "CC=${CMAKE_C_COMPILER}"
            "CGO_CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} ${WG_C_flags}"
            "CGO_LDFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} ${WG_LDFLAGS} -Wl,-soname=libwg-go.so"
            "$ENV{WG_GO_EXE}" build
                -tags linux
                -buildmode c-shared
                -trimpath
                -buildvcs=false
                "-ldflags=-X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/${ANDROID_PACKAGE_NAME}/cache/wireguard -buildid="
                -o "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwg-go.so"
                .
    )
else()
    add_custom_target(libwg-go.so WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go" COMMENT "Building wireguard-go" VERBATIM COMMAND "${ANDROID_HOST_PREBUILTS}/bin/make"
        ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
        ANDROID_PACKAGE_NAME=${ANDROID_PACKAGE_NAME}
        GRADLE_USER_HOME=${GRADLE_USER_HOME}
        CC=${CMAKE_C_COMPILER}
        CFLAGS=${CMAKE_C_FLAGS}
        LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
        SYSROOT=${CMAKE_SYSROOT}
        TARGET=${CMAKE_C_COMPILER_TARGET}
        DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        BUILDDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/../generated-src
    )
endif()

if(NOT CMAKE_HOST_WIN32)
    # Strip unwanted ELF sections to prevent DT_FLAGS_1 warnings on old Android versions
    # Skipped on Windows: elf-cleaner needs mmap(2) and CMake invokes it without an
    # .exe suffix (cmd.exe cannot run that). Verified a no-op for these artifacts at
    # api-level 24 — no PT_TLS segments, no DT_AARCH64_BTI_PLT/PAC_PLT tags, and
    # DT_FLAGS_1 only carries NOW/NODELETE, all supported since API 23.
    file(GLOB ELF_CLEANER_SOURCES elf-cleaner/*.c elf-cleaner/*.cpp)
    add_custom_target(elf-cleaner COMMENT "Building elf-cleaner" VERBATIM COMMAND cc
            -O2 -DPACKAGE_NAME="elf-cleaner" -DPACKAGE_VERSION="" -DCOPYRIGHT=""
            -o "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner" ${ELF_CLEANER_SOURCES}
    )
    add_custom_command(TARGET libwg.so POST_BUILD VERBATIM COMMAND "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner"
            --api-level "${ANDROID_NATIVE_API_LEVEL}" "$<TARGET_FILE:libwg.so>")
    add_dependencies(libwg.so elf-cleaner)
    add_custom_command(TARGET libwg-quick.so POST_BUILD VERBATIM COMMAND "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner"
            --api-level "${ANDROID_NATIVE_API_LEVEL}" "$<TARGET_FILE:libwg-quick.so>")
    add_dependencies(libwg-quick.so elf-cleaner)
endif()
