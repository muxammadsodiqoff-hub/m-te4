// Video Studio: stable-diffusion.cpp uchun yupqa JNI qatlami.
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

std::mutex g_mutex;  // g_ctx ni himoya qiladi (load / generate / release)
sd_ctx_t* g_ctx = nullptr;

std::mutex g_log_mutex;
std::string g_last_error;

JavaVM* g_vm = nullptr;
jobject g_listener = nullptr;  // faqat generate() davomida amal qiladi
jmethodID g_on_progress = nullptr;

void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (text == nullptr) return;
#ifdef __ANDROID__
    int prio = ANDROID_LOG_DEBUG;
    if (level == SD_LOG_INFO) prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, "sdjni", "%s", text);
#endif
    if (level == SD_LOG_ERROR) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error = text;
        while (!g_last_error.empty() &&
               (g_last_error.back() == '\n' || g_last_error.back() == '\r')) {
            g_last_error.pop_back();
        }
    }
}

void set_error(const char* text) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_error = text;
}

void progress_cb(int step, int steps, float time, void*) {
    if (g_vm == nullptr || g_listener == nullptr || g_on_progress == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    }
    env->CallVoidMethod(g_listener, g_on_progress, (jint)step, (jint)steps, (jfloat)time);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_uz_ms_videostudio_SdNative_load(JNIEnv* env, jobject, jstring model_path, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    set_error("");
    std::string path = to_string(env, model_path);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = path.c_str();
    if (threads > 0) params.n_threads = threads;

    g_ctx = new_sd_ctx(&params);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_uz_ms_videostudio_SdNative_release(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
}

// Boshqa oqimdan chaqiriladi; ataylab g_mutex olinmaydi (generate uni ushlab turadi).
JNIEXPORT void JNICALL
Java_uz_ms_videostudio_SdNative_cancel(JNIEnv*, jobject) {
    sd_ctx_t* ctx = g_ctx;
    if (ctx != nullptr) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

JNIEXPORT jstring JNICALL
Java_uz_ms_videostudio_SdNative_lastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

// Natija: [kenglik, balandlik, ARGB piksellar...] yoki xato bo'lsa null.
JNIEXPORT jintArray JNICALL
Java_uz_ms_videostudio_SdNative_generate(JNIEnv* env, jobject,
                                         jstring prompt, jstring negative,
                                         jint width, jint height,
                                         jint steps, jfloat cfg, jlong seed,
                                         jboolean lcm, jobject listener) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        set_error("Model yuklanmagan");
        return nullptr;
    }
    set_error("");

    std::string prompt_str = to_string(env, prompt);
    std::string negative_str = to_string(env, negative);

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = prompt_str.c_str();
    params.negative_prompt = negative_str.c_str();
    params.width = width;
    params.height = height;
    params.seed = seed;
    params.batch_count = 1;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfg;
    if (lcm == JNI_TRUE) {
        params.sample_params.sample_method = LCM_SAMPLE_METHOD;
        params.sample_params.scheduler = LCM_SCHEDULER;
    } else {
        params.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
        params.sample_params.scheduler = sd_get_default_scheduler(g_ctx, EULER_A_SAMPLE_METHOD);
    }

    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        g_on_progress = env->GetMethodID(cls, "onProgress", "(IIF)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_on_progress = nullptr;
        }
        g_listener = env->NewGlobalRef(listener);
        env->DeleteLocalRef(cls);
    }

    sd_cancel_generation(g_ctx, SD_CANCEL_RESET);
    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(g_ctx, &params, &result);

    if (g_listener != nullptr) {
        jobject ref = g_listener;
        g_listener = nullptr;
        env->DeleteGlobalRef(ref);
    }

    jintArray output = nullptr;
    if (ok && result != nullptr && result->count > 0 && result->items != nullptr) {
        const sd_image_t& image = result->items[0].image;
        if (image.data != nullptr && image.width > 0 && image.height > 0 && image.channel >= 3) {
            const size_t count = (size_t)image.width * (size_t)image.height;
            std::vector<jint> pixels(count + 2);
            pixels[0] = (jint)image.width;
            pixels[1] = (jint)image.height;
            const uint8_t* src = image.data;
            const uint32_t stride = image.channel;
            for (size_t i = 0; i < count; ++i) {
                const uint32_t r = src[i * stride];
                const uint32_t g = src[i * stride + 1];
                const uint32_t b = src[i * stride + 2];
                pixels[i + 2] = (jint)(0xFF000000u | (r << 16) | (g << 8) | b);
            }
            output = env->NewIntArray((jsize)pixels.size());
            if (output != nullptr) {
                env->SetIntArrayRegion(output, 0, (jsize)pixels.size(), pixels.data());
            }
        }
    }
    if (result != nullptr) free_sd_image_generation_result(result);
    if (output == nullptr) {
        std::lock_guard<std::mutex> log_lock(g_log_mutex);
        if (g_last_error.empty()) g_last_error = "Rasm yaratilmadi";
    }
    return output;
}

}  // extern "C"
