cmake_minimum_required(VERSION 3.22.1)  # modern, matches your AGP/NDK

project("midiplayer" LANGUAGES C CXX)

# ────────────────────────────────────────────────────────────────
# Path to libADLMIDI (assuming git submodule or folder in project root)
# Adjust if your folder name/location is different
# ────────────────────────────────────────────────────────────────
set(LIBADLMIDI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../libADLMIDI")

# Include libADLMIDI headers
include_directories("${LIBADLMIDI_ROOT}/include")

# Add libADLMIDI as subdirectory (builds it as static lib by default)
add_subdirectory("${LIBADLMIDI_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/libADLMIDI")

# ────────────────────────────────────────────────────────────────
# Your JNI wrapper library
# ────────────────────────────────────────────────────────────────
add_library(midiplayer

    SHARED

    native-lib.cpp          # your JNI file from previous steps
    # Add more .cpp files here if you split the code later
)

# Link against libADLMIDI (static) + Android log
target_link_libraries(midiplayer
    PRIVATE
        ADLMIDI_static      # ← this is the target name created by libADLMIDI's CMake
        log                 # Android logging
)

# Recommended compile definitions (you can change these)
target_compile_definitions(midiplayer
    PRIVATE
        USE_NUKED_EMULATOR=ON      # high quality, but more CPU
        USE_DOSBOX_EMULATOR=OFF    # disable if you want only Nuked
        #USE_YMFM_EMULATOR=OFF      # alternative modern emulator (if compiled in)
)

# Optimize for size & performance on Android
target_compile_options(midiplayer
    PRIVATE
        -Os
        -ffunction-sections
        -fdata-sections
)

# Linker flags to reduce binary size
target_link_options(midiplayer
    PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)

# Optional: force smaller page alignment (helps on some Android devices)
target_link_options(midiplayer
    PUBLIC
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)