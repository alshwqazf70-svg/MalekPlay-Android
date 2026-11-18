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

## Still required before calling the engine ready

- Run the Android Gradle unit-test suite, lint, and debug/release builds on this exact branch head.
- Run the Python desktop V2 test suite; verify Android/Python frame and manifest interoperability in a real socket session, not only shared wire vectors.
- Add or run a true Kotlin sender-to-Kotlin receiver batch integration test that exercises manifest, encryption, resume offsets, all file frames, SHA-256 verification, publication, and final per-file results.
- Test two physical Android phones on ordinary LAN, hotspot, and Wi-Fi Direct, including route changes and interruption/resume.
- Benchmark sustained throughput, frame-size alternatives, CPU use, memory, battery drain, and device temperature on representative low/mid/high-end devices.
- Expand/validate signatures for media formats not covered by the current signature table before claiming every advertised extension is supported.

## Verification caveat

The source changes above were committed, but no successful CI status or workflow run was returned for the current branch head during this work. No physical-device transfer, thermal measurement, or throughput benchmark has been performed here. Do not mark the engine production-ready until the remaining checks pass.
