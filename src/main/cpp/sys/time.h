#ifndef MOCK_SYS_TIME_H
#define MOCK_SYS_TIME_H

#ifndef CONFIG_VERSION
#define CONFIG_VERSION "2024-01-13"
#endif

#ifdef _WIN32
#include <winsock2.h>
#include <windows.h>
#include <malloc.h>
#include <stdint.h>

#ifndef alloca
#define alloca _alloca
#endif

struct timezone; // quickjs doesn't use timezone, just declare empty

static inline int gettimeofday(struct timeval * tp, void * tzp) {
    if (tp) {
        static const uint64_t EPOCH = 116444736000000000ULL;
        SYSTEMTIME  system_time;
        FILETIME    file_time;
        uint64_t    time;
        GetSystemTime(&system_time);
        SystemTimeToFileTime(&system_time, &file_time);
        time = ((uint64_t)file_time.dwLowDateTime);
        time += ((uint64_t)file_time.dwHighDateTime) << 32;
        tp->tv_sec = (long)((time - EPOCH) / 10000000L);
        tp->tv_usec = (long)(system_time.wMilliseconds * 1000);
    }
    return 0;
}
#endif

#endif
