// JNI bridge between RecorderCtrl (transcribe/WhisperLib.kt) and whisper.cpp.
// One session = one loaded model. Cancel and progress are plain atomics, so whisper's callbacks (which may run on
// ggml worker threads) never touch the JVM.
#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct Session {
    whisper_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
    std::atomic<int> progress{0};
};

Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }

// Nothing from whisper or ggml goes to the system log.
void quiet_log(enum ggml_log_level, const char *, void *) {}

bool abort_cb(void * user) { return static_cast<Session *>(user)->cancel.load(); }

bool encoder_begin_cb(whisper_context *, whisper_state *, void * user) {
    return !static_cast<Session *>(user)->cancel.load();
}

void progress_cb(whisper_context *, whisper_state *, int progress, void * user) {
    static_cast<Session *>(user)->progress.store(progress);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeOpen(JNIEnv * env, jclass, jstring path) {
    whisper_log_set(quiet_log, nullptr);
    if (path == nullptr) return 0;
    const char * p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    if (ctx == nullptr) return 0;
    auto * s = new Session();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeClose(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    if (s == nullptr) return;
    if (s->ctx != nullptr) whisper_free(s->ctx);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeCancel(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeProgress(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    return s == nullptr ? 0 : s->progress.load();
}

JNIEXPORT jboolean JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeIsMultilingual(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    return (s != nullptr && whisper_is_multilingual(s->ctx)) ? JNI_TRUE : JNI_FALSE;
}

// Transcribes 16 kHz mono float samples. language: a whisper code ("en", "de", ...) or "auto". Returns 0 when done,
// anything else on failure or cancel.
JNIEXPORT jint JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeRun(
        JNIEnv * env, jclass, jlong handle, jfloatArray samples, jstring language, jint threads) {
    Session * s = session(handle);
    if (s == nullptr || samples == nullptr) return -1;
    if (s->cancel.load()) return -2;
    s->progress.store(0);

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    std::string lang = "auto";
    if (language != nullptr) {
        const char * l = env->GetStringUTFChars(language, nullptr);
        if (l != nullptr) {
            lang = l;
            env->ReleaseStringUTFChars(language, l);
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.no_context = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = lang.c_str();
    params.detect_language = false;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = s;
    params.encoder_begin_callback = encoder_begin_cb;
    params.encoder_begin_callback_user_data = s;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = s;

    const int rc = whisper_full(s->ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (s->cancel.load()) return -2;
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeSegmentCount(JNIEnv *, jclass, jlong handle) {
    Session * s = session(handle);
    return s == nullptr ? 0 : whisper_full_n_segments(s->ctx);
}

// Start and end of a segment, in centiseconds from the start of the samples given to nativeRun.
JNIEXPORT jlong JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeSegmentStart(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_get_segment_t0(session(handle)->ctx, i);
}

JNIEXPORT jlong JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeSegmentEnd(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_get_segment_t1(session(handle)->ctx, i);
}

// The text as raw UTF-8 bytes: a segment can end in the middle of a character, which NewStringUTF would reject.
JNIEXPORT jbyteArray JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeSegmentText(JNIEnv * env, jclass, jlong handle, jint i) {
    const char * text = whisper_full_get_segment_text(session(handle)->ctx, i);
    const std::string t = text == nullptr ? std::string() : std::string(text);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(t.size()));
    if (out != nullptr && !t.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(t.size()), reinterpret_cast<const jbyte *>(t.data()));
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeDetectedLanguage(JNIEnv * env, jclass, jlong handle) {
    const int id = whisper_full_lang_id(session(handle)->ctx);
    const char * code = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(code == nullptr ? "" : code);
}

// Every language code whisper knows, in its own order.
JNIEXPORT jobjectArray JNICALL
Java_com_vknxworks_recorderctrl_transcribe_WhisperLib_nativeLanguages(JNIEnv * env, jclass) {
    const int count = whisper_lang_max_id() + 1;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; i++) {
        const char * code = whisper_lang_str(i);
        jstring s = env->NewStringUTF(code == nullptr ? "" : code);
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

}  // extern "C"
