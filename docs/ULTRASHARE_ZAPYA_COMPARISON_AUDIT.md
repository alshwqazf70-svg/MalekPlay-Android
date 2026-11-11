# UltraShare ↔ Zapya Engineering Comparison Audit

**Updated:** 2026-11-11  
**MalekPlay branch:** `feature/zapya-engineering-analysis`  
**MalekPlay audited source commit:** `de1eaafa87c4770320847c32444d1983e7e4441f`  
**Zapya analysis repository:** `alshwqazf70-svg/Zapya-Engineering-Analysis`  
**Zapya analysis commit:** `9aa9e5702b5ecdd5ff09da8ec5c5cb94acc086f3`

> This report supersedes earlier audit snapshots. It is a source-level comparison, not a claim of 100% equivalence or physical-device validation.

## 1. Evidence boundary

The reference repository contains `apk/Zapya.apk`, SHA-256:

`0089495864823fcb50ab6eacb3795adef9efdae7ad4868246e1e8ee390cc9132`

The APK is an older build; its ZIP entry timestamps were observed as 2021-04-09. The six engineering reports use manifest/DEX/JADX/native-symbol and ARM disassembly evidence. JADX recovered 10,832 Java files with 33 recoverable decompilation errors. No complete native decompilation, live packet capture, or two-device runtime experiment was established. Findings below describe the archived APK only, not every current Zapya release.

The comparison repository `MalekPlay-Comparison` is a separate MalekPlay v2.0 Final source snapshot, not a Zapya source repository. It is useful for historical MalekPlay comparisons but is not the Zapya reference.

## 2. Zapya behavior supported by the archived APK reports

- Discovery/advertising is integrated through a Huawei NSTACKX JNI/native wrapper.
- Control negotiation precedes bulk transfer. A request asks for a data endpoint; the response returns candidate addresses and a selected/dynamic port.
- WLAN and Wi-Fi Direct/P2P candidates are represented separately. The inspected manager prefers P2P when its group is connected/preferred, otherwise WLAN.
- A separate LocalOnlyHotspot service owns the hotspot reservation lifecycle.
- File, byte/message, and stream transfers use distinct setup/session paths.
- Session IDs and transfer IDs are distinct; callbacks carry progress/state/error information.
- File-frame creation and receive call AES-GCM encrypt/decrypt functions.
- File-specific ACK, retransmission and rate-adaptation functions are present.
- KCP call sites were traced in the DMsg/message path; the evidence does not establish KCP use for file payloads.
- The inspected Java file scheduler submits one file per invocation and guards one in-flight file task in that queue. The native DFile API accepts a multi-entry list; these layers must not be conflated.
- ACK/retry evidence does not prove user-visible resume after process death.

## 3. Current MalekPlay inventory

At the audited commit the branch contains 256 tracked files, 49 test source files (47 JVM/unit-side and 2 Android instrumentation files), and the following UltraShare implementation areas:

- Discovery and routing: `UltraShareDiscovery.kt`, `UltraShareNegotiation.kt`, `UltraShareNetworkRouting.kt`, `UltraShareBluetoothDiscovery.kt`, `UltraShareWifiDirect.kt`.
- Control/security: `UltraShareControlChannel.kt`, `UltraSharePairingAttemptLimiter.kt`, `UltraSharePeerMutexRegistry.kt`.
- Data plane: `UltraShareTransport.kt` (legacy V1), `UltraShareBatchProtocol.kt`, `UltraShareBatchTransport.kt` (V2), `UltraShareFolderArchive.kt`.
- Lifecycle and delivery: `UltraShareForegroundService.kt`, `UltraShareMultiRecipientCoordinator.kt`, `UltraSharePeerDeliveryStore.kt`, `UltraShareQueueStore.kt`, `UltraShareReceivedHistoryStore.kt`, `UltraShareReceivedPublisher.kt`.
- UI: `UltraShareExperienceScreen.kt`, `UltraShareRadar.kt`, `UltraShareSessionRoom.kt`, `UltraShareHelpCard.kt`, plus the existing send/receive/device-picker screens.
- Desktop/browser: `desktop/ultra_share_v2.py`, `desktop/ultra_share_pc.py`, `desktop/ultra_share_web.py` and their tests.

