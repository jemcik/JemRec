# The daemon watchdog

## The failure it exists for

The capture daemon is an orphaned shell process, and Android reclaims those.
Measured by leaving the app running overnight: after 13 hours the app's own
monitor service was still there, and the daemon was gone.

Nothing said so. The notification still read "JemRec is on". The next call
would simply not have been recorded.

That is the worst failure this app can have - not an error, but silence. A call
recorder that has quietly stopped working is worse than one that never worked,
because the user has stopped checking it.

## A ping, not a connect

Checking used to mean opening a socket to the daemon. That was wrong in a way
that only shows up once something polls: the daemon treats an incoming
connection as a **recording session**, so every health check span up an
AudioRecord on the voice-call source and tore it down again. A watchdog running
every five minutes would have been churning audio sessions all day.

So a connection now begins with a command byte - `P` to ping, `R` to record.
A ping also answers a better question than an open port does: something has to
be running the accept loop and able to reply. An open port would be satisfied
by a stale daemon from a previous app version, which is exactly the case that
bit next.

Since protocol 2 the bare `P` is the only command that needs no handshake.
Everything else follows a mutual challenge-response on the app's token (the
exchange is written out above `session()` in `Main.java`), so the port being
open to every process on the phone no longer means every process can fetch,
delete or start a recording. The ping's answer carries the protocol version,
and the app's health check is itself an authenticated ping: "running" means
*our* daemon, current, answering - an out-of-date one, or one left by a
previous install that does not know this token, is replaced over ADB rather
than trusted.

## Killing before starting

"Not answering a ping" and "not running" are different states, and the
difference matters precisely when repairing. An old daemon can still own port
28472 while failing the health check - after an app update, say. Starting a
fresh one then fails to bind, it exits, and the repair loops forever against a
port that is never free.

`ensureRunning` therefore kills any existing instance first, which makes
restarting idempotent.

## What recovery costs

Restarting the daemon needs ADB, which needs Wireless debugging, which needs
Wi-Fi. There is no way around that: with Wi-Fi off, adbd is not listening at
all. So the watchdog repairs **opportunistically** - whenever it next can - and
says UNAVAILABLE in the meantime rather than pretending.

It also re-arms Wireless debugging, since a reboot resets `adb_wifi_enabled` to
0. That write needs `WRITE_SECURE_SETTINGS`, granted once over ADB at setup.

Timings: a five-minute check while healthy (one loopback ping, so it can afford
to be frequent), and on failure a 30-second retry doubling to ten minutes.
Backing off matters because without Wi-Fi every attempt fails for the same
reason, and hammering it would burn battery to learn nothing.

## The notification tells the truth

| State | Title | Text |
|---|---|---|
| recording | Recording call | Saving this call |
| healthy | JemRec is on | Waiting for a call |
| recovering | JemRec cannot record | Restarting the recorder... |
| unavailable | JemRec cannot record | Connect to Wi-Fi to restart the recorder |

The title distinguishes "the app is running" from "a call would be recorded".
Those came apart overnight once, and nothing on screen showed it.

## Verified

Recovery, with the daemon killed under a running app:

```
watchdog: daemon is not answering, trying to restart it
monitor: daemon is RECOVERING
daemon: listening on 127.0.0.1:28472
watchdog: daemon restarted
monitor: daemon is HEALTHY
```

Honest failure, with Wi-Fi off and the daemon killed:

```
watchdog: re-armed Wireless debugging
autoConnect: no mDNS advertisement (expected with Wi-Fi off)
watchdog: no ADB session
watchdog: could not restart the daemon, retrying in 30s
monitor: daemon is UNAVAILABLE
```

## One thing this cannot fix

The watchdog needs the app's own process to be alive, since that is where it
runs. If Android reclaims the foreground service too, nothing is left to
notice. `START_STICKY` asks the system to bring the service back, which is the
best available answer but not a guarantee.
