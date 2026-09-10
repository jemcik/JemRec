# Milestone 4 - call detection

The brief left one question open here: `BroadcastReceiver` on `PHONE_STATE`
versus an `InCallService`, weighing better call information against the cost of
taking the dialer role.

**On MagicOS the answer is neither.** A manifest receiver cannot be relied on,
and the reason is not a bug in this app.

## What the device actually does with PHONE_STATE

This device enqueues **four** variants of `android.intent.action.PHONE_STATE`,
each with different required permissions. Read from
`dumpsys activity broadcasts` after real calls:

| Required permissions | Our receiver | Reason given by the system |
|---|---|---|
| `READ_PRIVILEGED_PHONE_STATE` | SKIPPED | Permission Denial - system only |
| `READ_PHONE_STATE` + `READ_CALL_LOG` | SKIPPED | Permission Denial - we hold neither |
| `READ_PHONE_STATE` | SKIPPED | **`iaware pevent send broadcast`** |
| `READ_PHONE_STATE` | DELIVERED | `remote app` |

The last two are the interesting pair, and which of them appears depends
entirely on whether the app's process is already running.

**Process alive:** the fourth variant is delivered, reason `remote app`, and the
receiver runs.

**Process dead:** that variant does not appear at all. The only one left is the
third, and Honor's iAware framework skips it with `iaware pevent send
broadcast`. Nothing is delivered, nothing starts, no recording happens.

The second measurement is the one that matters, because "process dead" is the
normal state of a call recorder that is waiting for a call.

### One test that proved nothing, and why

An earlier attempt used `am force-stop` to simulate the app not running. That is
not the same state: a force-stopped package is deliberately excluded from **all**
broadcasts until the user launches it again, and our receiver did not appear in
the broadcast records at all. `am kill` is the right tool - it kills the process
without setting the stopped flag, which is what an app looks like after the
system has reclaimed it.

## The consequence: a permanent foreground service

Since the app cannot be woken for a call, it has to be awake already.
`CallMonitorService` runs permanently, and takes call state from
`TelephonyCallback` rather than a broadcast, so iAware has no queue to
interfere with. Confirmed working the moment it registers:

```
monitor: start requested
monitor: watching call state
monitor: idle
```

Being permanently foreground also disposes of a second problem that would have
blocked the receiver design anyway: **Android 12 forbids starting a foreground
service from the background.** Even on the one occasion the receiver did run, it
could not have started one. A service that is already foreground has nothing to
start.

The cost is a permanent notification. For an app that records phone calls that
is not really a cost - it should be visibly running - but it is a genuine change
from what the brief assumed.

`BootReceiver` brings the monitor back after a reboot. `BOOT_COMPLETED` is one
of the few broadcasts still delivered to a not-running app, and one of the few
cases where starting a foreground service from the background is permitted.
`LOCKED_BOOT_COMPLETED` is handled and deliberately does nothing but log: it
arrives before first unlock while `/data` is still encrypted, and the app's ADB
identity lives there.

## Permissions, and one deliberate absence

`READ_PHONE_STATE` is held. `READ_CALL_LOG` is **not**, which is why one of the
four broadcasts skips us - it is the variant carrying the phone number. That is
an acceptable trade: the number would only ever improve a filename, and
`READ_CALL_LOG` is a sensitive permission that a recorder asking for it looks
much worse for.

`RECORD_AUDIO` is not held either, and the foreground service type is
`specialUse` rather than `microphone`. This process never opens an audio device
- the shell-UID daemon does - so the microphone type would mean holding a
permission the app has no use for in order to describe work it does not do.

## Verified end to end

A real call, with the app backgrounded and the monitor running:

```
02:50:19   off-hook          -> call-20260907-025019-out.jemrec created
02:50:37   notification re-posted (monitor switched to recording)
02:50:48   call ended
```

280,996 bytes captured over roughly 29 seconds - about 77 kbps, which is the
right order of magnitude for Opus carrying speech and silence at a nominal 128.
The file begins with the codec's own header, so it is real audio and not a
stuck stream:

```
4f 70 75 73 48 65 61 64   "OpusHead"
```

Recording therefore starts by itself when a call begins. No user action, no
foreground app.

### The bug this test found

The recording did not STOP. The file was still growing after the call ended,
filling with Opus silence frames, because `Job.cancel()` cannot interrupt a
thread blocked in a socket read - and the recording loop is exactly that.

The fix is to close the socket, which is the only thing that unblocks the read,
and to do it in a specific order: clear the stream reference first so the
coroutine can tell a deliberate close from a genuine failure, then close, then
cancel. Closing our end is also how the daemon learns the session is over, so
there is still no stop command to get out of sync with.

**The stop fix is not yet confirmed on a live call.** The next real call will
show it: a recording that ends when the call does, and a
`monitor: recording ended` line.
