# UltraShare Master Roadmap & Cross-Chat Handoff

Last updated: 2026-11-09
Repository: https://github.com/alshwqazf70-svg/MalekPlay-Android
Active development branch: `feature/zapya-engineering-analysis`
Production branch: `main` (do not modify or merge until the user explicitly approves release)

## Product goal

Build an original MalekPlay media-sharing experience with the ease and visual flow users associate with Zapya:
- A simple landing screen with two primary actions: Send media and Receive media.
- A nearby-device room with discovered devices, authenticated connected peers, clear status, and explicit connect/accept/reject.
- Select and transfer media only: videos, music/audio, and images/photos.
- Keep the experience phone-first, Arabic-friendly, and visually consistent with MalekPlay.
- Secure, reliable local transfer over WLAN / Wi-Fi Direct / receiver hotspot where supported, with a safe fallback.
- Do not copy Zapya binaries, source code, logos, or proprietary assets. Recreate interaction patterns with original MalekPlay UI.

## Non-negotiable scope boundary

Do not modify or refactor unrelated MalekPlay features. In particular, preserve:
- Video and music library screens, scanning, database/Room entities and DAOs.
- Player, playback, playlists, favourites, history, settings, and existing navigation outside the UltraShare entry/route.
- Existing media storage and library behavior.

Changes to media permission/startup code are out of scope unless the user explicitly authorizes them or a proven UltraShare integration defect requires a narrowly scoped change. Never clear, rebuild, migrate, or rewrite the library database as part of UltraShare UI work.

UltraShare may read media through existing Android content URIs / MediaRepository integration. It must not add generic APK/app/document sharing. Filter picked and incoming shared content to supported video/audio/image MIME types.

## Current implementation already present (source inspection)

### Transport and security
- LAN UDP discovery with versioned V2 offers and a V1 compatibility response.
- Wi-Fi Direct discovery/connection setup and LocalOnlyHotspot lifecycle.
- QR pairing payload and 12-digit pairing secret.
- Authenticated request/accept/reject control channel, challenge-response authentication, AES-GCM protected decisions, and persistent authenticated control sessions with heartbeat.
- Approved-peer registry and explicit disconnect handling.
- V2 batch manifest protocol with per-file results.
- V2 batch transport: one TCP data connection carries the manifest and all files in that batch; encrypted frames and integrity validation.
- SHA-256 verification, partial transfer state/resume, completed receipts, durable queue, retry/pause/skip, and receive history.
- Foreground service owns transfer work beyond the Compose screen lifecycle.
- Desktop companion protocol tests exist.

### UI
- UltraShare screen has Send/Receive actions, nearby scan, discovered peers, active peer cards, incoming approval card, QR scanning, manual pairing fallback, media selection for video/audio/image, batch queue, progress, and received history.
- The UI is a work-in-progress; it is not yet a finished Zapya-like dedicated transfer-room experience.
- Recent UX improvement: selecting a discovered peer now opens QR scanning when no pairing secret is present; successful QR scan immediately sends the authenticated connection request.
- The same QR-first peer-selection behavior was unified in the lower send panel.

## Latest commits in active branch

- `df5a840131cd9733caf447760d3ee950217f3966` — QR-first nearby peer pairing; auto-request after QR scan.
- `e6089241dccd0dd41844c1055e7d4142da567d7f` — unify nearby peer connect action across UltraShare room.
- The latest UI commits modify only `app/src/main/java/com/malekplay/ui/network/UltraShareScreen.kt`.
- Keep all future UltraShare implementation and handoff documentation on this branch. Do not cherry-pick to `main` automatically.

## CI / validation status at handoff

- Workflow run `42646297415` was still in progress when last checked. Desktop companion protocol tests had passed; Android unit tests/Lint and APK jobs had not yet completed. No artifact was available at that check.
- Workflow run `42646763050` was queued/pending for commit `e6089241dccd0dd41844c1055e7d4142da567d7f`.
- Do not claim a successful build or provide an APK until the run for the exact latest commit succeeds and its artifact is verified.
- CI does not replace two-Android-device testing. Verify send/receive on actual devices before calling the feature complete.

## Remaining work — execute in this order

