/*
 * Copyright (c) 2021 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0.
 * Android/Bionic adaptation for NSTACKX.
 */
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <time.h>
#include <errno.h>
#include <string.h>
#include <pthread.h>
#include <sched.h>
#include "nstackx_util.h"
#include "nstackx_error.h"
#include "nstackx_log.h"

#define TAG "NSTACKXAndroidUtil"

/* Keep upstream DFile path-traversal validation available on Android. */
uint8_t IsFileNameLegal(const char *fileName)
{
    static const char *const illegalHead[] = { "../" };
    static const char *const illegalPath[] = { "/../" };
    size_t i;
    if (fileName == NULL || fileName[0] == '\0') return NSTACKX_FALSE;
    for (i = 0; i < sizeof(illegalHead) / sizeof(illegalHead[0]); ++i) {
        size_t n = strlen(illegalHead[i]);
        if (strlen(fileName) >= n && memcmp(fileName, illegalHead[i], n) == 0)
            return NSTACKX_FALSE;
    }
    for (i = 0; i < sizeof(illegalPath) / sizeof(illegalPath[0]); ++i) {
        if (strstr(fileName, illegalPath[i]) != NULL) return NSTACKX_FALSE;
    }
    return NSTACKX_TRUE;
}

int32_t GetCpuNum(void)
{
    long count = sysconf(_SC_NPROCESSORS_ONLN);
    return count > 0 && count <= INT32_MAX ? (int32_t)count : 1;
}

/* Android scheduling policy is controlled by the OS; affinity is best-effort. */
void StartThreadBindCore(int32_t cpu)
{
#if defined(__ANDROID__) && defined(__linux__) && defined(CPU_SET)
    cpu_set_t set;
    if (cpu < 0 || cpu >= GetCpuNum() || cpu >= CPU_SETSIZE) return;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0)
        LOGD(TAG, "CPU affinity is unavailable: %d", errno);
#else
    (void)cpu;
#endif
}

void BindThreadToTargetMask(pid_t tid, uint32_t cpuMask)
{
#if defined(__ANDROID__) && defined(__linux__) && defined(CPU_SET)
    cpu_set_t set;
    int cpu;
    if (tid <= 0 || cpuMask == 0) return;
    CPU_ZERO(&set);
    for (cpu = 0; cpu < GetCpuNum() && cpu < CPU_SETSIZE && cpu < 32; ++cpu)
        if ((cpuMask & (1U << cpu)) != 0) CPU_SET(cpu, &set);
    if (sched_setaffinity((pid_t)tid, sizeof(set), &set) != 0)
        LOGD(TAG, "thread affinity request ignored: %d", errno);
#else
    (void)tid; (void)cpuMask;
#endif
}

void SetThreadName(const char *name)
{
    char safeName[16] = {0};
    size_t length;
    if (name == NULL || name[0] == '\0') return;
    length = strnlen(name, sizeof(safeName) - 1);
    memcpy(safeName, name, length);
    if (pthread_setname_np(pthread_self(), safeName) != 0)
        LOGD(TAG, "pthread_setname_np failed: %d", errno);
}

void SetMaximumPriorityForThread(void)
{
    /* Never request nice(-20): Android apps do not have CAP_SYS_NICE. */
}

void ClockGetTime(clockid_t id, struct timespec *tp)
{
    if (tp != NULL && clock_gettime(id, tp) != 0)
        LOGE(TAG, "clock_gettime failed: %d", errno);
}

int32_t SemInit(sem_t *sem, int pshared, unsigned int value) { return sem_init(sem, pshared, value); }
void SemGetValue(sem_t *sem, int *value) { if (sem_getvalue(sem, value) != 0) LOGE(TAG, "sem_getvalue: %d", errno); }
void SemPost(sem_t *sem) { if (sem_post(sem) != 0) LOGE(TAG, "sem_post: %d", errno); }
void SemWait(sem_t *sem) { int rc; do { rc = sem_wait(sem); } while (rc != 0 && errno == EINTR); if (rc != 0) LOGE(TAG, "sem_wait: %d", errno); }
void SemDestroy(sem_t *sem) { if (sem_destroy(sem) != 0) LOGE(TAG, "sem_destroy: %d", errno); }
int32_t PthreadCreate(pthread_t *tid, const pthread_attr_t *attr, void *(*entry)(void *), void *arg) { return pthread_create(tid, attr, entry, arg); }
void PthreadJoin(pthread_t thread, void **retval) { int rc = pthread_join(thread, retval); if (rc != 0) LOGE(TAG, "pthread_join: %d", rc); }
int32_t PthreadMutexInit(pthread_mutex_t *mutex, const pthread_mutexattr_t *attr) { return pthread_mutex_init(mutex, attr); }
void PthreadMutexDestroy(pthread_mutex_t *mutex) { int rc = pthread_mutex_destroy(mutex); if (rc != 0) LOGE(TAG, "mutex destroy: %d", rc); }
int32_t PthreadMutexLock(pthread_mutex_t *mutex) { return pthread_mutex_lock(mutex); }
int32_t PthreadMutexUnlock(pthread_mutex_t *mutex) { return pthread_mutex_unlock(mutex); }

/* NSTACKX epoll and DFile platform contract. */
void CloseDesc(int32_t desc)
{
    if (desc >= 0 && close(desc) != 0)
        LOGE(TAG, "close desc error: %d", errno);
}
