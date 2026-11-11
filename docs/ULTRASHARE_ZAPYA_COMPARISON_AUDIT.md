# UltraShare ↔ Zapya Engineering Comparison Audit

**Audit date:** 2026-11-11  
**MalekPlay repository:** `alshwqazf70-svg/MalekPlay-Android`  
**Audited branch:** `feature/zapya-engineering-analysis`  
**Audited MalekPlay commit:** `8ed1b0dd2c55e5c24b8719475f41d0057effd4c7`  
**Zapya analysis repository:** `alshwqazf70-svg/Zapya-Engineering-Analysis`  
**Zapya analysis commit:** `9aa9e5702b5ecdd5ff09da8ec5c5cb94acc086f3`

## 1. Evidence boundary

The available Zapya artifact is an older APK whose ZIP entry timestamps are 2021-04-09. The six engineering reports are based on static manifest/DEX/JADX/native-symbol and ARM disassembly analysis. JADX recovered 10,832 Java files with 33 recoverable decompilation errors. No live packet capture, full native decompilation, or two-device runtime experiment was part of that analysis.

Therefore this audit compares MalekPlay against the **observed architecture of that archived Zapya build** and the supplied UI reference screenshots. It does not claim that every current Zapya release behaves identically. Static evidence is not runtime proof.

## 2. Zapya architecture findings (observed)

- Discovery/advertising uses a Huawei NSTACKX JNI/native discovery wrapper; it is not merely a Java UDP broadcast loop.
- Control negotiation is separate from bulk data. A request asks for an endpoint; the response returns one or more transport candidates and a selected/dynamic data port.
- WLAN and Wi-Fi Direct/P2P candidates are represented separately. The inspected manager prefers P2P when its group is connected/preferred, otherwise uses WLAN.
- A LocalOnlyHotspot service manages its own reservation and lifecycle.
- File, byte/message, and stream transfers have distinct setup/session paths.
- Session IDs and transfer IDs are distinct; callbacks report file names, counts, total/transferred bytes, state and error information.
- Native file frames call AES-GCM encrypt/decrypt functions. File-specific ACK, retransmission and rate-adaptation functions are present.
- KCP calls were traced in the DMsg/message path; the evidence does **not** establish that file payloads use KCP.
- The Java scheduler observed in the APK submits one file per invocation and guards one in-flight file task in that queue. The native DFile API itself accepts a multi-entry list. Do not confuse these layers.
- ACK/retry evidence does not prove user-visible resume after process death. Resume needs durable transfer identity, verified offsets and peer agreement.

## 3. MalekPlay implementation found in the audited branch

### Present foundations

- Independent v1/v2 UDP discovery and versioned, secret-free v2 peer offers.
- Stable local peer identity and candidate lists for WLAN, Wi-Fi Direct and hotspot interfaces.
- Candidate reachability probes for both the control endpoint and v2 data endpoint.
- Separate authenticated TCP control channel (port 38744) and v2 encrypted batch data channel (port 38743), plus legacy v1 compatibility.
- PBKDF2-HMAC-SHA256 pairing-key derivation, challenge-response HMAC, explicit receiver approval, directional session keys and AES-GCM framed v2 data.
- SHA-256 preflight and receive-side verification; temporary partial files and receipt markers support safe same-content retry/resume across a new data session.
- Per-peer transfer locking, bounded receiver concurrency (up to four peers), per-file outcomes/progress, persistent send queue/history, and foreground-service transfer lifecycle.
- Wi-Fi Direct helper and Android LocalOnlyHotspot lifecycle exist.
- The audited tree contains 43 JVM unit-test source files, including dedicated discovery, negotiation, routing, pairing, control-channel, batch protocol, queue and transport tests; it contains 2 Android instrumentation test source files.

### Confirmed gaps relative to the observed Zapya experience

