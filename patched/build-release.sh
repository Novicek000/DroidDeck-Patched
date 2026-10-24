#!/usr/bin/env bash
# Builds the DroidDeck Patched release APK from this checkout.
#
#   patched/build-release.sh
#
# Needs: git, python3, curl, JDK 17 (JAVA_HOME), Android SDK (ANDROID_HOME) with build-tools and
# platform 34. Gradle downloads the rest. Output: patched/out/DroidDeck-Patched-<version>.apk
#
# The Kotlin/Java code and resources are compiled here; native libraries and the Linux runtime
# come unchanged from the official upstream APK named in patched/upstream.env (apk_tool.py).
# Signed with keystore/testkey.p12, the key keystore/release-signer.sha256 names.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$here")
cd "$root"
# shellcheck source=upstream.env
. "$here/upstream.env"

die() { echo "build-release: $*" >&2; exit 1; }

[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || die "set ANDROID_HOME to the Android SDK"
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
build_tools=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -n1)
[ -x "$build_tools/apksigner" ] || die "no build-tools in $sdk"
java -version 2>&1 | grep -q 'version "17' || die "JDK 17 is needed (JAVA_HOME / PATH)"

if ! git rev-parse -q --verify "refs/tags/$UPSTREAM_TAG" >/dev/null; then
    git fetch --no-tags "https://github.com/$UPSTREAM_REPO" "refs/tags/$UPSTREAM_TAG:refs/tags/$UPSTREAM_TAG"
fi
upstream_commit=$(git rev-parse "$UPSTREAM_TAG^{commit}")
python3 "$here/apk_tool.py" scope "$UPSTREAM_TAG"

out="$here/out"
mkdir -p "$out/cache"
official="$out/cache/$(basename "$UPSTREAM_APK_URL")"
if ! echo "$UPSTREAM_APK_SHA256  $official" | sha256sum -c --status 2>/dev/null; then
    curl -fL --retry 3 -o "$official.part" "$UPSTREAM_APK_URL"
    mv "$official.part" "$official"
fi
echo "$UPSTREAM_APK_SHA256  $official" | sha256sum -c --status || die "$official does not match UPSTREAM_APK_SHA256"

version=$(sed -n 's/^ *versionName "\(.*\)"$/\1/p' app/build.gradle)
[ -n "$version" ] || die "no versionName in app/build.gradle"
state=clean
[ -z "$(git status --porcelain)" ] || state=dirty
label="patched $state · $(git branch --show-current || true) at $(git rev-parse --short=7 HEAD)"

cp app/build.gradle "$out/build.gradle.orig"
trap 'cp "$out/build.gradle.orig" app/build.gradle' EXIT
python3 "$here/apk_tool.py" buildconfig "$official" "$build_tools/dexdump" "$upstream_commit" \
    "$(git rev-parse HEAD)" "$(git show -s --format=%ct HEAD)" "$label" >> app/build.gradle

./gradlew --no-daemon --console=plain -PskipRust=true :app:mergeDexRelease :app:optimizeReleaseResources
cp "$out/build.gradle.orig" app/build.gradle

apk="$out/DroidDeck-Patched-$version.apk"
rm -f "$out/unsigned.apk" "$out/aligned.apk" "$apk"
python3 "$here/apk_tool.py" graft "$official" app/build/intermediates/dex/release/mergeDexRelease \
    app/build/intermediates/optimized_processed_res/release/optimizeReleaseResources/resources-release-optimize.ap_ \
    "$UPSTREAM_TAG" "$out/unsigned.apk"
"$build_tools/zipalign" -p -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$build_tools/apksigner" sign --ks keystore/testkey.p12 --ks-type PKCS12 --ks-pass pass:android \
    --ks-key-alias testkey --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$apk" "$out/aligned.apk"
rm -f "$out/unsigned.apk" "$out/aligned.apk" "$apk.idsig"

signer=$("$build_tools/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$signer" = "$(cat keystore/release-signer.sha256)" ] || die "signed by $signer, not keystore/release-signer.sha256"
(cd "$out" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
echo "$apk"
cat "$apk.sha256"
