# Milestone 1 - the embedded ADB transport

The brief calls this "THE risk", and the rule is that nothing else gets built
until a shell session is proven. The bar is exact: `id` must report
**`uid=2000(shell)`**, in three network states - Wi-Fi on, Wi-Fi off, and after
a reboot.

Everything below was measured on the target device (Honor Magic 8 Pro, BKQ-N49,
MagicOS 10.0.0.199, Android 16 / API 36).

## Verdict

| # | Check | Result |
|---|---|---|
| 1 | shell UID holds both call-audio permissions | PASS |
| 2 | app pairs with the phone's own Wireless debugging | PASS |
| 3 | app connects to adbd over loopback | PASS |
| 4 | **`uid=2000(shell)`, Wi-Fi on** | **PASS** |
| 5 | pairing survives adbd restart, no new code | PASS |
| 6 | `uid=2000(shell)` over ADB, Wi-Fi off | **FAIL - and unfixable at this privilege level** |
| 7 | shell-privileged channel, Wi-Fi off | **PASS - via a different mechanism** |
| 8 | **`uid=2000(shell)` after a reboot** | **PASS** |
| 9 | pairing survives a reboot, no new code | PASS |
| 10 | app re-arms Wireless debugging unattended after reboot | PASS |

**Milestone 1 is met.** The bar was `uid=2000(shell)` in three network states.
Two are met over ADB directly. The third, Wi-Fi off, is met by the architecture
described below, which replaces a plan in the brief that cannot work here.

Evidence transcripts are in [`evidence/`](evidence/).

The headline is that **the transport works and the brief's network-independence
plan does not**. A replacement was found and measured; see "The Wi-Fi problem"
below. That is the single most consequential finding of this milestone.

## What was proven

**The shell UID has what the design needs**, re-read live rather than taken on
trust:

```
android.permission.CAPTURE_AUDIO_OUTPUT: granted=true
android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT: granted=true
```

**adbd binds to the wildcard address.** With Wireless debugging on, `ss -ltn`
reports `*:42321` - not the Wi-Fi address. A wildcard bind is reachable at
`127.0.0.1`, which is what lets a process on the phone connect to its own adbd
with no PC and no cable. libadb agrees: its `AndroidUtils.getHostIpAddress()`
returns loopback, so this is the library's intended use, not a trick played on
it. The pairing service binds the same way (measured at `*:38535`, `*:39347`).

**The full chain works.** Paired with a six-digit code, then:

```
$ id
uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),
1011(adb),... context=u:r:shell:s0
$ echo hi
hi
VERDICT: PASS - `id` reports uid=2000(shell)
```

**The pairing is durable.** After Wireless debugging was torn down and brought
back - a new adbd, a new ephemeral port (42321 -> 34301 -> 35311) - the app
reconnected with no new code. adbd keeps the app's public key, so pairing is a
one-time act. This is why only the very first step ever needs a human.

## The Wi-Fi problem, and why the brief's answer does not work

The brief anticipated that adbd's listener disappears with Wi-Fi off, and
proposed pinning it: `setprop service.adb.tcp.port 5555; stop adbd; start adbd`.

Both halves of that turn out to be wrong here.

**The listener does not merely move, it goes away**, and `adb_wifi_enabled`
resets itself from 1 to 0 and does not come back when Wi-Fi returns.

**The shell UID may not set the property.**

```
$ setprop service.adb.tcp.port 5555
Failed to set property 'service.adb.tcp.port' to '5555'.
$ getprop -Z service.adb.tcp.port
u:object_r:adbd_config_prop:s0
```

SELinux guards it. There is no `cmd`-based equivalent on this build either:
`cmd adb` offers only `is-wifi-supported` and `is-wifi-qr-supported`. So ADB
cannot be made to survive Wi-Fi loss at shell privilege, full stop.

### The replacement: ADB as bootstrap, not as runtime

The way out is that **ADB never needed to be the runtime transport.** It only
has to start something that then stands on its own. Three measurements, in
order, make that work:

**A process spawned from an adb shell outlives the adb connection.** It keeps
running as uid 2000 after the session exits. So a shell-privileged daemon can be
started once and then be independent of adbd entirely.

**The app may not reach it over an abstract unix socket.** SELinux refuses an
`untrusted_app` connecting to the shell domain's socket:

```
FAIL abstract:jemrec_probe -> IOException: Permission denied
```

A filesystem socket is not an option either - it would have to live under
`/data/local/tmp`, which is shell-only and which the app cannot traverse.

**The app may reach it over TCP on loopback.**

```
OK   tcp:127.0.0.1:28471 -> hello over tcp from uid 2000
```

