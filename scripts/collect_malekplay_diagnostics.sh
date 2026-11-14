#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE="$HOME/storage/shared/Download/MalekPlayDiagnostics"
DEST="$HOME/storage/shared/Download"
STAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$DEST/MalekPlayDiagnostics-$STAMP.tar.gz"

if [ ! -d "$SOURCE" ]; then
  echo "لم أجد مجلد التشخيص: $SOURCE"
  echo "افتح Termux ونفّذ termux-setup-storage ثم وافق على إذن الملفات."
  exit 1
fi

FILES=()
while IFS= read -r file; do
  FILES+=("$file")
done < <(ls -1t "$SOURCE"/ultrashare-*.jsonl 2>/dev/null | head -n 3 || true)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "لا توجد سجلات تشخيص بعد. شغّل Ultra Share وجرّب الاقتران أولاً."
  exit 1
fi

NAMES=()
for file in "${FILES[@]}"; do
  NAMES+=("$(basename "$file")")
done

tar -czf "$ARCHIVE" -C "$SOURCE" -- "${NAMES[@]}"
echo "تم جمع ${#FILES[@]} من أحدث ملفات التشخيص."
echo "الملف: $ARCHIVE"
