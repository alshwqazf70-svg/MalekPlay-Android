# MalekPlay Ultra Share — implementation notes

## Current first-stage implementation

- Android-to-Android transfer over the same private IPv4 LAN.
- TCP transfer port: `38741`.
- UDP discovery port: `38742`.
- Nearby discovery uses broadcast probes and replies only; it does not scan address ranges or ports.
- Discovery announcements contain only the MalekPlay service name and transfer port. Pairing secrets are never broadcast.
- A receiver creates a random 256-bit, one-session pairing secret.
- Pairing uses a fresh server challenge and HMAC-SHA256 proof. A session key is derived independently for each traffic direction.
- Metadata and file chunks are encrypted/authenticated with AES-GCM.
- Data is streamed in 256 KiB chunks without media recompression.
- The sender calculates SHA-256 before transfer. The receiver verifies the completed file before exposing it as received.
- Interrupted transfers keep a partial file and a sidecar identity marker. A later send of the same filename, size and digest can resume at the existing byte offset.
- The receiver rejects non-private IPv4 peers and the sender rejects non-private IPv4 destinations.
- Files are staged under the app-specific Downloads directory and can be exported through Android's Storage Access Framework.
- While a transfer is active, the service holds Wi-Fi and partial CPU wake locks to reduce interruptions when the display turns off; both locks are released on completion, cancellation, or service destruction. This increases battery use during transfers.
- A data-sync foreground service owns the send/receive coroutine and socket work. The ViewModel only submits commands and observes a process-local StateFlow, so removing the Ultra Share destination no longer cancels an active transfer while Android keeps the app process/service alive. The ongoing notification shows progress and provides a stop action.
- Pairing can be transferred through the generated QR payload, QR image import, or a copied pairing URI.

## Current limitations — do not advertise these as completed

- One file is transferred per receiver session.
- Both devices must be on a reachable local network; access-point client isolation can prevent communication.
- Process death, force-stop, reboot, or Android's foreground-service execution limits can still interrupt a transfer. Partial files and their identity marker are retained for a later manual resume; automatic service/job recovery is not implemented.
- Live camera QR scanning is not part of this stage; the app can import a QR image or pairing URI.
- Wi-Fi Direct, automatic hotspot creation, internet relay, desktop/browser receiver, and multi-file queues are not implemented.
- No speed benchmark has been run. Throughput depends on both radios, Wi-Fi generation, channel congestion, distance, device storage and CPU.

## Next engineering stages

1. Add a persistent multi-file queue with per-file progress, pause/cancel controls and resumable state.
2. Complete static review and then obtain approval for the first build/device test.
3. Verify discovery, pairing, encryption, background continuation, large-file transfer, interruption and resume on two real Android devices.
4. Add a direct Wi-Fi transport strategy (Wi-Fi Direct where supported, hotspot fallback where permitted).
5. Add optional desktop/browser receiving and cross-platform interoperability.
6. Benchmark against controlled LAN conditions before making any speed comparisons.

## Build policy

Do not run Gradle, CI builds, emulators or device tests during feature development until the user explicitly approves the final build stage. Keep development commits marked with `[skip ci]`.
