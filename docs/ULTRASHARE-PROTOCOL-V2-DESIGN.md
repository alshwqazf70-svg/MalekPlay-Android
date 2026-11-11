# Ultra Share Protocol v2 — Engineering Design

Status (updated 2026-11-11): Android Kotlin and desktop Python V2 batch data planes are implemented in source. MPC2 is a separate authenticated approval/heartbeat control channel. V1 remains available for compatibility. Desktop CI passed for commit bba536a98f1f0dddbf430e88065d43d48b2386e1; Android build and physical-device interoperability must be verified separately.
Target branch: feature/zapya-engineering-analysis

## Goals

1. Preserve interoperability with existing v1 Android and desktop clients.
2. Negotiate capabilities before selecting any v2-only behavior.
3. Transfer multiple files over one authenticated TCP session.
4. Keep per-file SHA-256 verification, durable resume, and collision-safe output names.
5. Improve throughput without adding redundant application-level retransmission on top of TCP.
6. Keep the desktop Python implementation and Android Kotlin implementation byte-for-byte compatible.

## Transport decision: TCP reliability

Ultra Share currently uses TCP. TCP already provides ordered delivery, congestion control, retransmission, and duplicate suppression. A per-chunk application ACK/retry loop would duplicate those mechanisms and can reduce throughput through extra round trips and head-of-line blocking.

Therefore v2 MUST NOT add stop-and-wait ACKs or retransmit individual TCP chunks. The application protocol should acknowledge:
- successful authentication and negotiation;
- the receiver's durable resume offset for each file;
- final per-file verification and publication;
- completion or failure of the whole batch.

If a future transport uses UDP, it must be a separate transport profile with its own congestion control, selective ACK, bounded in-flight window, packet-loss recovery, and security review. Do not reuse a TCP design as a UDP reliability layer.

## Control request/approval channel (implemented separately)\n\nThe receiver listens on TCP port 38744 while receiving is active. A sender connects using the pairing PIN shared out-of-band in the QR payload, receives a fresh 32-byte challenge, and authenticates request metadata with HMAC-SHA256 using PBKDF2-derived key material. The receiver presents the authenticated device name/address and waits for an explicit user decision. The decision is encrypted with AES-GCM using a domain-separated key and fresh nonce. After ACCEPT, the socket remains open for authenticated PING/PONG heartbeat and explicit close. The active control session gates the separate V2 batch data socket (default TCP 38742) as well as the legacy V1 transfer path (TCP 38741).\n\n## Compatibility and negotiation

- Keep the v1 magic and v1 frame layout unchanged.
- Introduce a distinct v2 protocol version in the authenticated metadata/handshake path; never reinterpret a v1 frame.
- A sender first offers supported versions and capabilities. The receiver selects the highest mutually supported version.
- If the peer only supports v1, fall back to the existing one-file v1 transfer without changing its framing.
- Unknown capability bits and optional fields must be ignored only when the enclosing version explicitly permits it; unknown mandatory fields must fail closed.
- The negotiated version, limits, and capabilities must be authenticated as part of the session transcript to prevent downgrade or tampering.
- Android and desktop must ship the same protocol vectors for every field, key derivation label, nonce, and encrypted frame.

## Proposed v2 session

1. Establish TCP connection and perform the existing private-LAN endpoint validation.
2. Perform challenge-response pairing authentication.
3. Negotiate protocol version, maximum frame payload, maximum batch count, and optional capabilities.
4. Derive directional session keys using domain-separated labels that include the selected protocol version and session transcript hash.
5. Send a batch manifest containing a bounded file count and each file's sanitized name, MIME type, exact size, and SHA-256 digest.
6. Receiver validates the complete manifest and replies with one resume decision per file. Each resume offset must be within the declared size and must refer to a matching transfer identity.
7. Sender streams file data in order over the same TCP connection. File boundaries and frame sequence numbers are explicit and authenticated.
8. Receiver writes to private partial files, syncs at safe checkpoints, and verifies final size and SHA-256 before atomically publishing each completed file.
9. Receiver returns a final result for every file and a batch summary. A failed file must not invalidate already verified files.

## Bounded resource limits

The implementation must define and test hard limits before accepting a batch:
- maximum files per batch;
- maximum UTF-8 filename and MIME lengths;
- maximum manifest byte length;
- maximum frame payload;
- maximum aggregate declared batch size;
- maximum number of simultaneously open file handles (prefer one active file);
- maximum resume metadata size.

Reject invalid lengths before allocation. Do not trust values from the peer, even after pairing authentication.

## Throughput strategy

- Retain buffered socket streams and batched writes; do not flush after every data frame.
- Flush at protocol boundaries: negotiation response, resume decisions, end-of-file, and final batch result.
- Keep frame size negotiated and capped by both peers. Start with conservative values and benchmark larger sizes on real devices; do not assume the largest frame is always fastest.
- Avoid a second full-file hashing pass where possible. A sender may hash during the initial source scan and must detect source mutation before declaring success; the receiver's final digest remains authoritative.
- Report throughput using monotonic time and bytes transferred during the current attempt, excluding resume bytes from the rate numerator.
- Benchmark small files, large files, mixed batches, slow storage, hotspot, and shared Wi-Fi separately.

## Resume identity and durability

- Transfer identity must include sanitized filename, exact size, and content digest (or an equally strong content identity).
- Never resume solely by filename and size.
- Persist resume metadata atomically.
- Sync partial file data at bounded checkpoints and before advertising a durable resume offset. Avoid fsync per frame.
- On reconnect, the receiver revalidates metadata and partial-file length before returning an offset.
- A completed receipt is valid only after checking the published file's size and digest.
- Never overwrite an existing destination file silently.

## Required cross-platform conformance tests

Create shared golden vectors consumed by Kotlin/JUnit and Python/unittest:
- handshake and challenge-response;
- negotiation success, v1 fallback, unsupported mandatory capability, and downgrade rejection;
- key derivation and directional nonce construction;
- manifest encoding/decoding and all size/count boundaries;
- resume offset validation and transfer identity;
- frame ordering, authentication failure, truncated frame, oversized frame, and sequence mismatch;
- partial disconnect and resume;
- per-file digest mismatch and mixed batch success/failure;
- filename traversal, control characters, Unicode, and collision handling.

## Rollout plan

1. Keep v1 unchanged and add shared golden vectors first.
2. Implement v2 parsing/negotiation behind an explicit capability flag on both clients.
3. Implement one-file v2 transfer and cross-platform conformance tests.
4. Add multi-file manifest and per-file results.
5. Add benchmark instrumentation and compare v1/v2 on actual Android devices and desktop.
6. Enable v2 by default only after Android-to-Android, Android-to-desktop, and desktop-to-desktop tests pass. Retain v1 fallback.

## Non-goals

- Compatibility with the proprietary Zapya wire protocol is not implied.
- Do not claim higher speed than Zapya without repeatable, same-network device benchmarks.
- Do not add custom TCP chunk retransmission or claim KCP is used by the current file payload path.
- Do not change the existing v1 wire format as part of v2 work.
