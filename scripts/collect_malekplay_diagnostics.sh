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

mapfile -t FILES < <(find "$SOURCE" -maxdepth 1 -type f -name 'ultrashare-*.jsonl' -printf '%T@ %p\n' | sort -rn | head -n 3 | cut -d' ' -f2-)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "لا توجد سجلات تشخيص بعد. شغّل Ultra Share وجرّب الاقتران أولاً."
  exit 1
fi

tar -czf "$ARCHIVE" -C "$SOURCE" "$(printf '%s\n' "${FILES[@]}" | sed 's#^.*/##')"
echo "تم جمع ${#FILES[@]} من أحدث ملفات التشخيص."
echo "الملف: $ARCHIVE"
