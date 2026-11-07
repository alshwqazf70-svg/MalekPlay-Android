# Kids Mode — parent-approved media only

Kids Mode is intentionally limited to local video and audio playback. It does not alter Ultra Share, network discovery, file management, or other device features.

## Parent setup
1. Open Settings → Family & privacy.
2. Choose **Choose allowed videos and music**.
3. Select only the library items approved for the child and save.
4. Enable Kids Mode and create a 4–8 digit parent PIN.

The allowlist stores stable library media IDs in the app's private SharedPreferences. An empty allowlist is fail-closed: no item is shown as approved.

## Child experience
- The app starts directly in the Kids library while Kids Mode is enabled, including when launched through an external media intent.
- Only items in the saved allowlist appear.
- Opening a media player is checked again against the allowlist.
- Audio and video next/previous queues are filtered to approved IDs, so notification and lock-screen controls cannot advance into unapproved local media.
- Existing playback is stopped and its queue cleared when the parent enables Kids Mode.

## Parent exit
The child screen consumes the normal Back action. A parent must use the in-app Parent access action and enter the PIN. The existing PIN verifier uses a random salt, PBKDF2-HMAC-SHA256, constant-time hash comparison, and a temporary lockout after repeated failures.

## Scope and limitations
- This is an in-app media restriction, not Android system-wide parental control or kiosk mode.
- It does not prevent a child from leaving MalekPlay through Android system navigation or opening another app.
- It controls items indexed in the MalekPlay local media library; it does not approve arbitrary URLs, network streams, or files opened by other apps.
- The allowlist is stored locally and is not included in the app's backup export at this stage.
- A parent should verify the selected files and supervise initial use. Media metadata and filenames are not a content-rating system.

## Verification status
Robolectric tests cover allowlist persistence, fail-closed behavior, and PIN-gated enable/disable. Device-level navigation, notification controls, and Android background-service behavior still require validation in the final test build.
