# Ultra Share V2 hardening status

This note records source-level hardening on `feature/zapya-engineering-analysis`.
It is not a device-test or release-readiness certificate.

## Implemented in source

- V2 manifests now reject batches whose aggregate declared size exceeds 20 GiB. The same limit is enforced by the Kotlin and Python manifest validators.
- Android and desktop receivers inspect the received file's leading container bytes after the SHA-256 check and before publishing it. The extension and declared MIME family must agree; generic provider MIME values are accepted only for a recognized media extension. Unknown signatures fail closed.
- The receiver checks the remaining bytes required by the batch against available storage and preserves a 64 MiB reserve before acknowledging resume offsets.
- The desktop V2 frame size is aligned to Android's 512 KiB frame limit.
- Regression tests were added for aggregate-size rejection and MP4 signature validation, including a renamed executable and a MIME-family mismatch.

## New automated integration coverage added

- A Robolectric/JVM integration test now starts the Kotlin MPC2 approval server and Kotlin V2 receiver, then transfers a multi-file batch from the Kotlin sender over real loopback/LAN TCP sockets. It verifies the final per-file statuses, published bytes, and SHA-256 digests.
- A gated Kotlin-to-Python integration test starts the actual desktop V2 receiver process, establishes an approved MPC2 session, sends media through the Android Kotlin sender, and verifies the Python receiver's published file.
- A gated Python-to-Kotlin integration test starts the actual Python V2 sender process, establishes an approved MPC2 session against the Kotlin control server, sends media to the Kotlin receiver, and verifies the received bytes.
- The Android GitHub Actions JVM test job now installs the desktop Python dependency and enables both cross-language socket tests with `ULTRASHARE_CROSS_LANG=1`. They are skipped during ordinary local unit-test runs unless explicitly enabled.

These are source-level test additions only until the corresponding GitHub Actions run completes successfully. They do not prove Wi-Fi Direct or hotspot routing on physical phones.

## Temporary connection behavior (latest source update)

- The Android app now keeps API 23 (Android 6) as its minimum and targets/compiles against stable Android 16 (API 36). The emulator matrix is configured for API 23, 29, and 36. Android 17 remains a beta platform at the time of this update, so it is not treated as the stable compatibility baseline.

- Approved peer credentials and sender targets now exist only while the live MPC2 room session is active.
- Disconnecting a peer removes its active control sockets and in-memory sender target; no reconnectable peer or secret is retained for a later session.
- Stopping the receive room now closes both inbound and outbound approved peer sessions, rather than only peers shown in the inbound list.
- Route recovery uses the currently active session identity only. A disconnected device must join the room again to establish a fresh session.

This removes persistent/reconnectable pairing behavior from the service state. It does not remove the short-lived room admission code used to join the current room, and it does not prove that every Wi-Fi Direct/Hotspot combination works on physical devices.

## Still required before calling the engine ready

- Run the Android Gradle unit-test suite, lint, and debug/release builds on this exact branch head.
- Run the Python desktop V2 test suite; verify Android/Python frame and manifest interoperability in a real socket session, not only shared wire vectors.
- Add or run a true Kotlin sender-to-Kotlin receiver batch integration test that exercises manifest, encryption, resume offsets, all file frames, SHA-256 verification, publication, and final per-file results.
- Test two physical Android phones on ordinary LAN, hotspot, and Wi-Fi Direct, including route changes and interruption/resume.
- Benchmark sustained throughput, frame-size alternatives, CPU use, memory, battery drain, and device temperature on representative low/mid/high-end devices.
- Expand/validate signatures for media formats not covered by the current signature table before claiming every advertised extension is supported.

## Verification caveat

The source changes above were committed, but no successful CI status or workflow run was returned for the current branch head during this work. No physical-device transfer, thermal measurement, or throughput benchmark has been performed here. Do not mark the engine production-ready until the remaining checks pass.

## Full-duplex group transfer update

- Each authenticated QR join now starts the joining phone's own temporary receive listeners; the room creator and joiners therefore expose both a sender and a receiver while their session is active.
- The control listener accepts up to four concurrent peer sessions. The V2 data listener accepts up to four concurrent incoming peer sockets, and the multi-recipient sender fans out to up to four peers concurrently.
- Sending to all approved room members and receiving from several members are independent operations. A local outgoing queue does not disable the inbound V2 listener.
- Receive-session serialization is now scoped by both peer identity and destination directory. Independent room endpoints sharing a peer identity no longer block each other's resume-state locks.
- Added a Kotlin TCP integration test that starts two independent V2 receive endpoints and starts both outbound batches together, then verifies both completions and byte-for-byte payloads. This is a JVM/Robolectric socket test, not a test on two physical phones.
- Group topology remains hub-and-spoke: each joining phone has a bidirectional session with the room host. Direct peer-to-peer links between two joining phones are not automatically created; the host can send a selected batch to all connected members.
- CI must pass the new JVM test, lint, APK/AAB builds, and API 23/29/36 runtime test jobs before this change is called verified.
