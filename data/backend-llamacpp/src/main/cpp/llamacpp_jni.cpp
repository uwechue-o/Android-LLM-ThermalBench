// JNI half of the ThermalBench llama.cpp adapter.
//
// One token per decode call. That is what lets the Kotlin side stop at exactly
// the token count the spec asks for instead of at the model's end-of-sequence,
// which is the difference between comparable and incomparable tokens/sec.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <strings.h>
#include <vector>

#include "llama.h"

#define LOG_TAG "ThermalBenchLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Context {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    int n_past = 0;
    std::vector<llama_token> pending;
};

Context *as_context(jlong handle) { return reinterpret_cast<Context *>(handle); }

// llama.cpp logs to stderr, which Android discards. Its load-time lines are
// the only record of which device a "GPU" run actually used and how many
// layers it offloaded, so they go to logcat. Debug chatter does not.
void log_to_logcat(ggml_log_level level, const char *text, void *) {
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO: priority = ANDROID_LOG_INFO; break;
        default: return;
    }
    __android_log_write(priority, LOG_TAG, text);
}

// Each ggml backend is a separate library (GGML_BACKEND_DL), loaded here by
// name. Loading by bare name lets the dynamic linker find it inside the APK.
// A GPU backend that cannot load on this device (no vendor OpenCL, no Vulkan
// driver) is logged and skipped; the CPU backend is the only one required.
void init_backends() {
    static bool initialized = false;
    if (initialized) return;
    initialized = true;
    llama_log_set(log_to_logcat, nullptr);
    struct Module { const char *file; bool required; };
    const Module modules[] = {
        {"libggml-cpu.so", true},
#ifdef THERMALBENCH_HAS_VULKAN
        {"libggml-vulkan.so", false},
#endif
#ifdef THERMALBENCH_HAS_OPENCL
        {"libggml-opencl.so", false},
#endif
    };
    for (const Module &module : modules) {
        if (ggml_backend_load(module.file) != nullptr) {
            LOGI("loaded backend %s", module.file);
        } else if (module.required) {
            LOGE("could not load required backend %s", module.file);
        } else {
            LOGI("backend %s unavailable on this device", module.file);
        }
    }
    llama_backend_init();
}

bool is_gpu(ggml_backend_dev_t device) {
    const auto type = ggml_backend_dev_type(device);
    // Phone GPUs share system memory, so ggml lists them as integrated (IGPU).
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

// The GPU device whose backend is named [api] ("Vulkan", "OpenCL"), or the
// first GPU when [api] is empty. Null when there is no such device.
ggml_backend_dev_t find_gpu(const std::string &api) {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (!is_gpu(device)) continue;
        const char *reg = ggml_backend_reg_name(ggml_backend_dev_backend_reg(device));
        if (api.empty() || strcasecmp(reg, api.c_str()) == 0) return device;
    }
    return nullptr;
}

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    const int upper_bound = static_cast<int>(text.size()) + 2;
    std::vector<llama_token> tokens(upper_bound);
    const int count = llama_tokenize(
        vocab, text.data(), static_cast<int>(text.size()),
        tokens.data(), upper_bound, add_special, /*parse_special=*/true);
    if (count < 0) {
        tokens.resize(-count);
        const int retry = llama_tokenize(
            vocab, text.data(), static_cast<int>(text.size()),
            tokens.data(), static_cast<int>(tokens.size()), add_special, true);
        tokens.resize(retry > 0 ? retry : 0);
        return tokens;
    }
    tokens.resize(count);
    return tokens;
}

