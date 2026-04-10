#include <jni.h>
#include <cstring>
#include <iostream>

extern "C" {
#include <quickjs.h>
}
struct JSMallocState;

/**
 * KhromiumMem.cpp에 구현된 실제 할당 함수들입니다.
 * 컴파일러가 요구하는 JSMallocState* 타입을 사용하도록 선언합니다.
 */
extern "C" {
    void* custom_js_malloc(JSMallocState* s, size_t size);
    void  custom_js_free(JSMallocState* s, void* ptr);
    void* custom_js_realloc(JSMallocState* s, void* ptr, size_t size);
    size_t custom_js_malloc_usable_size(const void *ptr);
}

static JSRuntime *rt = nullptr;
static JSContext *ctx = nullptr;

// 구조체 전역 변수
static JSMallocFunctions mf_safe;

extern "C" {

/**
 * Kotlin: external fun initRuntime(): Boolean
 */
JNIEXPORT jboolean JNICALL
Java_io_github_jwyoon1220_khromium_js_QuickJSEngine_initRuntime(JNIEnv *env, jobject thiz) {
    if (rt != nullptr) return JNI_TRUE;

    std::cout << "Init Runtime" << std::endl;

    /**
     * [수정 핵심 1] 에러 메시지에 따라 js_calloc은 제거합니다.
     * [수정 핵심 2] 캐스팅 시 첫 번째 인자를 (JSMallocState *)로 정확히 맞춥니다.
     */
    mf_safe.js_malloc = static_cast<void *(*)(JSMallocState *, size_t)>(custom_js_malloc);
    mf_safe.js_free   = static_cast<void (*)(JSMallocState *, void *)>(custom_js_free);
    mf_safe.js_realloc = static_cast<void *(*)(JSMallocState *, void *, size_t)>(custom_js_realloc);
    mf_safe.js_malloc_usable_size = static_cast<size_t (*)(const void *)>(custom_js_malloc_usable_size);

    std::cout << "Creating Runtime..." << std::endl;
    // 런타임 생성
    rt = JS_NewRuntime2(&mf_safe, nullptr);
    if (!rt) return JNI_FALSE;
    std::cout << "Creating Context..." << std::endl;
    // 컨텍스트 생성
    ctx = JS_NewContext(rt);
    if (!ctx) {
        std::cout << "Failed" << std::endl;
        JS_FreeRuntime(rt);
        rt = nullptr;
        return JNI_FALSE;
    }
    std::cout << "Good!" << std::endl;
    return JNI_TRUE;
}

/**
 * Kotlin: external fun destroyRuntime()
 */
JNIEXPORT void JNICALL
Java_io_github_jwyoon1220_khromium_js_QuickJSEngine_destroyRuntime(JNIEnv *env, jobject thiz) {
    if (ctx) {
        JS_FreeContext(ctx);
        ctx = nullptr;
    }
    if (rt) {
        JS_FreeRuntime(rt);
        rt = nullptr;
    }
}

/**
 * Kotlin: external fun eval(scriptSource: String): String
 */
JNIEXPORT jstring JNICALL
Java_io_github_jwyoon1220_khromium_js_QuickJSEngine_eval(JNIEnv *env, jobject thiz, jstring scriptSource) {
    if (!ctx) return env->NewStringUTF("Error: Runtime not initialized");

    const char *scriptStr = env->GetStringUTFChars(scriptSource, nullptr);
    size_t scriptLen = (size_t)env->GetStringUTFLength(scriptSource);

    JSValue val = JS_Eval(ctx, scriptStr, scriptLen, "<eval>", JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(scriptSource, scriptStr);

    if (JS_IsException(val)) {
        JSValue exception_val = JS_GetException(ctx);
        const char *exception_str = JS_ToCString(ctx, exception_val);
        jstring result = env->NewStringUTF(exception_str ? exception_str : "Unknown Exception");

        if (exception_str) JS_FreeCString(ctx, exception_str);
        JS_FreeValue(ctx, exception_val);
        JS_FreeValue(ctx, val);
        return result;
    }

    const char *resultStr = JS_ToCString(ctx, val);
    jstring result = env->NewStringUTF(resultStr ? resultStr : "undefined");

    if (resultStr) JS_FreeCString(ctx, resultStr);
    JS_FreeValue(ctx, val);

    return result;
}

} // extern "C"