// JNI half of the ThermalBench Qualcomm Genie adapter.
//
// libGenie.so is opened with dlopen rather than linked, so this library loads
// on every device. Where Genie is not packaged, or cannot load (no Qualcomm
// runtime), open() says why and the backend reports itself unavailable.
//
// Each streamed piece of the response is handed to Kotlin as it arrives, so
// time-to-first-token and the per-token timeline are taken on the same
// monotonic clock as every other backend's.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <string>

#include <cstdarg>

#include "GenieCommon.h"
#include "GenieDialog.h"
#include "GenieLog.h"

#define LOG_TAG "ThermalBenchGenie"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Api {
    decltype(&Genie_getApiMajorVersion) apiMajor = nullptr;
    decltype(&Genie_getApiMinorVersion) apiMinor = nullptr;
    decltype(&Genie_getApiPatchVersion) apiPatch = nullptr;
    decltype(&GenieDialogConfig_createFromJson) configFromJson = nullptr;
    decltype(&GenieDialogConfig_free) configFree = nullptr;
    decltype(&GenieDialog_create) create = nullptr;
    decltype(&GenieDialog_query) query = nullptr;
    decltype(&GenieDialog_reset) reset = nullptr;
    decltype(&GenieDialog_signal) signal = nullptr;
    decltype(&GenieDialog_setMaxNumTokens) setMaxNumTokens = nullptr;
    decltype(&GenieDialog_free) free = nullptr;
    decltype(&GenieLog_create) logCreate = nullptr;
    decltype(&GenieDialogConfig_bindLogger) bindLogger = nullptr;
};

Api api;
bool loaded = false;
JavaVM *vm = nullptr;
GenieLog_Handle_t logger = nullptr;

// Genie's own diagnostics to logcat. Warnings and errors only: a chattier
// level would log during the measurement itself.
void log_to_logcat(const GenieLog_Handle_t, const char *fmt, GenieLog_Level_t level, uint64_t, va_list args) {
    const int priority = level == GENIE_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_vprint(priority, LOG_TAG, fmt, args);
}

template <typename T>
bool bind(void *library, const char *name, T &slot, std::string &missing) {
    slot = reinterpret_cast<T>(dlsym(library, name));
    if (slot == nullptr) missing += std::string(missing.empty() ? "" : ", ") + name;
    return slot != nullptr;
}

/** Where one query's pieces go: the Kotlin listener and the method to call. */
struct Sink {
    jobject listener;
    jmethodID onPiece;
};

/** Genie may call back on its own thread; attach it to the VM if so. */
JNIEnv *env_for_callback(bool *attached) {
    JNIEnv *env = nullptr;
    *attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *attached = true;
    }
    return env;
}

void on_response(const char *response, const GenieDialog_SentenceCode_t code, const void *user_data) {
    const auto *sink = static_cast<const Sink *>(user_data);
    bool attached = false;
    JNIEnv *env = env_for_callback(&attached);
    if (env == nullptr) return;
    jstring text = env->NewStringUTF(response ? response : "");
    env->CallVoidMethod(sink->listener, sink->onPiece, text, static_cast<jint>(code));
    env->DeleteLocalRef(text);
    if (attached) vm->DetachCurrentThread();
}

GenieDialog_Handle_t as_dialog(jlong handle) { return reinterpret_cast<GenieDialog_Handle_t>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *java_vm, void *) {
    vm = java_vm;
    return JNI_VERSION_1_6;
}

