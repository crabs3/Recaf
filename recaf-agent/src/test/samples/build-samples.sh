#!/usr/bin/env bash
# Regenerates the binary test fixtures in ../resources/samples from the sources in this directory.
#
# Requires a JDK (javac, jar) on the PATH and ANDROID_HOME pointing at an Android SDK with a platform and
# build-tools installed. The fixtures are committed so that tests, including the on-device ones, never need to run this.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../resources/samples
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PLATFORM=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

# hello.jar: two plain Java classes and a properties file.
mkdir -p "$WORK/jar/config"
javac --release 17 -d "$WORK/jar" $(find jar -name '*.java')
printf 'endpoint=https://example.invalid/api\nmode=debug\n' > "$WORK/jar/config/app.properties"
jar --create --file "$OUT/hello.jar" -C "$WORK/jar" .

# sample.apk: a minimal (unsigned) Android application with a binary manifest and a single DEX file.
mkdir -p "$WORK/apk/classes" "$WORK/apk/dex"
javac --release 17 -cp "$PLATFORM/android.jar" -d "$WORK/apk/classes" $(find apk/java -name '*.java')
"$BUILD_TOOLS/d8" --min-api 26 --lib "$PLATFORM/android.jar" --output "$WORK/apk/dex" $(find "$WORK/apk/classes" -name '*.class')
"$BUILD_TOOLS/aapt2" link -o "$OUT/sample.apk" --manifest apk/AndroidManifest.xml -I "$PLATFORM/android.jar"
(cd "$WORK/apk/dex" && zip -q -j "$OLDPWD/$OUT/sample.apk" classes.dex)
echo "Wrote $OUT/hello.jar and $OUT/sample.apk"
