#!/usr/bin/env bash
# Build the VoIP feasibility probe to its own jar. Not the daemon, not shipped:
# see probe/com/jemcik/jemrec/probe/VoipProbe.java for what it measures.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-36}"
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../src"
OUT="$HERE/build"
JAR="$HERE/voip-probe.jar"
JAVAC="${JEMREC_JAVA_HOME:+$JEMREC_JAVA_HOME/bin/}javac"

rm -rf "$OUT" "$JAR"
mkdir -p "$OUT/classes" "$OUT/gen/com/genymobile/scrcpy"
cat > "$OUT/gen/com/genymobile/scrcpy/BuildConfig.java" <<'EOF'
package com.genymobile.scrcpy;

public final class BuildConfig {
    public static final boolean DEBUG = false;

    private BuildConfig() {
    }
}
EOF
find "$SRC" "$OUT/gen" "$HERE/com" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -nowarn --release 17 -encoding UTF-8 \
    -cp "$SDK/platforms/android-$ANDROID_PLATFORM/android.jar" \
    -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && find . -name '*.class' > ../classes.txt)
(cd "$OUT/classes" && "$SDK/build-tools/$BUILD_TOOLS/d8" --min-api 31 --output "$JAR" @../classes.txt)
echo "built $JAR ($(wc -c < "$JAR" | tr -d ' ') bytes)"
