package io.github.jwyoon1220.khromium.core

/**
 * Native Memory Manager that interfaces directly with the C++ TLSF implementation.
 * It provides identical capabilities to a traditional Heap Allocator but runs
 * off the JVM heap via JNI.
 */
object NativeMemoryManager {

    init {
        // We assume the native DLL is loaded by the main application class or here
        // If not packaged in jar, you may need System.load("absolute_path")
        try {
           System.loadLibrary("KhromiumCore")
        } catch (e: UnsatisfiedLinkError) {
           println("WARNING: Could not load KhromiumCore DLL. Ensure it's compiled and in PATH.")
        }
    }

    /**
     * Initializes the TLSF memory pool with the given size.
     * Returns the base address pointer.
     */
    external fun initHeap(size: Long): Long

    /**
     * Allocates memory from the TLSF pool.
     */
    external fun malloc(size: Int): Long

    /**
     * Frees previously allocated memory.
     */
    external fun free(ptr: Long)

    /**
     * Reallocates memory from the TLSF pool.
     */
    external fun realloc(ptr: Long, newSize: Int): Long

    /**
     * Native byte-level read operations.
     */
    external fun readByte(ptr: Long): Byte

    /**
     * Native byte-level write operations.
     */
    external fun writeByte(ptr: Long, value: Byte)

    // Helper functions built around the native JNI calls
    fun readInt(ptr: Long): Int {
        val b0 = readByte(ptr).toInt() and 0xFF
        val b1 = readByte(ptr + 1).toInt() and 0xFF
        val b2 = readByte(ptr + 2).toInt() and 0xFF
        val b3 = readByte(ptr + 3).toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun writeInt(ptr: Long, value: Int) {
        writeByte(ptr, (value and 0xFF).toByte())
        writeByte(ptr + 1, ((value shr 8) and 0xFF).toByte())
        writeByte(ptr + 2, ((value shr 16) and 0xFF).toByte())
        writeByte(ptr + 3, ((value shr 24) and 0xFF).toByte())
    }

    fun readLong(ptr: Long): Long {
        var result = 0L
        for (i in 0..7) {
            val b = readByte(ptr + i).toLong() and 0xFFL
            result = result or (b shl (i * 8))
        }
        return result
    }

    fun writeLong(ptr: Long, value: Long) {
        for (i in 0..7) {
            writeByte(ptr + i, ((value shr (i * 8)) and 0xFFL).toByte())
        }
    }
    
    fun readString(ptr: Long): String {
        val bytes = mutableListOf<Byte>()
        var currentAddr = ptr
        while (true) {
            val b = readByte(currentAddr)
            if (b.toInt() == 0) break
            bytes.add(b)
            currentAddr++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    fun writeString(str: String): Long {
        val bytes = str.toByteArray(Charsets.UTF_8)
        val ptr = malloc(bytes.size + 1)
        for (i in bytes.indices) {
            writeByte(ptr + i, bytes[i])
        }
        writeByte(ptr + bytes.size, 0)
        return ptr
    }
}
