# shellprobe

A throwaway experiment, kept because its result changed the architecture.

It answers one question: **which IPC channel may the app use to reach a
shell-privileged process, once ADB is out of the picture?**

That question only exists because ADB cannot be the runtime transport. With
Wi-Fi off, adbd's listener is torn down, and the shell UID is not permitted to
set `service.adb.tcp.port` to pin a stable one. So ADB can only bootstrap a
long-lived process, and something else has to carry the audio.

Result, measured on the target device:

```
FAIL abstract:jemrec_probe -> IOException: Permission denied
OK   tcp:127.0.0.1:28471   -> hello over tcp from uid 2000
```

SELinux refuses `untrusted_app` connecting to the shell domain's abstract unix
socket. TCP on loopback is allowed, and keeps working with every radio off.

It is also the rehearsal for milestone 2's toolchain - `javac` against platform
36, then `d8`, then run under `app_process` - on a file small enough that a
failure is obviously the pipeline and not the code.

## Build and run

```
SDK=$HOME/Library/Android/sdk
javac --release 17 -cp $SDK/platforms/android-36/android.jar -d build src/SocketProbe.java
$SDK/build-tools/36.0.0/d8 --lib $SDK/platforms/android-36/android.jar \
    --min-api 31 --output build build/SocketProbe*.class
(cd build && zip -q ../probe.jar classes.dex)
adb push probe.jar /data/local/tmp/jemrec-probe.jar
adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/jemrec-probe.jar exec app_process / SocketProbe" \
    > /data/local/tmp/jemrec-probe.out 2>&1 &'
```

Then, from the app's debug receiver:

```
adb shell am broadcast -a com.jemcik.jemrec.DIAG \
    -n com.jemcik.jemrec/.diag.SpikeReceiver --es token <t> --es op probe
```
