/*
 * Copyright (c) 2021 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0.
 * Android log adapter for NSTACKX.
 */
#include <android/log.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include "nstackx_log.h"

static int AndroidPriority(uint32_t level)
{
    switch (level) {
        case NSTACKX_LOG_LEVEL_FATAL: return ANDROID_LOG_FATAL;
        case NSTACKX_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case NSTACKX_LOG_LEVEL_WARNING: return ANDROID_LOG_WARN;
        case NSTACKX_LOG_LEVEL_INFO: return ANDROID_LOG_INFO;
        default: return ANDROID_LOG_DEBUG;
    }
}

void PrintfImpl(const char *moduleName, uint32_t logLevel, const char *format, ...)
{
    char message[1024];
    va_list args;
    if (format == NULL) return;
    va_start(args, format);
    (void)vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_print(AndroidPriority(logLevel), "NSTACKX",
        "%s: %s", moduleName != NULL ? moduleName : "core", message);
}
