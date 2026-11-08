# MalekPlay — Kids Mode and Parental Media Allowlist

## Scope

Kids Mode restricts media playback inside MalekPlay to videos and audio tracks explicitly approved by the parent. It does not lock the Android device, control other apps, or restrict Ultra Share, network connections, file management, or other Android features.

## Parent setup

1. Open Settings → Family / Child Mode.
2. Open **Choose allowed videos and music**.
3. Select the videos and audio tracks that the child may play, then save.
4. Enable Kids Mode and create a 4–8 digit parent PIN (or enter the existing PIN).
5. MalekPlay opens the child library. Only currently indexed media whose stable MediaStore ID is in the allowlist is displayed.

To update the allowlist, the parent uses **Parent access**, enters the PIN to leave Kids Mode, then returns to Settings and edits the selection.

## Enforcement points

- The child library is built from the approved IDs only.
- Playback queue filtering takes one immutable allowlist snapshot per queue, avoiding repeated preference reads while scanning large libraries.
- Video and audio navigation routes reject IDs that are not approved while Kids Mode is enabled.
- The central PlayerViewModel filters every queue again at the playback boundary. If the requested current item is not approved, the queue is not started.
- External URIs opened from another app are rejected while Kids Mode is enabled because they have not been explicitly selected from the local library.
- Audio/video next and previous queues are filtered to approved media.
- Entering Kids Mode clears any existing Media3 queue, including a session that may have survived Activity recreation.
- An empty allowlist fails closed: the child library remains empty and no media can be started.
- PINs are stored as salted PBKDF2-HMAC-SHA256 hashes; Arabic-Indic and Western digits are normalized consistently, and repeated failed attempts trigger a temporary lockout.
- Allowlist entries are bounded in count and identifier length. Stale IDs are removed when the parent next saves the current library selection.

## Known boundaries

- This is an in-app media allowlist, not Android kiosk mode. It does not prevent a child from leaving MalekPlay through Android system navigation or opening other apps.
- Media must be visible to MalekPlay's local media library and accessible under Android's current media permissions to be selected.
- A media item removed from the device or no longer indexed will not appear in the child library, even if its old ID remains until the parent saves again.
- The feature does not inspect or classify media content. The parent is responsible for selecting appropriate videos and music.

## Verification checklist

- [ ] Create a new PIN and enable Kids Mode.
- [ ] Verify an empty allowlist shows no playable media.
- [ ] Approve one video and one audio track; verify only those two appear.
- [ ] Try a non-approved video/audio ID through navigation and verify it is rejected.
- [ ] Verify next/previous and shuffle remain within the approved set.
- [ ] Open a media file from another app while Kids Mode is active and verify playback is blocked.
- [ ] Force-stop and relaunch MalekPlay while Kids Mode is active; verify the child library opens and old playback does not resume.
- [ ] Verify an incorrect PIN cannot exit Kids Mode and the lockout is applied after repeated failures.
- [ ] Verify PIN entry accepts both Arabic-Indic digits (٤٨٢٦) and Western digits (4826).
- [ ] Exit with the correct PIN; verify the parent returns to the normal app and can edit the allowlist.
