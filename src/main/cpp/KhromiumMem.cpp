#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <tlsf.h>

static void* tlsf_memory_pool = nullptr;
static tlsf_t tlsf_handle = nullptr;

extern "C" {

// 힙 초기화: QuickJS 구동 전 반드시 호출되어야 함
JNIEXPORT jlong JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_initHeap(JNIEnv *env, jobject thiz, jlong size) {
    if (tlsf_handle != nullptr) return reinterpret_cast<jlong>(tlsf_memory_pool);

    tlsf_memory_pool = std::malloc(static_cast<size_t>(size));
    if (!tlsf_memory_pool) return 0;

    tlsf_handle = tlsf_create_with_pool(tlsf_memory_pool, static_cast<size_t>(size));
    return reinterpret_cast<jlong>(tlsf_memory_pool);
}

// JNI: NativeMemoryManager.malloc
JNIEXPORT jlong JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_malloc(JNIEnv *env, jobject thiz, jint size) {
    if (!tlsf_handle) return 0;
    void* ptr = tlsf_malloc(tlsf_handle, static_cast<size_t>(size));
    return reinterpret_cast<jlong>(ptr);
}

// JNI: NativeMemoryManager.free
JNIEXPORT void JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_free(JNIEnv *env, jobject thiz, jlong ptr) {
    if (tlsf_handle && ptr) tlsf_free(tlsf_handle, reinterpret_cast<void*>(ptr));
}

// JNI: NativeMemoryManager.realloc
JNIEXPORT jlong JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_realloc(JNIEnv *env, jobject thiz, jlong ptr, jint newSize) {
    if (!tlsf_handle) return 0;
    void* newPtr = tlsf_realloc(tlsf_handle, reinterpret_cast<void*>(ptr), static_cast<size_t>(newSize));
    return reinterpret_cast<jlong>(newPtr);
}

// JNI: NativeMemoryManager.readByte
JNIEXPORT jbyte JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_readByte(JNIEnv *env, jobject thiz, jlong ptr) {
    return *reinterpret_cast<jbyte*>(ptr);
}

// JNI: NativeMemoryManager.writeByte
JNIEXPORT void JNICALL
Java_io_github_jwyoon1220_khromium_core_NativeMemoryManager_writeByte(JNIEnv *env, jobject thiz, jlong ptr, jbyte value) {
    *reinterpret_cast<jbyte*>(ptr) = value;
}

// QuickJS custom allocator functions.
// quickjs-ng uses void* opaque (not JSMallocState*) in its JSMallocFunctions.
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

size_t custom_js_malloc_usable_size(const void *ptr) {
    if (!tlsf_handle || !ptr) return 0;
    return tlsf_block_size(const_cast<void*>(ptr));
}

} // extern "C"