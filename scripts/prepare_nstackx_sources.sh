#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/native/nstackx/dependencies.lock"
DEST="$ROOT/third_party/nstackx"

if [[ ! -f "$LOCK" ]]; then
  echo "Missing NSTACKX dependency lock: $LOCK" >&2
  exit 2
fi

# The lock file contains only reviewed KEY=value assignments and comments.
set -a
# shellcheck disable=SC1090
source "$LOCK"
set +a

mkdir -p "$DEST"

checkout_pinned() {
  local name="$1" url="$2" commit="$3"
  local path="$DEST/$name"
  local actual=""

  if [[ -d "$path" && ! -d "$path/.git" ]]; then
    echo "Refusing to overwrite non-git directory: $path" >&2
    return 3
  fi

  if [[ ! -d "$path/.git" ]]; then
    git init "$path"
    git -C "$path" remote add origin "$url"
  else
    local existing_url
    existing_url="$(git -C "$path" remote get-url origin)"
    if [[ "$existing_url" != "$url" ]]; then
      echo "Remote mismatch for $name: $existing_url" >&2
      return 4
    fi
  fi

  actual="$(git -C "$path" rev-parse HEAD 2>/dev/null || true)"
  if [[ "$actual" != "$commit" ]]; then
    git -C "$path" fetch --no-tags --depth=1 origin "$commit"
    git -C "$path" checkout --detach FETCH_HEAD
  fi

  actual="$(git -C "$path" rev-parse HEAD)"
  if [[ "$actual" != "$commit" ]]; then
    echo "Pinned revision mismatch for $name: expected $commit, got $actual" >&2
    return 5
  fi
  printf 'verified %-10s %s\n' "$name" "$actual"
}

checkout_pinned nstackx "$NSTACKX_REPOSITORY" "$NSTACKX_COMMIT"
checkout_pinned libcoap "$LIBCOAP_REPOSITORY" "$LIBCOAP_COMMIT"
checkout_pinned cjson "$CJSON_REPOSITORY" "$CJSON_COMMIT"
checkout_pinned securec "$SECUREC_REPOSITORY" "$SECUREC_COMMIT"
checkout_pinned mbedtls "$MBEDTLS_REPOSITORY" "$MBEDTLS_COMMIT"
# The OpenHarmony Mbed TLS snapshot requires its framework submodule at configure time.
# Pin the exact gitlink SHA recorded by the upstream mbedtls-3.6.5 release tag.
checkout_pinned mbedtls/framework "$MBEDTLS_FRAMEWORK_REPOSITORY" "$MBEDTLS_FRAMEWORK_COMMIT"

echo "Pinned source acquisition completed."
echo "This script does not compile or link NSTACKX; Android adaptation/build is a later phase."
