# NSTACKX Android integration — implementation checkpoint

## Pinned upstream source

- Project: OpenHarmony `communication_t2stack` (t2stack)
- Upstream repository: https://github.com/openharmony/communication_t2stack
- Reviewed revision: `fbf280db18092eb00bb065e59ce6676c7b85b8c9`
- Upstream describes three distinct capabilities: device discovery (NSTACKX control), file transfer (DFile), and stream transfer (FillP). MalekPlayer's first integration scope is discovery + DFile; FillP is out of scope for ordinary file sharing.

## Integration rules

1. Keep the existing Kotlin/TCP transfer implementation operational as a fallback until real-device acceptance tests pass.
2. Keep NSTACKX behind an internal transport/discovery interface. Do not expose upstream C structs or callbacks to UI/ViewModel code.
3. Do not report NSTACKX as integrated merely because a JNI library loads. Acceptance requires successful init, discovery, authenticated peer selection, DFile send/receive, cancellation, close, and resource cleanup on two physical Android devices.
4. Bind every socket to the selected Android Network where applicable. Never route a Wi-Fi Direct address through an unrelated WLAN interface.
5. Treat QR/MPC2 as the authenticated invitation/session layer. NSTACKX discovery metadata is not an authorization token.
6. Do not replace the current transport or change the app ID, Room schema, navigation, or existing screens as part of the native port.

## Android port work remaining

### A. Native build and platform layer

- Port upstream GN build targets and platform abstractions to Android NDK/CMake (or provide a reproducible GN cross-toolchain); upstream is not an Android Gradle AAR and must not be treated as one.
- Inventory and pin all transitive dependencies, licenses, compiler flags, and required platform services before enabling the native target.
- Implement Android/Bionic adapters for threading, sockets, interface enumeration, timers, logging, and lifecycle. Avoid copying OpenHarmony-only platform code into the Android build.
- Build and inspect ABI outputs for all four ABIs currently declared by the app: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Any deliberate reduction of the app ABI policy must be reviewed and documented.
- Add native init/deinit smoke tests and verify repeated start/stop does not leak threads, descriptors, or callbacks.

### B. DFinder / discovery

- Wrap NSTACKX discovery in a Kotlin-facing repository interface.
- Map discovered peers to stable, authenticated invitation identities; do not trust display names or discovery payloads.
- Handle permission denial, Wi-Fi state changes, duplicate peers, discovery timeout, and cancellation.

### C. DFile / file transfer

- Add the server/client lifecycle, storage-path policy, collision-safe rename behavior, progress callbacks, per-file results, cancellation, and cleanup.
- Connect DFile to the existing authenticated session negotiation. Never send credentials or long-lived secrets in QR payloads.
- Test small/large files, multiple files, interrupted transfer, low storage, duplicate names, and peer disappearance.

## Current verified status

This checkpoint records the reviewed upstream source and acceptance requirements. It does **not** claim that NSTACKX native binaries, JNI bindings, DFinder, or DFile have been built or integrated. Those steps must be implemented and proven by CI/device tests before the feature is enabled in production.

## Acceptance gate

Do not switch the default transport until:
- native builds pass for supported ABIs;
- automated native/Kotlin lifecycle and protocol tests pass;
- two physical Android devices discover, pair, send, and receive files;
- disconnect/reconnect, cancel, and resource cleanup tests pass;
- the existing TCP fallback remains covered and usable.


## Dependency and Bionic audit update — 2026-11-12

See [NSTACKX Android dependency audit](NSTACKX_ANDROID_DEPENDENCY_AUDIT.md) for the exact DFinder source selection, inspected dependency versions and repository snapshots, license findings, and Android/Bionic compatibility gaps.

The audit confirms that upstream Linux platform code cannot be copied wholesale into the Android build. In particular, interface enumeration and `SO_BINDTODEVICE`, CPU-affinity syscalls, UDP GSO, and OpenHarmony logging require Android-specific adaptation or capability-gated fallbacks.

The dependency repository heads inspected in the audit are candidate snapshots only, not approved immutable pins. A reproducible lock and compatibility test are still required before native CMake is added. No Android native target or NSTACKX binary has been built yet.


## Phase 1 — immutable source preparation — 2026-11-12

