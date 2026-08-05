#include "cryptosteer/benchmark.hpp"
#include "cryptosteer/json.hpp"
#include "cryptosteer/miner_engine.hpp"
#include <jni.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {
JavaVM* g_vm = nullptr;

class JniEnv final {
public:
    JniEnv() {
        if (!g_vm) throw std::runtime_error("Java VM is unavailable");
        void* value = nullptr;
        jint status = g_vm->GetEnv(&value, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
#if defined(__ANDROID__)
            const jint attach_status = g_vm->AttachCurrentThread(&env_, nullptr);
#else
            const jint attach_status = g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env_), nullptr);
#endif
            if (attach_status != JNI_OK || env_ == nullptr) {
                throw std::runtime_error("failed to attach native callback thread");
            }
            attached_ = true;
        } else if (status == JNI_OK) {
            env_ = static_cast<JNIEnv*>(value);
        }
        else throw std::runtime_error("failed to obtain JNI environment");
    }
    ~JniEnv() { if (attached_ && g_vm) g_vm->DetachCurrentThread(); }
    JNIEnv* get() const noexcept { return env_; }
private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    char const* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("failed to read Java string");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv* env, std::string const& value) {
    return env->NewStringUTF(value.c_str());
}

void throw_java(JNIEnv* env, char const* type, std::string const& message) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message.c_str());
}

class JavaCallbacks final : public cryptosteer::EngineCallbacks {
public:
    JavaCallbacks(JNIEnv* env, jobject callbacks) {
        object_ = env->NewGlobalRef(callbacks);
        if (!object_) throw std::runtime_error("failed to retain native callbacks");
        jclass cls = env->GetObjectClass(callbacks);
        state_ = env->GetMethodID(cls, "onState", "(Ljava/lang/String;Ljava/lang/String;)V");
        stats_ = env->GetMethodID(cls, "onStats", "(Ljava/lang/String;)V");
        log_ = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;Ljava/lang/String;)V");
        accounting_ = env->GetMethodID(cls, "onAccounting", "(JJ)V");
        env->DeleteLocalRef(cls);
        if (!state_ || !stats_ || !log_ || !accounting_) throw std::runtime_error("NativeCallbacks method lookup failed");
    }
    ~JavaCallbacks() override {
        if (!object_) return;
        try { JniEnv holder; holder.get()->DeleteGlobalRef(object_); } catch (...) { }
    }
    void state(std::string const& state, std::string const& message) override { call_two(state_, state, message); }
    void stats(std::string const& json) override { call_one(stats_, json); }
    void log(std::string const& level, std::string const& message) override { call_two(log_, level, message); }
    void accounting(std::uint64_t user_nanos, std::uint64_t donation_nanos) override {
        JniEnv holder; JNIEnv* env = holder.get();
        env->CallVoidMethod(object_, accounting_, static_cast<jlong>(user_nanos), static_cast<jlong>(donation_nanos));
        clear_exception(env);
    }
private:
    static void clear_exception(JNIEnv* env) noexcept {
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    }
    void call_one(jmethodID method, std::string const& value) {
        JniEnv holder; JNIEnv* env = holder.get();
        jstring arg = to_jstring(env, value);
        env->CallVoidMethod(object_, method, arg);
        env->DeleteLocalRef(arg);
        clear_exception(env);
    }
    void call_two(jmethodID method, std::string const& first, std::string const& second) {
        JniEnv holder; JNIEnv* env = holder.get();
        jstring a = to_jstring(env, first); jstring b = to_jstring(env, second);
        env->CallVoidMethod(object_, method, a, b);
        env->DeleteLocalRef(a); env->DeleteLocalRef(b);
        clear_exception(env);
    }
    jobject object_ = nullptr;
    jmethodID state_ = nullptr;
    jmethodID stats_ = nullptr;
    jmethodID log_ = nullptr;
    jmethodID accounting_ = nullptr;
};

struct Handle {
    explicit Handle(std::shared_ptr<JavaCallbacks> callbacks) : callbacks(std::move(callbacks)), engine(this->callbacks) {}
    std::shared_ptr<JavaCallbacks> callbacks;
    cryptosteer::MinerEngine engine;
    std::mutex mutex;
};

Handle* checked(jlong handle) {
    if (handle == 0) throw std::invalid_argument("native miner handle is null");
    return reinterpret_cast<Handle*>(static_cast<intptr_t>(handle));
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_create(JNIEnv* env, jobject, jobject callbacks) {
    try {
        if (!callbacks) throw std::invalid_argument("callbacks cannot be null");
        auto bridge = std::make_shared<JavaCallbacks>(env, callbacks);
        auto* handle = new Handle(std::move(bridge));
        return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
    } catch (std::exception const& exception) {
        throw_java(env, "java/lang/IllegalStateException", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_start(JNIEnv* env, jobject, jlong value, jstring config_json) {
    try {
        Handle* handle = checked(value);
        std::lock_guard lock(handle->mutex);
        auto config = cryptosteer::parse_config(from_jstring(env, config_json));
        return handle->engine.start(std::move(config)) ? JNI_TRUE : JNI_FALSE;
    } catch (std::exception const& exception) {
        throw_java(env, "java/lang/IllegalArgumentException", exception.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_pause(JNIEnv* env, jobject, jlong value) {
    try { return checked(value)->engine.pause() ? JNI_TRUE : JNI_FALSE; }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_updateDonationBasisPoints(JNIEnv* env, jobject, jlong value, jint basis_points) {
    try { return checked(value)->engine.update_donation_basis_points(static_cast<int>(basis_points)) ? JNI_TRUE : JNI_FALSE; }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_resume(JNIEnv* env, jobject, jlong value) {
    try { return checked(value)->engine.resume() ? JNI_TRUE : JNI_FALSE; }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_stop(JNIEnv* env, jobject, jlong value) {
    try { if (value != 0) checked(value)->engine.stop(); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_destroy(JNIEnv* env, jobject, jlong value) {
    try { if (value != 0) delete checked(value); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_diagnostics(JNIEnv* env, jobject, jlong value) {
    try { return to_jstring(env, checked(value)->engine.diagnostics()); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_benchmark(JNIEnv* env, jobject, jstring config_json) {
    try { return to_jstring(env, cryptosteer::run_benchmark(from_jstring(env, config_json))); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_benchmarkProgress(JNIEnv* env, jobject) {
    try { return to_jstring(env, cryptosteer::benchmark_progress()); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_cancelBenchmark(JNIEnv*, jobject) {
    cryptosteer::cancel_benchmark();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_autoTune(JNIEnv* env, jobject, jstring config_json) {
    try { return to_jstring(env, cryptosteer::run_auto_tune(from_jstring(env, config_json))); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_cancelAutoTune(JNIEnv*, jobject) {
    cryptosteer::cancel_auto_tune();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_runSelfTests(JNIEnv* env, jobject) {
    try { return to_jstring(env, cryptosteer::run_native_self_tests()); }
    catch (std::exception const& exception) { throw_java(env, "java/lang/IllegalStateException", exception.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecryptosteer_xmrminer_nativebridge_NativeMiner_nativeVersion(JNIEnv* env, jobject) {
    return to_jstring(env, cryptosteer::native_diagnostics());
}
