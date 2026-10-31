#include <jni.h>
#include <android/log.h>
#include "whisper.h"
#include <string>
#include <sstream>
#include <iomanip>

#define TAG "VexWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ProgressBridge {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

struct StageBridge {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

static void reportStage(StageBridge *bridge, const char *stage) {
    if (!bridge || !bridge->env || !bridge->listener || !bridge->method) return;
    jstring value = bridge->env->NewStringUTF(stage);
    if (!value) return;
    bridge->env->CallVoidMethod(bridge->listener, bridge->method, value);
    bridge->env->DeleteLocalRef(value);
    if (bridge->env->ExceptionCheck()) bridge->env->ExceptionClear();
}

static void whisperProgressCallback(whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * bridge = static_cast<ProgressBridge *>(user_data);
    if (!bridge || !bridge->env || !bridge->listener || !bridge->method) return;
    bridge->env->CallVoidMethod(bridge->listener, bridge->method, static_cast<jint>(progress));
    if (bridge->env->ExceptionCheck()) bridge->env->ExceptionClear();
}

static std::string jsonEscape(const char *value) {
    std::ostringstream out;
    const unsigned char *p = reinterpret_cast<const unsigned char *>(value ? value : "");
    while (*p) {
        switch (*p) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (*p < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << (int)*p;
                else out << (char)*p;
        }
        ++p;
    }
    return out.str();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_vexlrc_generator_core_ai_WhisperNative_transcribe(
        JNIEnv *env, jobject,
        jstring modelPath, jfloatArray audio, jstring language, jint threads, jobject progressListener, jobject stageListener) {
    const jsize sampleCount = env->GetArrayLength(audio);
    if (sampleCount < 16000) {
        return env->NewStringUTF("{\"error\":\"Audio is empty or shorter than one second after decoding\"}");
    }
    StageBridge stage { env, stageListener, nullptr };
    if (stageListener) {
        jclass stageClass = env->GetObjectClass(stageListener);
        stage.method = env->GetMethodID(stageClass, "onStage", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(stageClass);
    }
    reportStage(&stage, "Checking model file");
    const char *model = env->GetStringUTFChars(modelPath, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    reportStage(&stage, "Loading Whisper model into memory");
    whisper_context *ctx = whisper_init_from_file_with_params(model, contextParams);
    env->ReleaseStringUTFChars(modelPath, model);
    if (!ctx) {
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("{\"error\":\"Whisper could not load this model file\"}");
    }

    reportStage(&stage, "Whisper model loaded; preparing inference");
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (!samples) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(language, lang);
        return env->NewStringUTF("{\"error\":\"Could not access decoded audio samples\"}");
    }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = (std::string(lang) == "auto" || std::string(lang).empty()) ? nullptr : lang;
    params.n_threads = threads > 0 ? threads : 2;
    params.no_context = true;
    params.token_timestamps = true;
    ProgressBridge bridge { env, progressListener, nullptr };
    if (progressListener) {
        jclass listenerClass = env->GetObjectClass(progressListener);
        bridge.method = env->GetMethodID(listenerClass, "onProgress", "(I)V");
        params.progress_callback = whisperProgressCallback;
        params.progress_callback_user_data = &bridge;
        env->DeleteLocalRef(listenerClass);
    }

    reportStage(&stage, "Running Whisper transcription");
    const int result = whisper_full(ctx, params, samples, sampleCount);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        whisper_free(ctx);
        return env->NewStringUTF("{\"error\":\"Whisper inference failed\"}");
    }

    std::ostringstream json;
    json << "{\"segments\":[";
    const int segmentCount = whisper_full_n_segments(ctx);
    for (int i = 0; i < segmentCount; ++i) {
        if (i) json << ",";
        const char *text = whisper_full_get_segment_text(ctx, i);
        json << "{\"text\":\"" << jsonEscape(text) << "\",\"startMs\":"
             << whisper_full_get_segment_t0(ctx, i) * 10
             << ",\"endMs\":" << whisper_full_get_segment_t1(ctx, i) * 10
             << ",\"tokens\":[";
        const int tokenCount = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < tokenCount; ++j) {
            if (j) json << ",";
            const whisper_token_data token = whisper_full_get_token_data(ctx, i, j);
            const char *tokenText = whisper_token_to_str(ctx, token.id);
            json << "{\"text\":\"" << jsonEscape(tokenText) << "\",\"startMs\":"
                 << token.t0 * 10 << ",\"endMs\":" << token.t1 * 10 << "}";
        }
        json << "]}";
    }
    json << "]}";
    whisper_free(ctx);
    reportStage(&stage, "Whisper finished");
    return env->NewStringUTF(json.str().c_str());
}
