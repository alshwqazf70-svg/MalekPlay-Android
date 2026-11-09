# Zapya-to-MalekPlay Comparative Audit — Connection, Transfer, and UI

Date: 2026-11-09
Reference: user-provided Zapya APK analysis artifact (APK package `com.dewmobile.kuaiya.play`, archive timestamps 2021-04-09).
Target: `feature/zapya-engineering-analysis` in MalekPlay-Android.

## Scope and evidence quality

The private Zapya analysis artifact was downloaded and inspected. It contains JADX-recovered Java sources, APKTool-decoded resources/layout XML, native-library inventory, and phase reports. The decompilation recovered 10,832 Java files but reported 33 decompilation errors. Native code was inspected through exported symbols and ABI-specific disassembly; this is static analysis, not a live two-device packet capture. The APK is an older 2021 build and may not represent current Zapya releases.

This document records architecture and UI behavior patterns only. It does not authorize copying Zapya's Java source, native libraries, protocol bytes, images, icons, fonts, branding, or other proprietary resources into MalekPlay.

## 1. Zapya connection and discovery model

### 1.1 Discovery
- Nearby discovery/advertising is delegated through Java JNI wrappers into Huawei NSTACKX native discovery.
- The observed Java scan setup initializes discovery, registers local device metadata, starts scanning, and forwards endpoint callbacks to the Java control layer.
- The exact over-the-air discovery packet format was not fully reconstructed.
- The APK also contains BLE/Wi-Fi scanner code; this does not prove that every transfer uses Bluetooth.

### 1.2 Control plane before data plane
The recovered Nearby manager uses a distinct request/response control exchange before opening a bulk-data session:
1. Requester asks for a data endpoint and identifies a transfer mode.
2. Receiver checks that a share key exists, collects usable endpoint candidates, and starts the matching server on an OS-assigned port.
3. Receiver returns one or more endpoint candidates and the selected port.
4. Requester selects a candidate and creates the corresponding client.
5. Connection callbacks indicate readiness; payload submission follows.

Observed transfer-mode distinctions:
- bytes/message payload;
- file transfer;
- stream transfer.

Candidate types include WLAN and P2P, with an address and port. The observed policy prefers P2P when it is connected and preferred; otherwise WLAN is used. This is an architecture pattern, not a reason to copy Zapya's TLV IDs, 19-byte candidate encoding, or proprietary control format.

### 1.3 Hotspot and Wi-Fi Direct
- A separate Android LocalOnlyHotspot service manages a hotspot reservation and its SSID/credential lifecycle.
- NSTACKX transfer management separately represents WLAN and P2P candidates.
- Therefore hotspot, WLAN, and Wi-Fi Direct should be modeled as connection candidates with explicit lifecycle and fallback, not treated as one identical connection mechanism.

## 2. Zapya file sending and receiving

### 2.1 Session separation
- Native file and byte/message sessions are separate.
- File mode uses native file server/client APIs and a storage path on the receiver.
- Byte mode uses a message server/client.
- Stream mode has a separate Java socket pipeline.
- Transient native session IDs and transfer IDs are mapped to higher-level task IDs.

### 2.2 Queue and scheduling
- The traced Java scheduler has independent queues for file, stream, and byte/message work.
- The file queue polls one item while a guard indicates an active file task.
- The traced Java call site submits one file per native call, even though the native file API accepts an array/list of files.
- The scheduler therefore behaves as one active Java file task at that call site. Native internal parallelism was not established.
- Do not infer concurrent multi-file transfer from the native array API alone.

### 2.3 Reliability and performance
High-confidence static call-site evidence:
- File-frame creation calls AES-GCM encryption; receive-side block writing calls AES-GCM decryption.
- The DFile handler includes file-specific ACK decoding, retransmission-list handling, transfer-completion ACKs, ACK-interval adjustment, and send-rate adaptation.
- The native engine includes heartbeat functions and retry bookkeeping.
- KCP symbols and call sites are present in the DMsg/message path. The traced `SendDataBlock` path conditionally uses KCP for one connection mode and SocketSend in another.
- The traced file-frame and file-ACK paths are separate from the observed KCP call sites. This does not prove that no untraced path can ever use KCP.
- Static evidence does not establish exact encryption/key derivation for every channel, full retry timers, congestion algorithm, or end-user resume guarantees.

Important distinction: Zapya's native file path has application-level ACK/retransmission logic. MalekPlay currently uses TCP for file payloads, so TCP already provides ordered delivery and transport-level retransmission. We must not transplant a UDP/KCP-style per-frame retry loop onto TCP without a measured reason. A future UDP transport would require its own congestion control and selective-ACK design.

## 3. Zapya screen and interaction patterns observed

The decompiled UI source and APKTool layout XML show these screen patterns:

1. **Nearby/discovery entry:** paged discovery UI with tabs, user/device panels, and actions for scanning/adding or joining.
2. **Send/join flow:** a distinct group-join/send state with a large central circular progress/connection indicator, local user identity/avatar, status text, and retry/interrupt handling.
3. **Receive/create-group flow:** parallel group creation/receive state with a central progress indicator and lifecycle handling for hotspot/P2P setup, timeout, failure, and retry.
4. **Transfer progress screen:** a task list, empty-state view, send/receive controls, history entry, interruption handling, and a user/peer panel.
5. **Peer transfer panel:** local avatar and remote peer avatars/names, progress visualization, percent/status, and transfer-speed text; the UI switches between active progress and completion state.
6. **PC connection route:** a separate connection-help screen for PC, with instructions and a launch/link action.

