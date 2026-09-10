# Working notes on libadb-android

Four things about this library cost real time to find. All were measured on the
target device; none are documented upstream, and three of them fail in ways that
point at the wrong culprit.

## 1. One stream per command kills the connection after exactly eight

The obvious way to run a command is `openStream("shell:<cmd>")`, read to EOF,
close. It works eight times.

```
 1..8   fine
 9      exec failed: Stream closed.
 10     exec failed: Stream closed.        ...and never recovers
```

Not a race, and retrying does not help. The cause is in `AdbStream.close()`:

```java
public void close() throws IOException {
    synchronized (this) {
        if (mIsClosed) return;     // <-- the whole problem
        notifyClose(false);
    }
    mAdbConnection.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
}
```

`mIsClosed` is exactly what the connection thread sets when adbd's `CLSE`
arrives. For any stream the remote closed first - which is every one-shot
command, because the command finishes and adbd tears the stream down - `close()`
takes that early return and **the reciprocal CLSE is never sent**. adbd is left
holding half-open sockets and stops granting new ones once it has enough.

The fix is not to fix `close()`, which cannot send a packet it has already
decided not to send. It is to stop opening streams: keep ONE and reuse it.

## 2. `shell:` gets a PTY, and a PTY echoes

A persistent shell needs clean pipes. A bare `shell:` makes adbd allocate a
PTY, which echoes back everything written to it and prints a prompt:

```
HNBKQ:/ $ id
uid=2000(shell) gid=2000(shell) ...
```

The echoed command is indistinguishable from a line the command itself printed,
and a sentinel line arrives wearing a prompt so it never matches on equality.

`exec:sh` is the better service - `exec:` is the non-PTY one - and it fixes the
stream exhaustion. On this device it still produces an echo, so the reader also
drops the echoed lines explicitly. That is safe because the echo is exactly
deterministic: two lines are written per command, so two echoed lines come back,
each ending with the text written, in order.

**Do not try to fix this from inside the shell.** `stty -echo; PS1=''` seems
obvious and desynchronises the session instead: its own output is echoed under
the old settings while the reader is already looking for the new behaviour.

## 3. `connect()` returns false for success

Its Javadoc says so, quietly: false means "the connection attempt is
unsuccessful, **or it has already been made**".

```java
if (isConnected()) {
    return false;
}
```

Treating that boolean as success/failure reports a perfectly good connection as
broken. `isConnected()` is what actually settles it, so every call site checks
`ok || mgr.isConnected`.

## 4. A dead connection yields empty output, not an error

`openStream()` on a manager whose connection has died does not throw. It returns
a stream that reads as empty, so every command "succeeds" with no output - which
for a recorder means a file full of nothing and no error anywhere. Checking
`isConnected` before each command turns that into a visible failure.

---

## Not a library problem: quoting reaches two shells

Worth writing down because it wasted a diagnosis. This looks right and is not:

```bash
adb shell am broadcast ... --es cmd "echo hello"
```

The local shell consumes the quotes, the DEVICE shell then splits on the space,
and the app receives `cmd="echo"`. Commands with no spaces work, so `id`
succeeds and everything else silently returns empty - which reads exactly like
a library bug. Quote for both:

```bash
adb shell am broadcast ... --es cmd "'echo hello'"
```
