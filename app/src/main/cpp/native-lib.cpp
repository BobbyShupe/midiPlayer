// native-lib.cpp
// JNI wrapper for libADLMIDI (real-time OPL3 MIDI synthesis)

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <unistd.h>  // for usleep
#include <algorithm> // Required for std::min
#include "adlmidi.h"

#define LOG_TAG "OPL_MIDI_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ADL_MIDIPlayer* g_player = nullptr;
static volatile bool g_isActive = false;
static std::atomic<bool> g_isGenerating{false};  // NEW: protects against concurrent reset
static std::atomic<float> g_volume{1.0f};
extern "C" {

// ────────────────────────────────────────────────
// Initialization
// ────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_example_midiPlayer_MainActivity_initAdlMidi(
        JNIEnv* env,
        jobject /* thiz */,
        jint sampleRate)
{
    if (g_player) {
        adl_close(g_player);
        g_player = nullptr;
    }

    g_player = adl_init(static_cast<long>(sampleRate));
    if (!g_player) {
        LOGE("adl_init failed");
        return JNI_FALSE;
    }

    // Sensible mobile defaults
    adl_setNumChips(g_player, 2);               // fuller stereo sound
    adl_setBank(g_player, 0);                   // General MIDI-ish
    adl_setVolumeRangeModel(g_player, 2);       // default volume model
    adl_setLoopCount(g_player, 0);              // no infinite looping

    // Optional: force Nuked if you compiled with it enabled
    // adl_setEmulator(g_player, ADLMIDI_EMU_NUKED);

    g_isActive = false;
    g_isGenerating = false;
    LOGD("libADLMIDI initialized at %d Hz", sampleRate);
    return JNI_TRUE;
}

// ────────────────────────────────────────────────
// Set active flag from Kotlin
// ────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_setIsActive(
        JNIEnv* env,
        jobject /* thiz */,
        jboolean active)
{
    g_isActive = (active == JNI_TRUE);
    LOGD("setIsActive: %d", g_isActive);
}

// ────────────────────────────────────────────────
// Load MIDI file from byte array
// ────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_example_midiPlayer_MainActivity_loadMidiData(JNIEnv* env, jobject, jbyteArray data) {
    if (!g_player) {
        LOGE("loadMidiData: player is null");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(data);
    LOGD("loadMidiData: received %d bytes", len);

    if (len < 14) {  // smallest valid MIDI is ~14 bytes
        LOGE("File too small: %d bytes", len);
        return JNI_FALSE;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        LOGE("GetByteArrayElements failed");
        return JNI_FALSE;
    }

    // Improved header check + log first 16 bytes
    char header[5] = {0};
    memcpy(header, bytes, 4);
    LOGD("File header: %.4s", header);

    if (strncmp(header, "MThd", 4) != 0) {
        // Also log next few bytes in hex
        char hex[100] = "";
        for (int i = 0; i < std::min(16, (int)len); i++) {
            char tmp[8];
            snprintf(tmp, sizeof(tmp), "%02x ", (unsigned char)bytes[i]);
            strncat(hex, tmp, sizeof(hex)-strlen(hex)-1);
        }
        LOGE("Not a MIDI file! Header: %.4s  First bytes: %s", header, hex);
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return JNI_FALSE;
    }

    int result = adl_openData(g_player, (uint8_t*)bytes, len);
    if (result != 0) {
        LOGE("adl_openData failed with code %d", result);
        // If your libADLMIDI is recent enough (check header or git):
        // const char* err = adl_errorInfo(g_player);
        // if (err) LOGE("libADLMIDI error message: %s", err);
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (result != 0) {
        LOGE("adl_openData failed → error code = %d", result);
        // You can also call adl_errorInfo(g_player) if your version supports it
        return JNI_FALSE;
    }

    LOGD("adl_openData success");
    return JNI_TRUE;
}

// ────────────────────────────────────────────────
// Generate the next block of stereo PCM samples
// ────────────────────────────────────────────────
// Update your generateSamples in native-lib.cpp
JNIEXPORT jint JNICALL
Java_com_example_midiPlayer_MainActivity_generateSamples(JNIEnv* env, jobject, jshortArray buffer, jint frames) {
    // 1. If not active or player is null, bail immediately
    if (!g_isActive || !g_player) return 0;

    // 2. Atomic Lock: If we can't get the lock, another thread is
    // likely re-initializing the synth. Return silence (0).
    bool expected = false;
    if (!g_isGenerating.compare_exchange_strong(expected, true)) {
        return 0;
    }

    // 3. Double-check g_player AFTER acquiring the lock
    if (!g_player) {
        g_isGenerating.store(false);
        return 0;
    }

    jshort* out = env->GetShortArrayElements(buffer, nullptr);

    // Perform synthesis
    int samplesGenerated = adl_play(g_player, frames * 2, out);

    // Apply native volume gain
    float vol = g_volume.load();
    for (int i = 0; i < samplesGenerated; i++) {
        out[i] = static_cast<short>(out[i] * vol);
    }

    env->ReleaseShortArrayElements(buffer, out, 0);

    // 4. Release the lock
    g_isGenerating.store(false);
    return samplesGenerated / 2;
}

// ────────────────────────────────────────────────
// Playback control
// ────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_playAdl(JNIEnv*, jobject) {
    if (g_player) {
        g_isActive = true;
        LOGD("Playback activated (generation enabled)");
    } else {
        LOGE("playAdl: g_player is NULL - cannot start");
    }
}

JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_pauseAdl(JNIEnv*, jobject) {
    g_isActive = false;
    LOGD("Playback paused");
}

JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_stopAdl(JNIEnv*, jobject) {
    g_isActive = false;

    if (g_player) {
        // Wait until no generation is in progress
        int waitMs = 0;
        while (g_isGenerating.load() && waitMs < 50) {
            usleep(1000);  // 1 ms
            waitMs++;
        }
        if (g_isGenerating.load()) {
            LOGW("stopAdl: generation still active after 50 ms wait");
        }
        adl_reset(g_player);
        LOGD("Playback stopped & sequencer reset");
    } else {
        LOGW("stopAdl: g_player is NULL");
    }
}

JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_setAdlVolume(JNIEnv*, jobject, jfloat vol) {
    // Store in atomic float so the audio thread picks it up immediately
    g_volume.store(vol);
}


// ────────────────────────────────────────────────
// Status queries
// ────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_example_midiPlayer_MainActivity_isAdlPlaying(JNIEnv*, jobject) {
    return g_isActive ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_midiPlayer_MainActivity_getAdlPositionMs(JNIEnv*, jobject) {
    if (!g_player) return 0;
    double posSec = adl_positionTell(g_player);
    return static_cast<jint>(posSec * 1000.0);
}

JNIEXPORT jint JNICALL
Java_com_example_midiPlayer_MainActivity_getAdlDurationMs(JNIEnv*, jobject) {
    if (!g_player) return 0;
    double lenSec = adl_totalTimeLength(g_player);
    return static_cast<jint>(lenSec * 1000.0);
}

// ────────────────────────────────────────────────
// Cleanup
// ────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_releaseAdl(JNIEnv*, jobject) {
    g_isActive = false; // Signal stop

    // ATOMIC LOCK: Prevent generation from starting
    bool expected = false;
    while (!g_isGenerating.compare_exchange_strong(expected, true)) {
        expected = false;
        usleep(1000); // Wait for current generation block to finish
    }

    if (g_player) {
        adl_close(g_player);
        g_player = nullptr;
    }

    // Keep g_isGenerating true if you want to prevent ANY use
    // until initAdlMidi is called, or reset it:
    g_isGenerating.store(false);
}

// Optional setters
JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_setAdlNumChips(JNIEnv*, jobject, jint count) {
    if (g_player && count >= 1 && count <= 8) {
        adl_setNumChips(g_player, count);
        LOGD("Chips set to %d", count);
    }
}

JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_setAdlBank(JNIEnv*, jobject, jint bank) {
    if (g_player) {
        adl_setBank(g_player, bank);
        LOGD("Bank set to %d", bank);
    }
}



}  // extern "C"