### 1. Stabilize and verify current connection flow
- Wait for the latest branch CI run; inspect failing job logs and fix only scoped UltraShare defects.
- Verify QR scan → authenticated request → receiver approval → active peer on both ends → batch send.
- Verify rejection, timeout, disconnect, wrong secret, repeated request, and cancellation states.
- Make sure QR scanning does not start a transfer without explicit receiver approval.

### 2. Dedicated Zapya-inspired room UX
- Reduce duplicated connection controls and legacy manual-entry emphasis.
- Make Send and Receive the clear primary choices.
- Show discovered peers separately from authenticated connected peers.
- Use a clear peer card: avatar/initial, device name, transport, state, connect/accept/reject/disconnect action.
- Add a focused transfer room showing sender/receiver, selected media count, total size, per-file state, aggregate progress, speed/ETA only when reliable, and completion summary.
- Add an obvious waiting-for-acceptance state on the sender and incoming-request state on the receiver.
- Preserve Arabic RTL behavior and MalekPlay styling; do not copy Zapya branding/assets.

### 3. Media-only selection and receiving
- Keep the picker limited to video/audio/image MIME types.
- Check every inbound/outbound content URI and MIME type at the transfer boundary; reject APKs and generic documents.
- Support multi-select and clear selected-media summaries (count, total size, thumbnails where safely available).
- Keep the current library scanner, media database and player untouched.
- After a successful receive, publish supported media through the existing received-media mechanism and refresh the existing repository only where already integrated.

### 4. Connection negotiation and transport fallback
- Prefer a reachable authenticated WLAN candidate; use Wi-Fi Direct or receiver hotspot only when the path is actually established and reachable.
- Never assume Wi-Fi Direct connection success solely because group formation succeeded.
- Keep V1 fallback for compatibility; do not expose pairing secrets in discovery broadcasts.
- Keep control-plane and data-plane roles separate.
- Do not add UDP/KCP reliability behavior to TCP file transfer without measured evidence.

### 5. Reliability, performance and security validation
- Test Android-to-Android on same Wi-Fi, receiver hotspot, Wi-Fi Direct, screen off, app background, process/service teardown, network switch, low storage, large files, duplicate names, corrupt frames, wrong secret, rejection, timeout, and interrupted resume.
- Verify partial resume and completed receipts against SHA-256 before trusting them.
- Benchmark realistic large video and many-small-file batches; report measured throughput, CPU/heat/battery where possible.
- Do not claim superiority over Zapya without controlled comparable measurements.

## Zapya analysis evidence and limits

The repository contains reverse-engineering reports and extracted source observations for an older Zapya APK (approximately 2021). The analysis indicates a separation between control and data paths, authenticated/encrypted file framing, ACK/retransmission handling in the traced file path, and KCP appearing in a message/DMsg path. Do not generalize KCP to the file payload path. The traced Java scheduling callsite submits one file at a time; do not claim proven concurrent multi-file sending. Exact native key derivation, congestion behavior, and some protocol internals remain unconfirmed.

Primary evidence documents:
- `docs/ZAPYA-COMPARATIVE-CONNECTION-TRANSFER-UI-AUDIT.md`
- `docs/ULTRASHARE-PROTOCOL-V2-DESIGN.md`
- `docs/ULTRASHARE-V2-IMPLEMENTATION-CHECKPOINT.md`
- `docs/ULTRASHARE-ZAPYA-PHASE6-ENGINEERING-NOTES.md`

## Instructions for the next ChatGPT conversation

1. Read this file first.
2. Work only in `feature/zapya-engineering-analysis`.
3. Fetch the latest branch head before editing; do not assume the commit hashes above are still the head.
4. Inspect the complete current source file and relevant call chain before making changes.
5. Keep each commit scoped to UltraShare files and this documentation. Avoid broad refactors.
6. Update this handoff after meaningful implementation or validation, including exact commit SHA, workflow run ID/status, artifact link/name if verified, and outstanding device tests.
7. Clearly distinguish implemented, CI-passed, artifact-built, and physically device-tested states.
8. Never claim the user has a tested final APK unless the exact latest commit's build artifact exists and is verified.