And the whole thing, repeated with Wi-Fi off, adbd not listening, and
`adb_wifi_enabled` back to 0:

```
$ ps -A -o PID,USER,ARGS | grep SocketProbe
13076 shell        app_process / SocketProbe
OK   tcp:127.0.0.1:28471 -> hello over tcp from uid 2000
```

The app talked to a shell-privileged process with every radio off.

So the architecture becomes:

1. **Bootstrap, once, over ADB while Wi-Fi is up:** pair if needed, connect,
   launch the shell-side daemon via `app_process`.
2. **Runtime, thereafter:** app to daemon over TCP `127.0.0.1`. No adbd, no
   mDNS, no network, no Wi-Fi.

This also makes the brief's "keep the ADB session warm so recording starts at
second zero" concern mostly moot: at call time there is no ADB session to warm,
only a socket to a process that is already running.

**The honest cost:** a reboot kills the daemon, because processes do not survive
reboots. After every reboot the user needs Wi-Fi and Wireless debugging once to
restart it. That is a real limitation, and a much smaller one than needing Wi-Fi
during every call. Whether it can be softened - a boot-completed receiver that
re-bootstraps the moment Wi-Fi appears - is a milestone-7 question.

## The reboot, and the deadlock it creates

A reboot is survivable, but not for free, and the interesting part is what
breaks rather than what holds.

**What holds:** the pairing. After a full reboot the app reconnected with no new
code and reported `uid=2000(shell)`. adbd's trusted-key store lives on `/data`
and outlives the boot, so pairing really is a once-ever act.

**What breaks:** `adb_wifi_enabled` is reset to 0 by the reboot. That creates a
deadlock, because each thing needed to fix it needs the others:

- the app needs ADB to run shell commands,
- ADB needs Wireless debugging to be on,
- turning Wireless debugging on is itself a secure setting the app may not write.

Left there, the app would need the user to re-toggle Wireless debugging by hand
after every reboot, forever.

**The way out** is `WRITE_SECURE_SETTINGS`, granted once by `pm grant` over the
ADB session that already exists during setup. Its protection level is
`signature|privileged|development`, and the development flag matters: `pm grant`
can give it to an app that declares it, but no runtime permission dialog can, so
there is no way for a user to be talked into granting it to anything.

Measured, with Wireless debugging off and the app therefore holding no route to
shell at all:

```
diag[armadb] putInt accepted=true; adb_wifi_enabled is now 1
$ ss -ltn
LISTEN  *:43311                       <- adbd came back
$ tools/milestone1.sh connect
connected
PASS - shell session confirmed
```

The app turned its own transport back on and recovered a shell session with no
user action. Post-reboot recovery is therefore automatic, provided Wi-Fi is
available once to carry the bootstrap.

Also worth noting: `/data` is encrypted until the first unlock, so nothing can
run before the user unlocks the phone once. A boot-completed receiver has to
wait for `LOCKED_BOOT_COMPLETED` -> `BOOT_COMPLETED` accordingly.

## Incidental findings worth keeping

- **`settings put global adb_wifi_enabled 1` re-arms Wireless debugging from
  shell.** Useful for the pairing wizard and for test scripts; it does not
  survive a Wi-Fi drop by itself.
- **The connect port is ephemeral and changes on every adbd restart** (42321,
  34301, 35311 across this session). Discovery is mandatory, never a saved port.
- **`adb mdns services` on the host caches services after they disappear.** It
  handed the app a dead pairing port and produced an `ECONNREFUSED` that read
  exactly like a loopback fault and was not one. Device-side `ss -ltn` is the
  only ground truth.
- **The pairing dialog does not stay open long.** A force-stop, an app restart
  and a 15 s mDNS wait do not fit inside it. `tools/pair-now.sh` does the whole
  thing in about two seconds by reading the port from `ss`.
- **MagicOS has no `Settings$AdbWirelessSettingsActivity`;** developer options is
  `Settings$DevelopmentSettingsActivity`. Driving that UI from a script was
  attempted and abandoned, since pairing happens exactly once.
- **A long piped `shell:` command once returned `Stream closed`.** The legacy
  one-shot shell protocol merges stdout and stderr and gives no exit status;
  something more robust will be wanted before commands get complicated.
- **The javac + d8 pipeline works here** against platform 36 with build-tools
  36.0.0, which is the milestone-2 toolchain. `shellprobe/` is the rehearsal.

## Reproducing

Pairing is one-time, so only the first step needs a human.

```
tools/pair-now.sh 123456       # while the pairing dialog is open
tools/milestone1.sh connect
tools/milestone1.sh selftest
tools/milestone1.sh state wifi-on
```
