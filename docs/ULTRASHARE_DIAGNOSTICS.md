# Ultra Share diagnostics

Ultra Share writes one JSON Lines file for each pairing/transfer attempt on Android 10 and newer:

`Download/MalekPlayDiagnostics/ultrashare-*.jsonl`

The app keeps the newest three session files in this folder and prunes older files when a new session starts. The records include the stage, event, timestamp, local session ID, network endpoint metadata, native DFile callbacks, and exception class/message/stack trace with source class, method, and line where available.

Secrets, pairing keys, passwords, bearer tokens, and file contents must never be logged. File names are intentionally omitted from transfer diagnostics; only file count, MIME type, and byte counts are recorded.

## Termux collection

1. Grant Termux shared-storage access once with `termux-setup-storage`.
2. Copy `scripts/collect_malekplay_diagnostics.sh` into Termux or run it from the checked-out repository.
3. Execute it with Bash. It packages up to the newest three JSONL files into a timestamped `.tar.gz` in Downloads.

Termux reads the public Downloads files. No app-private storage path or root access is required.

## Compatibility

The public Downloads export uses MediaStore's Downloads collection (Android 10+). On older Android releases the app records a clear diagnostic-export failure in Logcat; public Termux-readable export is not enabled by this implementation on those versions.
