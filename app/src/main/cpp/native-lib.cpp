// native-lib.cpp
// JNI wrapper for libADLMIDI (real-time OPL3 MIDI synthesis)

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <unistd.h>  // for usleep

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
Java_com_example_midiPlayer_MainActivity_loadMidiData(
        JNIEnv* env,
        jobject /* thiz */,
        jbyteArray midiData)
{
    if (!g_player) {
        LOGE("loadMidiData: g_player is NULL!");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(midiData);
    if (len <= 0) {
        LOGE("loadMidiData: empty array");
        return JNI_FALSE;
    }

    jbyte* bytes = env->GetByteArrayElements(midiData, nullptr);
    if (!bytes) {
        LOGE("loadMidiData: failed to get array elements");
        return JNI_FALSE;
    }

    LOGD("Calling adl_openData with %zd bytes", static_cast<size_t>(len));

    int res = adl_openData(g_player, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));

    env->ReleaseByteArrayElements(midiData, bytes, JNI_ABORT);

    if (res != 0) {
        LOGE("adl_openData failed: %s", adl_errorInfo(g_player));
        return JNI_FALSE;
    }

    g_isActive = false;  // reset playing state
    g_isGenerating = false;
    LOGD("MIDI loaded successfully (%zd bytes)", static_cast<size_t>(len));
    return JNI_TRUE;
}

// ────────────────────────────────────────────────
// Generate the next block of stereo PCM samples
// ────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_example_midiPlayer_MainActivity_generateSamples(
        JNIEnv* env,
        jobject /* thiz */,
        jshortArray buffer,
        jint framesRequested   // frames
) {
    if (!g_player || !g_isActive) {
        LOGD("generateSamples skipped: no player or inactive");
        return 0;
    }

    bool wasGenerating = g_isGenerating.exchange(true);
    if (wasGenerating) {
        LOGW("generateSamples: concurrent call - skipping");
        return 0;
    }

    const int channels = 2;
    const int samplesRequested = framesRequested * channels;

    jsize arrayLen = env->GetArrayLength(buffer);
    if (arrayLen < samplesRequested) {
        LOGE("Buffer too small! Required %d shorts, got %d",
             samplesRequested, arrayLen);
        g_isGenerating = false;
        return 0;
    }

    jshort* out = env->GetShortArrayElements(buffer, nullptr);
    if (!out) {
        g_isGenerating = false;
        LOGE("Failed to get buffer");
        return 0;
    }

    int samplesGenerated = adl_play(g_player, samplesRequested, out);

    if (samplesGenerated > 0) {
        float vol = g_volume.load(std::memory_order_relaxed);

        for (int i = 0; i < samplesGenerated; ++i) {
            int v = static_cast<int>(out[i] * vol);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i] = static_cast<short>(v);
        }
    }

    env->ReleaseShortArrayElements(buffer, out, 0);
    g_isGenerating = false;

    int framesGenerated = samplesGenerated / channels;


    return framesGenerated;
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
// ────────────────────────────────────────────────
// Volume control
// ────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_example_midiPlayer_MainActivity_setAdlVolume(
        JNIEnv*, jobject, jfloat vol
) {
    if (vol < 0.0f) vol = 0.0f;
    if (vol > 4.0f) vol = 4.0f; // allow a bit of boost if you like
    g_volume.store(vol, std::memory_order_relaxed);
    LOGD("Volume set to %.2f", vol);
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
    if (g_player) {
        // Wait for any ongoing generation
        while (g_isGenerating.load()) {
            usleep(1000);
        }
        adl_close(g_player);
        g_player = nullptr;
        g_isActive = false;
        g_isGenerating = false;
        LOGD("libADLMIDI closed");
    }
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