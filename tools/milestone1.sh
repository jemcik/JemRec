#!/usr/bin/env bash
# Milestone-1 harness: drive the embedded ADB transport from a USB shell.
#
# Pairing is a ONE-TIME act - adbd stores the app's public key permanently - so
# only `pair` needs a human. Everything after it, including the Wi-Fi-off and
# post-reboot runs that make up the real pass bar, is scriptable.
#
#   tools/milestone1.sh pair 123456     # while the pairing dialog is open
#   tools/milestone1.sh connect
#   tools/milestone1.sh selftest
#   tools/milestone1.sh state wifi-on   # selftest, saved under docs/evidence/
set -euo pipefail

PKG=com.jemcik.jemrec
RECEIVER="$PKG/.diag.SpikeReceiver"
ACTION=com.jemcik.jemrec.DIAG
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE="$REPO/docs/evidence"

# The receiver refuses anything without the right token and prints the expected
# value to logcat, which since Android 10 only adb and the app itself can read.
# So this bootstrap is deliberately a rejected call.
token() {
  # Retries rather than a single fixed sleep: this is often called right after
  # a force-stop, so the first broadcast has to cold-start the process before
  # anything can be logged. Every grep here is `|| true` guarded, because under
  # `set -e` with pipefail a grep that simply has not matched YET would abort
  # the whole script rather than let the loop go round again.
  local t=""
  for _ in 1 2 3 4 5 6 7 8; do
    adb shell am broadcast --include-stopped-packages -a "$ACTION" -n "$RECEIVER" \
      --es op status >/dev/null 2>&1 || true
    sleep 1
    t="$(adb logcat -d -s JemRec 2>/dev/null | grep -oE 'diag token: [0-9a-f]+' | tail -1 | awk '{print $3}' || true)"
    [ -n "$t" ] && break
  done
  echo "$t"
}

send() {
  local t; t="$(token)"
  [ -n "$t" ] || { echo "could not read the diag token; is the debug build installed?" >&2; exit 1; }
  # Cleared only now that the token is in hand, so `collect` cannot match
  # output left over from the bootstrap above.
  adb logcat -c
  adb shell am broadcast --include-stopped-packages -a "$ACTION" -n "$RECEIVER" \
    --es token "$t" "$@" >/dev/null 2>&1
}

# Wait for the receiver to print a complete report rather than sleeping a fixed
# amount: pairing and mDNS both have long, variable tails.
collect() {
  local label="$1" deadline=$((SECONDS + ${2:-30}))
  while [ $SECONDS -lt $deadline ]; do
    if adb logcat -d -s JemRec 2>/dev/null | grep -q "diag\[$label\] <<<" 2>/dev/null; then break; fi
    sleep 1
  done
  adb logcat -d -s JemRec 2>/dev/null \
    | sed -nE "s/.*diag\[$label\] (.*)$/\1/p" | grep -vE '^(>>>|<<<)$'
}

# Ground truth for "is the pairing dialog actually open" is the device's own
# listener table, not the host's mDNS cache, which keeps serving services that
# have already gone away. The app discovers the live port for itself; this is
# only here to fail with a useful message instead of ECONNREFUSED.
pairing_live() {
  local before after
  before="$(adb shell 'ss -ltn' 2>/dev/null | grep -cE 'LISTEN' || true)"
  [ "${before:-0}" -gt 3 ] && return 0
  adb mdns services 2>/dev/null | grep -q '_adb-tls-pairing' && return 0
  return 1
}

case "${1:-}" in
  pair)
    CODE="${2:-}"
    [ ${#CODE} -eq 6 ] || { echo "usage: $0 pair <6-digit-code>" >&2; exit 2; }
    if ! pairing_live; then
      echo "No pairing service is listening." >&2
      echo "Open Settings > Developer options > Wireless debugging >" >&2
      echo "'Pair device with pairing code' and leave it on screen." >&2
      exit 3
    fi
    echo "--- listeners while the dialog is open ---"
    adb shell 'ss -ltn' 2>/dev/null || true
    # libadb caches its SSLContext, and whether it chose the bundled Conscrypt,
    # in statics that live as long as the process. Start from a clean one so a
    # retry after a dependency change is actually testing the new code.
    adb shell am force-stop "$PKG"
    adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
    sleep 3
    send --es op pair --es code "$CODE"
    collect pair 60
    ;;
  connect)  send --es op connect;                 collect connect 30 ;;
  selftest) send --es op selftest;                collect selftest 40 ;;
  exec)     send --es op exec --es cmd "${2:?command}"; collect exec 30 ;;
  status)   send --es op status;                  collect status 15 ;;
  state)
    LABEL="${2:?usage: $0 state <label>}"
    mkdir -p "$EVIDENCE"
    send --es op connect >/dev/null; collect connect 30 >/dev/null || true
    send --es op selftest
    OUT="$EVIDENCE/milestone1-$LABEL.txt"
    collect selftest 40 | tee "$OUT"
    echo; echo "saved to ${OUT#"$REPO"/}"
    ;;
  ports)
    echo "--- adbd listeners on device ---"
    adb shell 'ss -ltn' 2>/dev/null | head -20
    echo "--- mDNS as seen from this host ---"
    adb mdns services 2>/dev/null
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2 ;;
esac
