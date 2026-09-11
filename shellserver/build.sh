#!/usr/bin/env bash
# Build the shell-side capture daemon to a classes.dex jar.
#
# Not scrcpy's build_without_gradle.sh: that script builds scrcpy's own tree
# and layout, and this fork has a different package root, no Options, and its
# own entry point. This is the same two steps without the parts that no longer
# apply.
#
# ANDROID_PLATFORM must match the platform the DEVICE runs, not the app's
# minSdk. This code links @hide framework internals, so it has to be compiled
# against the same API level it will execute on. The app's minSdk 31 is a
# separate decision that happens to be a different number on purpose.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-36}"
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"

# THE JAVAC MAJOR IS PART OF THE BUILD, and it is checked, not assumed.
#
# --release 17 fixes the class-file level, not the bytes: measured on 0.3, JDK
# 17 and JDK 21 turned these same sources into dex files 264 bytes apart. So
# every machine that builds a release has to compile with the same major, or
# the APK cannot be reproduced and F-Droid cannot publish the one signed here.
# 21 is what F-Droid's build server runs (Debian trixie, default-jdk), so 21
# is what CI installs and what this script insists on. When that moves, move
# all three together. JEMREC_JAVA_HOME points at a JDK explicitly; otherwise
# JAVA_HOME, then whatever javac is on the PATH.
JDK_MAJOR="${JEMREC_JDK_MAJOR:-21}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_JAR="$SDK/platforms/android-$ANDROID_PLATFORM/android.jar"
D8="$SDK/build-tools/$BUILD_TOOLS/d8"
OUT="$HERE/build"
JAR="$HERE/jemrec-capture.jar"

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR" >&2; exit 1; }
[ -x "$D8" ] || { echo "missing $D8" >&2; exit 1; }

JAVAC=""
for candidate in "${JEMREC_JAVA_HOME:+$JEMREC_JAVA_HOME/bin/javac}" \
                 "${JAVA_HOME:+$JAVA_HOME/bin/javac}" \
                 "$(command -v javac || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if [[ "$("$candidate" --version 2>/dev/null)" == "javac $JDK_MAJOR."* ]]; then
        JAVAC="$candidate"
        break
    fi
    echo "skipping $candidate: $("$candidate" --version 2>/dev/null || echo unknown)" >&2
done
[ -n "$JAVAC" ] || {
    echo "no javac $JDK_MAJOR found. The daemon must be compiled by JDK $JDK_MAJOR - the" >&2
    echo "same major F-Droid and CI use - or the APK is not reproducible. Install one and" >&2
    echo "point JEMREC_JAVA_HOME or JAVA_HOME at it." >&2
    exit 1
}

rm -rf "$OUT" "$JAR"
mkdir -p "$OUT/classes" "$OUT/gen/com/genymobile/scrcpy"

# scrcpy's sources expect a BuildConfig, which Gradle would normally generate.
# Upstream's build_without_gradle.sh writes the same file for the same reason.
# util.IO is the only consumer: it guards one assertion with BuildConfig.DEBUG.
cat > "$OUT/gen/com/genymobile/scrcpy/BuildConfig.java" <<'EOF'
package com.genymobile.scrcpy;

public final class BuildConfig {
    public static final boolean DEBUG = false;

    private BuildConfig() {
        // not instantiable
    }
}
EOF

find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
echo "compiling $(wc -l < "$OUT/sources.txt" | tr -d ' ') files against android-$ANDROID_PLATFORM"

# -nowarn because the fork deliberately calls deprecated and hidden APIs; that
# is the entire technique, and a wall of warnings about it hides real problems.
echo "javac: $JAVAC ($("$JAVAC" --version))"
"$JAVAC" --release 17 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" "@$OUT/sources.txt"

# d8 writes the jar itself when --output names one. Not a shortcut: it stamps
# the classes.dex entry with the epoch, so the same classes give the same bytes
# every time. The `zip` step this replaced stamped in the build's wall-clock
# time, which made every APK differ in this one asset and would have failed
# F-Droid's reproducible-build check for no reason a person would ever find.
echo "dexing with build-tools $BUILD_TOOLS"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --lib "$ANDROID_JAR" --min-api 31 --output "$JAR" @"$OUT/classes.txt"

echo "built $JAR ($(wc -c < "$JAR" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$JAR" | cut -c1-16)...)"
