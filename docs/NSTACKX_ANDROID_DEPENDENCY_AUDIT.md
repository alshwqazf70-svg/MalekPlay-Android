# NSTACKX Android dependency and platform audit

Date: 2026-11-12

Target application branch: `feature/zapya-engineering-analysis`

Target application base before this audit:
`6ba6fe08a7586b47e021d39728fb75c8bbc9f4e1`

Upstream source:
`openharmony/communication_t2stack@fbf280db18092eb00bb065e59ce6676c7b85b8c9`

## 1. Scope and outcome

This is a dependency and platform compatibility audit for the first native milestone
(DFinder only). It is not a native build result. No NSTACKX native source, dependency
source, Gradle configuration, JNI, or workflow file was changed in this audit.

The upstream t2stack bundle identifies itself as version `4.0.2` and declares
CoAP, cJSON, bounds_checking_function, OpenSSL and mbedTLS dependencies. The checked
upstream GN files confirm that the DFinder standard CoAP target uses the real
CoAP implementation and does not provide an Android CMake build target.

## 2. DFinder build inputs

The exact DFinder source selection in
`nstackx_ctrl/BUILD.gn` at the pinned upstream revision is:

Base sources:
- `core/json_payload.c`
- `core/nstackx_common.c`
- `core/nstackx_device.c`
- `core/nstackx_device_local.c`
- `core/nstackx_device_remote.c`
- `core/nstackx_dfinder_log.c`
- `core/nstackx_inet.c`

Standard CoAP sources:
- `core/coap_discover/coap_app.c`
- `core/coap_discover/coap_client.c`
- `core/coap_discover/coap_discover.c`
- `core/nstackx_dfinder_hidump.c`
- `core/nstackx_dfinder_mgt_msg_log.c`
- `core/nstackx_dfinder_hievent.c`
- `core/nstackx_statistics.c`
- `core/nstackx_smartgenius.c`

The target also depends on `nstackx_util`, `libcoap`, `cJSON` and
`bounds_checking_function`. The DFinder public API is
`nstackx_ctrl/interface/nstackx.h`.

The Linux utility source selection in `nstackx_util/BUILD.gn` is the basis for
the first Android adaptation review. It includes device enumeration, epoll, event,
socket, timer, utility and logging platform sources.

## 3. Third-party candidate inventory

The following repository heads were inspected to identify concrete candidates and
license obligations. Their SHAs are recorded for audit reproducibility, but these
moving-branch snapshots are **not yet approved integration pins**. Before vendoring
or adding submodules, compatibility with the exact NSTACKX source must be tested and
the selected revisions must be fixed in the build.

| Dependency | Inspected version / snapshot | Repository | License finding | Role |
|---|---|---|---|---|
| libcoap | 4.3.5; `90d3c3f9ceac2b25aea132bfce080af851315097` (2025-11-17) | `openharmony/third_party_libcoap` | BSD-2-Clause for library; retain included uthash/utlist notices | Required by DFinder CoAP target |
| cJSON | 1.7.19; `28dba43de56de279d1b5b74565f4277c89c50f35` (2026-09-09) | `openharmony/third_party_cJSON` | MIT | JSON payload parsing/serialization |
| bounds_checking_function | `61baa7a3eb9347efca58397d1d0504faa96d79f0` (2025-06-16) | `openharmony/third_party_bounds_checking_function` | Mulan PSL v2 | Implements securec functions such as `memcpy_s` and `strcpy_s` |
| Mbed TLS | 3.6.5; `15a33d7caace0ce655b4cdf20a1f440cf9afa712` (2026-09-22) | `openharmony/third_party_mbedtls` | Apache-2.0 OR GPL-2.0-or-later; if selected, use the Apache-2.0 option and ship its notices | Candidate DFile crypto backend, not required for the initial DFinder target |

The OpenHarmony manifest currently declares these projects at `revision="master"`,
not at immutable revisions. Therefore the manifest alone does not give a reproducible
dependency lock for this Android integration. The hashes above are recorded as
inspected snapshots only; do not silently substitute a later branch head.

