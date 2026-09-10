#!/usr/bin/env bash
# Pair as fast as possible, because the pairing dialog does not stay open long.
#
# Everything slow has been taken out of the path: no force-stop, no app restart,
# no mDNS wait. The port comes from the device's own listener table, which is
# instant and cannot go stale the way `adb mdns services` does.
#
# Identifying the port: adbd binds BOTH its TLS services to the wildcard
# address. The connect service is the persistent one and its port is already
# known; while the pairing dialog is open a SECOND wildcard listener appears,
# and that is the pairing service. Loopback-only listeners are other things and
# are ignored.
#
#   tools/pair-now.sh 123456
set -uo pipefail

PKG=com.jemcik.jemrec
RECEIVER="$PKG/.diag.SpikeReceiver"
ACTION=com.jemcik.jemrec.DIAG
CODE="${1:?usage: $0 <6-digit-code>}"
WAIT="${2:-120}"

connect_port() {
  adb mdns services 2>/dev/null \
    | awk '/_adb-tls-connect/ {split($3,a,":"); print a[2]; exit}'
}

wildcard_ports() {
  adb shell 'ss -ltn' 2>/dev/null | awk '$4 ~ /^\*:/ {split($4,a,":"); print a[2]}'
}

CONNECT="$(connect_port)"
echo "connect service port: ${CONNECT:-unknown}"

echo "waiting up to ${WAIT}s for the pairing dialog..."
PORT=""
for _ in $(seq 1 "$WAIT"); do
  for p in $(wildcard_ports); do
    if [ "$p" != "$CONNECT" ]; then PORT="$p"; break; fi
  done
  [ -n "$PORT" ] && break
  sleep 1
done

if [ -z "$PORT" ]; then
  echo "No pairing listener appeared. Open Settings > Developer options >" >&2
  echo "Wireless debugging > 'Pair device with pairing code'." >&2
  exit 3
fi
echo "pairing listener found on *:$PORT - firing immediately"

TOKEN="$(cat /tmp/jemrec.token 2>/dev/null || true)"
if [ -z "$TOKEN" ]; then
  adb shell am broadcast --include-stopped-packages -a "$ACTION" -n "$RECEIVER" \
    --es op status >/dev/null 2>&1
  sleep 2
  TOKEN="$(adb logcat -d -s JemRec 2>/dev/null | grep -oE 'diag token: [0-9a-f]+' | tail -1 | awk '{print $3}')"
  echo "$TOKEN" > /tmp/jemrec.token
fi

adb logcat -c
adb shell am broadcast --include-stopped-packages -a "$ACTION" -n "$RECEIVER" \
  --es token "$TOKEN" --es op pair --ei port "$PORT" --es code "$CODE" >/dev/null 2>&1

for _ in $(seq 1 45); do
  adb logcat -d -s JemRec 2>/dev/null | grep -q 'diag\[pair\] <<<' && break
  sleep 1
done
adb logcat -d -s JemRec 2>/dev/null | sed -nE 's/.*diag\[pair\] (.*)$/\1/p' | grep -vE '^(>>>|<<<)$'