The inspected 2021 layouts use a classic Android View hierarchy. MalekPlay is Jetpack Compose/Material 3; the correct port is to recreate the interaction hierarchy and states in Compose, not copy the old XML or drawables verbatim.

## 4. MalekPlay current implementation — observed in source

Already present on the development branch:
- LAN UDP discovery and advertising for MalekPlay peers.
- Wi-Fi Direct peer discovery and connection setup.
- LocalOnlyHotspot creation/stop lifecycle where Android permits it.
- QR pairing payload and camera scanner.
- A 12-digit pairing secret and challenge-response authentication.
- AES-GCM encrypted file frames and SHA-256 final file verification.
- A foreground service that owns active send/receive work independently of the screen.
- Persistent sequential file queue, retry/pause/skip actions, interrupted queue recovery, and received-file history.
- Partial-file resume metadata and completed-receipt validation.
- Desktop Python companion with multi-file sequential sending.
- Compose UI with create/receive and join/scan actions, discovered peers, QR/pairing details, hotspot details, queue, progress, and received history.

Current gaps versus the observed Zapya architecture:
1. Discovery currently advertises a simple MalekPlay LAN endpoint; it does not exchange an authenticated, versioned control-plane offer with a negotiated list of WLAN/P2P candidates.
2. LAN peer selection fills the host, but still asks the user to scan QR or enter the receiver's pairing code. It is not yet a one-tap authenticated connection.
3. Wi-Fi Direct connection establishes the group/remote host but does not yet perform the same end-to-end peer negotiation automatically.
4. Android file transfer currently opens a separate TCP transfer session per file in the queue; there is no single multi-file manifest/session on the wire.
5. The receiver is a long-lived single-file accept loop, not a multi-peer group receiver with explicit acceptance and task routing.
6. The UI shows a combined device panel and separate queue/progress cards, but it does not yet provide Zapya-style group peer avatars, per-peer acceptance state, or a dedicated unified transfer-room view.
7. There is no controlled two-device benchmark proving speed parity or superiority.

## 5. Implementation plan for MalekPlay (original implementation)

### Stage A — connection model and control plane
- Define an original, versioned control protocol for capability offers, peer identity, transport candidates, dynamic endpoint ports, acceptance/rejection, and session readiness.
- Advertise LAN and Wi-Fi Direct candidates without exposing pairing secrets in discovery packets.
- Authenticate and bind negotiation to the existing challenge-response pairing before bulk data begins.
- Select P2P only when connected and reachable; otherwise fall back to LAN/hotspot with an explicit user-visible reason.
- Keep v1 endpoint behavior intact and add v2 negotiation as opt-in until Android/desktop conformance tests pass.

### Stage B — sender/receiver task engine
- Introduce a bounded batch manifest and per-file results within one authenticated session.
- Keep one active file initially; benchmark bounded parallelism later rather than assuming more parallel files are faster.
- Keep persistent job IDs separate from socket/session IDs.
- Add receiver-side accept/reject, safe storage policy, cancellation, timeout, and cleanup states.
- Preserve per-file SHA-256 verification and safe resume. Do not mark bytes as remotely confirmed until the receiver reports a durable checkpoint/result.

### Stage C — Zapya-like UX, built in Compose
- Add a dedicated connection room with two clear modes: Send and Receive.
- Show nearby devices as peer cards with device name, transport type, connection status, and explicit Connect/Accept controls.
- Show a central connection/progress illustration with sender and receiver identities, progress, speed, bytes, ETA (only when reliable), and current file.
- Show batch task list and per-file states: waiting, connecting, sending, verifying, completed, retrying, failed, cancelled.
- Add a receive waiting screen with QR/pairing code, local network candidates, hotspot/P2P state, and a clear stop action.
- Add a PC companion route with connection instructions and pairing flow.
- Use MalekPlay's own visual system, icons, colors, and animations; do not reuse Zapya artwork or branding.

### Stage D — validation
- Kotlin/Python shared golden vectors for negotiation, framing, authentication, and batch manifest.
- Android-to-Android and Android-to-desktop tests on two physical devices.
- Test same Wi-Fi, hotspot, Wi-Fi Direct, screen-off, navigation away, process death, disconnect, bad PIN, duplicate files, and storage-full cases.
- Benchmark small, large, and mixed batches with the same devices/network; record median throughput, hashing overhead, CPU, heat, battery, and resume correctness.
- Enable v2 by default only after compatibility and security tests pass.

## 6. Scope of this audit commit

This audit adds the evidence map and staged implementation plan to the MalekPlay repository. It does not copy Zapya source/binaries/assets and does not claim that the control-plane v2, group receiver, or new Compose room is implemented yet.

## References within the Zapya analysis workspace

- `reports/PHASE-1-STATIC-ENGINEERING-FINDINGS.md`
- `reports/PHASE-2-CONTROL-AND-TRANSFER-FLOW.md`
- `reports/PHASE-3-DEEP-CONTROL-NATIVE-RELIABILITY.md`
- `reports/PHASE-4-APP-TASK-MODEL-AND-UNKNOWNS.md`
- `reports/PHASE-5-JAVA-SCHEDULER-CALLCHAIN.md`
- `reports/PHASE-6-NATIVE-FILE-VS-KCP-CALLCHAIN.md`