std::string piece_of(const llama_vocab *vocab, llama_token token) {
    char buffer[256];
    const int length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, /*special=*/false);
    if (length < 0) return {};
    return std::string(buffer, length);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_loadModel(
    JNIEnv *env, jobject, jstring path, jint threads, jint context_size, jint gpu_layers, jstring gpu_api) {
    init_backends();

    const std::string model_path = to_string(env, path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;
    // Exactly one device, never ggml's default of every GPU at once. On a
    // Snapdragon both Vulkan and OpenCL see the Adreno, and splitting a model
    // across the two would measure neither.
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    if (gpu_layers > 0) {
        const std::string api = to_string(env, gpu_api);
        devices[0] = find_gpu(api);
        if (devices[0] == nullptr) {
            LOGE("no GPU device for '%s'", api.c_str());
            return 0;
        }
        LOGI("offloading to %s (%s)", ggml_backend_dev_name(devices[0]),
             ggml_backend_dev_description(devices[0]));
    }
    model_params.devices = devices;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(context_size);
    ctx_params.n_batch = static_cast<uint32_t>(context_size);
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *wrapper = new Context();
    wrapper->model = model;
    wrapper->ctx = ctx;
    wrapper->vocab = llama_model_get_vocab(model);
    // Greedy: sampling noise would be indistinguishable from throughput noise.
    wrapper->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(wrapper->sampler, llama_sampler_init_greedy());

    LOGI("loaded %s (threads=%d ctx=%d gpu_layers=%d)", model_path.c_str(), threads, context_size, gpu_layers);
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_freeModel(JNIEnv *, jobject, jlong handle) {
    Context *context = as_context(handle);
    if (context == nullptr) return;
    if (context->sampler) llama_sampler_free(context->sampler);
    if (context->ctx) llama_free(context->ctx);
    if (context->model) llama_model_free(context->model);
    delete context;
}

JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_tokenCount(
    JNIEnv *env, jobject, jlong handle, jstring text) {
    Context *context = as_context(handle);
    if (context == nullptr) return -1;
    return static_cast<jint>(tokenize(context->vocab, to_string(env, text), true).size());
}

JNIEXPORT void JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_resetContext(JNIEnv *, jobject, jlong handle) {
    Context *context = as_context(handle);
    if (context == nullptr) return;
    llama_memory_clear(llama_get_memory(context->ctx), /*data=*/true);
    context->n_past = 0;
    context->pending.clear();
}

JNIEXPORT jint JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_prefill(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jlong seed) {
    Context *context = as_context(handle);
    if (context == nullptr) return -1;

    if (context->sampler) llama_sampler_free(context->sampler);
    context->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(context->sampler, llama_sampler_init_greedy());
    (void) seed;  // Greedy decoding ignores the seed; it is recorded in the spec regardless.

    const std::vector<llama_token> tokens = tokenize(context->vocab, to_string(env, prompt), true);
    if (tokens.empty()) return 0;

    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()),
                                            static_cast<int32_t>(tokens.size()));
    if (llama_decode(context->ctx, batch) != 0) {
        LOGE("prefill failed");
        return -1;
    }
    context->n_past += static_cast<int>(tokens.size());
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jstring JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_decodeToken(JNIEnv *env, jobject, jlong handle) {
    Context *context = as_context(handle);
    if (context == nullptr) return nullptr;

    const llama_token token = llama_sampler_sample(context->sampler, context->ctx, -1);
    if (llama_vocab_is_eog(context->vocab, token)) return nullptr;

    llama_sampler_accept(context->sampler, token);
    const std::string piece = piece_of(context->vocab, token);

    llama_token next = token;
    llama_batch batch = llama_batch_get_one(&next, 1);
    if (llama_decode(context->ctx, batch) != 0) {
        LOGE("decode failed");
        return nullptr;
    }
    context->n_past += 1;
    return env->NewStringUTF(piece.c_str());
}

// The GPU APIs llama.cpp can drive on this device, each as
// "<api>	<device name>	<description>", e.g. "Vulkan	Vulkan0	Mali-G78".
// Empty when no GPU backend is built in, or when none found a usable device;
// offloading would then be silently ignored, and a "GPU" run would really be a
// CPU run.
JNIEXPORT jobjectArray JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_gpuDevices(JNIEnv *env, jobject) {
    init_backends();
    std::vector<std::string> found;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (!is_gpu(device)) continue;
        std::string entry = ggml_backend_reg_name(ggml_backend_dev_backend_reg(device));
        entry += '	';
        entry += ggml_backend_dev_name(device);
        entry += '	';
        entry += ggml_backend_dev_description(device);
        LOGI("GPU device: %s", entry.c_str());
        found.push_back(entry);
    }
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(found.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < found.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i), env->NewStringUTF(found[i].c_str()));
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_thermalbench_backend_llamacpp_LlamaCppNative_runtimeVersion(JNIEnv *env, jobject) {
    // llama.cpp has no version symbol; the build stamps the checkout it linked.
#ifdef THERMALBENCH_LLAMA_VERSION
    return env->NewStringUTF(THERMALBENCH_LLAMA_VERSION);
#else
    return env->NewStringUTF("llama.cpp (unversioned checkout)");
#endif
}

}  // extern "C"
