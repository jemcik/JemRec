# Setting up JemRec

Everything happens on the phone. You will not need a computer, a cable, or a
second app.

Every step below was walked through on an Honor Magic 8 Pro running MagicOS 10,
and the screen names are what actually appear there. Other phones will differ in
wording; the shape of the process will not.

---

## Step 1 — Open JemRec and allow two permissions

1. Open **JemRec**.
2. It says **Step 1 — Allow two permissions**. Tap **Allow**.
3. Android asks twice. Allow both:
   - **Phone** — so the app can tell when a call starts and ends.
   - **Notifications** — so you can always see whether recording is working.

JemRec does **not** ask for microphone access and does not have it. The
recording is done by a separate, more privileged helper that the app starts
later; the app itself only writes the audio to a file.

---

## Step 2 — Turn on Developer options

If Developer options are already on, skip to step 3.

1. Open **Settings**.
2. Use the **search box** and look for `build number`. It normally lives under
   **About phone → Version**, but the path differs between phones, which is why
   searching is more reliable than hunting.
3. Tap **Build number** seven times.
4. Enter your PIN or pattern if asked.

You should see *"You are now a developer"*.

---

## Step 3 — Turn on Wireless debugging

1. In JemRec, tap **Open settings**. It takes you straight to **Developer
   options**.
2. Scroll down to **Wireless debugging**. On this phone it sits between
   *Revoke USB debugging authorizations* and *Always prompt when connecting to
   USB*, roughly a third of the way down.
3. **Tap it** — it is a sub-screen, not a switch in the list.
4. Turn the **Wireless debugging** toggle on and accept the warning.

Your phone must be on **Wi-Fi**, or the toggle will not stay on.

You should now see **Device name**, **IP address & Port**, and two blue links:
*Pair device with QR code* and *Pair device with pairing code*.

> **If you see an old "JemRec" under PAIRED DEVICES**, tap the gear beside it and
> forget it. It is left over from a previous install and is not usable — a
> reinstalled app has a new key and must pair again. Leaving it there only
> causes confusion.

---

## Step 4 — Pair, from your notifications

**Read this step before starting it.** The obvious approach does not work, and
knowing why saves repeating it.

The pairing dialog exists only while Settings is on screen. Leave Settings — by
switching apps or pressing Back — and Android tears the dialog down instantly,
along with the code. Measured: opening the dialog starts a service on a port,
and switching to JemRec removes it within seconds.

The notification shade is different. It is part of the system, not another app,
so pulling it down leaves Settings running and the dialog intact. That is where
you type the code.

JemRec posts that notification as soon as pairing becomes the step, so the
field is already waiting before you go anywhere.

1. In JemRec, tap **Open settings**.
2. Tap **Wireless debugging**, then **Pair device with pairing code**. A
   six-digit code appears.
3. **Swipe down the notification shade** over that dialog.
4. Find **JemRec — pairing** and tap **Enter code**.
5. Type the six digits and send.

The dialog stays open the whole time, so nothing expires while you type.

You do not need the port number. JemRec finds that by itself.

> The code changes every time you reopen the dialog, so always use the one
> currently on screen. If pairing fails, reopen the dialog and use the new code.

> If you swiped the notification away, tap **Show the code field again** in
> JemRec to bring it back.

---

## Step 5 — Wait a few seconds

JemRec now does the rest by itself:

- grants itself the one privileged permission it needs,
- starts the recorder,
- begins watching for calls.

When it finishes you will see **Ready to record**, and a permanent notification
saying **JemRec is on**.

That notification is deliberate. The app has to stay running to catch a call,
and something that records phone calls should be visibly running.

You can swipe it away — Android has allowed that for foreground services since
Android 13, and the app keeps running when you do. It comes back the next time
the service posts it, which is whenever it restarts or changes state. If what
you want is for JemRec to stop, use the switch in the app's header; that is the
only thing that actually stops it.

---

## Step 6 — Where recordings go (optional)

Nothing to do here. Recordings go to the phone's standard **Recordings/JemRec**
folder, which any file manager or music app can open, and which survives
uninstalling the app.

To use a different folder instead, tap the gear and then **Change** under
Saving to.

---

## Step 7 — Test it

Call someone and talk for twenty seconds. Both of you should speak.

- During the call the notification changes to **Recording call**.
- After you hang up, open JemRec. The recording appears under **Recordings**.

Files are named like `20260907_150909_out.ogg` — date, time, and `in` or `out`
for the direction. They are ordinary Ogg Opus files that any player can open.

## Deleting recordings

Tap **Select** above the list, or long-press any recording. Tick the ones you
want, or use the box in the toolbar to take all of them, then tap the bin.
Back, or the ✕, leaves without deleting anything.

Deleting is permanent, so it asks first and tells you how many. Recordings are
kept when JemRec is uninstalled, because they are yours rather than the app's —
so if you want them gone, do it here **before** you uninstall.

