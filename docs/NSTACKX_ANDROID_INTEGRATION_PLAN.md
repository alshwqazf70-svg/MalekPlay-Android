# NSTACKX Android Integration Assessment and Migration Plan

## Decision

Adopt OpenHarmony `communication_t2stack` as the target native networking engine for UltraShare, in staged integration. Do not replace the existing transport in one step.

Pinned upstream assessment revision: `openharmony/communication_t2stack@fbf280db18092eb00bb065e59ce6676c7b85b8c9` (2026-05-09).

Upstream repository: https://github.com/openharmony/communication_t2stack

License: Apache License 2.0 (verify and retain notices for every imported source file and all transitive dependencies).

## What NSTACKX means in this repository

The upstream t2stack repository contains distinct capabilities. They must not be conflated:

- `nstackx_ctrl`: NSTACKX device discovery/control (DFinder, including WLAN/CoAP discovery).
- `nstackx_core/dfile`: DFile file-transfer engine.
- `fillp`: streaming transport, intended for media/audio/video streams rather than replacing the file-transfer API.
- `nstackx_util` and `nstackx_congestion`: shared support components.

The upstream README documents DFile server/client sessions, file-list sending, storage path and rename hooks, and capability negotiation. The public DFile header exposes per-file and session progress/failure callbacks, multipath structures, a resumable-transfer capability bit, and a 16-byte session encryption-key contract.

## Android compatibility finding

The current MalekPlay app is a Kotlin/Compose Android application with minSdk 23. Its app Gradle configuration does not currently declare `externalNativeBuild`, CMake, an NDK version, or packaged NSTACKX ABI libraries.

The upstream t2stack GN build describes Linux/Unix and OpenHarmony targets, but the inspected DFile build does not provide a ready-made Android Gradle/NDK target. The standard Linux build also names external dependencies such as OpenSSL, bounds-checking functions and OpenHarmony build-system dependencies. Therefore, adding an arbitrary Maven dependency or copying decompiled Zapya binaries would not constitute a valid integration.

An Android NDK port/build must explicitly resolve:
- Android/Bionic compatibility for the Unix platform sources and socket/interface binding.
- ABI outputs for arm64-v8a and armeabi-v7a at minimum (x86_64 for emulator CI if feasible).
- crypto provider compatibility and key handling; do not silently substitute or disable DFile encryption.
- libcoap, cJSON, bounds-checking and crypto dependency versions/licences.
- lifecycle, callback threading, cancellation and deterministic native resource destruction.
- Android network binding and Wi-Fi Direct/local-only-hotspot interface selection.
- upstream version pinning, license/NOTICE attribution and reproducible native builds.

## Safe target architecture

Keep the current Kotlin domain and UI contracts. Introduce a native engine behind adapters rather than coupling Compose or ViewModels directly to JNI:

1. `UltraShareDiscovery` remains the app-facing peer model and coordinator.
2. Add an NSTACKX DFinder provider behind the discovery interface. Convert NSTACKX device results to stable MalekPlay peer identities and route candidates. Treat discovery metadata as untrusted.
3. Keep the existing authenticated MPC2 control/approval handshake as the trust authority. NSTACKX discovery alone must never authorize a transfer or supply trusted session secrets.
4. Add a separate DFile transport adapter implementing the existing transfer contract. Derive a short-lived 16-byte DFile session key only after authenticated control-channel negotiation; never persist or log it.
5. Map DFile callbacks to per-peer/per-file receipts and progress. Treat DFile's session/file callbacks as native events, copy callback-owned strings/data before returning, and marshal events safely to Kotlin.
6. Keep the existing TCP batch transport as a compatibility/fallback path until DFile passes the complete acceptance matrix on real Android devices.
7. Preserve Bluetooth/BLE discovery, Wi-Fi Direct, hotspot, browser and desktop flows. Do not claim that NSTACKX automatically supports these paths; each needs an explicit adapter or fallback.

## Required implementation stages

### Stage A — Native build proof (blocking gate)
- Add a reproducible Android NDK/CMake build for pinned upstream source.
- Build all supported ABIs and package the resulting shared libraries.
- Add license/NOTICE files and a dependency inventory.
- Run native smoke tests and verify app startup on minSdk 23 and current Android.
- Do not enable the native engine by default until this gate is green.

### Stage B — DFinder discovery integration
- Add JNI initialization, local device registration, start/stop discovery and callback registration.
- Add stable peer identity, stale-result expiry, deduplication and interface-aware addresses.
- Test duplicate names, multiple interfaces, permission denial, Wi-Fi changes, process recreation and cleanup.
- Keep existing UDP/BLE/Bluetooth discovery as fallback.

### Stage C — DFile transfer pilot
- Implement native server/client lifecycle, secure session key injection, file-list submission, storage path, rename policy, progress and terminal callbacks.
- Enforce MalekPlay's file policy before native handoff: allow supported general files, explicitly reject APK and PDF, and do not trust file names/extensions alone.
- Preserve per-file/per-peer results, queue persistence and user-visible errors.
- Verify cancellation closes sessions and releases all native resources.
- Test partial transfer, resume, corrupted input, duplicate names, disk full, process death, network change and independent recipient failure.

### Stage D — Controlled rollout
- Add a runtime feature flag and select DFile only for validated device/route combinations.
- Compare correctness, throughput, battery, memory and failure recovery against the current transport.
- Keep fallback and rollback until multiple real-device test cycles pass.
- Only then consider making DFile the default for supported Android routes.

## Non-negotiable invariants

- Do not modify or merge `main`; work only on `feature/zapya-engineering-analysis`.
- Do not remove or replace any UltraShare screen or existing browser/desktop flow.
- Do not remove the current transport before DFile parity and fallback are proven.
- Never trust a discovered IP/name as device authentication.
- Never disable encryption to make the native port compile.
- Never claim Android/NSTACKX compatibility, build success or device-test success without recorded evidence.

## Acceptance matrix

| Area | Required evidence |
|---|---|
| Native build | Reproducible CI build for each shipped ABI |
| Discovery | Two or more Android devices discover each other across supported LAN routes |
| Identity | Duplicate names and stale IPs cannot redirect a session |
| Security | Authenticated control approval precedes DFile session key delivery |
| Transfer | Multiple files, large files, empty files and supported MIME types |
| Integrity | End-to-end digest validation and no partial file published as complete |
| Resume | Network/IP change resumes safely without corrupting or duplicating completed files |
| Multi-recipient | One peer disconnecting does not cancel other peers |
| Cancellation | Native sessions, sockets, callbacks and temporary files are cleaned up |
| Persistence | Process death/restart behavior is explicit and tested |
| Compatibility | Existing fallback, desktop/browser and all UltraShare screens remain functional |

## Current status

This is an engineering integration decision and source assessment, not a claim that NSTACKX is already integrated. The current MalekPlay Kotlin transport remains the active production path until the native Android build and staged acceptance gates pass.
