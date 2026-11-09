# MalekPlay Ultra Share — implementation notes

## Implemented Android transfer

- Android-to-Android file transfer over a reachable private IPv4 LAN.
- TCP transfer port: `38741`; UDP discovery port: `38742`.
- LAN discovery uses bounded broadcast probes and replies. It does not scan address ranges or ports, and discovery packets never contain pairing secrets.
- Pairing uses a short 12-digit PIN, a fresh challenge, PBKDF2-HMAC-SHA256 (210,000 iterations), and HMAC-SHA256 challenge-response. Legacy 64-character hexadecimal secrets remain accepted for compatibility.
- Session traffic uses direction-specific keys and AES-256-GCM authenticated frames.
- Files stream in 256 KiB chunks by default; the sender exposes a bounded 64–256 KiB tuning hook for controlled benchmarks without exceeding the v1 receiver frame limit. SHA-256 is checked before a completed file is exposed to the user. Hashing loops handle zero-length reads safely.
- Transfer identity includes sanitized filename, size, and content digest. Partial files resume from the saved offset only when the sidecar identity matches; the completed file is SHA-256 verified before it is exposed. A verified receipt prevents duplicate copies when the final acknowledgement was lost. A full-sized partial is re-hashed before the receiver accepts a zero-byte resume.
- The receiver sanitizes incoming filenames, preserves existing destination files, and accepts only private/link-local IPv4 peers.
- A foreground data-sync service owns send/receive sockets and coroutines independently of the navigation/ViewModel lifecycle. The ongoing notification exposes cancellation; Wi-Fi and CPU wake locks are acquired before source hashing/connection attempts and released after the active transfer. The service shows a preparation status during the initial SHA-256 pass. Receiver partial-file data is synced to storage when each receive attempt closes, including interrupted attempts, so its saved resume length is durable without syncing every chunk.
- A persistent DataStore queue supports multiple selected files, per-file status, retry of failed items, skip, pause, and manual resume. Interrupted sending rows are recovered as pending when the UI starts.
- Queue decoding tolerates malformed individual rows without discarding other valid queue entries.
- Pairing is available through a QR payload, live camera scanning via Google Play services Code Scanner, QR image import, and clipboard pairing URI.
- Received files are recorded in local history and can be exported through Android's Storage Access Framework.
- Wi-Fi Direct peer discovery and connection are implemented as an optional direct-network path. The receiver should be the group owner; the sender connects as a client and uses the reported group-owner IPv4 address.
- A desktop companion in `desktop/` supports Windows, Linux, and macOS. It can send to Android and receive from Android over the same local-network protocol. Its receiver also flushes and fsyncs partial data when a transfer attempt ends or is interrupted, matching Android's durable-resume behavior. A user-enabled phone hotspot can provide the local network when no router is available.

## Current limitations — do not advertise these as completed

- Wi-Fi Direct behavior varies by Android vendor and device. It requires real-device testing; the helper does not guarantee that every handset permits peer discovery or group formation.
- The app does not silently enable or configure the phone hotspot. The user enables the hotspot in Android settings and connects the computer/peer to it.
- No internet relay, cloud transfer, remote-network traversal, or browser-based receiver is implemented.
- Foreground service execution is still subject to Android and vendor limits. Android 15+ can stop a data-sync foreground service after its allowed execution window. Process death, force-stop, reboot, or revoked document-provider permissions can interrupt a transfer; partial data is retained where possible and the user can manually resume after reopening the app.
- Persistable document permissions are requested for files selected through the document picker. Files shared from other apps may provide temporary-only grants; those files may need to be selected again after process death.
- A transfer has not yet been validated across the full device matrix. Required real-device checks include Android-to-Android, Android-to-desktop in both directions, Wi-Fi Direct, hotspot fallback, screen-off transfer, large files, interruption/resume, duplicate receipt, and low-storage behavior.
- No controlled throughput benchmark has been completed. Do not claim a speed advantage until repeatable measurements are collected.

## Remaining engineering and verification stages

1. Completed the defensive transfer pass: malformed queue rows no longer discard valid history, unreadable resume markers are rebuilt safely, failed LAN discovery leaves manual-IP receiving available, partial receive data is synced on interrupted attempts, and zero-returning streams cannot stall digest calculation. Continue reviewing cancellation and stale-file edge cases during final verification.
2. Review the Android and desktop protocol implementations together and keep cross-platform test vectors in sync.
3. Run unit tests, Android lint, desktop protocol tests, and debug/release builds after feature development is frozen.
4. Test on at least two physical Android devices and one desktop, including Android 15+ background limits and vendor-specific Wi-Fi Direct behavior.
5. Fix any failures found in the final build/device pass before publishing artifacts.

## Build policy

Feature commits made before the agreed final build stage should use `[skip ci]` so development does not trigger repeated builds. Do not start the final Gradle/CI build, emulator suite, or device-validation pass until the feature set is frozen and the user approves the final build stage.