`UltraShareScreen.kt` was replaced in the feature branch by the newer `UltraShareExperienceScreen.kt` plus dedicated send/receive/radar/session-room components. This is a source-file refactor, not proof that every old user-facing flow has an equivalent; navigation and screen-by-screen regression checks remain required. This is not a screenshot-by-screenshot visual sign-off.

## 4. Parity matrix

| Area | Current MalekPlay state | Assessment against archived Zapya evidence |
|---|---|---|
| LAN discovery | V1/V2 UDP discovery, stable peer identity and v2 candidate offers | Functional foundation; architecture differs from NSTACKX |
| WLAN/P2P candidates | WLAN, Wi-Fi Direct and hotspot candidates; route binding and reachability probes | Partial; no single automatic native-equivalent transport manager |
| Bluetooth | Classic Bluetooth discovery hints | Additional discovery path; not a proven Zapya bulk-transfer equivalent |
| Hotspot | Android LocalOnlyHotspot lifecycle in foreground service | Implemented in source; physical lifecycle validation remains |
| Control plane | Authenticated MPC2 TCP channel, challenge/HMAC, explicit approval, AES-GCM decision, heartbeat | Independent implementation of the separate-control-plane pattern |
| Endpoint negotiation | MPC2 control listener remains on TCP 38744; V2 batch receiver binds an OS-selected ephemeral TCP port and advertises the actual listening port to discovery; route refresh is matched by stable peer ID and uses the refreshed candidate port | Improved in current source: V2 data port is dynamic; control port is still fixed. Must validate collision, interface binding and endpoint-authentication behavior on devices |
| V2 file data | One encrypted TCP connection per batch; manifest supports up to 512 files; per-file results | Implemented in Android and Python source; not the same wire protocol as Zapya |
| Legacy V1 | Authenticated encrypted one-file sessions retained for compatibility | Implemented |
| File types | MIME validation accepts general document, archive and binary types, excluding APK/PDF; picker supports document selection | Broader scope is implemented in V2 source; verify on real Android providers |
| Integrity | SHA-256 preflight and receive verification; atomic publication and receipt markers | Strong source-level implementation; runtime evidence still required |
| Resume | Content-derived identity, partial files, durable offsets/receipts and retry logic | Source-level foundation; process-death and changed-source fault tests remain |
| Multi-recipient | Sender-side approved target list, bounded coordinator, per-peer delivery state; receiver accepts bounded peers | Per-peer cards and per-file status summaries are present in source; real-device fan-out and disconnect isolation still need validation |
| Folder transfer | ZIP archive creation and guarded extraction | Implemented in source; large/deep/special-file tests and device validation remain |
| PC companion | V1 and V2 receiver/sender, MPC2 approval, resume state | Desktop CI passes; no Android↔PC physical test yet |
| Browser companion | Loopback-only UI; selected files are staged and finalized through `stage-v2` and `send-v2-batch` in one V2 manifest/session | Multi-file browser batch path is implemented in source; desktop CI is the current evidence, not Android↔PC physical validation |
| Background lifecycle | Foreground service, Wi-Fi/wake locks, persistent queue and hotspot ownership | Source foundations present; screen-off, process death, OEM battery and network-switch tests remain |
| Performance | Bounded buffers/concurrency and per-file progress | No controlled throughput/CPU/memory/battery comparison |
| UI | Radar, send/receive, room, device picker, transfer/history screens remain | Functionality present; visual parity and per-peer room detail remain incomplete |

## 5. Verified findings and CI status

- The desktop workflow `UltraShare Desktop Protocol Tests` passed for commit `bba536a98f1f0dddbf430e88065d43d48b2386e1` (Python compile plus V1/V2 desktop unit/integration tests).
- Earlier desktop workflow failures exposed a missing exact-read helper and missing `secrets` import in the MPC2 server. Both were corrected; tests for fragmented reads and premature EOF were added.
- The Android APK workflow for the same commit was still in progress at the time of this update. Do not treat it as a successful build until GitHub Actions reports success.
- No physical Android-to-Android or Android-to-PC transfer has been demonstrated by these CI results.

## 6. Highest-priority remaining work