// Null when Genie is ready; otherwise why it is not, in words for the screen.
JNIEXPORT jstring JNICALL
Java_com_thermalbench_backend_genie_GenieNative_open(JNIEnv *env, jobject) {
    if (loaded) return nullptr;
    void *library = dlopen("libGenie.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        const char *error = dlerror();
        std::string reason = "libGenie.so could not be loaded: ";
        reason += error ? error : "unknown error";
        return env->NewStringUTF(reason.c_str());
    }
    std::string missing;
    bind(library, "Genie_getApiMajorVersion", api.apiMajor, missing);
    bind(library, "Genie_getApiMinorVersion", api.apiMinor, missing);
    bind(library, "Genie_getApiPatchVersion", api.apiPatch, missing);
    bind(library, "GenieDialogConfig_createFromJson", api.configFromJson, missing);
    bind(library, "GenieDialogConfig_free", api.configFree, missing);
    bind(library, "GenieDialog_create", api.create, missing);
    bind(library, "GenieDialog_query", api.query, missing);
    bind(library, "GenieDialog_reset", api.reset, missing);
    bind(library, "GenieDialog_signal", api.signal, missing);
    bind(library, "GenieDialog_setMaxNumTokens", api.setMaxNumTokens, missing);
    bind(library, "GenieDialog_free", api.free, missing);
    bind(library, "GenieLog_create", api.logCreate, missing);
    bind(library, "GenieDialogConfig_bindLogger", api.bindLogger, missing);
    if (!missing.empty()) {
        std::string reason = "libGenie.so lacks: " + missing;
        return env->NewStringUTF(reason.c_str());
    }
    loaded = true;
    if (api.logCreate(nullptr, log_to_logcat, GENIE_LOG_LEVEL_WARN, &logger) != GENIE_STATUS_SUCCESS) {
        logger = nullptr;
    }
    LOGI("Genie API %u.%u.%u", api.apiMajor(), api.apiMinor(), api.apiPatch());
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_thermalbench_backend_genie_GenieNative_apiVersion(JNIEnv *env, jobject) {
    if (!loaded) return nullptr;
    std::string version = std::to_string(api.apiMajor()) + "." + std::to_string(api.apiMinor()) + "." +
                          std::to_string(api.apiPatch());
    return env->NewStringUTF(version.c_str());
}

// Returns a dialog handle, or throws with Genie's status code.
JNIEXPORT jlong JNICALL
Java_com_thermalbench_backend_genie_GenieNative_create(JNIEnv *env, jobject, jstring config_json) {
    const char *json = env->GetStringUTFChars(config_json, nullptr);
    GenieDialogConfig_Handle_t config = nullptr;
    Genie_Status_t status = api.configFromJson(json, &config);
    env->ReleaseStringUTFChars(config_json, json);
    if (status != GENIE_STATUS_SUCCESS) {
        std::string message = "Genie rejected the bundle's config (status " + std::to_string(status) + ")";
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
        return 0;
    }
    if (logger != nullptr) api.bindLogger(config, logger);
    GenieDialog_Handle_t dialog = nullptr;
    status = api.create(config, &dialog);
    api.configFree(config);
    if (status != GENIE_STATUS_SUCCESS) {
        std::string message = "Genie could not create the model (status " + std::to_string(status) +
                              "); see logcat tag " LOG_TAG " and the QNN logs";
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(dialog);
}

JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_genie_GenieNative_setMaxTokens(JNIEnv *, jobject, jlong handle, jint tokens) {
    return api.setMaxNumTokens(as_dialog(handle), static_cast<uint32_t>(tokens));
}

// Runs one query; every streamed piece goes to listener.onPiece(text, sentenceCode).
JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_genie_GenieNative_query(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jobject listener) {
    jclass type = env->GetObjectClass(listener);
    Sink sink{listener, env->GetMethodID(type, "onPiece", "(Ljava/lang/String;I)V")};
    const char *text = env->GetStringUTFChars(prompt, nullptr);
    const Genie_Status_t status =
        api.query(as_dialog(handle), text, GENIE_DIALOG_SENTENCE_COMPLETE, on_response, &sink);
    env->ReleaseStringUTFChars(prompt, text);
    return status;
}

JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_genie_GenieNative_reset(JNIEnv *, jobject, jlong handle) {
    return api.reset(as_dialog(handle));
}

JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_genie_GenieNative_abort(JNIEnv *, jobject, jlong handle) {
    return api.signal(as_dialog(handle), GENIE_DIALOG_ACTION_ABORT);
}

JNIEXPORT void JNICALL
Java_com_thermalbench_backend_genie_GenieNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) api.free(as_dialog(handle));
}

}  // extern "C"
