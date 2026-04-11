#include <jni.h>
#include <cstring>
#include <iostream>

extern "C" {
#include <quickjs.h>
}

// quickjs-ng JSMallocFunctions uses JSMallocState* for the opaque allocator state parameter.
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

    // quickjs-ng JSMallocFunctions uses JSMallocState* — assign directly without cast
    mf_safe.js_malloc             = custom_js_malloc;
    mf_safe.js_free               = custom_js_free;
    mf_safe.js_realloc            = custom_js_realloc;
    mf_safe.js_malloc_usable_size = custom_js_malloc_usable_size;

    std::cout << "Creating Runtime..." << std::endl;
    rt = JS_NewRuntime2(&mf_safe, nullptr);
    if (!rt) return JNI_FALSE;
    // Limit the QuickJS interpreter stack to 4 MB so that complex real-world
    // scripts throw a recoverable JavaScript "Maximum call stack size exceeded"
    // error instead of overflowing the native C stack and triggering a Windows
    // STATUS_STACK_BUFFER_OVERRUN crash (0xC0000409).
    JS_SetMaxStackSize(rt, 4 * 1024 * 1024);
    std::cout << "Creating Context..." << std::endl;
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