The libcoap source CMake project reports API version 3 and version 4.3.5. The
NSTACKX CoAP header includes `coap3/coap.h`, so the selected library must provide
that API/header layout. The cJSON source CMake project reports version 1.7.19.

## 4. Android/Bionic adaptation findings

Do not compile the entire `platform/unix` directory blindly. The inspected upstream
Linux adapter contains code that needs Android-specific treatment:

- `nstackx_util/platform/unix/sys_dev.c` enumerates interfaces using
  `SIOCGIFCONF`, `SIOCGIFADDR`, `SIOCGIFNETMASK`, assumes interface-name
  prefixes such as `wlan`, `eth` and `p2p`, and attempts
  `SO_BINDTODEVICE`. Android permission and network-binding semantics differ;
  discovery must bind to the Android-selected `Network`/interface and handle
  Wi-Fi Direct and LAN without relying on hard-coded interface names.
- `nstackx_util/platform/unix/sys_util.c` uses `gettid()`,
  `syscall(__NR_sched_setaffinity,...)`, `CPU_SET` and `prctl`. These
  optimizations must be isolated or replaced with Bionic-supported APIs; failure
  to set CPU affinity must not prevent discovery.
- `nstackx_util/platform/unix/sys_socket.c` contains UDP GSO ancillary-message
  handling. Android kernel support is device-dependent; the feature must be
  capability-gated and fall back to ordinary UDP.
- `nstackx_util/platform/unix/sys_log.c` can use printf-based logging when
  `ENABLE_DFINDER_HILOG` is not defined. The Android build should provide a
  bounded Android log adapter rather than depend on OpenHarmony HiLog.
- securec is included directly by DFinder and utility source files. It must be
  built as a pinned static target or included from a pinned source revision; do not
  replace the safe APIs with empty macros.
- The upstream DFile Unix file manager calls `realpath`, filesystem access checks,
  and path-based storage APIs. This is a separate Android scoped-storage issue and
  is deliberately outside the first DFinder-only build gate.

The application currently declares four Android ABIs: `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`. Native delivery must cover all four,
or an intentional app ABI-policy change must be reviewed and documented first.

## 5. Crypto decision for later DFile work

The inspected standard OpenHarmony DFile target links `openssl:libcrypto_shared`
and defines `SSL_AND_CRYPTO_INCLUDED`. Android NDK does not provide a general
public OpenSSL `libcrypto` shared library for application linking. The Linux
LiteOS target instead selects the Mbed TLS backend via `MBEDTLS_INCLUDED`.

For Android DFile, Mbed TLS is a plausible candidate backend, but it is not yet
approved: compile the exact NSTACKX Mbed TLS adapter against the pinned Mbed TLS
revision and a deliberately configured feature set, then run encrypt/decrypt
round-trip, invalid-tag, concurrent-session and random-source tests. Keep the
DFile session key separate from QR/pairing secrets; derive a temporary 16-byte
DFile key from the authenticated session.

## 6. First native milestone gate

Before declaring DFinder buildable:

1. Freeze immutable dependency revisions and store their license/NOTICE files.
2. Add a CMake Android platform layer rather than treating OpenHarmony GN as CMake.
3. Compile the exact upstream DFinder and utility source list for each app ABI.
4. Replace/adapt Linux interface discovery and socket binding with Android-aware
   network selection, including the Wi-Fi Direct network path.
5. Provide bounded Android logging and verify securec calls resolve to real code.
6. Add a native test harness covering init, local device registration, discovery
   callback, stop and deinit; add two-device LAN discovery verification.
7. Inspect every produced shared object with `llvm-readelf` and verify the
   expected ABI, exported NSTACKX API symbols and dependency closure.

Only after these checks pass should DFile implementation begin. JNI/Kotlin wiring
must not be used to disguise an unbuilt or nonfunctional native library.

## 7. Current status

- Dependency repositories, candidate versions, license families and inspected
  repository snapshots: recorded.
- Immutable, compatibility-tested dependency lock: pending.
- Android/Bionic adapter: pending.
- Android CMake build: not yet added.
- DFinder native compile and tests: not yet run.
- DFile, JNI and Kotlin integration: not started.