Tap a recording to play it inside JemRec, with a scrubber to move around it.
The share button sends it elsewhere, and the bin deletes it after asking. Only
the newest twenty are listed at first; if you have more, the list says how many
older ones there are and offers to show them.

---

## Turning JemRec off

The switch in the header, next to the gear, is the on/off control for the whole
app. Off stops the call monitor and the recorder, and the permanent
notification goes with them. Your recordings and your pairing are untouched.

Turning it back on has to start the recorder again, and starting it needs a
moment of Wi-Fi. Away from Wi-Fi the header will say it cannot record, and it
will fix itself when you are next on Wi-Fi — the same as after a restart.

While it is starting you will briefly see Android's own **Wireless debugging
connected** banner. That is the system's notification, not JemRec's, and no app
can dismiss another app's. It appears because the recorder is being started,
and it goes away a few seconds later when JemRec turns Wireless debugging back
off — it is only needed for those few seconds.

---

## Choosing when to record

**Record every call**, under the gear, decides this.

**On** is the default. Every call is recorded and you are not asked.

**Off** means JemRec asks. When a call starts, a notification appears with
**Record** and **Not now** buttons. Nothing is captured until you tap Record, so
the opening seconds of that call are not saved — that is the cost of being
asked, and it is why the switch is on by default.

The banner stays up for around a minute rather than the few seconds an
ordinary notification gets, and if you miss it the notification is still in the
shade for the rest of the call. On a locked screen you get the same question as
a full screen instead.

---

## Settings

The gear in the top right holds the things you set once and forget.

- **Saving to** — which folder recordings go in, and a Change button if you
  want a different one.
- **Start fresh** — puts JemRec back to how it was on the day you installed it.
  It hands back the two permissions you granted, turns Wireless debugging off,
  forgets the pairing and stops the recorder, so setup runs again from step 1.
  The app closes when you confirm; open it again to set it up. Useful if
  pairing has gone wrong, or before handing the phone to someone else. **Your
  recordings are not deleted.**
- **Show who called** — labels each recording with the contact's name and
  photo, or the number when they are not in your contacts. Optional, and asked
  for in the app rather than at install: it needs permission to read your call
  log for the name and your contacts for the photo. Say no and everything still
  works, with the time as each recording's headline.
- **Show diagnostics** — whether the recorder is running, a self-test that
  proves a call would be recorded, a way to restart the recorder, and the log.
  It is what a bug report wants.

---

## Uninstalling

Uninstall it however you like: long-press the icon, or Settings → Apps. There
is nothing special to do.

The recorder is a separate system process rather than part of the app, so
removing the package does not stop it by itself. It checks every few minutes
whether JemRec is still installed, and when it is not it turns Wireless
debugging off, deletes its own files and exits. A reinstall is never fooled by
one that has not got there yet either: the app only trusts a recorder that this
install has the pairing key for, and retires any other on sight.

> **What no app can undo.** The phone goes on trusting the key it was paired
> with, in a list only root can edit, so it stays listed under Wireless
> debugging → Paired devices; you can remove it there with the gear beside it.
> Developer options stays switched on if you turned it on. And Start fresh
> leaves the one privileged permission granted, because handing that back would
> kill the app halfway through the reset.

---

## What to expect afterwards

**It keeps working with Wi-Fi off.** Setup needs Wi-Fi; recording does not.
Calls on mobile data are recorded normally.

**After a restart, open JemRec once.** A reboot stops the recorder, and it needs
a moment of Wi-Fi to start again. The app tries on its own, but opening it once
is the reliable way.

**Watch the notification.** It is the honest indicator:

| It says | Meaning |
|---|---|
| JemRec is on | A call would be recorded |
| Recording call | Recording right now |
| JemRec cannot record | Something is wrong — connect to Wi-Fi and open the app |

---

## If something goes wrong

**Wireless debugging keeps turning itself off.** It does that when Wi-Fi drops
and after every reboot. Turn it on again with Wi-Fi connected.

**Pairing fails every time.** Almost always the dialog closed. It survives only
while Settings is on screen, which is why step 4 uses the notification shade
rather than switching to the app. Get a fresh code each attempt.

**"JemRec cannot record".** Connect to Wi-Fi, open the app, tap **Check**. It
needs a working Wi-Fi connection for a few seconds to restart its recorder.

**Recordings have only your voice.** This is the one thing no app can fix. It
means your phone's audio hardware does not expose the other person's side to
apps at all.

---

## Two honest warnings

**The other person is being recorded too.** Whether you may do that without
telling them differs by country and sometimes by region. That is your call to
make, not the app's.

**This uses Wireless debugging, which stays on.** It is a developer feature that
lets software on your phone act with elevated privileges. JemRec needs it to
record calls at all. If you stop using JemRec, turn Wireless debugging and
Developer options back off.
