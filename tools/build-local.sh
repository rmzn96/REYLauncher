#!/bin/bash
# REYLauncher: how the published APKs are built when the official Android SDK download
# (dl.google.com) is not reachable. Everything comes from public, checkable sources:
#   - aapt2, dalvik-exchange (dx), zipalign, apksigner: Ubuntu 24.04 packages
#       apt-get install aapt dalvik-exchange zipalign apksigner openjdk-8-jdk-headless
#   - Android 14 framework classes and resources (compile time only, not shipped in the APK):
#       https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
#       saved as $T/android-all-14.jar
#   - retrolambda 2.5.7 (turns Java 8 lambdas into plain classes so dx can dex them):
#       https://repo1.maven.org/maven2/net/orfjackal/retrolambda/retrolambda/2.5.7/retrolambda-2.5.7.jar
#       saved as $T/retrolambda.jar, run with Java 8
#   - $T/android-all-14-v52.jar: a copy of android-all with the class-file version set to 52 so
#       retrolambda's Java 8 can read it (only used while backporting lambdas, never shipped).
#       python3: for each .class in android-all-14.jar, set bytes 6..7 to 0x0034.
# With the official SDK, ./build.sh (aapt2 + d8 + apksigner) builds the same sources.
#
# Usage: tools/build-local.sh <source dir> <out apk> [keystore] [storepass] [alias]
# The signing key is never part of this repository.
set -euo pipefail
SRC="$(cd "$1" && pwd)"; OUT="$2"
KS="${3:-}"; KSPASS="${4:-}"; ALIAS="${5:-}"
T="${REYBUILD_TOOLS:-/opt/reybuild}"
FW="$T/android-all-14.jar"
B="$(mktemp -d /tmp/reybuild.XXXX)"
VERSION_CODE="${VERSION_CODE:-1}"; VERSION_NAME="${VERSION_NAME:-1.0}"
cd "$SRC"
mkdir -p "$B/gen" "$B/classes" "$B/classes-rl" "$B/dex"
aapt2 compile --dir res -o "$B/res.zip"
aapt2 link -o "$B/unsigned.apk" -I "$FW" --manifest AndroidManifest.xml \
    -A assets --java "$B/gen" --min-sdk-version 24 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" ${RENAME_PKG:+--rename-manifest-package "$RENAME_PKG"} "$B/res.zip"
if ! javac -nowarn -Xmaxerrs 60 -encoding UTF-8 --release 8 -classpath "$FW" -d "$B/classes" $(find src "$B/gen" -name '*.java') > "$B/javac.log" 2>&1; then
    grep -v -E '^(Note|warning): ' "$B/javac.log"; echo "JAVAC FAILED"; exit 1
fi
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -Dretrolambda.inputDir="$B/classes" -Dretrolambda.classpath="$B/classes:$T/android-all-14-v52.jar" \
     -Dretrolambda.outputDir="$B/classes-rl" -Dretrolambda.defaultMethods=true -Dretrolambda.quiet=true \
     -javaagent:"$T/retrolambda.jar" -jar "$T/retrolambda.jar"
dalvik-exchange --dex --min-sdk-version=24 --output="$B/dex/classes.dex" "$B/classes-rl"
(cd "$B/dex" && zip -q "$B/unsigned.apk" classes.dex)
zipalign -f 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ -n "$KS" ]; then
    apksigner sign --ks "$KS" --ks-pass "pass:$KSPASS" --ks-key-alias "$ALIAS" --out "$OUT" "$B/aligned.apk"
else
    cp "$B/aligned.apk" "$OUT"
fi
apksigner verify --print-certs "$OUT" 2>/dev/null | head -2 || true
ls -la "$OUT"
rm -rf "$B"