### P0 — endpoint and route negotiation
1. Complete collision, port-advertisement and endpoint-authentication tests for the OS-selected V2 data port; keep the MPC2 control listener distinct.
2. Ensure control and data sockets are bound to the intended Android `Network`, and preserve the stable peer identity when switching between WLAN/P2P/hotspot routes.
3. Add multiple-interface, stale-candidate, route-switch, changed-IP and rejected-pairing integration tests.

### P1 — multi-peer UI and delivery correctness
1. Validate every active peer card and its per-file summary in `UltraShareSessionRoom`.
2. Verify per-peer progress, speed, per-file results, failure, retry and cancellation remain independent when a route/IP changes.
3. Test duplicate names/content, storage-full, revoked URI permission, partial failure and one-recipient disconnect during fan-out.

### P2 — reliability and lifecycle proof
1. Fault-inject disconnects during handshake, manifest, file frame, final result and receipt publication.
2. Test receiver and sender process death, screen-off, Wi-Fi changes, hotspot shutdown and OEM background restrictions.
3. Verify resume only for the same content identity; reject changed source bytes.
4. Benchmark chunk sizes and concurrency on the same devices/network; do not claim speed parity without measurements.

### P3 — desktop/browser parity and UI
1. Make the browser upload action send one multi-file V2 manifest instead of one batch per selected file.
2. Add explicit per-device state/progress to the desktop/browser companion.
3. Compare all supplied Zapya UI references screen by screen while retaining MalekPlay branding and all current UltraShare screens.

## 7. Engineering constraints

- Keep MalekPlay's protocol independent. Do not copy Zapya source, native libraries, proprietary TLV layouts, packet formats, or branded artwork.
- Do not add KCP to file transfer merely because KCP symbols exist in the archived APK; the inspected call sites place KCP in the DMsg/message path.
- Do not infer runtime resume, performance, or process-death guarantees from fields, symbols, or unit tests alone.
- Preserve V1 compatibility until V2 interoperability and migration tests are complete.
- Continue work only on `feature/zapya-engineering-analysis`; do not modify or merge `main`.

## 8. Acceptance criteria before claiming Zapya-like readiness

- Two physical Android devices discover and pair without manual IP entry on supported routes.
- WLAN and Wi-Fi Direct/hotspot routes are exercised; selected route and endpoint are visible/debuggable.
- Control approval is authenticated and bound to the correct peer and selected data endpoint.
- Multiple documents/media/APKs/folders transfer with independent per-file and per-peer state.
- SHA-256 matches at both ends; incomplete files are never published as complete.
- Disconnect/reconnect/resume works at tested failure points, including screen-off and process restart.
- All sockets, receivers, hotspot reservations, wake locks and temporary files are released on cancellation/failure.
- Android unit tests, Room/instrumentation tests, desktop tests and real-device tests pass.
- Throughput claims are based on repeated controlled measurements, not assumptions.


## 9. NSTACKX migration assessment (2026-11-11)

A separate staged integration decision is recorded in [NSTACKX_ANDROID_INTEGRATION_PLAN.md](NSTACKX_ANDROID_INTEGRATION_PLAN.md). The selected upstream is OpenHarmony `communication_t2stack` (Apache-2.0), pinned for assessment at `fbf280db18092eb00bb065e59ce6676c7b85b8c9`.

NSTACKX is not a single interchangeable file-transfer library: `nstackx_ctrl` provides DFinder discovery, `nstackx_core/dfile` provides file transfer, and `fillp` provides streaming. The upstream public DFile API exposes file/session progress and failure callbacks, a resumable-transfer capability bit, multipath structures and a 16-byte session-key contract. However, the upstream GN build inspected does not provide a ready-made Android Gradle/NDK target, and this MalekPlay branch currently has no CMake/NDK integration or packaged NSTACKX ABI libraries.

**Decision:** target NSTACKX DFinder first, then DFile behind an adapter and feature flag after a reproducible Android NDK build. Keep MPC2 authenticated peer approval and the current TCP batch transport as fallback until native build, security, resume, multi-recipient, lifecycle and physical-device acceptance tests pass. No NSTACKX binary has been added and no current transfer path is claimed to use NSTACKX yet.
