#!/usr/bin/env bash
# Build and verify the church-signed agent APK.
#
# Usage: scripts/church/release-build.sh <versionName> <versionCode>
#   e.g. scripts/church/release-build.sh 0.3.3-church.1 304
#
# The release key lives outside Git in MDMESH_RELEASE_DIR (default ~/.config/mdmesh-church-release,
# mode 700) or is restored there from 1Password "135 Main" / "MDMesh - release signing key":
#   mdmesh-church-release.p12   PKCS12 keystore, alias mdmesh-church
#   store-password              keystore password (PKCS12: also the key password)
# Secrets reach Gradle only through this process's environment; nothing is printed or written.
# The output is the APK path, its SHA-256 and the signing certificate digest, which are public.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <versionName> <versionCode>" >&2
  exit 2
fi
version_name=$1
version_code=$2
case $version_code in ''|*[!0-9]*) echo "versionCode must be a number" >&2; exit 2 ;; esac

key_dir=${MDMESH_RELEASE_DIR:-$HOME/.config/mdmesh-church-release}
store=$key_dir/mdmesh-church-release.p12
password_file=$key_dir/store-password
for f in "$store" "$password_file"; do
  [ -f "$f" ] || { echo "missing $f (restore it from 1Password: 135 Main / MDMesh - release signing key)" >&2; exit 1; }
done
if [ -n "$(find "$key_dir" -maxdepth 1 -perm -o=r -o -maxdepth 1 -perm -g=r)" ]; then
  echo "$key_dir is readable by others; chmod 700 it and 600 its files" >&2
  exit 1
fi

repo=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo/agent-android"

MDM_RELEASE_STORE_FILE=$store
MDM_RELEASE_STORE_PASSWORD=$(cat "$password_file")
MDM_RELEASE_KEY_ALIAS=mdmesh-church
MDM_RELEASE_KEY_PASSWORD=$MDM_RELEASE_STORE_PASSWORD
export MDM_RELEASE_STORE_FILE MDM_RELEASE_STORE_PASSWORD MDM_RELEASE_KEY_ALIAS MDM_RELEASE_KEY_PASSWORD

./gradlew --no-daemon -q :app:assembleRelease \
  -PversionName="$version_name" -PversionCode="$version_code"

apk=app/build/outputs/apk/release/app-release.apk
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
apksigner=$(find "$sdk/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1)
[ -n "$apksigner" ] || { echo "apksigner not found under $sdk/build-tools" >&2; exit 1; }

"$apksigner" verify --print-certs "$apk" 2>/dev/null | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "APK: $repo/agent-android/$apk"
echo "APK SHA-256: $(shasum -a 256 "$apk" | cut -d' ' -f1)"