1. **Transport discovery and automatic P2P orchestration:** MalekPlay has a Wi-Fi Direct helper, but the current user flow exposes a separate manual scan/select/connect action. It is not yet an automatic candidate-negotiation lifecycle comparable to Zapya's integrated discovery/transport manager.
2. **Endpoint negotiation:** MalekPlay currently relies on fixed control/data ports rather than a fully negotiated per-session endpoint/port response. This is simpler, but less flexible in port-conflict and multi-interface cases.
3. **File-type coverage:** v2 manifest validation and transport metadata currently restrict sharing to video, audio and image MIME types. Zapya's general file-sharing experience is broader; MalekPlay needs a safe arbitrary-file/document/APK/folder policy if it is to match that scope.
4. **Multi-recipient sending UX:** the receive engine can accept up to four peers, but the current send flow is centered on one approved host per batch. Receiver concurrency is not equivalent to one-to-many sender selection.
5. **Multi-peer session presentation:** the session room currently derives its headline peer from the first active peer. It needs a full device list with independent state, progress, speed, retry and cancellation controls.
6. **Background and lifecycle parity:** the transfer foreground service is present, but process-death, screen-off, Wi-Fi switching, hotspot shutdown, Android vendor battery restrictions and service restart behavior still require paired-device validation.
7. **Performance parity:** current 256 KiB frame / 1 MiB I/O buffer settings are implementation choices, not benchmark results. No controlled throughput, CPU, memory, battery or packet-loss comparison is established.
8. **Automated integration evidence:** JVM coverage is meaningful, but there is no evidence in this audit of a completed Android two-device instrumentation suite covering discovery → pairing → multi-file send → checksum → interruption → resume → process restart.

## 4. Important technical distinctions

- Do not add KCP to file transfer just because Zapya's native library contains KCP. The archived call-chain report places the directly observed KCP calls in DMsg/message handling; the file path has its own AES-GCM and ACK/retransmission call sites.
- MalekPlay's v2 partial-file mechanism is stronger evidence of an application-level retry/resume design than simply storing byte counters, but its behavior still needs two-device fault-injection tests.
- A successful desktop protocol test or GitHub CI build does not establish Android Wi-Fi Direct/hotspot compatibility.
- MalekPlay should remain an independent protocol and implementation. Do not copy Zapya APK code, native libraries, proprietary packet layouts, or branded artwork. Reproduce the user-facing interaction patterns using MalekPlay identity.

## 5. Prioritized parity work

### P0 — connection correctness
- Build a single lifecycle-managed connection coordinator for WLAN, Wi-Fi Direct and hotspot candidates.
- Tie candidate identity, actual Android Network binding, control session and data session to the same peer identity.
- Add dynamic endpoint/port negotiation (or a documented collision-safe fixed-port policy).
- Add integration tests for same-WLAN, hotspot, Wi-Fi Direct group-owner/client roles, multiple interfaces, stale candidates, rejected pairing and route changes.

### P1 — sharing scope and multi-device behavior
- Extend file selection/validation safely beyond media MIME types; support documents, APKs and folders with explicit path traversal, symlink, size and storage safeguards.
- Implement sender-side multi-recipient selection and independent per-recipient transfer jobs.
- Render all active peers and per-device/per-file progress, speed, errors, retry and cancellation.
- Verify duplicate names, duplicate content, storage-full and permission-revoked cases.

### P2 — resilience and measurable performance
- Fault-inject disconnects at handshake, mid-frame, end-of-file and final receipt publication.
- Test same-content resume, changed-source rejection, receiver process death and sender process death.
- Benchmark 64/128/256/512 KiB and 1 MiB chunk sizes on the same devices/network before tuning.
- Measure throughput, CPU, memory, battery, radio contention and checksum correctness.

### P3 — UI fidelity
- Match the supplied Zapya screenshots' information hierarchy, radar composition, group card, QR prominence, send/receive affordances and device states while keeping MalekPlay branding.
- Ensure every visible action is wired to a real state transition and has loading/empty/error/success feedback.

## 6. Validation status at audit time

The GitHub Actions run for commit `8ed1b0dd2c55e5c24b8719475f41d0057effd4c7` was still in progress when inspected: desktop companion protocol tests had succeeded; Android build and Android unit tests/lint had not yet produced final results. No APK from that run was available at audit time.

## 7. Acceptance criteria before claiming Zapya-like readiness

- Two physical Android devices discover each other without manual IP entry on the supported route.
- Both WLAN and Wi-Fi Direct/hotspot routes are exercised and the selected route is visible/debuggable.
- Pairing approval is authenticated and cannot be replayed or confused across peers.
- Multiple file types and folders transfer with independent per-device/per-file progress.
- SHA-256 matches on both sides; no partial file is published as complete.
- Disconnect/reconnect/resume works under tested failure points, including screen off and process restart.
- All active sockets, receivers, hotspot reservations, wake locks and service jobs are released on cancellation/stop.
- CI build, JVM tests, lint and Android instrumentation tests pass on the exact commit being delivered.
