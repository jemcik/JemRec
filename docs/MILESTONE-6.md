# Milestone 6 - the setup wizard

Setup is two things the user does by hand. Turn on Wireless debugging, and type
a six-digit pairing code. Everything else is automatic, and **no computer is
involved at any point**.

## The finding that made that possible

The app can grant **itself** `WRITE_SECURE_SETTINGS`, by running `pm grant` over
its own ADB session.

That permission is what breaks the post-reboot deadlock (docs/MILESTONE-4.md),
and the obvious assumption is that it needs a PC, since `pm grant` is
traditionally something you type into a terminal. It does not. Once paired, the
app holds a shell at uid 2000, and that shell can grant development-protection
permissions to anything - including the app it belongs to.

Measured rather than assumed: the permission was revoked, the app was asked to
run `pm grant`, and it came back `granted=true`.

```
autoConnect: mDNS found connect port 36793
setup: grant -> granted
daemon: already running
```

## What the user is not asked

**The pairing port.** It changes every time the pairing dialog is opened and
means nothing to anyone, so it is discovered over mDNS. The old diagnostic
console had a "Find port" button and a numeric field for it, which is a fine
thing to show a developer and a terrible thing to show anyone else.

**Anything about ADB, uids, or sockets.** All of that moved behind "Show
diagnostics", hidden rather than deleted - ports, uids and raw shell output are
exactly what a bug report needs and exactly what a normal screen should never
have on it.

## The screens

`SetupScreen` shows one step at a time, derived from what is actually true
rather than from a stored "onboarding done" flag:

| Step | Shown when |
|---|---|
| NEEDS_WIRELESS_DEBUGGING | no ADB session and `adb_wifi_enabled` is 0 |
| NEEDS_PAIRING | no ADB session but Wireless debugging is on |
| FINISHING | paired; granting the permission and starting the recorder |
| READY | a call would be recorded right now |

Deriving it matters. `currentStep` tries to connect before deciding, because a
previous pairing is remembered by adbd and needs no code - so a fresh process
must not mistake "already set up" for "needs pairing".

`HomeScreen` answers one question: **would a call be recorded right now?** Not
"is the app running". Those two came apart in testing, with the app alive for 13
hours while the recorder had been reclaimed, and nothing on screen to show it.

## Verified end to end

Simulating an unconfigured phone - permission revoked, Wireless debugging off:

1. The app showed **Step 1 - Turn on Wireless debugging**.
2. Wireless debugging was switched on, "I have done it" tapped.
3. The app reconnected over mDNS, granted itself the permission, confirmed the
   recorder, and landed on **Ready to record**.

No pairing code was needed for that run, which is itself the point: the pairing
survives, so setup is genuinely once-ever.