- Added `native/nstackx/dependencies.lock` with immutable commit IDs for the selected NSTACKX source, libcoap, cJSON, bounds-checking-function (securec), and the Mbed TLS candidate.
- Verified each recorded commit exists in its declared upstream GitHub repository through the commit API.
- Added `scripts/prepare_nstackx_sources.sh` to fetch each exact commit into `third_party/nstackx/`, detach the checkout, and verify the resulting HEAD against the lock. The script refuses to overwrite a non-Git directory or silently change an existing remote.
- Validated the script's Bash syntax with `bash -n`.
- The source-fetch script has **not** been executed against a local checkout in this environment, and no Android compilation has been run. Mbed TLS remains a candidate backend pending DFile crypto API adaptation and tests.
- Phase 1 is therefore complete as a reproducible source-lock/preparation implementation, not as proof that the upstream code compiles on Android.

## Remaining implementation phases

1. Phase 2: import/prepare pinned sources and create the Android NDK/CMake build graph, with explicit target/source lists.
2. Phase 3: implement Android platform adapters and build/test DFinder on every app ABI.
3. Phase 4: port DFile and congestion control, including Android crypto and filesystem adapters.
4. Phase 5: implement JNI lifecycle/callback safety and integrate with the existing Kotlin authenticated session and transfer fallback.
5. Phase 6: compile, fix native/Gradle issues, run automated tests and two-device discovery/send/receive acceptance.


## Phase 2 — Android NDK/CMake build graph — 2026-11-12

