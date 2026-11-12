/*
 * MalekPlayer NSTACKX Android native engine build identity.
 *
 * This symbol is diagnostic metadata only. Operational JNI entry points are
 * added in phase 5 after the upstream DFinder and DFile APIs are adapted.
 */
#ifndef NSTACKX_ANDROID_NSTACKX_REVISION
#define NSTACKX_ANDROID_NSTACKX_REVISION "unversioned"
#endif

const char *NSTACKX_AndroidEngineRevision(void)
{
    return NSTACKX_ANDROID_NSTACKX_REVISION;
}
