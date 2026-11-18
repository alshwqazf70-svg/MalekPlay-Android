# Ultra Share V2 hardening status

This note records source-level hardening on `feature/zapya-engineering-analysis`.
It is not a device-test or release-readiness certificate.

## Implemented in source

- V2 manifests now reject batches whose aggregate declared size exceeds 20 GiB. The same limit is enforced by the Kotlin and Python manifest validators.
- Android and desktop receivers inspect the received file's leading container bytes after the SHA-256 check and before publishing it. The extension and declared MIME family must agree; generic provider MIME values are accepted only for a recognized media extension. Unknown signatures fail closed.
- The receiver checks the remaining bytes required by the batch against available storage and preserves a 64 MiB reserve before acknowledging resume offsets.
- The desktop V2 frame size is aligned to Android's 512 KiB frame limit.
- Regression tests were added for aggregate-size rejection and MP4 signature validation, including a renamed executable and a MIME-family mismatch.

## Still required before calling the engine ready

- Run the Android Gradle unit-test suite, lint, and debug/release builds on this exact branch head.
- Run the Python desktop V2 test suite; verify Android/Python frame and manifest interoperability in a real socket session, not only shared wire vectors.
- Add or run a true Kotlin sender-to-Kotlin receiver batch integration test that exercises manifest, encryption, resume offsets, all file frames, SHA-256 verification, publication, and final per-file results.
- Test two physical Android phones on ordinary LAN, hotspot, and Wi-Fi Direct, including route changes and interruption/resume.
- Benchmark sustained throughput, frame-size alternatives, CPU use, memory, battery drain, and device temperature on representative low/mid/high-end devices.
- Expand/validate signatures for media formats not covered by the current signature table before claiming every advertised extension is supported.

## Verification caveat

The source changes above were committed, but no successful CI status or workflow run was returned for the current branch head during this work. No physical-device transfer, thermal measurement, or throughput benchmark has been performed here. Do not mark the engine production-ready until the remaining checks pass.