- Added `native/nstackx/CMakeLists.txt` with separate targets for securec, cJSON, NSTACKX utility, DFinder, congestion control, DFile, and a shared native packaging target.
- The source lists were cross-checked against the selected immutable OpenHarmony tree; cJSON and securec source/header locations were also checked against their pinned upstream trees.
- Added `native/nstackx/nstackx_engine_entry.c`, which exports the pinned source revision as diagnostic build metadata. This is not a JNI bridge and does not claim to initialize NSTACKX.
- Pinned Android NDK `27.2.12479018`, CMake `3.22.1`, and ABI filters for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` in the app Gradle configuration.
- Updated the Android workflow to install the pinned NDK/CMake versions and prepare all immutable source checkouts.
- Corrected the source-preparation script's repository-root calculation.
- Added a deliberate Gradle property gate: `-PenableNstackxNativeBuild=true` enables CMake only when the platform adapters and JNI bridge are ready. Routine app builds will not accidentally attempt an incomplete native port.
- **Not yet validated:** the CMake graph has not been configured or compiled with the NDK. Android/Bionic platform adaptation remains phase 3; DFile crypto/filesystem adaptation remains phase 4; JNI remains phase 5. The shared library is not yet wired into Kotlin and no real transfer is claimed.

Phase 2 establishes the build graph and toolchain provisioning, not a successful native build.


## Phase 3 — Android platform adaptation (in progress)

Implemented Android/Bionic platform adapters:
- `native/nstackx/android/sys_dev.c`: enumerates active IPv4 interfaces with `getifaddrs`, resolves local IP/interface names, derives broadcast addresses, classifies the source interface as P2P/WLAN for the upstream `GetConnectionTypeByDev` contract, and performs verified best-effort interface binding. It does not assume privileged routing or force an interface when Android rejects `SO_BINDTODEVICE`.
- `native/nstackx/android/sys_log.c`: routes NSTACKX logs to Android logcat.
- `native/nstackx/android/sys_util.c`: uses Android/Bionic thread, semaphore, clock and pthread APIs; CPU affinity is best-effort and maximum-priority escalation is intentionally disabled.
- `native/nstackx/android/sys_socket.c`: replaces Linux UDP_SEGMENT/GSO assumptions with ordinary UDP datagrams split at the requested MSS, preserving the byte-stream segmentation contract on Android kernels without GSO and mapping EAGAIN/EWOULDBLOCK back to NSTACKX retry status.
- CMake now selects these Android adapters instead of the upstream Unix versions for the four replaced platform modules and links Android `log`.

Still pending within phase 3:
- NDK compilation of the Android adapters and the complete DFinder target.
- Verify UDP fallback behavior under packet loss/MTU constraints and verify Wi-Fi Direct/LAN socket routing on Android devices.
- Review remaining epoll/event/timer sources against the minimum Android API.
- Wire Android ConnectivityManager network handles into socket creation before claiming deterministic multi-network routing.

No native build, DFinder runtime discovery, or two-device connection is claimed yet.


## Phase 3 — per-socket Android Network binding adapter — 2026-11-12

Added:
- `native/nstackx/android/sys_network.h` and `sys_network.c` expose
  `NstackxBindSocketToNetwork(fd, networkHandle)`.
- The adapter uses NDK `android_setsocknetwork()` (Android API 23+) with the
  `Network.getNetworkHandle()` value supplied by the Android layer. It binds
  an individual socket, does not change the process-wide default network, and
  returns failure rather than silently routing a socket over another network.
- CMake includes the adapter and links the NDK `android` library.

This is the native half of deterministic socket routing. It is **not yet called
from JNI/Kotlin**: the Java/Kotlin `ConnectivityManager` selection, handle
lifetime, socket-creation ordering, and P2P-vs-WLAN routing policy remain phase 5
integration work. NDK compilation and runtime behavior are still unverified.


## Phase 5 — first JNI / Kotlin DFinder lifecycle slice — 2026-11-12

Implemented on the development branch:
- Added `native/nstackx/android/nstackx_jni.c` with JNI init, DFinder thread init, local-device registration, discovery start/stop, callback delivery, and deterministic release.
- Added `NstackxNativeBridge.kt` as a Kotlin facade. Native callbacks are dispatched to registered listeners; discovered IDs/names/interfaces remain untrusted discovery metadata and do not bypass QR/PIN authentication.
- Added `NstackxAndroidDiscoverySession.kt` to select a currently available Android Wi-Fi/Wi-Fi Direct/Ethernet Network, obtain its interface/IP/Network handle, and own native discovery lifecycle.
- Extended the Android socket adapter with an atomic selected Network handle. NSTACKX's existing interface-binding hooks now attempt `android_setsocknetwork()` when `SO_BINDTODEVICE` is unavailable, so socket routing is applied per socket rather than changing the process default.
- Updated the CMake shared target to compile the JNI bridge and expose the required pinned public headers.
- Updated the Android workflow to pass `-PenableNstackxNativeBuild=true` for debug APK, release APK, and AAB builds. The first CI compile is expected to expose remaining upstream/NDK portability issues; do not disable this gate to hide failures.

Important limits:
- This JNI slice integrates DFinder lifecycle and peer metadata only. It does not yet wire DFile server/client sessions or transfer callbacks into the existing authenticated Ultra Share protocol.
- Android Network selection currently starts DFinder on one selected network per session; simultaneous dual-interface discovery/multipath remains follow-up work.
- No successful NDK build or two-physical-device discovery/transfer is claimed until CI and device acceptance pass.


## Phase 4/5 — DFile JNI API slice and app discovery hook — 2026-11-12

Additional implementation now present:
- JNI exposes DFile server/client creation, multi-file send queueing, session close, transfer progress/error callbacks, storage-root configuration, and collision-safe rename callback.
- Kotlin facade validates private IPv4 endpoints, 16-byte session-key length, app-owned writable receive directory, readable local files, and the existing APK/PDF share restrictions. Temporary key copies are zeroed after native handoff.
- Native release tracks and closes outstanding DFile sessions before tearing down DFinder callback references.
- Ultra Share ViewModel starts DFinder as a best-effort supplemental discovery signal while receiving or scanning. Existing UDP v1/v2 candidate discovery, QR approval, AES-GCM TCP transfer, and fallback remain authoritative; NSTACKX peer metadata only enriches names for matching stable peer IDs.

Still not complete:
- The current DFile JNI API is a low-level integration surface; it is not yet substituted into the foreground-service transfer coordinator. A caller must derive the 16-byte DFile key from the authenticated session context, stage content URIs into readable app-owned files, and manage DFile server/client event state.
- DFile server/client runtime, encryption interoperability, receive publication to MediaStore, cancellation semantics, resume behavior, and two-device transfer are not yet accepted.
- NDK CMake build, all four ABI outputs, unit tests, and real-device discovery/transfer remain unverified until CI completes.


## DFile session-key binding — 2026-11-12

- MPC2 already derives a per-connection 256-bit control key from the challenge-response handshake.
- The approved client and server now derive a dedicated 128-bit DFile key using HMAC-SHA256 with the domain label `MalekPlayer/NSTACKX/DFile/v1`, truncated to 16 bytes.
- The key is stored only with the corresponding live approved control session, exposed as a defensive copy, and cleared when that session closes or is replaced. This avoids reusing the visible pairing PIN as the DFile encryption key.
- This establishes matching key material for both peers; it does not yet mean DFile has been connected to the foreground-service batch coordinator or proven interoperable on devices.
