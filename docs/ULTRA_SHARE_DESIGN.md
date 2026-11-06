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
- A data-sync foreground service displays an ongoing notification while a send or receive session is active, allowing the user to briefly switch apps.
- Pairing can be transferred through the generated QR payload, QR image import, or a copied pairing URI.

## Current limitations — do not advertise these as completed

- One file is transferred per receiver session.
- Both devices must be on a reachable local network; access-point client isolation can prevent communication.
- The Ultra Share destination must remain in the navigation back stack. The foreground service is intended to keep a transfer alive during brief app switching, but process-death recovery and extended background behavior still require device verification.
- Live camera QR scanning is not part of this stage; the app can import a QR image or pairing URI.
- Wi-Fi Direct, automatic hotspot creation, internet relay, desktop/browser receiver, and multi-file queues are not implemented.
- No speed benchmark has been run. Throughput depends on both radios, Wi-Fi generation, channel congestion, distance, device storage and CPU.

## Next engineering stages

1. Complete static review and then obtain approval for the first build/device test.
2. Verify discovery, pairing, encryption, large-file transfer, interruption and resume on two real Android devices.
3. Add a foreground transfer service, notifications, persistent queue and multi-file batches.
4. Add a direct Wi-Fi transport strategy (Wi-Fi Direct where supported, hotspot fallback where permitted).
5. Add optional desktop/browser receiving and cross-platform interoperability.
6. Benchmark against controlled LAN conditions before making any speed comparisons.

## Build policy

Do not run Gradle, CI builds, emulators or device tests during feature development until the user explicitly approves the final build stage. Keep development commits marked with `[skip ci]`.
