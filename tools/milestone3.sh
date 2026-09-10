#!/usr/bin/env bash
# Milestone 3: capture a real call and prove BOTH directions are present.
#
# Run this, then make a call. It waits for the call to go off-hook, captures,
# and reports per-channel energy. Nothing to time by hand.
#
#   tools/milestone3.sh [seconds]
#
# Raw PCM rather than Opus, deliberately: the claim under test is about the two
# channels of the stereo stream, and measuring PCM tests the capture rather than
# the capture plus a decoder. Opus is what real recordings use.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECONDS_TO_CAPTURE="${1:-25}"
PORT=28472
JAR=/data/local/tmp/jemrec-capture.jar
OUT="$HERE/docs/evidence/milestone3-call.wav"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
mkdir -p "$HERE/docs/evidence"

if [ ! -f "$HERE/shellserver/jemrec-capture.jar" ]; then
  echo "building the daemon..."
  (cd "$HERE/shellserver" && ./build.sh) || exit 1
fi

echo "=== starting the capture daemon in raw mode ==="
adb push "$HERE/shellserver/jemrec-capture.jar" "$JAR" >/dev/null
adb shell "pkill -f jemrec.shell.Main" 2>/dev/null
sleep 1
adb shell "nohup sh -c 'CLASSPATH=$JAR exec app_process / com.jemcik.jemrec.shell.Main $PORT raw' \
  > /data/local/tmp/jemrec-capture.out 2>&1 &" >/dev/null
sleep 4
adb shell 'cat /data/local/tmp/jemrec-capture.out'
adb shell "ss -ltn" 2>/dev/null | grep -q ":$PORT" || {
  echo "daemon is not listening; see /data/local/tmp/jemrec-capture.out" >&2
  exit 1
}

adb forward --remove tcp:$PORT >/dev/null 2>&1
adb forward tcp:$PORT tcp:$PORT >/dev/null

# 0 IDLE, 1 RINGING, 2 OFFHOOK. This is the same signal milestone 4 will use
# from inside the app, via PHONE_STATE; polling it here keeps this script
# independent of any app code that does not exist yet.
call_state() {
  adb shell dumpsys telephony.registry 2>/dev/null \
    | grep -m1 -oE 'mCallState=[0-9]+' | cut -d= -f2
}

echo
echo "=================================================================="
echo "  MAKE A CALL NOW. Both people should talk - the whole point is"
echo "  to hear TWO voices on TWO channels."
echo
echo "  Waiting up to 3 minutes for the call to go off-hook..."
echo "=================================================================="
echo

STATE=""
for _ in $(seq 1 180); do
  STATE="$(call_state)"
  [ "$STATE" = "2" ] && break
  [ "$STATE" = "1" ] && echo "  ringing..."
  sleep 1
done

if [ "$STATE" != "2" ]; then
  echo "No call went off-hook. Nothing captured." >&2
  exit 2
fi

echo "call is off-hook - capturing ${SECONDS_TO_CAPTURE}s"
echo
python3 "$HERE/tools/capture-call.py" --port "$PORT" \
  --seconds "$SECONDS_TO_CAPTURE" --out "$OUT"
RC=$?

echo
echo "call state now: $(call_state)  (0 idle, 2 off-hook)"
echo "wav: ${OUT#"$HERE"/}"
exit $RC
