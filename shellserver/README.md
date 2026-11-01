# shellserver

The shell-side capture daemon: an audio-only fork of scrcpy v4.1 (Apache-2.0)
plus the files of our own in `src/com/jemcik/jemrec/shell`.

It runs as the **shell UID**, and that is the whole reason it is a separate
program rather than a class in the app. Capturing call audio needs
`CAPTURE_VOICE_COMMUNICATION_OUTPUT`, which `com.android.shell` holds and an
ordinary app cannot get. On top of that, the capture path calls `startActivity`
and `forceStopPackage` on `com.android.shell` itself to satisfy the "must be in
the foreground to record" check, which only a process already running as that
UID may do.

**23 files kept, 67 dropped.** See [PATCHES.md](PATCHES.md) for every change
against upstream and why each one exists.

## Build

```
./build.sh
```

`javac` against platform 36, then `d8`, into `jemrec-capture.jar`
(about 85 KB). `ANDROID_PLATFORM` and `BUILD_TOOLS` are overridable but default
to 36 and 36.0.0.

`ANDROID_PLATFORM` must match the platform the **device** runs, not the app's
`minSdk`. This code links `@hide` framework internals, so it has to compile
against the API level it will execute on. The app's `minSdk 31` is a different
decision that happens to be a different number on purpose.

## Run

```
adb push jemrec-capture.jar /data/local/tmp/jemrec-capture.jar
adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/jemrec-capture.jar \
    exec app_process / com.jemcik.jemrec.shell.Main" \
    > /data/local/tmp/jemrec-capture.out 2>&1 &'
```

`Main [port] [raw]` - port defaults to 28472, and passing `raw` sends
uncompressed PCM instead of Opus. A daemon started by hand like this has no
token (the app passes it in the environment), so it refuses every command but a
bare ping - see Protocol.

It then listens on `127.0.0.1:28472` and stays. Started once, it outlives the
ADB connection that launched it, which is the entire point: with Wi-Fi off adbd
tears its listener down and the shell UID cannot pin a fixed port, so there is
no ADB available at the moment a call arrives.

TCP on loopback specifically, because SELinux refuses an `untrusted_app`
connecting to the shell domain's abstract unix socket, and a filesystem socket
would have to live under `/data/local/tmp`, which the app cannot traverse.

## Protocol

Every connection but a bare ping (`P`, answered `PONG <version> <build>`)
starts with a handshake in both directions, proved with the token the app
passes in the environment (`A`; `Main.authenticate`), then one command byte.
The commands and their replies are in `Main.session`, and every reply that is
a line of text is listed in `tests/fixtures/protocol.txt`, which the app's
ProtocolTest checks against both.

The audio a capture (`R`) streams is scrcpy's format, unchanged:

```
4 bytes   codec id, ASCII ("opus" or "raw")
repeating:
  8 bytes pts, big endian, flags in the top 3 bits
  4 bytes payload length
  n bytes payload
```

One `R` connection is one capture, and there is no stop command for it: the
encoder writes into the socket, so the app closing its end is what ends it.
Calls are recorded by the daemon itself, into files the app fetches (`F`).

## Verifying a device

The app's Settings > Diagnostics > Self-test opens a real capture (`R`) over the
path a call takes and says what came out of it, both directions included.
`tools/capture-call.py` predates the handshake: it sends nothing, so the
daemon refuses it.
