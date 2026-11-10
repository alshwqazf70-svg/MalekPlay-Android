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
- UltraShare screen has a focused Send / Receive mode switch: Send mode contains media selection, discovery and peer room; Receive mode contains receiver setup, QR/pairing and hotspot controls. The inactive workflow is hidden to reduce duplicated controls and clutter.
- A central room-status panel now shows the active mode, connection/acceptance state, connected peer names, and transfer progress/bytes when a trustworthy progress sample exists.
- Nearby-device room now has a searchable peer list, larger original MalekPlay avatar cards, clear availability labels, and a primary connect action. Authenticated peers have distinct online cards and an explicit disconnect action.
- Removed the second duplicate peer picker from the media-send panel; the nearby-device room is the single peer-selection surface. Manual host/secret entry is hidden under advanced pairing options while QR-first remains available.
- Nearby scan, discovered peers, authenticated peer cards, incoming approval, QR scanning, manual pairing fallback, media-only selection, batch queue, progress and received history remain integrated.
- Selecting a discovered peer opens QR scanning when no pairing secret is present; successful QR scan from camera, QR image, or pasted payload sends the authenticated connection request.
- A dedicated connection/transfer room now presents sender and receiver identities side by side, identifies which side is this phone, and displays the current pairing/connected/transferring state.
- The central connection glyph pulses only while a connection request is pending; the infinite animation is disposed when the pending state ends to avoid idle animation work.
- The transfer room surfaces the current file progress, throughput, aggregate batch progress, and batch status counts using existing service state. Existing queue rows continue to show per-item states and retry/skip controls.
- Sender completion is shown only when every item in the latest batch is actually marked completed; failed or pending entries do not produce a false success screen. Receiver completion is shown from the existing received-file/status state.
- The receive room now mirrors the sender room with sender/receiver identity placement, a ready-state indicator, and a framed high-contrast QR card with a clear scan instruction.
- The transfer room lists individual media items in the active batch with each item state, current-item progress, and any per-item error; legacy queue controls remain available for retry/skip/pause/cancel.
- Removed duplicate standalone progress cards; received-file actions and receive history are now shown only in Receive mode to keep Send and Receive rooms focused.
- This is an incremental Zapya-inspired Compose UI pass, not yet proof of pixel-identical Zapya screens or two-device usability.


## Latest UI architecture update — 2026-11-10

- The UltraShare navigation entry now opens `UltraShareExperienceScreen`, a dedicated multi-page experience instead of the previous long, mode-switched screen.
- Separate pages are provided for: Home, Send media, Receive / Create receiving room, Join group by QR, Nearby devices / radar, Live transfer room, and Transfer history.
- The pages use the existing `UltraShareViewModel`, foreground transfer service, pairing protocol, discovery, Wi-Fi Direct, hotspot, queue and history stores. No parallel transport engine was introduced.
- The previous `UltraShareScreen` composable remains in the source tree; it was not deleted. The navigation route now points to the new multi-page composable.
- Preserved in the new flow: media-only selection, persisted URI grants for externally shared media, camera and image QR scanning, manual pairing fallback, incoming request approval/rejection, peer disconnect, batch send, pause/cancel, retry/skip/resume queue actions, hotspot controls, received-file export/open actions, and sent/received history.
- Internal page back navigation is supported through the Android system Back action and the page header.
- This is the first structural multi-page pass. It still requires exact-head Android CI/build verification and two-device usability testing before it can be called release-ready.

## Latest commits in active branch

