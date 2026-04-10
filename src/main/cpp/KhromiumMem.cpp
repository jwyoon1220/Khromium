#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <tlsf.h>

struct JSMallocState;

static void* tlsf_memory_pool = nullptr;
static tlsf_t tlsf_handle = nullptr;

extern "C" {

// 힙 초기화: QuickJS 구동 전 반드시 호출되어야 함
JNIEXPORT jlong JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_initHeap(JNIEnv *env, jobject thiz, jlong size) {
    if (tlsf_handle != nullptr) return reinterpret_cast<jlong>(tlsf_memory_pool);

    tlsf_memory_pool = std::malloc(size);
    if (!tlsf_memory_pool) return 0;

    tlsf_handle = tlsf_create_with_pool(tlsf_memory_pool, size);
    return reinterpret_cast<jlong>(tlsf_memory_pool);
}

// QuickJSBridge에서 사용할 내부 함수들 (void* opaque 시그니처)
void* custom_js_malloc(void* opaque, size_t size) {
    if (!tlsf_handle) return nullptr;
    return tlsf_malloc(tlsf_handle, size);
}

void custom_js_free(void* opaque, void* ptr) {
    if (tlsf_handle && ptr) tlsf_free(tlsf_handle, ptr);
}

void* custom_js_realloc(void* opaque, void* ptr, size_t size) {
    if (!tlsf_handle) return nullptr;
    return tlsf_realloc(tlsf_handle, ptr, size);
}

void* custom_js_calloc(void* opaque, size_t count, size_t size) {
    size_t total = count * size;
    void* ptr = custom_js_malloc(opaque, total);
    if (ptr) memset(ptr, 0, total);
    return ptr;
}

size_t custom_js_malloc_usable_size(const void *ptr) {
    return 0; // TLSF 버전에 따라 구현하거나 0 반환
}

} // extern "C"