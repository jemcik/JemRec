# shellserver

The shell-side capture daemon: an audio-only fork of scrcpy v4.1 (Apache-2.0)
plus one file of our own.

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
(about 24 KB). `ANDROID_PLATFORM` and `BUILD_TOOLS` are overridable but default
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
uncompressed PCM instead of Opus, for diagnostics.

It then listens on `127.0.0.1:28472` and stays. Started once, it outlives the
ADB connection that launched it, which is the entire point: with Wi-Fi off adbd
tears its listener down and the shell UID cannot pin a fixed port, so there is
no ADB available at the moment a call arrives.

TCP on loopback specifically, because SELinux refuses an `untrusted_app`
connecting to the shell domain's abstract unix socket, and a filesystem socket
would have to live under `/data/local/tmp`, which the app cannot traverse.

## Protocol

Unchanged from scrcpy, so its tooling still reads it.

```
4 bytes   codec id, ASCII ("opus" or "raw")
repeating:
  8 bytes pts, big endian, flags in the top 3 bits
  4 bytes payload length
  n bytes payload
```

One connection is one recording session. There is no stop command and none is
needed: the encoder writes into the socket, so the app closing its end is what
ends the session. That keeps the daemon free of any protocol that could get out
of sync with the app.

## Verifying a device

```
adb forward tcp:28472 tcp:28472
../tools/capture-call.py --seconds 25 --out call.wav
```

During a real call. It reports per-channel energy, because the question is
whether **both directions** are present, not merely whether audio arrived.