- `df5a840131cd9733caf447760d3ee950217f3966` — QR-first nearby peer pairing; auto-request after QR scan.
- `e6089241dccd0dd41844c1055e7d4142da567d7f` — unify nearby peer connect action across UltraShare room.
- `5050ae54899d56ee3a3d18b6ce52d76047628cb5` — clarify media-only QR-first instructions in the help card.
- `a3687d3c72006fd8d4420fec00a3d6806397a45f` — create focused Send and Receive rooms, show only the selected workflow, and auto-request authenticated connection after camera QR scan.
- `2ea66d7c82b0efc2489f10c947e8be9eb39be22c` — add a central transfer-room status panel with peer/connection state and live progress when available.
- `4a69853188a49ff1388fdc26816a8e9c56b75731` — redesign nearby-device cards, add device search, and fold manual pairing into advanced options so QR-first stays the primary path.
- `7dc380847dbccd32562db82f3aa20406579c7e78` — remove duplicate peer lists from the send panel; the device room is the single source for peer selection.
- `c48f1310df2d2b0b6c2afd7355b6245aeadac59e` — upgrade authenticated connected-peer cards with clearer online state and disconnect action.
- `edae4d3ebc121b75f77fe63d6de7f6b17b209e5f` — remove an empty conditional left by the Send/Receive room separation.
- `ce7790484d09a0c53db33e46a91b7892301bbd84` — add the central animated two-device identity room, current transfer status/progress, batch summary, and completion panels.
- `01f6c024901101a2350303fbde5c777b2ecaaace` — mirror and polish the receive room, including ready-state badge and framed QR presentation; tighten completion-state checks.
- `a9486fe38a3aab0790c26d656bfcb02a54122849` and `4eb86147916b89089586a165e92f34101e061cbb` — declare and include the Compose animation library used by the pairing pulse.
- `9ad1a507809bd3e060a1c1f261a49152488b8cef` — scope the repeating pulse animation to active pairing only, so it is not continuously running while idle.
- `674b13dfc2fec52566a931c1efa6385851385a20` — show each media item and its current state directly inside the transfer room.
- `ba8bbf2c398b3f3100487b723cc30d415d29c524` — consolidate progress presentation and remove duplicate progress cards.
- `45ddfc5ea4165d0d72132c87340be37989256d2d` — keep received-media actions and history inside Receive mode only.
- The latest UI implementation remains scoped to `app/src/main/java/com/malekplay/ui/network/UltraShareScreen.kt`; animation dependency changes are limited to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
- Keep all future UltraShare implementation and handoff documentation on this branch. Do not cherry-pick to `main` automatically.

## CI / validation status at handoff

- Earlier workflow runs `42646297415`, `42646763050`, and `42646886195` were cancelled when newer commits superseded them; do not interpret cancellation as a code failure or success.
- Workflow run `42646987275` for commit `678e38e07e8b32c9ed43bd3b90d13da19ef5ead8` was in progress at the last check. Desktop companion protocol tests passed; Android unit tests passed; Android lint and release packaging were still running. Debug APK and universal install artifacts had uploaded for that older commit only.
- Commit `a3687d3c72006fd8d4420fec00a3d6806397a45f` had a queued workflow `42648006942`, but that run was cancelled when the next commit superseded it.
- Workflow `42648892813` for documentation commit `0e47e2207262ca339645cd7c54ca93468c832b9a` was in progress at the last check. Desktop companion protocol tests passed; Android unit tests/lint and APK packaging were still running. This run includes the preceding UI changes through `c48f1310df2d2b0b6c2afd7355b6245aeadac59e`.
- Workflow `42650867161` for source commit `45ddfc5ea4165d0d72132c87340be37989256d2d` was in progress at the last check. Desktop companion protocol tests passed; Android unit tests and ABI debug APK assembly were still running; no artifacts had been uploaded yet.
- Workflow `42650438523` for commit `9ad1a507809bd3e060a1c1f261a49152488b8cef` was cancelled when later commits superseded it. The current workflow above is the relevant validation attempt for the subsequent UI changes.
- The handoff documentation commit is newer than the source commit validated by run `42650867161`; if a workflow is triggered for the documentation update, inspect that exact run as well. Do not claim build success or provide an APK until a completed run and artifact are verified.
- Earlier runs for superseded commits were cancelled; cancellation is neither a build failure nor success. Do not claim the latest UI commit has a successful build or provide its APK until an exact-head workflow succeeds and its artifact is verified.
- Do not claim the new UI commit has a successful build or provide its APK until its exact-commit workflow succeeds and the artifact is verified.
- CI does not replace two-Android-device testing. Verify send/receive on actual devices before calling the feature complete.

## Remaining work — execute in this order

### 1. Stabilize and verify current connection flow
- Wait for the latest branch CI run; inspect failing job logs and fix only scoped UltraShare defects.
- Verify QR scan → authenticated request → receiver approval → active peer on both ends → batch send.
- Verify rejection, timeout, disconnect, wrong secret, repeated request, and cancellation states.
- Make sure QR scanning does not start a transfer without explicit receiver approval.

### 2. Dedicated Zapya-inspired room UX — visual pass implemented; validation pending
- Send/Receive room separation, searchable nearby devices, authenticated peer cards, and QR-first pairing are implemented.
- Two-sided sender/receiver identity room, pending-pairing pulse, transfer progress/summary, per-item queue states, and completion panels are implemented from existing state.
- Receive room now mirrors the sender identity layout and gives the QR code a dedicated framed presentation.
- Still required: verify actual two-phone identity names and orientation, animation lifecycle, completion behavior for success/failure/multi-file batches, and QR readability on physical screens.
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
