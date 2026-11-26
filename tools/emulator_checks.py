#!/usr/bin/env python3
"""Release checks on an Android emulator: real call handling, end to end.

    python3 tools/emulator_checks.py --avds jemrec-api33,jemrec-api36 [--apk PATH]
                                     [--only a,b] [--no-retry]
    python3 tools/emulator_checks.py [--serial emulator-5554] [--apk PATH]
                                     [--only a,b] [--no-retry]

--avds boots each AVD named from nothing (EMULATOR_FLAGS: -no-snapshot, 4 GB,
no boot animation, no cameras, no window, no crash-report question), with --wipe its data
wiped too, two at a time,
checks it, shuts it down, and ends with a line for each; --serial checks an
emulator already up.

Builds and installs the shrunk build (./gradlew :app:assembleShrunk, which
redoes only what changed; --apk installs another instead) - the debug build
through R8 with the release's rules, not debuggable, so what runs here is
what R8 makes for a release (issue #69: the debug build is not shrunk, and a
constructor R8 removed failed every one-time work in the release without one
check seeing it) - sets JemRec up from nothing, and runs simulated calls
through the real app and daemon, checking what each one leaves behind:

  ringing     an outgoing call is saved from its answer to its end, exactly
  ringing-gap  the same when nothing is captured until the call is
              answered, as over Wi-Fi Calling on a Pixel (issue #40)
  unanswered  an outgoing call nobody answered is not saved
  incoming    an incoming call is saved from its answer to its end, exactly
  rules       with Other numbers off, a stranger's call is never recorded
  succession  a contact's call that rings inside the daemon's end check of a
              stranger's call is kept, and is incoming (the 0.14.1 daemon fix)
  caller-id   a number in no contact is named as the call log names it
              (issue #30)
  number-names  with the phone number in file names, a contact dialled
              without the country code is named by the number with it, not
              by the contact (issue #71); and a call whose row in the call
              log says it was made in Ukraine is named in Ukraine's code,
              though the emulator's network says US
  self-test   PASS, with exact call tracking on
  answering   the answering machine answers a stranger after its delay, plays
              the greeting, and saves the message marked as one, with the
              microphone given back at the end
  greeting-hangup  a caller who hangs up during the greeting: only what they
              said over it is kept (on a phone, silence leaves nothing)
  message-recorder-lost  the recorder killed while the machine takes a
              message: the call's own service hangs up that call, its file
              is saved as a message, the recorder started again gives back
              its mute
  answer-rules  with Other numbers off, a stranger's call rings on unanswered
  answer-during-voip  a phone call ringing over a messenger or meeting call
              rings on unanswered (a stand-in call from shellserver/probe,
              built here if need be)
  answer-after-emergency  a day after a call to an emergency number - the
              pause set as the call tracker sets it, no such number dialled -
              a caller rings on unanswered
  answer-takeover-ask-first  in Ask first, taking over a call the machine
              answered keeps what was said as the message, and asks before
              recording the rest
  test-call-wifi  the answering machine's test call, armed as the screen
              arms it, answered with the check tone and the greeting, and
              Telecom's list read mid-greeting for the call's Wi-Fi mark
  greeting-moves  the call moving under its greeting, as a handover to
              Wi-Fi takes communication mode: from Android 14 the greeting
              stops and plays again on each path; on 13 a cut, played again;
              on 12 it plays on; never a hang-up
  app-call    another app's call through Telecom, as Google Meet's are (a
              stand-in from the debug build) is not taken for a phone call; a
              contact's phone call rung in over it is recorded, exactly, and
              ends where it is hung up though the app's call goes on; and the
              app's call, in the call log, is not counted as a missed recording
  mode-switch  a phone call whose audio mode turns "communication" in the
              middle (the stand-in call takes it, playing a tone) is
              recorded through the mixer from then, the tone in its copy, and
              through the call capture again once the mode is back; it is
              saved exactly, and Diagnostics' Copy a report says each way.
              Before Android 14, where there is no copy, it stays on the
              call capture
  app-call-in-front  the stand-in app call placed over a phone call being
              recorded, as a Meet call taken with the phone call on hold:
              its "communication" mode keeps the phone call on the call
              capture, so the app's call is not recorded with it
  messenger-recorder-lost  the recorder ended in the middle of a messenger
              call (a stand-in app named as Telegram's telegram.org build,
              built here with javac and d8, holding a call as a messenger
              does) and brought back by the call's own watch: both parts
              saved as one call, told once; and a call ended while its
              recorder is down ended from the audio mode (1.5 s out), what
              the recorder left saved as a part (Android 14 and later)
  waveform    a call's 800 levels are worked out and kept as it is saved,
              so its row has its waveform the first time it is drawn
  quality     three Quality choices, each the rate the recorder starts the
              next call at, and a new recorder taking the choice from its
              environment
  background-playback  a recording played from its row goes on with the
              screen off, called only "Call recording" while the phone is
              locked and by its caller again once unlocked; a call ringing
              in holds it back, and it plays on once the call is over
  both-ears   a recording playing, switched by its setting from both sides
              in both ears to each in its own ear and back, opens again
              each time from where it was; and its sound stalled 0.8 s
              (the app stopped), the playhead falls behind the clock by the
              stall, not running on ahead of the sound
  raise-to-listen  a recording playing from its row moves to the earpiece
              as the proximity sensor reads near - voice-call audio, the
              screen covered and its lock held, the audio mode left normal
              (the recorder's rule for a messenger call needs it changed) -
              and back to the loudspeaker, playing
              on, as it reads far; the player's button moves it too, and
              with Earpiece in Settings a recording opens there (the debug
              build acts as if the emulator had an earpiece); each move
              opens it again within 50 ms of where the sound was - raised,
              lowered, or paused, moved and played on
  one-window  JemRec is one window by its own ways in and the tile's long
              press; a folder picker is kept by the launcher and finished by
              JemRec's way in
  process-death  the app's process ended in the background (`am kill`):
              JemRec comes back on the screen and the home tab it was left on
  recently-deleted  a recording deleted is brought back by Restore all in
              Recently deleted; deleted again, Delete all for good removes
              it, from Android's own trash as well, its file with it
  recently-deleted-folder  the same in a folder picked in Settings through
              Android's own picker, where a deleted recording waits renamed
              in its folder (issue #51: Android 17 reports that file under
              its old name); and one there whose 30 days are up is removed
              by the daily pass
  name-taken  a name already taken, in Recordings/JemRec and in a picked
              folder: Android's provider makes another and the save says
              which; the same name trashed in both has a row each, Restore
              all brings back each, Delete all for good leaves neither
  looks       the app, started again in Deck (dark), Multitrack and Memo,
              opens in each, its own header drawn
  reinstall   a message, kept from its row, is a kept message again once the
              app is uninstalled and installed again and the empty list's
              Allow is answered in Android's own dialog: the marks are read
              back from the recording's own tags
  cable       a USB cable in or out wakes the app from the daemon; a recorder
              that lives through it is left alone, and one killed meanwhile
              (as unplugging an Honor from a computer does, three seconds
              later) is started again, with Wireless debugging switched off
              and on - the port is the emulator's own simulated one
  usb-debugging  USB debugging switched off, as an Honor's Settings app does
              whenever Developer options is shown, wakes the app, which
              switches it back on and has the recorder answering again
  hide-developer-options  When JemRec is off set to Hide Developer options:
              off takes Developer options and Wireless debugging off and keeps
              the recorder; on puts them back
  debugging-off  set to Turn all debugging off: off has the recorder switch
              Wireless and USB debugging off and go, adb with it; on puts all
              of it back and starts the recorder again
  off-just-left  Off from the background, JemRec 20 s off the screen and
              no call up: the process lives (the call service is not sent
              to the foreground, which Android refused)
  adbd-stopped  a revive that finds adbd stopped with both debugging switches
              on, as an Honor's restart left it, switches Wireless debugging
              off and on and has the recorder back
  allow-this-wifi  on a Wi-Fi Android has not allowed, the header's tap puts
              Android's question up, and after Allow the recorder is back
  refused-grant  setup's last step on a phone that refuses its grant, as an
              OPPO, OnePlus or realme does with Disable system optimization
              off (faked, issues #61, #64): it is told that switch and is not
              set up; with no ADB session it is told that, not sent to a
              switch; Try again without the fake grants, read back from the
              package's own record
  listener-grant  notification access for the app's listener, taken away
              and granted again by the recorder with the app's ADB session
              closed, as Show who called and Hide the debugging
              notification ask for it
  keep-paired  Keep JemRec paired turns Android's pairing timeout off,
              keeps the old value in the recorder's own file and puts it
              back from there; a timeout off by hand is left alone
  pairing-card  a pairing code that is not six digits, twenty times running:
              the pairing card ends on what was wrong, with the field for
              the next code, and not on "Pairing…"
  start-without-wifi  on Wi-Fi, a revive has adbd open its classic port,
              5555; with Wi-Fi off, Turn all debugging off and back on has
              the recorder started over that port; switched off, the port is
              closed again on Wi-Fi - or left open where that revive came
              over the port itself; and first, on a Wi-Fi Android has not
              allowed, no asking again after Cancel
  telegram-builds  Telegram from telegram.org and Telegram Beta - stand-in
              apps of those package names, built here - are visible to JemRec
              (Android's own list of what each app may see: the manifest's
              <queries>, issue #93), and from Android 14 Settings' Messenger
              calls lists them as Telegram (telegram.org) and Telegram Beta
  newest-version  Settings' About shows the installed version, as the
              package says it, and Newest version hands GitHub's newest
              release to the browser (discussion #91)
  opus-valid  a call's recording is a valid Ogg Opus stream by RFC 7845 (its
              page positions and last-page mark), and so is every recording
              in the folder after the pass over ones saved before 1.2
  ask-button  Ask first's button over a call, "display over other apps"
              allowed by the recorder: dragged and kept there, Record records
              the call; its cross is Not now, after which Record on the call's
              own notification still records; shown over a call answered on a
              PIN-locked phone, not while it rings nor on the lock screen
              itself. With the permission taken back the question is a banner,
              which the power key makes a window over the call's screen: a
              tap outside its dialog gives the call's screen back, unanswered
  notifications-off  notifications taken back after setup leave home and the
              recordings there with a notice; in Ask first the header says
              no call can be recorded (Android 13 and later); full-screen
              notifications taken away are said in Ask first, Turn on
              opening their page (Android 14 and later)
  folder-lost  a picked folder deleted: the next call is saved in the standard
              folder, a notification says so, and home says the folder is lost
  stopped-early  the recorder ended mid-call: brought back while the call is
              up by the call's own watch, said in the call's notification,
              both parts saved and neither said to have stopped early;
              brought back during an incoming call, joining it, both parts
              saved incoming; lost unnoticed just before a hang-up and
              a redial a second after the call's mode goes normal - two calls, the first's
              file a part; and with
              Wi-Fi off, not brought back - the call's end read from the
              audio mode at once, its notification gone after the call, and
              the recording said to have stopped early
  killed-mid-save  the app's process killed while it saves a call - a second
              into the transfer, written but not published, published but
              the recorder's copy not yet deleted (held there by the debug
              build), and ending itself straight after the save journal's
              writes - in the standard folder and in a picked one: the
              recorder keeps its copy, nothing half written shows in the
              standard folder, and opening JemRec saves the call whole,
              once, under its own name, nothing left pending
  early-end   the recorder's encoder ended mid-call, its write failing on a
              full phone, with exact call tracking off: the call's
              notification says so during the call, the recorder names the
              file with what it missed, and the app says it stopped early
              from that alone; a message's recording ended the same way is
              hung up on at once
  doze        the phone put in Doze by Android's own test command: a call is
              recorded and saved at once, and the recorder ended, a kick
              brings it back
  battery-restricted  on battery, JemRec's uid idle: a call is recorded
              and saved at once with Android's Battery set to Restricted
              for JemRec and in the restricted standby bucket, each with
              exact call tracking on and off - but for Restricted with
              tracking off, where Android refuses the recorder's wake and
              the call is saved once JemRec opens, home and a notification
              saying why; in Ask first there nothing is asked or recorded,
              the header saying so; under Restricted the keep-alive's jobs
              held, as JobScheduler says; in the restricted bucket a kick
              brings an ended recorder back
  audio-server-restart  the audio server ended mid-call, and the recording
              goes on: Android's AudioRecord restores itself, or the
              recorder makes its capture anew
  stall-told  the audio server held down mid-call, no read failing: the
              call's notification says the recording stopped, and that it
              goes on once the server does
  background  the daemon's watch for this phone stopping JemRec in the
              background runs and reads the device's own exit records; from
              Android 15, a Force stop's open is read from the app's own
              start record and not taken for the phone's doing
  wifi-back   Wi-Fi coming back starts the recorder within seconds, though
              a kick of the keep-alive waits out a retry a minute or more off

Every call is the emulator's own simulation - nothing leaves the machine -
and the script refuses to touch anything that is not an emulator. It needs a
google_apis image: root, through its su, to read the app's files (the shrunk
build is not debuggable, so run-as refuses), and adb root for the caller-id
check. Run it on each Android
version a release claims; see docs/DEVELOPMENT.md, Tests.

A check that fails is run again, with the others that failed, on the same
emulator shut down and booted again from nothing, as --avds boots it: the
emulator's own call network, Wi-Fi and timing give way now and then under
load, with nothing wrong in JemRec. It counts as failed only if it fails both
times, and the first failure stays in the output - but one during which JemRec
crashed or stopped responding fails the run whatever its retry does (CRASHED):
a retry is for the emulator's bad moments, not the app's. Setting JemRec up, before
any check, is tried once more the same way. An emulator that ends - crashed,
or ended by its own hang detector - stops the pass: the check under way and
those left run on the fresh boot, and the Mac's crash report is named (GONE,
LOST). --no-retry reports it as it is,
for working on a check. Every check's first try and retry is kept across runs
(HISTORY, ~/.cache/jemrec/emulator-checks.tsv, with the tree it ran on), and a
check that passed only on its retry in 2 of the last 3 runs on an Android
version fails the run: the retry is for the emulator's bad moments, not to
keep a flaky check - or a bug - out of sight.

Exit status: 0 if every check passed.
"""
import argparse
import atexit
import contextlib
import datetime
import fcntl
import hashlib
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from xml.etree import ElementTree

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "com.jemcik.jemrec"
APP_DIR = f"/data/data/{PKG}"
PREFS = "shared_prefs/jemrec.xml"
CONTACT = "5557777"      # made a contact here, as "TestKept"
STRANGER_IN = "5554321"  # rings in; no contact
STRANGER_OUT = "5551234"  # dialled; no contact
CALLER_ID = ("3034997111", "NIST Radio Station WWV")  # issue #30's call
# A contact, made here as "TestNumbered": a number Android takes for a valid US
# one (libphonenumber's own example), so it can be given its +1.
NUMBERED = ("2015550123", "TestNumbered")
UA_DIALLED = "0501234567"  # a Ukrainian mobile number, as dialled there
USB_GUARD = "jemrec_usb_guard"  # the snapshot usb_debugging comes back to

serial = "emulator-5554"


def adb(*args, check=False, text=True, input=None, timeout=120):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=text, input=input, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")
    return r.stdout if text else r.stdout


def sh(command):
    return adb("shell", command)


def wait_for(predicate, timeout, what, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        value = predicate()
        if value:
            return value
        time.sleep(step)
    raise TimeoutError(what)


def log():
    return adb("logcat", "-d", "-s", "JemRec:V", "scrcpy:V")


def wait_log(pattern, timeout, what):
    return wait_for(lambda: re.search(pattern, log()), timeout, what)


# --- refusing anything but an emulator ------------------------------------------

def only_an_emulator():
    qemu = sh("getprop ro.kernel.qemu").strip() or sh("getprop ro.boot.qemu").strip()
    if not serial.startswith("emulator-") or qemu != "1":
        sys.exit(f"{serial} is not an emulator - these checks place calls; refusing.")


# Every wait capped, as adb's are: a build that hangs would otherwise hold
# the build lock, and stall each run waiting on it, for good.
GRADLE_TIMEOUT_S = 30 * 60  # a cold R8 build with downloads takes minutes
PROBE_TIMEOUT_S = 5 * 60
LOCK_WAIT_S = GRADLE_TIMEOUT_S + 10 * 60  # longer than any one holder may take


@contextlib.contextmanager
def build_lock():
    """Held while a run builds into the tree - the shrunk APK, the stand-in
    call's jar - or copies or pushes what it built. Two runs started
    together, one per AVD, would otherwise build into the same outputs at
    once, or remove a jar the other is pushing: build-probe.sh starts by
    deleting its output. The second waits, and finds the work done."""
    os.makedirs(os.path.join(ROOT, "app", "build"), exist_ok=True)
    path = os.path.join(ROOT, "app", "build", "emulator_checks.lock")
    with open(path, "w") as lock:
        deadline, told = time.time() + LOCK_WAIT_S, False
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.time() > deadline:
                    raise TimeoutError(f"waited {LOCK_WAIT_S // 60} min for {path}: "
                                       "another run is building, or stuck holding it")
                if not told:
                    print("waiting for another run's build (the build lock)")
                    told = True
                time.sleep(1)
        yield


# --- setting JemRec up -------------------------------------------------------------

def set_prefs(**values):
    """Set preferences through the app itself (the debug build's diag 'pref'):
    a file written from outside loses to a running app process, which writes
    its own copy back over it."""
    for name, value in values.items():
        if isinstance(value, bool):
            said = diag("pref", "--es", "key", name, "--ez", "bool", str(value).lower())
        elif isinstance(value, int) and -2**31 <= value < 2**31:
            said = diag("pref", "--es", "key", name, "--ei", "int", str(value))
        elif isinstance(value, int):
            said = diag("pref", "--es", "key", name, "--el", "long", str(value))
        elif value is None:
            said = diag("pref", "--es", "key", name)
            assert f"{name} removed" in said, said
            continue
        elif isinstance(value, (set, list, tuple)):
            said = diag("pref", "--es", "key", name, "--es", "set", ",".join(sorted(value)))
        else:
            said = diag("pref", "--es", "key", name, "--es", "value", str(value))
        assert f"{name} = " in said, said


def diag(op, *extra, wait=60):
    token = diag.token
    sh("logcat -c")
    adb("shell", "am", "broadcast", "-n", f"{PKG}/.diag.SpikeReceiver", "--es", "token", token, "--es", "op", op, *extra)
    wait_log(rf"diag\[{op}\] <<<", wait, f"diag {op}")
    return "\n".join(l for l in log().splitlines() if f"diag[{op}]" in l)


# How long On gets to come back with adb away (TurnOnLater). Its 30 s is
# JobScheduler's to keep, and nothing can be asked meanwhile: on the Android 16
# AVD, 2026-11-05, a retry due after 30 s came after 189 s. Inferred, not
# measured: that a late job is why debugging never came back on there once,
# with 120 s here.
TURN_ON_LATER_S = 300


def diag_cut_off(op, *extra):
    """A diag op that takes adbd down with it - all debugging off, or a
    revive that restarts it for Start without Wi-Fi's port - sent without
    waiting on its reply. The app logs that reply and then switches
    debugging off, and a read of the log that came a moment late waited on
    an adb going with adbd: `adb logcat` hung its full 120 s (the Android 16
    AVD, 2026-10-28). What follows such an op waits for adb itself."""
    try:
        adb("shell", "am", "broadcast", "-n", f"{PKG}/.diag.SpikeReceiver", "--es", "token", diag.token,
            "--es", "op", op, *extra, timeout=15)
    except subprocess.TimeoutExpired:
        pass  # adbd going away under the command is the point


def daemon_pid():
    out = sh("ps -A -o PID,USER,ARGS | grep com.jemcik.jemrec.shell.Main | grep -v grep").split()
    return out[0] if out else None


def recorder_answers():
    """The recorder answering PONG on its loopback port."""
    return "PONG" in sh("printf P | toybox nc -w 2 127.0.0.1 28472")


def adb_ready():
    """This adb's connection to the emulator, which every adbd restart or
    stop takes away for a moment or longer."""
    try:
        return adb("get-state", timeout=5).strip() == "device"
    except subprocess.TimeoutExpired:
        return False


def connect_app():
    """The app's own ADB session, to the emulator's adbd on 5555."""
    if "connected" not in diag("connect", "--ei", "port", "5555"):
        raise RuntimeError("the app could not connect to adbd on 5555")


def start_recorder():
    """Open the app (a never-opened app may not reach 127.0.0.1:5555), connect it
    to the emulator's adbd, and start a fresh daemon from this build."""
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    connect_app()
    sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
    time.sleep(1)
    diag("bootstrap")
    wait_for(daemon_pid, 20, "the daemon to start")



def start_recorder_after():
    """start_recorder() in a check's `finally`: a recorder that will not start
    is printed, not raised - raised, it took the place of whatever failed the
    check (review, 2026-11-18)."""
    try:
        start_recorder()
    except Exception as e:  # not in place of whatever failed the check
        print(f"     (the recorder could not be started again afterwards: {e})")


def quietly(step, *args, **kwargs):
    """A cleanup step in a check's `finally`: one that fails is printed, not
    raised. Raised, it took the place of whatever failed the check - Python
    keeps the first only as the second's __context__, and run() prints the
    second (review, 2026-11-18)."""
    try:
        return step(*args, **kwargs)
    except Exception as e:  # not in place of whatever failed the check
        print(f"     (a cleanup step failed afterwards: {getattr(step, '__name__', step)}: {e})")
        return None

def as_root(command):
    """Run one command as root, then put adbd back as shell - which kills the
    daemon, so the recorder is started again."""
    adb("root")
    adb("wait-for-device")
    wait_for(lambda: "uid=0" in sh("id"), 30, "adbd as root")
    out = sh(command)
    adb("unroot")
    adb("wait-for-device")
    wait_for(lambda: "uid=2000" in sh("id"), 30, "adbd as shell")
    start_recorder()
    return out


# Each stream's volume for a run, of Android's own stream numbers: system,
# ring, music, alarm and notification at their lowest step, 1 - they play on
# the Mac's speakers through a whole run, over the user's meetings (the
# user's ask, 2026-11-20: "turn down the volume to minimum"; 2026-10-28 it
# was half, "do not mute them"). Not 0: the emulator's sound must keep
# playing - without it, -audio none, the Android 17 AVD played too slowly to
# check (EMULATOR_FLAGS) - and a ring volume of 0 changes Android's ringer
# mode. No check reads these volumes. The voice call stream too, at its own
# lowest, 1 (it has no 0): left at Android's own until 2026-11-20, as the
# checks measure what the recorder captures of a call - with the other five
# at 1 the user still heard the run loudly through a meeting, and stopped it.
# That the call checks pass with it is the next full run's to show.
# Music - the recordings played and the greeting - muted outright, 0 (the
# user, 2026-11-20): a muted stream still plays, its position moving, so the
# playback checks time it as before. None of this reaches a call's own
# sound - a tone the emulator's audio HAL makes, played at full volume
# whatever the volumes, Android's master mute, or QEMU_AUDIO_OUT_DRV=none
# (each tried, the run still heard in full, 2026-11-20) - so the emulators
# run with no audio at all (-no-audio, EMULATOR_FLAGS), and these volumes
# matter for an emulator started by hand with its sound.
QUIET = {0: 1, 1: 1, 2: 1, 3: 0, 4: 1, 5: 1}


def quieten():
    """Turn the emulator's sounds down for the run; a boot can reset them.
    Through the app while it is in front (the debug build's diag 'volume'):
    Android 17's audio hardening ignores the shell's `cmd media_session
    volume`, though 12, 13 and 16 take it."""
    said = diag("volume", "--es", "set", ",".join(f"{s}:{i}" for s, i in QUIET.items()))
    got = {int(k): int(v) for k, v in re.findall(r"\b(\d+)=(\d+)\b", said)}
    loud = {s: got.get(s) for s, i in QUIET.items() if got.get(s) != i}
    # Said either way, in the run's log: the user hears these through meetings.
    print(f"     sounds: {', '.join(f'{s}={v}' for s, v in sorted(got.items()))}"
          + (f" - NOT turned down: {loud}" if loud else ""))


def set_up(apk):
    # Animations off, as Android's testing guide sets a device up for UI
    # tests (Espresso setup: window, transition and animator scales), so a
    # screen a check waits on is there when drawn, not part way in. Not
    # Settings.Global.HIDE_ERROR_DIALOGS, which some test setups turn on: with
    # it, Android kills an app that stops answering where it would ask
    # (AppErrors.handleShowAnrUi, "Just kill the app if there is no dialog to
    # be shown") - System UI restarted in the middle of a check, where
    # watch_for_dialogs answers Wait.
    for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {scale} 0")
    # Before the install: a run killed with early_end's file filling /data
    # (SIGKILL, a hung QEMU - no `finally` runs) left the phone full, and the
    # install, which stages the APK on /data, would fail at every boot
    # (review round 9).
    su("rm -f /data/local/tmp/jemrec-fill")
    # And stall_told's audio server held stopped: every recording in the run
    # after it would block (review round 12).
    quietly(su, "p=$(pidof audioserver) && kill -CONT $p")
    print(f"installing {apk}")
    adb("install", "-r", apk, check=True)
    for permission in ("READ_CALL_LOG", "READ_CONTACTS", "POST_NOTIFICATIONS"):
        sh(f"pm grant {PKG} android.permission.{permission}")  # NOTIFICATIONS fails before 13: fine
    # Setup grants this to the app over its own ADB session, and these checks
    # skip setup. Without it the app cannot put USB debugging back: the
    # usb_debugging check locked the Android 12 and 13 AVDs out until this
    # line (2026-10-26, "cannot write adb_enabled - SecurityException").
    sh(f"pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS")
    add_contact("TestKept", CONTACT)
    # The diag receiver's token: refused once, it says what it wants.
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    sh("logcat -c")
    sh(f"am broadcast -n {PKG}/.diag.SpikeReceiver --es op selftest")
    diag.token = wait_log(r"diag token: (\S+)", 20, "the diag token").group(1)
    # Setup's keep-paired question answered (keep_paired_asked), or it would
    # stand in front of home for every check: a check of its own sets it.
    set_prefs(setup_complete=True, hide_debug_notification=False, keep_paired_asked=True, file_names="DATE_AND_CALLER",
              rules_other_numbers=True, call_tracking=True, recorder_enabled=True, recording_mode="AUTOMATIC")
    # Off until a check of its own turns it on: an emulator keeps its data
    # between boots, and a run that ended on an answering check left it on for
    # the call-timing checks of the next.
    diag("answer", "--ez", "on", "false")
    # Start without Wi-Fi off too: a run cut short left it on for the Android
    # 16 AVD's next boot, whose first revive opened the port before
    # start_without_wifi was watching (2026-10-28).
    set_prefs(start_without_wifi=False)
    # And the screenshots' invented calls off (tools/shoot.py, DemoRecordings):
    # left on, the waveforms are the invented ones and nothing is kept - the
    # waveform check failed on the Android 12 and 13 AVDs, 2026-10-23.
    diag("demo", "--ez", "on", "false")
    quieten()  # JemRec is still in front, opened for the token above
    # And the standard folder, where the checks look: a recently_deleted_folder
    # cut short leaves its picked folder set.
    set_prefs(recordings_tree_uri=None)
    # On through the switch too, not the preference above alone: the switch
    # notes what the save reads (CoverageHistory). On the Android 12 and 16
    # AVDs, 2026-11-05, 17 and 18 recordings in a run were discarded as switched
    # off, and none after this line. Inferred, the note not read at the time: a
    # run cut short with JemRec off had left it saying off.
    diag("switch", "--ez", "on", "true")
    # And the 1.2 settings a check changes, at their defaults: a run cut
    # short leaves them as it had them (quality left at 24 kbps, 2026-11-17).
    set_prefs(recording_quality=None, both_ears=None)
    # Android's battery rules let go, and the battery as the emulator has it:
    # a run cut short in battery_restricted or doze would leave JemRec held
    # back for the runs after - the bucket may outlive a boot (the app op did
    # not, on the Android 17 AVD, 2026-11-18), the unplugged battery and a
    # forced Doze last until a boot, or a --serial run's next check (review,
    # 2026-11-18).
    sh(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow")
    sh(f"am set-standby-bucket {PKG} active")
    sh("dumpsys battery reset")
    sh("dumpsys deviceidle unforce")
    # notifications_off's full-screen notifications, taken away (Android
    # 14+): back to the platform's own default for this install, not forced
    # on - which would hide what an install is given (review round 9).
    if int(sh("getprop ro.build.version.sdk").strip()) >= 34:
        sh(f"appops set {PKG} USE_FULL_SCREEN_INTENT default")
    # And what a check cut short leaves that the next could trip on: When
    # JemRec is off at Turn all debugging off, under which off_just_left
    # turns all debugging off with no snapshot to come back to - adb locked
    # out, as on 2026-10-26; early_end's file filling the storage; a look
    # other than Memo, whose words the checks read (review, 2026-11-18).
    set_prefs(off_mode=None, look="MEMO", light_dark="PHONE")
    su("rm -f /data/local/tmp/jemrec-fill")
    allow_wifi()
    start_recorder()


def add_contact(name, number):
    """A contact, if this emulator has none with the number yet."""
    if number in sh("content query --uri content://com.android.contacts/data/phones --projection data1"):
        return
    sh("content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:")
    raw = max(int(x) for x in re.findall(r"_id=(\d+)", sh("content query --uri content://com.android.contacts/raw_contacts --projection _id")))
    sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{raw} "
       f"--bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:{name}")
    sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{raw} "
       f"--bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:{number} --bind data2:i:2")


# --- calls -------------------------------------------------------------------------

def count(pattern):
    return len(re.findall(pattern, log()))


def wait_more(pattern, before, timeout, what):
    """Wait for one more line matching [pattern] than the [before] there were."""
    wait_for(lambda: count(pattern) > before, timeout, what)


def phone_idle():
    return "mCallState=0" in sh("dumpsys telephony.registry | grep -m1 mCallState=")


def tracker_bound():
    """JemRec's CallTracker among the in-call services Telecom holds now, read
    from `dumpsys telecom`: InCallController's "(InCalls registered):" list -
    "mInCallServices" before it on Android 12, "combinedInCallServiceMap" in
    AOSP main (InCallController.dump); the "(InCalls bound):" list after it
    names only the UI's connection. Bound only with exact call tracking on
    (MANAGE_ONGOING_CALLS, Tracking.java), and only while a call is up."""
    dump = sh("dumpsys telecom")
    at = dump.find("(InCalls registered):")
    assert at >= 0, "dumpsys telecom lists no in-call services"
    return "capture.CallTracker" in "\n".join(dump[at:].splitlines()[:40])


def hang_up():
    """End the call, and make sure it ended: a key pressed before the call
    screen is up does nothing."""
    for _ in range(5):
        sh("input keyevent KEYCODE_ENDCALL")
        try:
            wait_for(phone_idle, 4, "")
            return
        except TimeoutError:
            pass
    raise RuntimeError("the call would not end")


def dial(number, ring_s, talk_s):
    before = count(r"off-hook \(")
    sh(f"am start -a android.intent.action.CALL -d tel:{number}")
    wait_more(r"off-hook \(", before, 15, f"the call to {number} to go off-hook")
    time.sleep(ring_s)
    if talk_s:
        adb("emu", "gsm", "accept", number)
        time.sleep(talk_s)
    hang_up()


def ring_in(number, ring_s, talk_s):
    rings, answers = count(r"callwatch: ringing"), count(r"off-hook \(")
    adb("emu", "gsm", "call", number)
    wait_more(r"callwatch: ringing", rings, 15, f"{number} to ring")
    time.sleep(ring_s)
    sh("input keyevent KEYCODE_CALL")
    wait_more(r"off-hook \(", answers, 15, f"the call from {number} to be answered")
    time.sleep(talk_s)
    hang_up()


def tracked():
    """Telecom's account of the calls, as the app kept it: (id, incoming, answered, ended)."""
    xml = app_file(PREFS)
    m = re.search(r'<set name="tracked_calls">(.*?)</set>', xml, re.S)
    calls = []
    for s in re.findall(r"<string>(.*?)</string>", m.group(1) if m else "", re.S):
        p = s.replace("&amp;", "&").split("|")
        if len(p) in (5, 6):  # six with the clock offset (issue #40)
            answered = [int(a) for a in p[3].split(",") if a]
            calls.append((int(p[0]), p[1] == "in", answered[0] if answered else 0, int(p[4])))
    return sorted(calls)


RECORDINGS = "/sdcard/Recordings/JemRec"


def saved(folder=RECORDINGS):
    """The call just over, as saved: (packets, seconds, file name). The log
    line leaves the name out (a name with the caller in it is personal), so
    the name is the newest file in the recordings folder."""
    m = wait_log(r"save: wrote (\d+) packets, ([\d.]+)s from \S+", 30, "the recording to be saved")
    newest = sh(f"ls -t {folder}/").splitlines()
    return int(m.group(1)), float(m.group(2)), (newest[0].strip() if newest else "")


def undelivered():
    """What the capture never delivered inside the recording just saved, in
    seconds, as the app counts it (RecordingSaver): 0 for one that ran through."""
    m = re.search(r"save: wrote \d+ packets, [\d.]+s from [^\s,]+, ([\d.]+)s not delivered by the capture", log())
    return float(m.group(1)) if m else 0.0


def call_audio_at(start_ms):
    """When Telecom last put the phone in call mode at or before [start_ms]
    (a recording's start), in ms on the wall clock: its
    CallAudioModeStateMachine's own log in `dumpsys telecom` ("Mode
    MODE_IN_CALL", the phone's local time). None where it is not found."""
    offset = sh("date +%z").strip()  # the phone's zone, as its log's times are
    if not re.fullmatch(r"[+-]\d{4}", offset):
        return None
    zone = datetime.timezone(datetime.timedelta(hours=int(offset[:3]), minutes=int(offset[0] + offset[3:])))
    best = None
    for stamp in re.findall(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+) - Mode MODE_IN_CALL", sh("dumpsys telecom")):
        at = int(datetime.datetime.fromisoformat(stamp).replace(tzinfo=zone).timestamp() * 1000)
        if at <= start_ms + 1000 and (best is None or at > best):
            best = at
    return best


def recording_start():
    """When the recording just saved began, in ms on the wall clock: the
    daemon's own stamp in its file's name (jemrec_rec_<ms>_...); None if no
    save line names one."""
    m = re.search(r"save: wrote \d+ packets, [\d.]+s from \S*?_rec_(\d+)_", log())
    return int(m.group(1)) if m else None


def accounted():
    """The time the app's clock says the capture left out of the daemon's
    file - before its first packet and in its gaps (PacketClock) - against
    what the file's own span and packet count make it, read without that
    clock: the daemon's start (the file's name) to its stop (its log line),
    less 20 ms a packet. The daemon drops what its capture still holds as it
    stops, so the span may hold a little more than the clock claims - never
    less: a gap the clock made up, its packets all there, fails here."""
    m = re.search(r"save: (jemrec_rec_(\d+)_\w+\.dat) - (\d+) packets, the first ([\d.]+) s after the start, "
                  r"then (\d+) gap\(s\), ([\d.]+) s", log())
    if not m:
        return ""
    recfile, start, packets = m.group(1), int(m.group(2)), int(m.group(3))
    first, gaps, lost = float(m.group(4)), int(m.group(5)), float(m.group(6))
    stop = next((float(line.split()[0]) for line in adb("logcat", "-d", "-v", "epoch", "-s", "scrcpy:V").splitlines()
                 if f"recording: stopped -> {recfile}" in line), None)
    assert stop is not None, f"no stop line for {recfile}"
    missing = stop - start / 1000 - packets * 0.020
    claimed = first + lost
    assert claimed <= missing + 0.1, f"the clock left out {claimed:.2f} s, the daemon's file has room for {missing:.2f} s"
    return f"{gaps} gap(s), the daemon's file agreeing"


def exact(name_part):
    """Saved, named with [name_part], and as long as Telecom says the call
    was - less what the capture itself never delivered inside it, which no
    cut can put back, and less what came before the recording began: an
    incoming call is recorded from the off-hook the recorder hears, and on
    the Android 13 emulator that came 0.15 to 0.64 s after Telecom marked the
    call active, the call's first moment gone before there was a recording
    (2026-11-13: in six runs, on this tree and the one before it, what the
    check found missing was that delay, within 0.04 s; 0.01 and 0.04 s on
    the 12 and 16 emulators). More than LATE_START_S of it fails: JemRec
    starting late. The Android 13 emulator's capture loses ~0.7 s just after
    an incoming call goes off-hook, every call (2026-10-24: the stamps jump,
    and the beep's one-second rhythm shows a merged tone); 0.19.2, placing
    packets by counting, filled it with audio from after the hang-up and
    came out the right length."""
    packets, _, name = saved()
    seconds = packets * 0.020  # one Opus packet, 20 ms
    lost = undelivered()
    agreed = accounted()
    # Waited for: read at once, the app's account came back empty once, the
    # first call on the Android 12 AVD in a run beside another emulator
    # (2026-10-24) - the call saved, its end seen, and it passed run again.
    _, _, answered, ended = wait_for(tracked, 5, "Telecom's account of the call in the app's preferences")[-1]
    talk = (ended - answered) / 1000
    assert name_part in name, f"saved as {name}, wanted {name_part!r} in it"
    gone = f" + {lost:.2f} s the capture did not deliver" if lost else ""
    start = recording_start()
    late = max(0.0, (start - answered) / 1000) if start else 0.0
    # Android's own part: no call audio can be captured before Telecom puts
    # the phone in call mode, and on a loaded emulator that came 2.5 s after
    # the answer - the call's off-hook to the recorder as late, and JemRec
    # recording the moment it was told (the Android 17 AVD, 2026-11-19; on
    # the Honor the same day, a recording began 24 ms after the answer).
    audio = call_audio_at(start) if start else None
    android = max(0.0, (audio - answered) / 1000) if audio else 0.0
    own = max(0.0, late - android)
    began = f" + {late:.2f} s before the recording began" if late >= 0.05 else ""
    if android >= 0.05:
        began += f" (Android's call audio {android:.2f} s after the answer)"
    assert own <= LATE_START_S, (f"the recording began {own:.2f} s after the call's audio"
                                 f" (Android's own {android:.2f} s after the answer)")
    assert abs(seconds + lost + late - talk) <= 0.15, f"{seconds:.2f} s saved{gone}{began} for {talk:.2f} s of call"
    return f"{name}: {seconds:.2f} s{gone}{began} for {talk:.2f} s answered" + (f" ({agreed})" if lost else "")


# The most of a call that may pass before its recording begins (exact),
# counted from the call's audio (call_audio_at): the Android 13 emulator's
# late off-hook came to 0.64 s at most (2026-11-13).
LATE_START_S = 1.0


# --- the checks --------------------------------------------------------------------

def ringing():
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=3, talk_s=5)
    return exact("_out_")


def ringing_gap():
    # Over Wi-Fi Calling on a Pixel 11 Pro XL nothing was captured while an
    # outgoing call rang (issue #40), and a cut placed by counting packets
    # from the dialling landed past the call. Here the daemon is stopped the
    # moment its file appears - before it captures anything - and let go
    # 1.4 s later, before the emulator answers, which it does 2.3 s after the
    # dialling (API 37, measured).
    #
    # A cut 1.4 s late runs on into the two seconds the daemon records past a
    # hang-up, and comes out as long as the call - unnoticed, where the
    # Pixel's 32 s ran out of packets. So the daemon is stopped again as the
    # phone goes idle, for those two seconds, which costs nothing of the call:
    # a late cut then comes out short. Not before it has seen the idle: its
    # two seconds would only start once it was let go (measured, three late
    # cuts passed).
    #
    # Both from the emulator itself, not over adb: a stop sent from here
    # landed up to a second late, past the daemon's two seconds, and a late
    # cut passed.
    pid = daemon_pid()
    quiet = subprocess.Popen(
        ["adb", "-s", serial, "shell",
         # Each wait gives up: the host kills only its adb client on a
         # timeout, and a script left waiting here would stop the daemon
         # at some later check's hang-up.
         "end=$(( $(date +%s) + 40 )); "
         "until ls /data/local/tmp | grep -q '^jemrec_rec_.*_out.dat$'; do [ $(date +%s) -gt $end ] && exit 1; done; "
         f"kill -STOP {pid}; a=$(date +%s%3N); sleep 1.4; kill -CONT {pid}; b=$(date +%s%3N); "
         "until dumpsys telephony.registry | grep -m1 -q mCallState=0; do [ $(date +%s) -gt $end ] && exit 1; done; sleep 0.3; "
         f"kill -STOP {pid}; c=$(date +%s%3N); sleep 2.5; kill -CONT {pid}; d=$(date +%s%3N); "
         "echo $a $b $c $d"],
        stdout=subprocess.PIPE, text=True)
    try:
        sh("logcat -c")
        time.sleep(0.5)
        dial(STRANGER_OUT, ring_s=3, talk_s=5)
        a, b, c, d = (int(t) for t in quiet.communicate(timeout=15)[0].split())
    finally:
        if quiet.poll() is None:
            quiet.kill()
        sh(f"kill -CONT {pid}")
    started = int(re.findall(r"jemrec_rec_(\d+)_out", log())[-1])
    _, _, answered, ended = wait_for(tracked, 5, "Telecom's account of the call")[-1]
    stopped = f"stopped {(a - started) / 1000:.2f}-{(b - started) / 1000:.2f} s after the dialling " \
              f"(answered at {(answered - started) / 1000:.2f}) and {(c - ended) / 1000:.2f}-{(d - ended) / 1000:.2f} s after the end"
    try:
        return f"{exact('_out_')}; {stopped}"
    except AssertionError as e:
        raise AssertionError(f"{e}; {stopped}") from e


def unanswered():
    """A call hung up while it rings leaves no recording. The hang-up has to
    come well before the AVD's modem answers by itself: it made every outgoing
    call that went active so 1.93-2.08 s after the dialling, with no `gsm
    accept` needed (eight runs on the four AVDs, 2026-11-05). 1.2 s after
    off-hook left half a second of that: with off-hook 0.7 s late, on the
    Android 13 AVD just after a cold boot, the hang-up came 20 ms after Telecom
    said ACTIVE, and the app was right to keep the call."""
    sh("logcat -c")
    dial("5559876", ring_s=0.3, talk_s=0)
    wait_log(r"never answered", 30, "the unanswered call to be dropped")
    assert "save: wrote" not in log(), "it was saved"
    return "not saved"


def incoming():
    sh("logcat -c")
    ring_in(STRANGER_IN, ring_s=3, talk_s=6)
    return exact("_in_")


def rules():
    set_prefs(rules_other_numbers=False)
    # No early skip within 10 s of another call's end (TelecomAccount
    # .RECENT_END_MS) - the saver would leave it out instead. Wait it out.
    time.sleep(10)
    sh("logcat -c")
    ring_in(STRANGER_IN, ring_s=3, talk_s=6)
    wait_log(r"calltrack: call ended", 30, "the call to end")
    wait_log(r"the rules leave this call out; not recording it", 10, "the daemon's skip")
    time.sleep(3)
    assert "save: wrote" not in log(), "it was saved"
    return "not recorded"


def succession():
    # Other numbers still off: a stranger dialled and dropped, then the
    # contact rings within the daemon's two-second end check.
    dial("5550002", ring_s=0.3, talk_s=0)
    before = count(r"off-hook \(incoming\)")
    ring_in(CONTACT, ring_s=3, talk_s=6)
    assert count(r"off-hook \(incoming\)") > before, "the contact's call was not taken as incoming"
    return exact("_in_TestKept")


def logged_as(number, bind, name_part):
    """A call dialled to [number] whose row in the call log is changed by
    [bind] (content update's --bind) before it is saved, then exact(). For
    the change to come first, the save at hang-up is turned off - the backup
    collector saves 5 s later."""
    as_root(f"pm disable {PKG}/.capture.CallMonitorService")
    try:
        start = sh("date +%s").strip()
        sh("logcat -c")
        dial(number, ring_s=2, talk_s=4)
        wait_for(lambda: "Row" in sh(f"content query --uri content://call_log/calls --projection _id --where \"date>={start}000\""),
                 20, "the call log entry")
        sh(f"content update --uri content://call_log/calls --bind '{bind}' --where 'date>={start}000'")
        return exact(name_part)
    except Exception as e:
        # Before the finally: putting the service back restarts the daemon.
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        quietly(as_root, f"pm enable {PKG}/.capture.CallMonitorService")


def caller_id():
    number, name = CALLER_ID
    set_prefs(rules_other_numbers=True)
    # The emulator has no caller ID; the name is written into the call log
    # the way Google Phone writes it.
    return logged_as(number, f"name:s:{name}", f"_out_{name}")


def number_names():
    number, name = NUMBERED
    add_contact(name, number)
    set_prefs(rules_other_numbers=True, file_names="DATE_AND_NUMBER")
    try:
        sh("logcat -c")
        # Dialled as a person dials it, without the +1: the call log keeps it
        # so, and the name is to have the code.
        dial(number, ring_s=2, talk_s=4)
        here = exact(f"_out_+1{number}.ogg")
        # The call's own country, as the call log has it, over the phone's: a
        # call made in Ukraine, the emulator's network still saying US. No US
        # number starts with 0, so only the log's UA gives this one its +380.
        abroad = logged_as(UA_DIALLED, "countryiso:s:UA", f"_out_+380{UA_DIALLED[1:]}.ogg")
        return f"{here}; {abroad}"
    finally:
        # The checks after this look for the caller in names.
        quietly(set_prefs, file_names="DATE_AND_CALLER")


GREETING = "/data/local/tmp/jemrec-test-greeting.pcm"


def end_any_call():
    """A call left up by a check that failed half way, ended from the call
    screen: the far side's cancel (`emu gsm cancel`) does not end an outgoing
    call on these AVDs (round 26's measure) - though the emulator console's
    docs say it ends an inbound or an outbound call (developer.android.com,
    studio/run/emulator-console): measured otherwise, keep the key. Left up,
    the recorder started in cleanup joined it, and the checks after ran with
    a call up (review rounds 32, 33)."""
    if not phone_idle():
        hang_up()


def no_call_left():
    """A check that failed half way can leave a call ringing - and every call
    after it is then call waiting, which is never answered."""
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_for(phone_idle, 10, "the phone to be idle")


def answering_on(seconds=3):
    """A greeting of two tones, three seconds unless [seconds], the
    answering machine on after five seconds, and both given to the recorder
    through the app."""
    no_call_left()
    import math
    import struct
    import tempfile
    rate = 48000
    frames = [int(6000 * math.sin(2 * math.pi * (440 if i < rate * seconds / 2 else 660) * i / rate))
              for i in range(rate * seconds)]
    with tempfile.NamedTemporaryFile(suffix=".pcm", delete=False) as f:
        f.write(struct.pack("<%dh" % len(frames), *frames))
    adb("push", f.name, GREETING, check=True)
    sh(f"chmod 644 {GREETING}")
    set_prefs(answer_delay_s=5)
    said = diag("answer", "--es", "greeting", GREETING)
    assert "recorder took it: true" in said and "wire 1," in said, said
    # All of it: a greeting the recorder refuses (Greeting.MAX_BYTES) leaves
    # it the one before, and the check after runs on that.
    assert f"recorder has {rate * seconds * 2} bytes" in said, said


def mute_given_back():
    """The recorder's own give-back of the answering machine's mute: its
    marker gone (Paths.MUTE_MARKER). mic_unmuted alone passes on Telecom's
    unmute as a call ends (AOSP CallAudioRouteStateMachine.reinitialize ->
    setMuteOn(false)), whatever the recorder did (review round 14)."""
    return sh("test -e /data/local/tmp/jemrec-muted && echo yes || echo no").strip() == "no"


def mute_left():
    """The answering machine's mute marker there: what mute_given_back looks
    for gone - without it, that wait passes on nothing (review round 15)."""
    return sh("test -e /data/local/tmp/jemrec-muted && echo yes || echo no").strip() == "yes"


def mic_unmuted():
    return "FromApi=false" in sh("dumpsys audio | grep -m1 'mic mute'")


def answering():
    try:
        return _answering()
    finally:
        quietly(no_call_left)


def _answering():
    answering_on()
    time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: answering", 20, "the answering machine to answer")
    wait_log(r"answer: greeting played", 20, "the greeting to play")
    time.sleep(3)
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_log(r"save: wrote", 40, "the message to be saved")
    wait_for(mic_unmuted, 10, "the microphone to be given back")
    wait_for(mute_given_back, 10, "the recorder to give back the mute")
    # This message's own mark: the marks outlive runs (install -r keeps the
    # app's data), so any "_in" there could be an earlier run's.
    _, _, name = saved()

    def marked():
        marks = app_file(PREFS)
        m = re.search(r'<set name="message_recordings">(.*?)</set>', marks, re.S)
        return bool(m) and f"<string>{name}</string>" in m.group(1)

    wait_for(marked, 5, f"{name} to be marked as a message")
    return "message saved, marked, microphone back"


def test_call_wifi():
    try:
        return _test_call_wifi()
    finally:
        quietly(no_call_left)


def _test_call_wifi():
    """The answering machine's test call, armed as the screen arms it, the
    machine itself off as on a phone not yet set up: the next call answered
    with the check tone and the greeting, and mid-greeting Telecom's list of
    the calls there are now read for the active call's Wi-Fi mark
    (Checks.callOnWifi) - what the voice network type cannot give: a Galaxy S23 said LTE for a test call on Wi-Fi from its start
    (2026-11-12). The emulator's call is its modem's, never on Wi-Fi: "no"
    says the list was found and the call in it read on this Android, where
    "cannot tell" would leave the call's type to the voice network type
    again. Straight to the recorder: no result kept in the app, no route of
    its changed."""
    answering_on()
    said = diag("answer", "--ez", "on", "false")
    assert "recorder took it: true" in said, said
    time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
    arming = int(time.time() * 1000)
    said = diag("armtest", "--el", "arming", str(arming))
    assert "ARMED" in said, said
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: answering", 20, "the test call to be answered")
    # The emulator's call takes its voice path: the app's route, not the
    # call-screening one a call through the mixer gets.
    m = wait_log(r"answer: the greeting on route (\w+) \(([^)]*)\)", 30, "the greeting to start")
    assert m.group(2) == "the call's voice path", f"the emulator's call read as {m.group(2)}"
    # Only a test call reads this, so it is the test that answered.
    m = wait_log(r"answer: test call on Wi-Fi: (yes|no|cannot tell)", 30, "Telecom's list of calls to be read")
    assert m.group(1) == "no", f"the emulator's call read as on Wi-Fi: {m.group(1)}"
    wait_log(r"answer: greeting played", 30, "the greeting to play")
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_log(r"answer: test call over", 30, "the test call to end")
    result = diag("testresult")
    assert "state=done" in result and f"arming={arming}" in result and "wifi=0" in result, result
    assert "mixer=0" in result, result
    return ("answered, the greeting played on the call's voice path, its call read in Telecom's list as not on"
            " Wi-Fi")


def greeting_moves():
    try:
        return _greeting_moves()
    finally:
        quietly(fake_call_down)
        quietly(no_call_left)
        quietly(set_prefs, answer_route=None)


def _greeting_moves():
    """The call moving under its greeting, as a call handed over from LTE to
    Wi-Fi takes communication mode: a test call answered on its voice path,
    then the stand-in call taking "communication" as the shell over it, then
    letting it go. On Android 14 and later the machine, told by Android's
    mode listener, takes the phone call for gone through the mixer
    (AnswerMachine.pathApplied): the greeting stops, plays again on the
    call-screening route, and stops and plays again on the call's voice path
    once the mode is back - the real listener, Telecom's questions, and a
    call-screening track made for communication mode, which no unit test
    runs. Before 14 a phone call never plays through the mixer here
    (Checks.phoneCallPath): no move - but on 13 a call-screening greeting's
    track is released as the mode leaves in call (AudioManager's
    CallInjectionModeChangedListener), a cut, played again once the mode is
    back. On 12, with no call-screening route, the greeting plays on. Never a
    hang-up. The route is pinned to call-screening where there is one: the
    emulator's default, in-call music, is released by no move, and the S23's
    case - a call-screening greeting cut as its call moved - would go unrun."""
    sdk = int(sh("getprop ro.build.version.sdk").strip())
    copies = sdk >= 34
    screening = sdk >= 33  # AudioManager.getCallUplinkInjectionAudioTrack
    prepare_fake_call()
    if screening:
        set_prefs(answer_route="screening")
    # Under Greeting.MAX_BYTES (30.5 s), long enough for both moves.
    answering_on(seconds=25)
    said = diag("answer", "--ez", "on", "false")
    assert "recorder took it: true" in said, said
    time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
    arming = int(time.time() * 1000)
    said = diag("armtest", "--el", "arming", str(arming))
    assert "ARMED" in said, said
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    m = wait_log(r"answer: the greeting on route (\w+) \(the call's voice path\)", 30, "the greeting to start")
    assert m.group(1) == ("screening" if screening else "music"), f"the greeting on route {m.group(1)}"
    fake_call_up(tone=True)
    if not copies:
        # Back well inside the machine's 8 s wait for the call's audio: the
        # cut comes as the mode is applied, before the stand-in says so.
        time.sleep(1)
        fake_call_down()
        time.sleep(3)
        cut = "the call's audio left" in log()
        assert cut == screening, ("no cut of the call-screening track as communication mode came" if screening
                                  else "a cut on Android 12, which has no call-screening track to release")
        if cut:
            wait_for(lambda: len(re.findall(r"answer: the greeting on route (\w+) \(the call's voice path\)",
                                            log())) >= 2, 15, "the greeting again once the mode is back")
        assert "the call moved" not in log(), "a phone call taken for gone through the mixer before Android 14"
        hung = "hanging up" in log()
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_log(r"answer: test call over", 30, "the test call to end")
        assert not hung, "hung up on the caller as communication mode came and went"
        return ("no move before Android 14; " + ("cut as its call-screening track went with the mode, played"
                                                 " again once it was back" if cut else "the greeting played on"))
    wait_log(r"answer: the greeting stopped \(the call moved (to the mixer|to its voice path)\)", 15,
             "the move to stop the greeting")
    m = wait_log(r"answer: the greeting on route (\w+) \(the call is through the mixer\)", 15,
                 "the greeting again, for the mixer")
    assert m.group(1) == "screening", f"the greeting through the mixer on route {m.group(1)}"
    time.sleep(3)
    assert "the greeting could not be played" not in log(), "the call-screening track for the mixer would not play"
    fake_call_down()
    wait_for(lambda: len(re.findall(r"answer: the greeting stopped \(the call moved (to the mixer|to its voice path)\)",
                                    log())) >= 2, 15, "the move back to stop the greeting")
    wait_for(lambda: len(re.findall(r"answer: the greeting on route (\w+) \(the call's voice path\)", log())) >= 2,
             15, "the greeting again, on the call's voice path")
    hung = "hanging up" in log()
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_log(r"answer: test call over", 30, "the test call to end")
    assert not hung, "hung up on the caller during the moves"
    return "stopped and played again through the mixer on call-screening, and back on the call's voice path"


def greeting_hangup():
    try:
        return _greeting_hangup()
    finally:
        quietly(no_call_left)


def message_recorder_lost():
    try:
        return _message_recorder_lost()
    finally:
        quietly(no_call_left)
        # Killed here: a revive that failed would fail every answering check
        # after this one in answering_on (review round 14).
        start_recorder_after()


def _message_recorder_lost():
    """The recorder killed while the answering machine takes a message: the
    machine, its mute and its length limit go with it, and until 1.2 the
    caller heard silence until they hung up (since 0.17). With exact call
    tracking on, the call's own service hangs up that call - the message's,
    by Telecom's own Call (MessageHangUp) - the file is saved as a message,
    whole (the recorder never named it one; LostMessages, review round 14),
    and the recorder started again gives back the mute it left (its marker
    gone; Telecom unmutes by itself as the call ends) (review round 13, the
    user's choice)."""
    answering_on()
    time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: greeting played", 30, "the greeting to play")
    time.sleep(2)
    assert mute_left(), "no mute marker while the machine takes the message"
    sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
    wait_log(r"monitor: the recorder was lost while taking a message; hanging up its call", 45,
             "the message's call to be hung up")
    wait_for(phone_idle, 15, "the message's call to end")
    wait_log(r"save: wrote", 120, "the message to be saved")
    _, _, name = saved()

    def marked():
        marks = app_file(PREFS)
        m = re.search(r'<set name="message_recordings">(.*?)</set>', marks, re.S)
        return bool(m) and f"<string>{name}</string>" in m.group(1)

    wait_for(marked, 10, f"{name} to be marked as a message")
    wait_for(mute_given_back, 120, "the recorder to give back the mute it left")
    wait_for(mic_unmuted, 10, "the microphone to be given back")
    return "the recorder killed mid-message: its call hung up, saved as a message, the mute given back"


def _greeting_hangup():
    """A caller who hangs up during the greeting. On a phone, silence then is
    "_msgnone" and nothing is saved. The emulator's caller is never silent -
    its call audio is a constant test signal - so here it is words said over
    the greeting: what is checked is that only they are kept, trimmed to the
    caller's first sound, and that the microphone comes back. On Android 12
    the emulator's caller is heard only as the line goes down, after the
    call - or, as the recorder now knows the call is over, not at all:
    nothing is kept then, and that must not read as a silent call."""
    answering_on()
    time.sleep(10)
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: answering", 20, "the answering machine to answer")
    time.sleep(1)
    adb("emu", "gsm", "cancel", STRANGER_IN)
    m = wait_log(r"save: wrote \d+ packets, ([\d.]+)s from \S+_msg(\d+)_in"
                 r"|save: \S+ - (nothing after the message's start"
                 r"|the caller hung up during the greeting)", 40, "the message to be saved")
    # A message trimmed to nothing is "no audio" to the writer too, just before
    # the saver calls it a hang-up during the greeting: only alone is it a
    # silent call.
    text = log()
    assert "save: no audio in" not in text or "nothing after the message's start" in text, \
        "a message trimmed to nothing was taken for a silent call"
    wait_for(mic_unmuted, 10, "the microphone to be given back")
    # The recorder's own give-back: Telecom unmutes by itself at a call's end.
    wait_for(mute_given_back, 10, "the recorder to give back the mute")
    if m.group(3) == "the caller hung up during the greeting":
        return "no word before the hang-up: nothing kept"
    if m.group(1) is None:
        return "nothing after the caller's first sound: hung up during the greeting"
    seconds = float(m.group(1))
    assert seconds < 2.5, f"{seconds} s kept: the greeting was not cut"
    return f"{seconds} s kept from the caller's first sound"


def answer_rules():
    answering_on()
    set_prefs(rules_other_numbers=False)
    try:
        time.sleep(10)
        sh("logcat -c")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"answering machine - not answering: whose calls to keep", 15, "the decision not to answer")
        time.sleep(8)
        assert "answer: answering" not in log(), "it answered"
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_for(phone_idle, 10, "the call to end")
    finally:
        quietly(set_prefs, rules_other_numbers=True)
        quietly(diag, "answer", "--ez", "on", "false")
    return "rang on, unanswered"


PROBE_DIR = os.path.join(ROOT, "shellserver", "probe")
PROBE_JAR = os.path.join(PROBE_DIR, "voip-probe.jar")
FAKE_CALL_LOG = "/data/local/tmp/fakecall.txt"


def probe_stale():
    """Whether the stand-in's jar is missing, or older than anything
    build-probe.sh builds it from: the probes, the script, and the daemon's
    sources, which it compiles in."""
    if not os.path.exists(PROBE_JAR):
        return True
    built = os.path.getmtime(PROBE_JAR)
    for top in (PROBE_DIR, os.path.join(ROOT, "shellserver", "src")):
        for d, dirs, files in os.walk(top):
            dirs[:] = [x for x in dirs if x != "build" and not x.startswith(".")]
            for f in files:
                path = os.path.join(d, f)
                if path != PROBE_JAR and not f.startswith(".") and os.path.getmtime(path) > built:
                    return True
    return False


def prepare_fake_call():
    """The stand-in call's jar built and pushed, under the build lock - ahead
    of a call it must not keep waiting while it builds."""
    with build_lock():
        if probe_stale():
            built = subprocess.run(["bash", os.path.join(PROBE_DIR, "build-probe.sh")],
                                   capture_output=True, text=True, timeout=PROBE_TIMEOUT_S)
            if built.returncode != 0:
                raise RuntimeError("could not build the stand-in call (JDK 21? set JEMREC_JAVA_HOME):\n"
                                   + built.stdout[-800:] + built.stderr[-800:])
        adb("push", PROBE_JAR, "/data/local/tmp/voip-probe.jar", check=True)


def fake_call_up(tone=False):
    """A stand-in for a messenger call, run as the shell: the audio mode at
    MODE_IN_COMMUNICATION, call audio playing - silence, or a tone - the
    microphone open."""
    prepare_fake_call()
    sh(f"rm -f {FAKE_CALL_LOG}")
    sh("CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / "
       f"com.jemcik.jemrec.probe.FakeCallProbe 60{' tone' if tone else ''} < /dev/null > {FAKE_CALL_LOG} 2>&1 &")
    wait_for(lambda: "mode=3" in sh(f"cat {FAKE_CALL_LOG}"), 15, "the stand-in call's audio mode")


def fake_call_down():
    # Its own adb shell: a pkill on one line with the launch would match it.
    sh("pkill -f '[F]akeCallProbe'")


def answer_during_voip():
    answering_on()
    try:
        time.sleep(10)
        fake_call_up()
        sh("logcat -c")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"a messenger or meeting call is up|during a messenger or meeting call", 20,
                 "the decision not to answer over the messenger call")
        time.sleep(8)
        assert "answer: answering" not in log(), "it answered over the messenger call"
        modes = sorted(set(re.findall(r"mode=(\d)", sh(f"cat {FAKE_CALL_LOG}"))))
    finally:
        adb("emu", "gsm", "cancel", STRANGER_IN)
        quietly(fake_call_down)
        quietly(no_call_left)
    return "rang on, unanswered (audio modes seen during it: " + ",".join(modes) + ")"


def stand_in_down():
    """No stand-in call in Telecom. phone_idle cannot say: telephony's own
    state stays idle through a self-managed call (the Android 17 AVD,
    2026-10-26), while Telecom lists it."""
    return f"tpac=ComponentInfo{{{PKG}/" not in sh("dumpsys telecom")


def end_app_call():
    """End the stand-in call - and if it will not end, stop the app, whose
    calls Telecom then drops (measured). Left up, it holds Telecom's call
    state off-hook, so a later phone call's off-hook never reaches the
    recorder, and no_call_left cannot reach it."""
    try:
        diag("appcall", "--es", "do", "end")
        wait_for(stand_in_down, 10, "")
    except Exception:  # a diag that never answers too: the app may be gone
        sh(f"am force-stop {PKG}")
        wait_for(stand_in_down, 10, "the stand-in call to end with the app stopped")
        sh(f"am start -n {PKG}/.MainActivity")


def app_call():
    since = device_ms() - 60_000
    try:
        # Inside the try: an "already up" is a stand-in left from before, which
        # the finally ends.
        report = diag("appcall", "--es", "do", "start")
        assert "placed" in report, report
        # The Honor, 2026-10-26: the call state went off-hook for Meet's calls,
        # and each was recorded as a phone call.
        wait_log(r"another app's call through Telecom is off-hook", 15, "the app's call to be left alone")
        time.sleep(3)
        assert "recording: started" not in log(), "the app's call was recorded as a phone call"
        rings, answers = count(r"callwatch: ringing"), count(r"off-hook \(")
        adb("emu", "gsm", "call", CONTACT)
        wait_more(r"callwatch: ringing", rings, 15, f"{CONTACT} to ring over the app's call")
        time.sleep(3)
        sh("input keyevent KEYCODE_CALL")
        wait_more(r"off-hook \(", answers, 15, f"the call from {CONTACT} to be answered")
        time.sleep(6)
        # Hung up from the far end; the app's call, held for it, stays up, and
        # with it the call state's off-hook: only the audio mode says it ended.
        adb("emu", "gsm", "cancel", CONTACT)
        recorded = exact("_in_TestKept")
    except Exception as e:
        # Before the finally: ending the stand-in clears the log.
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        quietly(end_app_call)
    # In the call log under its own account, as Meet's calls are - and not
    # among the calls the missed-recording check holds to a recording.
    def logged():  # this call's row: an earlier stand-in's may be there too
        return f"{PKG}/" in sh("content query --uri content://call_log/calls --projection subscription_component_name "
                              f"--where \"date>={since + 60_000}\"")
    try:
        wait_for(logged, 10, "")
        row = "logged by Android"
    except TimeoutError:
        # The Android 17 AVD logs none of this stand-in's calls (2026-10-26),
        # though 12 and 13 do, and Meet's are logged on an Honor running 17:
        # the row is written here the way Telecom writes one.
        sh(f"content insert --uri content://call_log/calls --bind number:s:5550100 --bind type:i:2 "
           f"--bind date:l:{since + 60_000} --bind duration:i:20 "
           f"--bind subscription_component_name:s:{PKG}/{PKG}.diag.FakeAppCallService")
        wait_for(logged, 10, "the app's call written into the call log")
        row = "written into the call log by the check"
    answered = diag("answered", "--el", "since", str(since))
    assert "TestKept" in answered, f"the phone call is not among the answered calls: {answered}"
    assert "5550100" not in answered, f"the app's call was taken for a phone call: {answered}"
    return recorded + f"; the app's own call left alone, and its row ({row}) too"


def mode_switch():
    """A phone call whose audio mode turns "communication" in the middle, as
    a Wi-Fi call put through after the off-hook may (issue #37): the
    stand-in call takes the mode, as the shell, over the emulator's own.
    Before Android 14 the shell may not copy a call's audio: there the call
    stays on the call capture."""
    copies = int(sh("getprop ro.build.version.sdk").strip()) >= 34
    rings, answers = count(r"callwatch: ringing"), count(r"off-hook \(")
    try:
        adb("emu", "gsm", "call", CONTACT)
        wait_more(r"callwatch: ringing", rings, 15, f"{CONTACT} to ring")
        time.sleep(2)
        sh("input keyevent KEYCODE_CALL")
        wait_more(r"off-hook \(", answers, 15, f"the call from {CONTACT} to be answered")
        wait_log(r"recording: the call's voice path \(audio mode 2", 10, "the call to start on its voice path")
        time.sleep(2)
        fake_call_up(tone=True)
        if copies:
            wait_log(r"recording: the call plays through the mixer now \(audio mode 3\)", 10,
                     "the switch to the mixer")
            time.sleep(4)
            fake_call_down()
            wait_log(r"recording: the call is back on the modem's voice path \(audio mode 2\)", 10,
                     "the switch back to the call capture")
        else:
            wait_log(r"recording: audio mode 3 during the call", 10, "the mode change to be heard")
            time.sleep(4)
            assert "through the mixer" not in log(), "switched to the mixer, which Android does not allow here"
            fake_call_down()
        time.sleep(2)
        adb("emu", "gsm", "cancel", CONTACT)
        if copies:
            recorded = exact("_in_TestKept")
        else:
            # Kept on the call capture, which the mode flipping under it
            # costs a moment of: 0.15 s more than the cut accounts for on the
            # Android 13 AVD (2026-10-27), not this check's subject there.
            _, seconds, name = saved()
            assert "_in_TestKept" in name, f"saved as {name}"
            recorded = f"{name}: {seconds:.2f} s"
    except Exception as e:
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        quietly(fake_call_down)
        adb("emu", "gsm", "cancel", CONTACT)
        try:
            wait_for(phone_idle, 10, "")
        except TimeoutError:
            # Said, not raised: raised here it would replace the failure
            # above, and the recorder's lines that came with it.
            print("     (the call would not end after mode_switch)")
    # Diagnostics' Copy a report, as the app builds it.
    report = diag("callreport")
    stretches = re.findall(r"\d s  (the call capture|the mixer)", report)
    assert "audio mode 3" in report, f"no mode change in the report:\n{report}"
    if not copies:
        assert stretches == ["the call capture"], f"stretches {stretches}:\n{report}"
        return f"{recorded}; the mode change heard, the call capture kept (no copy before Android 14)"
    assert stretches == ["the call capture", "the mixer", "the call capture"], f"stretches {stretches}:\n{report}"
    copy = re.search(r"right \(the other side, the copy\) (-[\d.]+) dB", report)
    assert copy and float(copy.group(1)) > -40, f"the copy did not have the tone in it:\n{report}"
    assert "voice call" in report and "What was playing" in report, f"no look at what was playing:\n{report}"
    return f"{recorded}; call capture, mixer, call capture, the copy's tone at {copy.group(1)} dB"


def app_call_in_front():
    """Another app's call through Telecom placed over a phone call being
    recorded, as a Google Meet call taken with the phone call on hold: its
    "communication" mode must not move the phone call's recording to the
    mixer, where the app's call would be recorded with it."""
    copies = int(sh("getprop ro.build.version.sdk").strip()) >= 34
    rings, answers = count(r"callwatch: ringing"), count(r"off-hook \(")
    try:
        adb("emu", "gsm", "call", CONTACT)
        wait_more(r"callwatch: ringing", rings, 15, f"{CONTACT} to ring")
        time.sleep(2)
        sh("input keyevent KEYCODE_CALL")
        wait_more(r"off-hook \(", answers, 15, f"the call from {CONTACT} to be answered")
        wait_log(r"recording: the call's voice path \(audio mode 2", 10, "the call to start on its voice path")
        time.sleep(2)
        report = diag("appcall", "--es", "do", "start")
        assert "placed" in report, report
        # Asked for is not up: Telecom has to hold the phone call for it.
        wait_for(lambda: not stand_in_down(), 10, "the stand-in call to come up over the phone call")
        if copies:
            wait_log(r"recording: audio mode 3, but another app's call is up; the call capture stays", 15,
                     "the app's call in front to keep the phone call off the mixer")
        else:
            wait_log(r"recording: audio mode 3 during the call", 15, "the app's call to take the mode")
        time.sleep(3)
        assert "through the mixer" not in log(), "the phone call went to the mixer under the app's call"
    except Exception as e:
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        quietly(end_app_call)
        adb("emu", "gsm", "cancel", CONTACT)
        try:
            wait_for(phone_idle, 10, "")
        except TimeoutError:
            print("     (the call would not end after app_call_in_front)")
    return "the phone call kept on the call capture under the app's call" + ("" if copies else " (no copy before 14)")


def messenger_recorder_lost():
    """The recorder ended in the middle of a messenger call and brought back
    by the call's own watch: the call saved as one - both parts, one
    after-call notice - not as two calls. A messenger call's start never
    says it was joined, as a phone call's does, and round 16's rule for a
    new call took the revived recorder's start for one: the first part was
    dropped from the call, told and judged on its own (review round 17).
    The call is the stand-in messenger's (standin_apk: Telegram's
    telegram.org build by name), its setting written as Settings' switch
    writes it, and the recorder started again to take the list (the switch
    tells a running recorder itself). Then a second call ended while its
    recorder is down, before the revive lands: ended from the audio mode,
    1.5 s out - not when the watch can look again, its revive landing in the
    next call - its notification gone, and what the recorder left saved and
    marked a part, no short call (review rounds 19 to 22). Android 14 and
    later: messenger calls are recorded only there (VoipApps.supported)."""
    if int(sh("getprop ro.build.version.sdk").strip()) < 34:
        return "messenger calls are recorded from Android 14: nothing to check here"
    notes = lambda: sh("dumpsys notification --noredact")
    found = rf"voip: call in {STANDIN}(?! ended)"
    workdir = tempfile.mkdtemp(prefix="jemrec-standin-")
    try:
        adb("install", "-r", "-g", standin_apk(workdir), check=True)
        set_prefs(voip_apps={STANDIN})
        start_recorder()
        diag("clearnotes")
        sh("logcat -c")
        sh(f"am start -n {STANDIN}/{STANDIN_CALL} --ei seconds 120")
        wait_log(found, 20, "the stand-in's call taken for a messenger call")
        wait_for(lambda: "Recording call" in notes(), 20, "the call's notification to say it records")
        time.sleep(4)
        seen, saves, told = count(found), count(r"save: wrote \d+ packets"), count(r"aftercall: posted for")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        killed = time.time()
        wait_for(lambda: "Trying again - this part of the call is not recorded" in notes(), 30,
                 "the call's notification to say the recorder is gone")
        wait_for(recorder_answers, 90, "the recorder brought back during the call", step=1)
        wait_more(found, seen, 30, "the brought-back recorder to find the call")
        back = time.time() - killed
        wait_for(lambda: "Recording call" in notes(), 30, "the call's notification back to recording")
        time.sleep(4)
        # Ended as a messenger killed mid-call ends: Android puts the mode back.
        sh(f"am force-stop {STANDIN}")
        wait_log(rf"voip: call in {STANDIN} ended", 30, "the call's end")
        wait_more(r"save: wrote \d+ packets", saves + 1, 90, "the call's two parts to be saved")
        wait_more(r"aftercall: posted for", told, 60, "the call's after-call notice")
        time.sleep(10)  # a second notice, if the parts were told as two calls
        notices = count(r"aftercall: posted for") - told
        assert notices == 1, f"the call told {notices} times - its parts saved as calls of their own"
        assert re.search(r"part: saved \S+, a part of its call", log()), "neither part saved as the call's part"
        said = f"the recorder back {back:.0f} s after its end, mid-call; both parts saved as one call, told once"
        # The call over while its recorder is down, the revive not landed:
        # ended from the audio mode, 1.5 s out - not when the watch can look
        # again, its revive landing in the next call - and what the recorder
        # left saved as a part, no short call (review rounds 19 to 22).
        # The first call's saves and its keep-alive run done: saved() below
        # reads the newest file, which must be this call's.
        time.sleep(10)
        for attempt in (1, 2):
            sh("logcat -c")
            sh(f"am start -n {STANDIN}/{STANDIN_CALL} --ei seconds 120")
            wait_log(found, 20, "the stand-in's second call taken for a messenger call")
            wait_for(lambda: "Recording call" in notes(), 20, "the second call's notification to say it records")
            time.sleep(3)
            sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
            wait_for(lambda: "Trying again - this part of the call is not recorded" in notes(), 30,
                     "the second call's notification to say the recorder is gone")
            sh(f"am force-stop {STANDIN}")
            # The revive landed first (3-4 s after the loss is said; a host
            # under load): the recorder back in the call as the service saw
            # it when the mode left, and the call then ends as any call does,
            # no case for the mode. Once more, said. Keyed on the service's
            # own decision, either line (review rounds 23, 24: the recorder
            # finding the call, then the watch's revive line, came apart
            # from what the service decides on).
            ended = wait_log(r"monitor: out of a call, the recorder (still lost; the call is over"
                             r"|back in it; its own end ends it)", 15, "the call's end read from the audio mode")
            if ended.group(1).startswith("back in it"):
                if attempt == 2:
                    raise AssertionError("the revive landed before the call ended, twice")
                print("     (the revive landed before the call ended; the second pass once more)")
                # Its call over: the service stood down - neither of its
                # notification's titles left (round 24: "Recording call"
                # alone was gone while it said "Recording stopped").
                wait_for(lambda: not re.search(r"Recording (call|stopped)", notes()), 60,
                         "the first try's call to end")
                time.sleep(10)
                continue
            break
        wait_for(lambda: "Trying again - this part of the call is not recorded" not in notes(), 15,
                 "the call's notification to go")
        wait_log(r"save: wrote \d+ packets", 120, "what the lost recorder left to be saved")
        _, _, name = saved()

        def a_part():
            m = re.search(r'<set name="part_recordings">(.*?)</set>', app_file(PREFS), re.S)
            return bool(m) and f"<string>{name}</string>" in m.group(1)

        wait_for(a_part, 10, f"{name} to be marked a part of its call")
        said += "; a call over during the revive ended from the audio mode, what it left saved as a part"
    except Exception as e:
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        quietly(sh, f"am force-stop {STANDIN}")
        quietly(set_prefs, voip_apps=None)
        quietly(adb, "uninstall", STANDIN)
        # Its list without the stand-in.
        start_recorder_after()
        shutil.rmtree(workdir, ignore_errors=True)
    return said


def device_ms():
    """The emulator's own clock, in milliseconds, as the app reads it."""
    return int(sh("date +%s").strip()) * 1000


def answer_after_emergency():
    try:
        return _answer_after_emergency()
    finally:
        quietly(set_prefs, emergency_call_at=None)
        adb("emu", "gsm", "cancel", STRANGER_IN)
        quietly(no_call_left)


def _answer_after_emergency():
    answering_on()
    time.sleep(10)
    # What CallTracker notes as an emergency call ends. Dialling 112 here is
    # simulated too, but there is no need to.
    set_prefs(emergency_call_at=device_ms())
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"not answering: paused after an emergency call", 20, "the decision not to answer")
    time.sleep(8)
    assert "answer: answering" not in log(), "it answered within a day of an emergency call"
    return "rang on, unanswered"


def answer_takeover_ask_first():
    try:
        return _answer_takeover_ask_first()
    finally:
        quietly(set_prefs, recording_mode="AUTOMATIC")
        # The mode back on the recorder, and the machine off again: on, it
        # would answer the calls of the checks after this one.
        quietly(diag, "answer", "--ez", "on", "false")
        quietly(no_call_left)


def _answer_takeover_ask_first():
    set_prefs(recording_mode="ON_DEMAND")
    answering_on()
    time.sleep(10)
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: answering", 20, "the answering machine to answer")
    time.sleep(4)  # the emulator's caller: a few seconds of sound, on 13 and 17 (2026-10-22)
    said = diag("talk")
    assert "-> true" in said, said
    m = wait_log(r"answer: the call was taken(?:; the message so far -> \S+_msg(\d+)_in"
                 r"| before a word was left)", 15, "the message to be split off")
    wait_log(r"asking whether to record", 15, "the Record prompt for the rest of the call")
    time.sleep(2)
    adb("emu", "gsm", "cancel", STRANGER_IN)
    # Nothing recorded after the takeover without a Record tap. The log here
    # begins at the takeover: diag clears it.
    assert count(r"recording: started") == 0, "the call went on being recorded after it was taken"
    if m.group(1) is None:
        return "no word before the takeover: nothing kept, the rest asked about"
    wait_log(r"save: wrote \d+ packets, [\d.]+s from \S+_msg\d+_in", 40, "the message to be saved")
    return "the message kept, the rest asked about"


def ui():
    """The screen as uiautomator sees it."""
    sh("uiautomator dump /sdcard/ui.xml")
    return sh("cat /sdcard/ui.xml")


def waveform_file(name):
    """Where the app keeps [name]'s levels - Waveforms.cacheFile: the first
    12 bytes of the name's SHA-256, in hex."""
    return f"{APP_DIR}/cache/waveforms-v2/{hashlib.sha256(name.encode()).digest()[:12].hex()}.lvl"


def waveform_levels(name):
    """The 800 levels the app keeps for [name], once it has: each 0 to 1,
    the loudest twentieth of a second in its stretch of the file, 0 quieter
    than -55 dBFS (WaveformDecoder.reduce, WaveformCache.toBytes)."""
    wait_for(lambda: su(f"stat -c %s {waveform_file(name)}").strip() == "800", 30,
             "the call's 800 levels to be kept")
    return [int(v) / 255 for v in su(f"od -An -tu1 -v {waveform_file(name)}").split()]


def waveform():
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=4)
    _, _, name = saved()
    levels = waveform_levels(name)
    assert len(levels) == 800, f"{len(levels)} levels read"
    return f"{name}: 800 levels kept"


def ogg_pages(data):
    """[data]'s Ogg pages as (header type, position, packets ended on it,
    whether its CRC holds) - read here, not by the app, as RFC 3533 has it."""
    def crc(page):
        c = 0
        for b in page:
            c ^= b << 24
            for _ in range(8):
                c = ((c << 1) ^ 0x04C11DB7) if c & 0x80000000 else c << 1
            c &= 0xFFFFFFFF
        return c
    pages, at = [], 0
    while at < len(data):
        assert data[at:at + 4] == b"OggS", f"no page at byte {at}"
        segments = data[at + 26]
        lacing = data[at + 27:at + 27 + segments]
        end = at + 27 + segments + sum(lacing)
        page = bytearray(data[at:end])
        stored = int.from_bytes(page[22:26], "little")
        page[22:26] = b"\0\0\0\0"
        pages.append((data[at + 5], int.from_bytes(data[at + 6:at + 14], "little", signed=True),
                      sum(1 for v in lacing if v < 255), crc(bytes(page)) == stored))
        at = end
    return pages


def quality():
    """Settings' Quality reaches the recorder and the file. Each rate, chosen
    as a person chooses it, is the rate the recorder starts the next call at
    (its log), the next recorder takes it from its environment, and the file
    saved has its first audio page alone and no other page longer than a
    second (OggPages), every CRC holding, its last position its length."""
    said = []
    # From the default: a rate already chosen is no change, and the recorder
    # is told nothing - a quality check cut short on 2026-11-17 left the
    # Android 12 and 13 AVDs at 24 kbps, and the next run's choice of 24
    # waited for a word that never came.
    set_prefs(recording_quality=None)
    start_recorder()
    try:
        return _quality(said)
    finally:
        # At the default for the checks after it, whatever it got to - set
        # first: the preference goes by a broadcast, which a force-stopped
        # app does not take, and set after the stop it never arrived, every
        # check after this one recording at 128 kbps (review, 2026-11-18).
        quietly(set_prefs, recording_quality=None)
        # Left on Settings, the next check's `am start` brings Settings
        # back, not the list it looks for (background_playback, 2026-11-16).
        sh(f"am force-stop {PKG}")
        start_recorder_after()


def _quality(said):
    # Ending away from the recorder's own default (32 kbps, Quality.DEFAULT_BPS),
    # so the new recorder below can only have its rate from the environment.
    rates = {}
    for label, kbps in (("Smallest", 24), ("Standard", 32), ("Highest", 128)):
        open_settings_row("Quality", "Smallest", "the Quality dialog")
        sh("logcat -c")
        tap_until(label, lambda: not on_screen("Smallest"), 10, f"{label} chosen")
        wait_log(rf"quality: daemon says QUALITY {kbps * 1000}\b", 15, f"the recorder told {kbps} kbps")
        sh("input keyevent KEYCODE_HOME")
        sh("logcat -c")
        dial(STRANGER_OUT, ring_s=2, talk_s=8)
        _, seconds, name = saved()
        assert re.search(rf"recording: started at {kbps} kbps", log()), f"the recorder did not start at {kbps} kbps"
        data = adb("exec-out", f"cat '{RECORDINGS}/{name}'", text=False)
        pages = ogg_pages(data)
        assert all(ok for *_, ok in pages), f"{name}: a page's CRC does not hold"
        audio = pages[2:]
        assert audio[0][2] == 1, f"{name}: the first audio page holds {audio[0][2]} packets, not one"
        # OggPages joins up to a second from its first packet's end: no more
        # than 48,000 samples, and one 20 ms packet before it.
        longest = max((b[1] - a[1] for a, b in zip(audio, audio[1:])), default=0)
        assert longest <= 48_960, f"{name}: a page holds {longest / 48:.0f} ms"
        length = audio[-1][1] / 48000
        assert abs(length - seconds) < 1, f"{name}: ends at {length:.2f} s, saved as {seconds:.2f} s"
        # The rate the file has, not only the one the recorder said it
        # started at: an encoder that went on at another would pass that.
        rates[kbps] = len(data) * 8 / seconds / 1000
        assert rates[kbps] <= kbps * 1.2, f"{name}: {rates[kbps]:.0f} kbps in the file, chosen {kbps}"
        said.append(f"{kbps} kbps: {len(audio)} pages in {seconds:.1f} s, the longest {longest / 48:.0f} ms, "
                    f"{rates[kbps]:.0f} kbps in the file")
    # Opus's rate is a ceiling, and the emulator's caller - a plain tone -
    # takes less than it may (ARCHITECTURE: 18-30 kbps at 24 and 32, 125-130
    # at 128 on the phone): held to the order, and Highest to well over the
    # others.
    assert rates[24] < rates[32] < rates[128] and rates[128] > 2.5 * rates[32], \
        f"the files' rates are not the choices' order: {rates}"
    # A recorder started now takes the choice from its environment: its
    # spawn line (Main), not the app's word after it ("... from the app",
    # Commands), which a recorder told nothing at its spawn would show too
    # (review, 2026-11-18).
    sh("logcat -c")
    start_recorder()
    spawned = re.search(r"recording: quality 128 kbps\s*$", sh("cat /data/local/tmp/jemrec-capture.out"), re.M)
    assert spawned, "the new recorder did not take 128 kbps from its environment"
    return "; ".join(said) + "; a new recorder at 128 kbps from its environment"


# PlaybackState's states by number: Android 13 and older print the number
# alone ("state=3"), Android 17 its name too ("state=PLAYING(3)").
PLAYBACK_STATES = {"0": "NONE", "1": "STOPPED", "2": "PAUSED", "3": "PLAYING", "6": "BUFFERING", "7": "ERROR"}


def session_read():
    """JemRec's media session from one `dumpsys media_session`: a dict of its
    state (PLAYING, PAUSED, ...), position now in ms, title, the dump's own
    position and update time, and the device's time then - or None.

    The position now, as Android's own controllers work it out: the dump's
    position is where the sound was at the session's last update ("updated",
    elapsed realtime), which Media3 sends every 3 s while playing
    (MediaSessionImpl's DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS), so while
    it plays it is moved on by the time since, at the session's speed - and
    not while paused, which the speed does not say (review, 2026-11-18). Read
    raw, two reads 5 s apart differed by one update or two: background_playback
    measured 3008 ms "played in 5 s" on the Android 13 AVD in both runs of
    2026-11-17, and passed when a second update fell inside its 5 s."""
    dump = sh("dumpsys media_session; echo; echo uptime=$(cat /proc/uptime)")
    uptime = re.search(r"uptime=([\d.]+)", dump)
    now_ms = float(uptime.group(1)) * 1000 if uptime else None  # CLOCK_BOOTTIME, as elapsedRealtime
    for block in dump.split("package=")[1:]:
        if not block.startswith(PKG):
            continue
        state = re.search(r"state=PlaybackState \{state=(?:\w+\()?(\d+)\)?, position=(\d+)"
                          r"(?:, buffered position=-?\d+, speed=([\d.]+), updated=(\d+))?", block)
        title = re.search(r"description=([^,\n]*)", block)
        if state:
            name = PLAYBACK_STATES.get(state.group(1), state.group(1))
            raw = int(state.group(2))
            updated = int(state.group(4)) if state.group(4) else 0
            position = raw
            if name == "PLAYING" and state.group(3) and now_ms is not None and updated > 0:
                position += int((now_ms - updated) * float(state.group(3)))
            return {"state": name, "position": position, "title": title.group(1).strip() if title else "",
                    "raw": raw, "updated": updated, "now": now_ms,
                    "speed": float(state.group(3)) if state.group(3) else 1.0}
    return None


def session():
    """JemRec's media session: (state, position now in ms, title) - or None
    (session_read)."""
    read = session_read()
    return (read["state"], read["position"], read["title"]) if read else None


# A move opens the file again (SessionPlayer.reopen) and seeks to where the
# sound was. Two measures of it. The aim: where the reopen asks the player to
# seek, from its own log line, against where the session said the sound was
# at that moment - both the playhead's clock (PlayClock), so 0 to the ms
# (both AVDs, 2026-11-18); past AIM_MS it aims somewhere else, and no line at
# all is a move that did not open the file again. A player that went
# elsewhere than it was asked is across's to see: the aim is only what was
# asked (SessionPlayerTest's engine is a fake, which goes where it is told).
# Across: the session's position across the move against the
# device's own clock - a move done right reads below 0 by the gap it leaves
# (-117 to -160 ms on the Android 17 AVD, 2026-11-18); past SKIPPED_MS its
# sound ran on ahead, by more than that gap and SKIPPED_MS together - about a
# quarter second - and below WENT_BACK_MS it went back, started again, say.
# The aim catches a seek a few dozen ms off, which across alone let through
# up to about 250 ms ahead (review, 2026-11-18). Neither sees the clock
# itself wrong: both read it (PlayClockTest holds it).
AIM_MS = 50
SKIPPED_MS = 100
# A move done right reads -75 to -160 ms (both AVDs, 2026-11-18): below this
# it landed short of where the sound was - by more than an inexact seek's
# own error - and the call played on from earlier (review, 2026-11-18).
WENT_BACK_MS = -400


def assert_went_on(skipped, what):
    """[skipped]: (aim, across), as skipped_across returns them."""
    aim, across = skipped
    assert abs(aim) <= AIM_MS, f"{what}, it opened again {aim:.0f} ms from where the sound was"
    assert across < SKIPPED_MS, f"{what}, {across:.0f} ms of the recording skipped"
    assert across > WENT_BACK_MS, f"{what}, it went {-across:.0f} ms back"


def reopen_aim(before):
    """Where the reopen after [before] (a session_read) aimed against where
    the session said the sound was then, in ms: its log line's position less
    the session's at the line's elapsed time - moved on at its speed while it
    played. The first such line after [before] was read, by its time: a move
    may clear the log first."""
    def line():
        for m in re.finditer(r"player: opened again at (\d+) ms, elapsed (\d+)", log()):
            if int(m.group(2)) > before["now"]:
                return m
        return None
    m = wait_for(line, 10, "the recording to open again")
    at, elapsed = int(m.group(1)), int(m.group(2))
    expected = before["raw"]
    if before["state"] == "PLAYING":
        expected += (elapsed - before["updated"]) * before["speed"]
    return at - expected


def skipped_across(move, settle_s=4.0):
    """[move]'s (aim, across), in ms, playing throughout (assert_went_on):
    where its reopen aimed against where the sound was then (reopen_aim), and
    how far the session's position ran ahead of the device's own clock
    across it - about 0, or below by the gap a move leaves; above, what the
    move skipped. Media3 1.11.1's audio sink lets its AudioTrack go with new
    audio attributes, with up to 500 ms in it not played yet, and went on
    from the next buffer (review, 2026-11-18): a raise skipped 390 ms of the
    call, on this branch before the move became a reopen. Read [settle_s]
    after the move, past the session's next update (every 3 s while
    playing); each read's time is its own dump's."""
    before = session_read()
    assert before, "no media session of JemRec's to read"
    move()
    aim = reopen_aim(before)
    time.sleep(settle_s)
    after = session_read()
    assert after, "JemRec's media session went with the move"
    return aim, (after["position"] - before["position"]) - (after["now"] - before["now"])


def session_is(state):
    s = session()
    return s if s and s[0] == state else None


def digits(text):
    return re.sub(r"\D", "", text or "")


def tap_text(digits_in):
    """Tap the first node on screen whose text holds [digits_in]."""
    return tap_node(lambda node: digits_in in digits(re.search(r' text="([^"]*)"', node).group(1)))


def no_underrun(what):
    """Normal play said no underrun: each puts the playhead back to the
    player's word and holds it there (PlayClock.underrun) - the stepping the
    clock is there to remove, which no check looked for once the underrun was
    handled (review, 2026-11-18). Every underrun the player reports, not
    only one the clock acted on: it starved the sound either way. Read before
    each clearing of the log in a playback check, so no stretch of play goes
    unread."""
    assert "player: underrun" not in log(), f"the audio track ran short {what}"


def speaker_backlog():
    """The emulator's speaker output: audio written to it and not yet played,
    in ms - AudioFlinger's own "Threadloop write latency" for the speaker's
    mixer thread, from the live part of `dumpsys media.audio_flinger` (before
    its history of closed threads). None before it has played at all."""
    live = sh("dumpsys media.audio_flinger").split("Historical")[0]
    for block in live.split("Output thread ")[1:]:
        if "AUDIO_DEVICE_OUT_SPEAKER" in block:
            found = re.search(r"Threadloop write latency stats: ave=([\d.]+)", block)
            return float(found.group(1)) if found else None
    return None


def fresh_audio_server():
    """The audio server started afresh before a check that times playback
    against the clock; the speaker backlog it had, for the check's line.
    An emulator's speaker builds a backlog of audio written and not yet
    played that nothing drains until the audio server restarts: on the
    Android 13 AVD alone, 1510 ms after the 24 checks run before these, and
    about 0.35 ms a second from ringing alone, growing in jumps when the
    Mac's audio stalls under load (inferred). A player started behind it is
    heard that much late, and these checks read that as JemRec's playhead
    running ahead: background_playback, both_ears and raise_to_listen failed
    first and passed on a fresh boot in two runs of three on 12 and 13.
    Restarted, the same checks passed on the same boot (2026-11-19). A
    phone's audio hardware keeps its own clock; there is no host to fall
    behind. The recorder holds no capture between calls, so it loses
    nothing; audio_server_restart is the check of a restart mid-call."""
    backlog = speaker_backlog()
    server = sh("pidof audioserver").strip()
    assert server, "no audio server to start afresh"
    su(f"kill -9 {server}")
    wait_for(lambda: sh("pidof audioserver").strip() not in ("", server), 15, "the audio server back")
    time.sleep(3)  # its outputs opened again
    return "no speaker backlog yet" if backlog is None else f"a speaker backlog of {backlog:.0f} ms cleared"


def background_playback():
    """Played from its row, a recording goes on in PlaybackService with the
    screen off, named for the lock screen; a call holds it back."""
    fresh = fresh_audio_server()
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=30)
    saved()
    try:
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: tap_text(STRANGER_OUT), 30, "the recording's row to tap")
        wait_for(lambda: session_is("PLAYING"), 10, "the recording to play")
        sh("input keyevent KEYCODE_SLEEP")
        time.sleep(2)
        first = session_read()
        before, title = first["position"], first["title"]
        time.sleep(5)
        second = session_read()
        state, after = second["state"], second["position"]
        assert state == "PLAYING" and after - before >= 4000, \
            f"with the screen off it went {state}, {before} -> {after} ms in 5 s"
        # Moved on by the app's own updates, not only worked out from the
        # last: Media3 updates the session every 3 s while it plays, and a
        # process frozen or starved updates nothing while its session still
        # says PLAYING (review, 2026-11-18).
        assert second["updated"] > first["updated"], "with the screen off the app updated its session not once in 5 s"
        assert title == "Call recording", f"with the screen off it is called {title!r}"
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
        wait_for(lambda: STRANGER_OUT in digits((session() or ("", 0, ""))[2]), 10,
                 "the caller's number back once unlocked")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_for(lambda: session_is("PAUSED"), 15, "a ringing call to hold it back")
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_for(phone_idle, 15, "the ringing call to end")
        wait_for(lambda: session_is("PLAYING"), 10, "it to play on after the call")
        # Its sound under way again, not only the session's word (said on
        # focus, before anything is written), and a few seconds of it.
        time.sleep(3)
        no_underrun("as it played, the screen off and on, and held back by a call")
    finally:
        sh(f"am force-stop {PKG}")
    return f"{fresh}; {after - before} ms played in 5 s with the screen off, as \"{title}\"; held back by a call, then on"


def both_ears():
    """Settings' "Both sides in both ears" reaches what plays: on unless
    switched off, a recording opens mixed; switched off while it plays -
    through the app's own preferences, which PlaybackService watches - it
    opens again with the sides apart, from where it was, and plays on; and
    back. What the ears hear is the Honor's to say (2026-11-16): this is the
    wiring."""
    fresh = fresh_audio_server()
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=30)
    saved()
    uid = app_uid()
    try:
        set_prefs(both_ears=None)
        sh("logcat -c")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: tap_text(STRANGER_OUT), 30, "the recording's row to tap")
        wait_for(lambda: session_is("PLAYING"), 10, "the recording to play")
        wait_log(r"player: open, both sides in both ears", 10, "the recording to open mixed")
        said = []
        for on, words in ((False, "each side in its own ear"), (True, "both sides in both ears")):
            time.sleep(3)

            def switch():
                no_underrun("as it played")
                sh("logcat -c")
                set_prefs(both_ears=on)
                wait_log(rf"player: open, {words}", 10, f"the player to open again, {words}")
                wait_for(lambda: session_is("PLAYING"), 10, "it to play on")
                # Its sound, not only the session's word: the session says
                # PLAYING for a player set to play whether or not one sounds
                # (review, 2026-11-18).
                wait_for(lambda: any(state == "started" for state, _ in players(uid)), 10,
                         f"the new player's sound, {words}")

            # From where it was: measured as a move to the earpiece is.
            skipped = skipped_across(switch)
            assert_went_on(skipped, f"switched to {words}")
            said.append(f"{words}, aimed {skipped[0]:+.0f} ms, {skipped[1]:.0f} ms against the clock")
        said.append(stalled_sound())
    finally:
        quietly(set_prefs, both_ears=None)
        sh(f"am force-stop {PKG}")
    return f"{fresh}; opened mixed; " + "; ".join(said)


def stalled_sound():
    """The sound stalled under the playhead: the app's process stopped for
    0.8 s as it plays - its player feeds nothing and the audio track runs
    dry - and let go on. The track says it starved (Media3's
    onAudioUnderrun), and the playhead goes on from the player's word
    (PlayClock.underrun): the session's place, across the stall, falls
    behind the device's clock by the time the sound stood still. Left where
    it was, the clock ran that far ahead of the sound for good, under the
    second it takes to see a stall itself, and a move then skipped as much
    (review, 2026-11-18)."""
    time.sleep(2)
    no_underrun("opened again, before the stall")
    sh("logcat -c")
    before = session_read()
    pid = sh(f"pidof {PKG}").strip()
    assert pid, "no process of JemRec's to stop"
    # Stopped and let go in one shell on the device, timed there: two adb
    # calls put their own round trip into the stop. 0.8 s, less what the
    # audio track holds (Media3 1.11's PCM buffer is 500 ms, refilled at
    # half - 250 to 500 ms in it), leaves the
    # sound standing for well under the second PlayClock takes for a stall
    # by itself (MOST_AHEAD_MS) - the case the underrun is for: 1.2 s, as
    # this was first, stood long enough for the old guard alone to catch it
    # (review, 2026-11-18).
    su(f"kill -STOP {pid}; sleep 0.8; kill -CONT {pid}")
    wait_log(r"player: the audio track ran dry", 10, "the audio track to say it ran dry")
    time.sleep(4)
    after = session_read()
    behind = (after["position"] - before["position"]) - (after["now"] - before["now"])
    assert -750 < behind < -100, \
        f"stalled, the playhead went on {behind:.0f} ms against the clock - ahead of the sound, or more than stood still"
    return f"stalled 0.8 s: the track ran dry, the playhead {behind:.0f} ms against the clock"


def app_uid():
    # `dumpsys package` names it userId= on older Androids and appId= on 17.
    return re.search(rf"package:{re.escape(PKG)} uid:(\d+)", sh(f"pm list packages -U {PKG}")).group(1)


def players(uid):
    """JemRec's players as `dumpsys audio` lists them: (state, usage) each."""
    return re.findall(rf"AudioPlaybackConfiguration piid:\d+ .*?u/pid:{uid}/\d+ state:(\w+) "
                      r"attr:AudioAttributes: usage=(\w+)", sh("dumpsys audio"))


def audio_mode():
    """The phone's audio mode, as `dumpsys audio` says it: NORMAL, IN_CALL, ..."""
    dump = sh("dumpsys audio")
    named = re.search(r"Actual mode = MODE_(\w+)", dump)
    if named:
        return named.group(1)
    numbered = re.search(r"mMode=(\d+)", dump)
    return {"0": "NORMAL", "1": "RINGTONE", "2": "IN_CALL", "3": "IN_COMMUNICATION"}.get(
        numbered.group(1), numbered.group(1)) if numbered else None


def ear_lock():
    """Whether JemRec holds the lock that darkens the screen at the ear: in
    `dumpsys power`'s wake locks held now, not in its history of them."""
    held = re.search(r"Wake Locks: size=\d+\n(.*?)\n\s*\n", sh("dumpsys power"), re.S)
    return bool(held) and "'jemrec:ear'" in held.group(1)


def play_row(digits_in):
    """Tap the row whose text holds [digits_in] until its recording plays. A
    tap right after a cold start can be lost: the list's tip comes in a moment
    later and moves the rows down (the Android 17 AVD, 2026-11-15). Tapped
    again only after 4 s still not playing - a second tap would pause it -
    where a tap that lands plays in under one."""
    if not tap_text(digits_in):
        return None
    try:
        return wait_for(lambda: session_is("PLAYING"), 4, "the recording to play")
    except TimeoutError:
        return None


def covered():
    """Whether JemRec covers its screen at the ear (EarCover): its window at
    the lowest brightness, `sbrt=0.0` among the window's attributes in
    `dumpsys window windows`."""
    for block in sh("dumpsys window windows").split("Window{")[1:]:
        head = block.split("\n", 1)[0]
        if f"{PKG}/" in head and "MainActivity" in head:
            return "sbrt=0.0" in block
    return False


def proximity(cm):
    """The emulator's proximity sensor, through its console: 0 near, 5 far."""
    adb("emu", "sensor", "set", "proximity", str(cm))


def seek_preroll():
    """Where the sound after a seek begins, against where it was sent (1.4).
    Android's Opus decoder drops its 80 ms pre-roll after every flush, and
    Media3 feeds it from the target's own packet: until 1.4 the sound after a
    seek began 53.5-73.5 ms after the target on the Android 12 AVD and
    73.5-92.5 ms after it on 17 (2026-11-20, 20 seeks each, a probe matching
    the sink's input against a straight decode). OpusPreRoll sends the player
    80 ms early once its decoder is up, so the decoder's own drop ends there;
    a seek before the decoder is up (a reopen) goes where it is sent. The diag
    op SeekCheckOp encodes 20 s of noise with Android's own Opus encoder,
    writes it with the app's Ogg writer, decodes it straight for the
    reference, and plays it through an ExoPlayer built as RecordingPlayer
    builds its own, sent as OpusPreRoll says: six seeks while it plays, three
    while paused and then played, and three files opened at a place, as a
    reopen is (opened and then sent there, as reopens were, one of three in a
    run lost 74 ms on the 17 AVD, 2026-11-20). Each seek's sound, matched against the
    reference from 20 ms in, begins between 40 ms before its target and 30 ms
    after it. Before, as the early send can leave it: the decoder's drop ends
    at a packet's start, up to a packet and the stream's 6.5 ms pre-skip
    short of the target - measured -13 to -32 ms on 12 (2026-11-20), a
    repeat, not a loss. From 20 ms in: a fresh decoder decodes its first
    packet with no pre-roll, not quite as a straight decode does - matched
    0.85-0.87 from its first sample on a packet's start, 12 and 17 - said as
    r0, not judged. RecordingPlayer's own use of the rule is unit-tested;
    this is Android's decoder doing what the rule counts on."""
    fresh = fresh_audio_server()
    said = diag("seekcheck", wait=300)
    assert "seekcheck: done" in said, f"the seek check did not finish: {said[-400:]}"
    rows = [(way, int(target), float(skip), float(r), float(r0))
            for way, target, skip, r, r0 in
            re.findall(r"seek (\w+) (\d+) skip (-?[\d.]+) r ([\d.]+) r0 ([\d.]+)", said)]
    assert len(rows) == 12, f"12 seeks measured, not {len(rows)}: {said[-600:]}"
    every = "every seek (way, target ms, skip ms, r, r0): " + ", ".join(str(row) for row in rows)
    unmatched = [row for row in rows if row[3] < 0.9]
    assert not unmatched, f"seeks whose sound matched no place near their target: {unmatched}; {every}"
    off = [row for row in rows if not -40 <= row[2] <= 30]
    assert not off, f"seeks whose sound began more than 40 ms before or 30 ms after their target: {off}; {every}"
    by_way = {}
    for way, _, skip, _, _ in rows:
        by_way.setdefault(way, []).append(skip)
    return (f"{fresh}; " + ", ".join(f"{way} {min(s):+.1f} to {max(s):+.1f} ms" for way, s in by_way.items())
            + f"; lowest r0 {min(row[4] for row in rows):.3f}")


def raise_to_listen():
    """Raised to the ear - the proximity sensor set near through the
    emulator's console - a recording playing from its row moves to the
    earpiece: voice-call audio, the lock that darkens the screen held, the
    screen covered, and the audio mode left normal - which is what keeps the
    recorder from taking it for a messenger call: it counts one only in
    communication mode (Voip.java; measured on the Honor with four messengers
    watched, 2026-11-15; no check here switches a messenger on). Lowered,
    it goes back to the loudspeaker and plays on. A call answered, JemRec
    off the screen and the phone raised, moves it to the earpiece while it
    waits, and it plays on there. The player's button moves
    it too; and with Earpiece in Settings, a recording opens there. An
    emulator has no earpiece: the debug build acts as if it had one (the
    `earpiece` diag op), so what is checked is all but the sound."""
    fresh = fresh_audio_server()
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=30)
    saved()
    uid = app_uid()
    try:
        proximity(5)
        sh(f"am start -n {PKG}/.MainActivity")
        diag("earpiece", "--ez", "on", "true")
        # Opened again, so the screen reads the earpiece it now has.
        sh(f"am force-stop {PKG}")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: play_row(STRANGER_OUT), 40, "the recording's row to play it")
        wait_for(ear_lock, 10, "the lock that darkens the screen at the ear")
        wait_for(lambda: ("started", "USAGE_MEDIA") in players(uid), 10, "it to play on the loudspeaker")
        time.sleep(1)  # past the quiet start, when a reading waits (AtTheEar)

        def raise_it():
            proximity(0)
            wait_for(lambda: ("started", "USAGE_VOICE_COMMUNICATION") in players(uid), 10,
                     "the recording to move to the earpiece")

        skipped = skipped_across(raise_it)
        assert_went_on(skipped, "raised")
        assert session_is("PLAYING"), f"raised, it is {session()}"
        wait_for(covered, 10, "JemRec to cover its screen at the ear")
        mode = audio_mode()
        assert mode == "NORMAL", f"raised, the audio mode is {mode}"
        time.sleep(1)  # past the quiet after the move

        def lower_it():
            proximity(5)
            wait_for(lambda: ("started", "USAGE_MEDIA") in players(uid), 10,
                     "the recording back on the loudspeaker as it is lowered")

        skipped_down = skipped_across(lower_it)
        assert_went_on(skipped_down, "lowered")
        assert session_is("PLAYING"), f"lowered, it is {session()}"
        wait_for(lambda: not covered(), 10, "the cover to go as the phone is lowered")
        assert ear_lock(), "lowered, the lock was let go while it plays on"
        # A call holds it back. Answered, the call's screen over JemRec's,
        # and raised for the call: it is moved to the earpiece while it
        # waits, and plays on there after the call. Unwatched under the
        # call's screen it played on 1.8 s from the loudspeaker at the ear
        # (the Honor, 2026-11-16), until JemRec's screen came back.
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_for(lambda: session_is("PAUSED"), 15, "a ringing call to hold it back")
        sh("input keyevent KEYCODE_CALL")
        wait_for(lambda: audio_mode() == "IN_CALL", 15, "the call to be answered")
        # The Honor's call screen covers JemRec's; answered by the call key,
        # the Android 12 and 13 AVDs left JemRec in front (2026-11-16), so
        # it is sent Home there - off the screen, as under the call's.
        def in_front():
            return PKG in sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:'")
        try:
            wait_for(lambda: not in_front(), 5, "")
        except TimeoutError:
            sh("input keyevent KEYCODE_HOME")
        wait_for(lambda: not in_front(), 10, "JemRec's screen gone under the call")
        time.sleep(1)  # past the quiet start of the watch under the call
        proximity(0)
        wait_for(lambda: any(u == "USAGE_VOICE_COMMUNICATION" for _, u in players(uid)), 10,
                 "the recording to move to the earpiece under the call")
        assert session_is("PAUSED"), f"under the call, it is {session()}"
        hang_up()
        wait_for(lambda: ("started", "USAGE_VOICE_COMMUNICATION") in players(uid), 15,
                 "it to play on at the earpiece after the call")
        time.sleep(1)  # past the quiet after the move
        proximity(5)
        wait_for(lambda: ("started", "USAGE_MEDIA") in players(uid), 10,
                 "the loudspeaker as it is lowered after the call")
        sh(f"am start -n {PKG}/.MainActivity")
        tap_until("Playing through the loudspeaker - tap for the earpiece",
                  "Playing through the earpiece - tap for the loudspeaker", 15, "the player's earpiece button")
        wait_for(lambda: ("started", "USAGE_VOICE_COMMUNICATION") in players(uid), 10,
                 "the button to move it to the earpiece")
        # Paused, moved back by the button, played: it goes on from where the
        # pause left it. Read once the sound has run 4 s, so the player's own
        # word has had its say over the clock (above), against where the
        # pause held it and the moment the session said it played - its
        # first word's update time, not the media key's, whose `input`
        # command took up to half a second to start (2026-11-18).
        sh("input keyevent KEYCODE_MEDIA_PAUSE")
        wait_for(lambda: session_is("PAUSED"), 10, "the media key to pause it")
        time.sleep(1)
        paused = session_read()
        held = paused["position"]
        tap_until("Playing through the earpiece - tap for the loudspeaker",
                  "Playing through the loudspeaker - tap for the earpiece", 15, "the button, paused")
        # Opened again where the pause held it: both the clock's place, so
        # this cannot see the clock itself wrong at a pause (PlayClockTest).
        aim_paused = reopen_aim(paused)
        time.sleep(1)
        sh("input keyevent KEYCODE_MEDIA_PLAY")
        went_on = wait_for(lambda: (lambda r: r if r and r["state"] == "PLAYING" else None)(session_read()), 10,
                           "the media key to play it on", step=0.1)
        time.sleep(4)
        later = session_read()
        skipped_paused = (aim_paused, (later["position"] - held) - (later["now"] - went_on["updated"]))
        assert_went_on(skipped_paused, "paused and moved")
        # Every move opens a new audio track - at the ear, as voice-call
        # audio - and none ran short in all of them (review, 2026-11-18).
        no_underrun("moved between the loudspeaker and the earpiece")
        # Earpiece in Settings: the next recording opens there. The screen
        # reads the setting as it opens: opened again after.
        diag("pref", "--es", "key", "play_through", "--es", "value", "EARPIECE")
        sh(f"am force-stop {PKG}")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: play_row(STRANGER_OUT), 40, "the recording's row to play it with Earpiece set")
        wait_for(lambda: ("started", "USAGE_VOICE_COMMUNICATION") in players(uid), 10,
                 "it to open at the earpiece")
        assert ("started", "USAGE_MEDIA") not in players(uid), f"it played on the loudspeaker too: {players(uid)}"
        mode = audio_mode()
        assert mode == "NORMAL", f"at the earpiece by Settings, the audio mode is {mode}"
    finally:
        proximity(5)
        # A step that failed under the call leaves it up for the checks after;
        # said, not raised, so the step's own failure is the one reported.
        try:
            if not phone_idle():
                hang_up()
        except Exception as e:
            print(f"     (the call could not be ended afterwards: {e})")
        try:
            quietly(diag, "pref", "--es", "key", "play_through")
            quietly(diag, "earpiece", "--ez", "on", "false")
        finally:
            sh(f"am force-stop {PKG}")
    return (f"{fresh}; raised: voice-call audio, aimed {skipped[0]:+.0f} ms, {skipped[1]:.0f} ms ahead of the clock, "
            f"screen covered, mode NORMAL, lock held; "
            f"lowered: the loudspeaker, aimed {skipped_down[0]:+.0f} ms, {skipped_down[1]:.0f} ms, playing on; "
            f"paused, moved and played: aimed {skipped_paused[0]:+.0f} ms, {skipped_paused[1]:.0f} ms; "
            "raised under a call: the earpiece while held back, playing on there; "
            "the button moves it; Earpiece in Settings opens it there")


def windows():
    """How many of JemRec's main screens Android holds, in any task."""
    return len(re.findall(r"Hist\s+#\d+: ActivityRecord\{[^}]*" + re.escape(PKG) + r"/\S*MainActivity",
                          sh("dumpsys activity activities")))


# MainActivity.open's flags: NEW_TASK, CLEAR_TOP and SINGLE_TOP, as the tile,
# the media notification and every notification start JemRec.
OPEN_FLAGS = "0x34000000"


def one_window():
    """JemRec is one window by its own ways in. Begun by one of them - as
    from a notification, so the task's first intent is not the launcher's -
    the same way in again and the tile's long press, which Android starts,
    leave one main screen; a folder picker opened over it stays when the
    launcher's intent comes (`am start` with Launcher3's flags for an icon's
    tap; the window it makes then goes at once), and JemRec's own way in
    finishes it."""
    # Launcher3's flags for an icon's tap: NEW_TASK and RESET_TASK_IF_NEEDED.
    launcher = (f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                f"-f 0x10200000 -n {PKG}/.MainActivity")
    try:
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
        sh(f"am force-stop {PKG}")
        sh(f"am start -W -n {PKG}/.MainActivity -f {OPEN_FLAGS}")
        wait_for(lambda: windows() == 1, 15, "JemRec open")
        sh(f"am start -W -n {PKG}/.MainActivity -f {OPEN_FLAGS}")
        sh(f"am start -W -a android.service.quicksettings.action.QS_TILE_PREFERENCES -n {PKG}/.MainActivity")
        time.sleep(2)
        assert windows() == 1, f"{windows()} windows after JemRec's way in and the tile's long press"
        tap_until("Settings", "Back", 30, "the Settings gear")
        scroll_to("Saving to")
        tap_until("Saving to", "Change", 15, "the folder question")
        tap_until("Change", lambda: on_screen_any_case("Use this folder"), 15, "Android's folder picker")
        sh("input keyevent KEYCODE_HOME")
        sh(launcher)
        time.sleep(2)
        assert on_screen_any_case("Use this folder"), "the launcher did not bring the picker back"
        assert windows() == 1, f"{windows()} windows after the launcher over the picker"
        sh(f"am start -W -n {PKG}/.MainActivity -f {OPEN_FLAGS}")
        wait_for(lambda: not on_screen_any_case("Use this folder"), 10, "JemRec's own way in to finish the picker")
        assert windows() == 1, f"{windows()} windows after JemRec's way in over the picker"
    finally:
        sh(f"am force-stop {PKG}")
    return "one window: its own way in, the tile's long press, the launcher over a picker (kept), its way in (picker gone)"


def process_death():
    """Android may end JemRec's process while it is out of sight, and the
    user coming back finds it where they left it: the screen and the home
    tab, from the window's saved state (SavedPlace). Made to happen with
    `am kill`, which ends an app's process only where that is safe - in the
    background - as Android ends one for the memory (adb's reference: "kills
    only processes that are safe to kill"); come back to by the launcher's
    intent, which brings the task forward. Until 1.2 it came back on home's
    first tab."""
    launcher = (f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                f"-f 0x10200000 -n {PKG}/.MainActivity")

    def tab_picked(label):
        # Compose marks the tab selected and draws its name in a child:
        # the Android 17 AVD's dump has selected="true" on a View with no
        # text, and "Calls" on the TextView inside it (2026-11-18).
        try:
            tabs = ElementTree.fromstring(ui()).iter("node")
        except ElementTree.ParseError:
            return False  # a dump cut short: look again
        return any(tab.get("selected") == "true" and any(label in (n.get("text"), n.get("content-desc")) for n in tab.iter("node"))
                   for tab in tabs)

    def in_front():
        return PKG in sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:'")

    try:
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
        sh(f"am force-stop {PKG}")
        sh(f"am start -W -n {PKG}/.MainActivity -f {OPEN_FLAGS}")
        tap_until("Calls", lambda: tab_picked("Calls"), 30, "the Calls tab")
        tap_until("Settings", "Back", 30, "the Settings gear")
        sh("input keyevent KEYCODE_HOME")
        wait_for(lambda: not in_front(), 15, "JemRec off the screen")
        process = sh(f"pidof {PKG}").strip()
        assert process, "no process to end"
        # Android saves the window's state as it stops it; a kill before that
        # would test nothing.
        time.sleep(2)
        sh(f"am kill {PKG}")
        wait_for(lambda: sh(f"pidof {PKG}").strip() != process, 15, "the process to end")
        assert windows() == 1, f"{windows()} main screens held after the kill, where Android keeps one"
        sh(launcher)
        wait_for(lambda: in_front() and on_screen("Back"), 20, "JemRec back on Settings")
        assert sh(f"pidof {PKG}").strip() != process, "the same process: nothing was ended"
        tap_until("Back", lambda: tab_picked("Calls"), 20, "home on the Calls tab")
    finally:
        sh(f"am force-stop {PKG}")
    return "ended in the background: back on Settings, and Back to the Calls tab"


def tap_node(found):
    """Tap the middle of the first node on screen that [found] picks."""
    for node in re.findall(r"<node [^>]*>", ui()):
        if found(node):
            x1, y1, x2, y2 = bounds(node)
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return True
    return False


def labels(node):
    """What uiautomator says a node shows: its text and content description."""
    return re.findall(r'(?:text|content-desc)="([^"]*)"', node)


def bounds(node):
    """A node's (left, top, right, bottom) on screen."""
    return tuple(int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())


def tap(label):
    """Tap the first node whose text or content description is [label]."""
    return tap_node(lambda node: label in labels(node))


def tap_id(resource_id):
    """Tap the first node with [resource_id]."""
    return tap_node(lambda node: f'resource-id="{resource_id}"' in node)


def on_screen(label):
    return f'"{label}"' in ui()


def header_says(words, dump=None):
    """Whether home's header says [words] first. Each look's header is one
    node to accessibility, the sentence TalkBack hears (headerSpeech), and a
    dump is what accessibility gets: since 1.4 Memo's title is no node of its
    own either - TalkBack said it twice (the Honor, 2026-11-20)."""
    return f'content-desc="{words}' in (dump if dump is not None else ui())


def tap_until(label, shown, timeout, what):
    """Tap [label] until [shown] is on screen - or, a function, says it is.
    A tap can be lost: on the Android 17 AVD, the gear tapped at the first
    dump that showed it, ~3 s after `am start`, opened nothing six times in
    six, and one dump later ten in ten (2026-10-25); and a tap on a list
    still moving after a swipe only stops it."""
    end = time.time() + timeout
    while time.time() < end:
        if tap(label):
            try:
                return wait_for(shown if callable(shown) else lambda: on_screen(shown), 3, what)
            except TimeoutError:
                continue
        time.sleep(0.5)
    raise TimeoutError(what)


def scroll_to(label):
    """Scroll until [label] is drawn in the top two thirds of the screen,
    where a tap reaches it. Listed is not enough: uiautomator lists a row
    still below the edge, with no size (bounds [0,0][0,0], the Android 12
    AVD), and a row low in Android's folder picker sits under its button
    bar (the Honor) - a tap on either opened nothing (2026-10-26). Small
    swipes, so a row cannot jump past that window. Never listed at all, it
    is a failure of its own, not of the tap after it."""
    width, height = (int(v) for v in re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1])
    seen = False
    for _ in range(12):
        node = next((n for n in re.findall(r"<node [^>]*>", ui()) if label in labels(n)), None)
        if node:
            seen = True
            _, top, _, bottom = bounds(node)
            if top < bottom and top < height * 2 // 3:
                return
        sh(f"input swipe {width // 2} {height * 2 // 3} {width // 2} {height // 3} 400")
        time.sleep(0.5)
    if not seen:
        raise TimeoutError(f"{label} anywhere on the screen")


def open_settings_row(row, shown, what):
    """A row of Settings, reached as a person reaches it: the gear, then the
    row, scrolled to and tapped until [shown] is on screen."""
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {PKG}/.MainActivity")
    tap_until("Settings", "Back", 30, "the Settings gear")
    scroll_to(row)
    return tap_until(row, shown, 15, what)


def open_trash():
    """Recently deleted, from its row in Settings."""
    return open_settings_row("Recently deleted", "Restore all", "Recently deleted, with Restore all")


def trash_place(name, move=False, purge=False):
    """Where the recording [name] is - (in the list, in Recently deleted) - as
    the debug build's diag 'trash' finds it: after moving it to Recently
    deleted as the list's delete does, or after the daily pass, if asked."""
    said = diag("trash", "--es", "name", name, *(("--ez", "move", "true") if move else ()),
                *(("--ez", "purge", "true") if purge else ()))
    m = re.search(r"listed=(\w+) trashed=(\w+)", said)
    assert m, said
    return (m.group(1) == "true", m.group(2) == "true"), said


def files_named(name, folder):
    return [f for f in sh(f"ls -a {folder}/").split() if name in f]


def after_call_up():
    """Whether the last call's notification is up (AfterCall's id 33)."""
    return re.search(rf"NotificationRecord\(.*pkg={PKG} .*id=33 ", sh("dumpsys notification --noredact")) is not None


def restore_then_delete(name, folder):
    """[name] moved to Recently deleted comes back with Restore all, tapped
    there; moved again, Delete all for good and its question remove it, its
    file in [folder] with it. The file's name while it waited."""
    wait_for(after_call_up, 20, "the after-call notification")
    place, said = trash_place(name, move=True)
    assert place == (False, True), f"not moved to the trash: {said}"
    wait_for(lambda: not after_call_up(), 5, "the notification to go with the recording")
    open_trash()
    tap("Restore all")
    wait_for(lambda: trash_place(name)[0] == (True, False), 20, "Restore all to bring it back")
    assert files_named(name, folder) == [name], f"restored, its files are {files_named(name, folder)}"
    place, said = trash_place(name, move=True)
    assert place == (False, True), f"not moved to the trash again: {said}"
    trashed = files_named(name, folder)
    open_trash()
    tap("Delete all for good")
    wait_for(lambda: on_screen("Delete for good"), 10, "the question")
    tap("Delete for good")
    wait_for(lambda: trash_place(name)[0] == (False, False), 20, "Delete all for good to remove it")
    assert not files_named(name, folder), f"deleted for good, its files are {files_named(name, folder)}"
    return trashed[0] if trashed else "?"


def recently_deleted():
    """A recording moved to Recently deleted (the debug build's diag 'trash',
    as the list's delete moves it) comes back with Restore all, tapped there;
    moved again, Delete all for good and its question remove it - from
    MediaStore's trash, which Robolectric cannot run, and its file with it."""
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=4)
    _, _, name = saved()
    try:
        trashed = restore_then_delete(name, RECORDINGS)
    finally:
        sh(f"am force-stop {PKG}")
    return f"restored by Restore all; deleted for good, {trashed} gone"


PICKED = "JemRecPicked"  # in /sdcard; recently_deleted_folder picks it


def on_screen_any_case(label):
    """[on_screen] in any case. Android's folder picker wrote its buttons in
    capitals on the 12 and 17 AVDs and the Honor ("USE THIS FOLDER",
    2026-10-26); a picker that does not still matches."""
    return f'"{label.upper()}"' in ui().upper()


def press(label):
    """Tap the first node showing [label], in any case - or, where
    uiautomator gives it no size, move the keyboard's focus to it and press
    Enter: the Android 12 AVD draws the folder picker's button bar below
    what it reports as the window's bottom, so the button's bounds are
    [0,0][0,0] and a tap cannot be aimed (2026-10-26)."""
    def node():
        return next((n for n in re.findall(r"<node [^>]*>", ui())
                     if label.upper() in (t.upper() for t in labels(n))), None)
    found = node()
    if found is None:
        return False
    x1, y1, x2, y2 = bounds(found)
    if y1 < y2:
        sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
        return True
    for _ in range(12):
        if 'focused="true"' in (node() or ""):
            sh("input keyevent KEYCODE_ENTER")
            return True
        sh("input keyevent KEYCODE_TAB")
        time.sleep(0.4)
    return False


def settings_written(what):
    """The app's settings file written for good: its old copy
    (jemrec.xml.bak) gone and the file the same a second apart.

    Android moves the old file aside, writes the new one, and deletes the old
    last; a process killed in that gap gets the old one back
    (SharedPreferencesImpl). Choosing a folder writes it twice - the folder,
    then the flag "said" taken back - and folder_lost force-stopped the app
    some 60 ms after the new file could be read: the folder, or the flag's
    taking back, was lost, so nothing was said of it - in 18 of 27 tries on
    the 13 and 16 AVDs, with the app from before 1.7's change too
    (2026-11-23); waiting for the .bak alone left 2 of 12."""
    def snap():
        if ".bak" in su(f"ls {APP_DIR}/shared_prefs/"):
            return None
        return su(f"md5sum {APP_DIR}/{PREFS}")

    def quiet():
        one = snap()
        time.sleep(1)
        return one and one == snap()

    wait_for(quiet, 20, what, step=0.2)


def pick_folder(name):
    """Settings' Saving to, Change, and /sdcard/[name] chosen in Android's own
    folder picker, as a person chooses it: from the phone's storage, which the
    picker may open somewhere else, then Use this folder and Allow."""
    open_settings_row("Saving to", "Change", "the folder question")
    tap_until("Change", lambda: on_screen_any_case("Use this folder"), 15, "Android's folder picker")
    if not on_screen(name):
        model = sh("getprop ro.product.model").strip()
        tap("Show roots")
        wait_for(lambda: tap(model), 10, f"{model} in the picker's list of places")
    scroll_to(name)
    tap_until(name, f"Files in {name}", 15, f"{name} in the picker")
    wait_for(lambda: press("Use this folder"), 10, "Use this folder")
    wait_for(lambda: on_screen_any_case("Allow"), 10, "the picker's question")
    press("Allow")
    wait_for(lambda: f"tree/primary%3A{name}<" in app_file(PREFS), 15,
             f"{name} kept as the folder to save to")
    settings_written(f"{name} kept as the folder to save to")


def recently_deleted_folder():
    """The same in a folder picked in Settings, through Android's own picker:
    there a deleted recording is renamed .trashed-<expiry>-<name> where it is,
    and Android 17 reports that file under its old name (issue #51). Then
    another, its 30 days up as its name says, is removed by the daily pass."""
    folder = f"/sdcard/{PICKED}"
    old = "20200101_000000_out.ogg"
    sh(f"rm -rf {folder}; mkdir -p {folder}")
    try:
        pick_folder(PICKED)
        sh("logcat -c")
        dial(STRANGER_OUT, ring_s=2, talk_s=4)
        _, _, name = saved(folder)
        assert name.endswith(".ogg"), f"no recording saved in {folder}: {files_named('', folder)}"
        trashed = restore_then_delete(name, folder)
        assert trashed.startswith(".trashed-"), f"it waited as {trashed}"
        sh(f"echo not-audio > {folder}/{old}")
        place, said = trash_place(old, move=True)
        assert place == (False, True), f"not moved to the trash: {said}"
        [waiting] = files_named(old, folder)
        expired = re.sub(r"^\.trashed-\d+-", ".trashed-1000000000-", waiting)
        sh(f"mv {folder}/{waiting} {folder}/{expired}")
        place, said = trash_place(old, purge=True)
        assert place == (False, False), f"its 30 days up, still somewhere: {said}"
        assert not files_named(old, folder), f"its 30 days up, its files are {files_named(old, folder)}"
    finally:
        # A picker left open by a failure outlives the app's force-stop, and
        # sat over the next screens (the Android 12 AVD, 2026-10-26).
        for _ in range(3):
            if "documentsui" in sh("dumpsys window | grep mCurrentFocus"):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1)
        # Said, not raised: raised, it would stand in for whatever failed
        # above, and skip the rest. set_up clears it on the next run.
        quietly(set_prefs, recordings_tree_uri=None)
        sh(f"am force-stop {PKG}")
        sh(f"rm -rf {folder}")
    return f"in {PICKED}: waited as {trashed}, restored, deleted for good; one expired, gone after the daily pass"


def store(name, recordings=False, data=None):
    """A file written under [name] as a save writes one (the debug build's diag
    'store'), holding [data] (base64) if given: what the place called it."""
    said = diag("store", "--es", "name", f"'{name}'", *(("--ez", "recordings", "true") if recordings else ()),
                *(("--es", "bytes", data) if data else ()))
    m = re.search(r"published as (.+?) *$", said, re.M)
    assert m, said
    return m.group(1)


def lines_in(folder):
    """The files in [folder], one name a line: a name a provider made has a space."""
    return sh(f"ls -a {folder}/").splitlines()


def place_of(name):
    return trash_place(f"'{name}'")[0]


def name_taken():
    """A name already taken, in Recordings/JemRec and in a folder picked in
    Settings: Android's provider makes another, and the save says which. And
    the same name in both, trashed: Recently deleted shows a row for each,
    Restore all brings back each, Delete all for good leaves neither."""
    folder = f"/sdcard/{PICKED}"
    # Its time differs each run: what a run cut short left is no name taken.
    stem = time.strftime("20200102_%H%M%S_in")
    name = f"{stem}.ogg"
    sh(f"rm -rf {folder}; mkdir -p {folder}")
    made = {}
    try:
        pick_folder(PICKED)
        for place, recordings in ((RECORDINGS, True), (folder, False)):
            first = store(name, recordings)
            assert first == name, f"in {place}, a free name came back as {first}"
            made[place] = store(name, recordings)
        there = {place: [f for f in lines_in(place) if stem in f] for place in made}
        assert all(said != name and said in there[place] for place, said in made.items()), f"said {made}, there: {there}"
        # A second apart: one name in both, at one expiry, reads as one file.
        trash_all = (name, name, *made.values())
        for each in trash_all:
            trash_place(f"'{each}'", move=True)
            time.sleep(1.1)
        assert place_of(made[RECORDINGS]) == (False, True) and place_of(made[folder]) == (False, True) \
            and place_of(name) == (False, True), "not all four in the trash"
        # What says one file seen through two places is one: Android's expiry
        # for the row is the one in the file's name (RecordingLibrary.trashed).
        said = trash_place(f"'{made[RECORDINGS]}'")[1]
        expires = int(re.search(r"expires=(\d+)", said).group(1))
        waiting = [f for f in lines_in(RECORDINGS) if f.endswith(made[RECORDINGS])]
        assert waiting == [f".trashed-{expires // 1000}-{made[RECORDINGS]}"], f"expires {expires}, the file: {waiting}"
        open_trash()
        wait_for(lambda: ui().count('text="Restore"') >= 4, 20, "a row for each copy")
        shown = ui().count('text="Restore"')
        assert sh(f"pidof {PKG}").strip(), "the app stopped showing them"
        tap("Restore all")
        wait_for(lambda: all(name in lines_in(place) and made[place] in lines_in(place) for place in made), 20,
                 "Restore all to bring back all four")
        for each in trash_all:
            trash_place(f"'{each}'", move=True)
            time.sleep(1.1)
        sh(f"am force-stop {PKG}")
        open_trash()
        tap("Delete all for good")
        wait_for(lambda: on_screen("Delete for good"), 10, "the question")
        tap("Delete for good")
        gone = lambda: not [f for place in (RECORDINGS, folder) for f in lines_in(place) if stem in f]
        wait_for(gone, 20, "Delete all for good to leave none")
    finally:
        for _ in range(3):
            if "documentsui" in sh("dumpsys window | grep mCurrentFocus"):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1)
        quietly(set_prefs, recordings_tree_uri=None)
        sh(f"am force-stop {PKG}")
        sh(f"rm -rf {folder}")
    return f"made {made[RECORDINGS]!r}, and the same in {PICKED}; {shown} rows on screen, each restored, none left"


# What each look's header shows uiautomator, with the recorder ready. Deck's
# and Multitrack's headers are one sentence to a screen reader (headerSpeech),
# Memo's its own words; Multitrack's tabs are in capitals.
# What tells the looks apart in a dump of home: not the header, one
# sentence in each (header_says), but the list's day heading - "Today" in
# Memo, "TODAY" in Deck and Multitrack - and Multitrack's tabs, "CALLS"
# (the 16 AVD, 2026-11-21). Deck is a heading neither Memo's nor
# Multitrack's: its "TODAY" is "Today" put in capitals as it is drawn, no
# word the app writes for ScreenWordsTest to hold.
LOOK_SIGNS = {
    "DECK": lambda x: header_says("Ready to record", x) and 'content-desc="Today, ' in x
    and 'text="Today"' not in x and 'text="CALLS"' not in x,
    "MULTITRACK": lambda x: 'text="CALLS"' in x,
    "MEMO": lambda x: header_says("Ready to record", x) and 'text="Today"' in x,
}


def looks():
    """Each look picked and the app started again: it opens in it - four
    fonts and a light or dark of its own, on this Android. A recording of
    today's first, by the emulator's clock, so the list has the heading the
    looks are told apart by (LOOK_SIGNS)."""
    today = sh("date +%Y%m%d_%H%M%S").strip() + "_in.ogg"
    store(today, recordings=True)
    try:
        for look, sign in LOOK_SIGNS.items():
            set_prefs(look=look, light_dark="DARK" if look == "DECK" else "PHONE")
            sh(f"am force-stop {PKG}")
            sh(f"am start -n {PKG}/.MainActivity")
            wait_for(lambda: sign(ui()), 30, f"the app to open in {look}")
            assert sh(f"pidof {PKG}").strip(), f"the app stopped in {look}"
    finally:
        quietly(set_prefs, look="MEMO", light_dark="PHONE")
        sh(f"am force-stop {PKG}")
        # For good, as opus_valid's: not left for a later check's list.
        quietly(sh, "content delete --uri content://media/external/audio/media "
                    f"--where \"_display_name='{today}'\"")
    return "it opened in Deck (dark), Multitrack and Memo"


def head(name):
    """The first bytes of a saved recording: its header pages, tags and all."""
    return adb("exec-out", f"head -c 2048 '{RECORDINGS}/{name}'", text=False)


def media_row(name):
    """What Android's media index has of a recording: (date_modified, duration)."""
    out = sh(f"content query --uri content://media/external/audio/media --projection date_modified:duration "
             f"--where \"_display_name='{name}'\"")
    m = re.search(r"date_modified=(\d+), duration=(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else None


def marks(key):
    xml = app_file(PREFS)
    m = re.search(rf'<set name="{key}">(.*?)</set>', xml, re.S)
    return set(re.findall(r"<string>(.*?)</string>", m.group(1), re.S)) if m else set()


def reinstall():
    """A message, kept from its row, is shown as a kept message again after
    the app is uninstalled and installed again: the marks are in the file
    (RecordingTags), and the app's preferences go with an uninstall."""
    answering()
    _, seconds, name = saved()
    assert b"JEMREC_MESSAGE=1" in head(name), f"{name} was saved without its message tag"
    before = wait_for(lambda: media_row(name), 10, "the message in Android's media index")
    try:
        sh(f"am start -n {PKG}/.MainActivity")
        # The newest row is the message's: its keep button is the first.
        wait_for(lambda: tap("Keep: never auto-delete"), 30, "the message's keep button")
        wait_for(lambda: b"JEMREC_KEEP=1" in head(name), 10, "the keep mark to be written into the file")
    finally:
        sh(f"am force-stop {PKG}")
    after = wait_for(lambda: media_row(name), 10, "the message in Android's media index")
    # The recorder first: a running one, its app gone, turns debugging off.
    sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
    adb("uninstall", PKG, check=True)
    set_up(reinstall.apk)
    assert not marks("message_recordings") and not marks("kept_recordings"), "the reinstall kept the old marks"
    try:
        # As a person does it: the empty list's card, then Android's own
        # question - READ_MEDIA_AUDIO, or READ_EXTERNAL_STORAGE before 13 -
        # whose words are kept for the report.
        sh(f"am force-stop {PKG}")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: tap("Allow"), 30, "the card asking to show the older recordings")
        question = wait_for(lambda: re.search(
            r'<node [^>]*text="([^"]*)"[^>]*resource-id="com.android.permissioncontroller:id/permission_message"', ui()),
            15, "Android's question").group(1)
        wait_for(lambda: tap_id("com.android.permissioncontroller:id/permission_allow_button"), 10, "Android's Allow")
        wait_for(lambda: name in marks("message_recordings") and name in marks("kept_recordings"), 30,
                 "the list to read the marks back from the file")
        wait_for(lambda: on_screen("Kept - tap to stop keeping") and on_screen("Message"), 15,
                 "the row to show it kept and a message")
    finally:
        sh(f"am force-stop {PKG}")
    _, duration = media_row(name)
    assert abs(duration / 1000 - seconds) < 1, f"Android's index reads {duration} ms of {seconds:.1f} s"
    moved = "" if after[0] == before[0] else f"; the keep write moved its date {after[0] - before[0]} s"
    return f"{name}: a kept message again after the reinstall, {duration} ms in Android's index{moved}; asked \"{question}\""


def cable():
    """The daemon reads the USB port's status once a second and wakes the app
    when a cable goes in or comes out; the app watches the recorder for a few
    seconds and starts it again if it went. An emulator has no port, so
    UsbService's simulated one stands in, and the kill a computer's cable
    brings on an Honor is done by hand."""
    daemon_log = "cat /data/local/tmp/jemrec-capture.out"
    sh("dumpsys usb add-port cable dual")
    sh("dumpsys usb connect-port cable ufp sink device")
    try:
        start_recorder()  # the daemon looks for ports as it starts
        wait_for(lambda: "cable: watching USB port(s) [cable" in sh(daemon_log), 10, "the daemon to watch the port")
        before = daemon_pid()
        sh("dumpsys usb disconnect-port cable")
        wait_log(r"cable: unplugged; watching the recorder", 10, "the app woken by the unplug")
        wait_log(r"cable: the recorder is unaffected", 15, "the app to leave a recorder that lived")
        assert daemon_pid() == before, "the recorder was restarted for nothing"
        sh("logcat -c")
        sh("dumpsys usb connect-port cable ufp sink device")
        wait_log(r"cable: plugged in; watching the recorder", 10, "the app woken by the plug")
        time.sleep(2)
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_log(r"cable: the recorder went", 10, "the app to see the recorder go")
        wait_log(r"switching Wireless debugging off and on", 15, "Wireless debugging switched off and on")
        back = wait_log(r"cable: the recorder is back", 40, "the recorder started again")
        after = wait_for(daemon_pid, 10, "a recorder process")
        assert after != before, "the same recorder process"
        return f"left alone when it lived; started again when killed ({back.group(0)})"
    finally:
        sh("dumpsys usb reset")
        start_recorder_after()


def revive_log():
    return app_file("files/revive.log")


def revive_since(before):
    """What the app has noted in its revive log since [before] was read. Read
    as what follows it, not by looking for a line: the log outlives a run, and
    an earlier run's lines would pass for this one's. It is appended to, and
    moved to revive.log.1 once past 64 kB (ReviveLog)."""
    now = revive_log()
    if now.startswith(before):
        return now[len(before):]
    older = app_file("files/revive.log.1")
    return (older[len(before):] if older.startswith(before) else older) + now


def usb_debugging():
    """On an Honor, showing Developer options without a cable has the phone's
    own Settings app switch USB debugging off; adbd stops, and the recorder
    with it. Android wakes the app on that change (UsbDebuggingWatch, a
    content trigger on the setting), and the keep-alive turns the switch back
    on and starts the recorder again. Here the shell switches it off instead,
    with the app in the background - which takes this adb connection down with
    adbd until the app has put the switch back.

    If the app never does, nothing over adb can reach the emulator again - the
    Android 12 and 13 AVDs stayed locked out through a restart on 2026-10-26,
    when these checks did not yet grant the app WRITE_SECURE_SETTINGS (see
    set_up). So the emulator's console, which needs no adbd, takes a snapshot
    first, and loads it if the switch is not back in time."""
    start_recorder()
    before = daemon_pid()
    log_before = revive_log()
    # The snapshot before Home, not after: saving one stops the emulator for
    # seconds, and JemRec's window, losing its focus to Home just then, took
    # its focus event late - "Input dispatching timed out ... Waited 5011ms
    # for FocusEvent(hasFocus=false)", its main thread idle in its loop, an
    # "isn't responding" filed on the Android 12 AVD (2026-11-18), which a
    # crash of JemRec now fails the run for.
    if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
        raise RuntimeError("no snapshot to come back to; not switching USB debugging off")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    try:
        try:
            adb("shell", "settings put global adb_enabled 0", timeout=15)
        except subprocess.TimeoutExpired:
            pass  # adbd going away under the command is the point

        def switched_back_on():
            try:
                return adb_ready() and sh("settings get global adb_enabled").strip() == "1"
            except subprocess.TimeoutExpired:
                return False

        try:
            wait_for(switched_back_on, 60, "")
        except TimeoutError:
            back_from_snapshot("the app never switched USB debugging back on")
    finally:
        adb("emu", "avd", "snapshot", "delete", USB_GUARD)
    wait_for(lambda: "usbwatch: USB debugging changed" in revive_since(log_before), 10,
             "the app woken by the change")
    # Sixty: on the Android 16 AVD (2026-10-27) the app's first start after
    # the switch connected while adbd was still coming back and lost the link
    # copying the jar ("Stream closed"); the keep-alive's next try, 20 s
    # later, had the recorder up 44 s after the change.
    after = wait_for(daemon_pid, 60, "a recorder process")
    wait_for(recorder_answers, 30,
             "the recorder answering")
    if after == before:
        return "woken by the change, switch back on; adbd kept the recorder here"
    return "woken by the change, switch back on, recorder started again"


def global_setting(name):
    return sh(f"settings get global {name}").strip()


def su(command):
    """[command] as root through the image's su, which leaves adbd as it is -
    as_root restarts it, and a recorder the app started through a root adbd
    would run as root."""
    return sh(f"su 0 sh -c {shlex.quote(command)}")


def app_file(path):
    """[path] under the app's data directory, read as root: the shrunk build
    these checks install is not debuggable (app/build.gradle.kts says why), so
    run-as refuses it. Empty when there is no such file, as run-as cat was."""
    return su(f"cat {APP_DIR}/{path} 2>/dev/null")


def adbd_stopped():
    """After a restart of the Honor (MagicOS 11), 2026-10-28, adbd was not
    running though USB debugging and Wireless debugging both read on; every
    revive found nothing advertised, and the header asked to allow a Wi-Fi
    Android had allowed long before. A revive that reads adbd stopped
    (init.svc.adbd, AdbdState) now switches Wireless debugging off and on,
    which has Android start adbd, and starts the recorder.

    An emulator's adbd runs from boot, so it is stopped here by hand, as root,
    with Wireless debugging on and the recorder killed - which takes this adb
    connection with it. The revive comes from inside the app once adbd reads
    stopped (the debug build's ReviveLater), a routine one as the keep-alive's
    first after a boot. Guarded by the console's snapshot as usb_debugging is."""
    start_recorder()
    before = daemon_pid()
    wireless_before = global_setting("adb_wifi_enabled")
    try:
        return _adbd_stopped_and_started(before)
    finally:
        if wireless_before != "1":  # 0, or "null" for never set
            sh("settings put global adb_wifi_enabled 0")


def _adbd_stopped_and_started(before):
    sh("settings put global adb_wifi_enabled 1")

    # Asked, where Android has not allowed the AVD's Wi-Fi yet: allowed, as a
    # phone's would be - the Honor's was. Android 12 keeps the switch off
    # while it asks; 13 and later show it on before the question is up.
    def allowed():
        _answer_questions(allow=True)
        return global_setting("adb_wifi_enabled") == "1" and not _questions()

    wait_for(allowed, 20, "Wireless debugging on, on a Wi-Fi allowed")
    for _ in range(6):  # a question that comes after the switch reads on
        time.sleep(0.5)
        _answer_questions(allow=True)
    log_before = revive_log()

    # The guard first: a save that fails leaves the recorder as it was.
    if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
        raise RuntimeError("no snapshot to come back to; not stopping adbd")
    try:
        diag("revivelater")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        try:
            adb("shell", "su 0 setprop ctl.stop adbd", timeout=15)
        except subprocess.TimeoutExpired:
            pass  # adbd going away under the command is the point
        try:
            wait_for(adb_ready, 90, "")
        except TimeoutError:
            back_from_snapshot("adbd never came back")
    finally:
        adb("emu", "avd", "snapshot", "delete", USB_GUARD)
    wait_for(lambda: "recorder up (connect CONNECTED)" in revive_since(log_before), 60, "the recorder started")
    text = revive_since(log_before)
    assert "revive: adbd STOPPED" in text, "the revive never read adbd stopped"
    assert "revive: Wireless debugging CYCLE" in text, "Wireless debugging was not switched off and on"
    after = wait_for(daemon_pid, 30, "a recorder process")
    wait_for(recorder_answers, 30, "the recorder answering")
    assert after != before, "the same recorder: it never went"
    user = sh("ps -A -o USER,ARGS | grep com.jemcik.jemrec.shell.Main | grep -v grep").split()
    assert user and user[0] == "shell", f"the recorder runs as {user[:1]}, not shell"
    return "adbd stopped with Wireless debugging on: read stopped, switched off and on, recorder back as shell"


def hide_developer_options():
    """When JemRec is off, Hide Developer options: off switches Developer
    options off - and Wireless debugging, where the user had it on - and
    leaves USB debugging, so adbd and the recorder, alone; on puts back what
    it switched off. On the Honor (2026-10-26) the recorder lived through that
    off the cable, through Wi-Fi off and on, a locked phone and a real call;
    here, that the switches go and come back and the recorder keeps its
    process. Twice: with Wireless debugging on from the shell - the user's,
    switched off and put back - and with it off, as JemRec leaves it between
    starts since 1.5, which On must not switch on."""
    set_prefs(off_mode="HIDE_DEVELOPER_OPTIONS")
    try:
        start_recorder()
        before = daemon_pid()
        said = []
        for users in (True, False):
            sh("settings put global development_settings_enabled 1")  # as on any phone set up
            sh(f"settings put global adb_wifi_enabled {1 if users else 0}")
            wait_for(lambda: global_setting("adb_wifi_enabled") == ("1" if users else "0"), 10, "")
            if users:
                _answer_questions(allow=True)
            diag("switch", "--ez", "on", "false")
            wait_for(lambda: global_setting("development_settings_enabled") == "0", 15, "Developer options off")
            if users:
                wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off")
            assert global_setting("adb_enabled") == "1", "USB debugging went off too"
            time.sleep(3)
            assert daemon_pid() == before, "the recorder went with them"
            assert recorder_answers(), "the recorder stopped answering"
            diag("switch", "--ez", "on", "true")
            wait_for(lambda: global_setting("development_settings_enabled") == "1", 15, "Developer options back")
            if users:
                wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 15, "Wireless debugging back")
            else:
                time.sleep(3)
                assert global_setting("adb_wifi_enabled") == "0", "On switched Wireless debugging on for nothing"
            assert daemon_pid() == before, "the recorder was restarted for nothing"
            said.append("Developer options and the user's Wireless debugging off and back on" if users else
                        "with Wireless debugging off, Developer options off and back on, Wireless debugging left off")
        return "; ".join(said) + f"; the recorder kept (pid {before})"
    finally:
        # On again whatever failed, or every check after this one runs with
        # recording off. A second on puts back nothing and finds the recorder up.
        quietly(diag, "switch", "--ez", "on", "true")
        quietly(set_prefs, off_mode=None)
        quietly(sh, "settings put global adb_wifi_enabled 0")


def debugging_off():
    """When JemRec is off, Turn all debugging off: off has the recorder switch
    Wireless and USB debugging off and go, and adbd - this adb connection with
    it - goes away. On, here a debug-build delay (TurnOnLater) since the adb
    that would send it is gone, puts all of it back and starts the recorder.
    Guarded as usb_debugging is: a snapshot the console loads if adb never
    comes back."""
    set_prefs(off_mode="ALL_DEBUGGING_OFF")
    start_recorder()
    sh("settings put global development_settings_enabled 1")
    # Off, as JemRec leaves it between starts (1.5): On's revive switches it
    # on to start the recorder, and the recorder off again after.
    sh("settings put global adb_wifi_enabled 0")
    # From here: what came before - a revive of the keep-alive's, say - is
    # not On's (review round 1).
    log_before = revive_log()
    before = daemon_pid()
    adbd_before = sh("pidof adbd").strip()
    if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
        raise RuntimeError("no snapshot to come back to; not switching debugging off")
    try:
        diag_cut_off("switch", "--ez", "on", "false", "--ei", "back_after_s", "30")
        # Not "adb gone": on the 13 and 17 AVDs the Mac's adb went on calling
        # the emulator a device while adbd was down (2026-10-26). adbd's new
        # process ID, below, is the proof it stopped.
        time.sleep(5)

        def all_back():
            try:
                return (adb_ready()
                        and global_setting("adb_enabled") == "1"
                        and global_setting("development_settings_enabled") == "1")
            except subprocess.TimeoutExpired:
                return False

        try:
            wait_for(all_back, TURN_ON_LATER_S, "")
        except TimeoutError:
            back_from_snapshot("debugging never came back on")
    finally:
        adb("emu", "avd", "snapshot", "delete", USB_GUARD)
        quietly(set_prefs, off_mode=None)
    after = wait_for(daemon_pid, 60, "a recorder process")
    wait_for(recorder_answers, 30, "the recorder answering")
    log_text = revive_since(log_before)
    assert "debugging off: the recorder says OFF NOW" in log_text, "the recorder was not the one to switch it off"
    assert "debugging off: Wireless debugging, then USB debugging" in sh("cat /data/local/tmp/jemrec-capture.out.1"), \
        "the old recorder never switched them off"
    assert sh("pidof adbd").strip() != adbd_before, "adbd never stopped: USB debugging did not go off"
    assert "debugging back on: USB_DEBUGGING" in log_text, "On did not put USB debugging back"
    assert after != before, "the same recorder: it never went"
    wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off again after the start")
    log_text = revive_since(log_before)
    assert "revive: Wireless debugging TURN_ON" in log_text, "On's revive did not switch Wireless debugging on"
    assert "revive: Wireless debugging off - the recorder says SWITCHED_OFF" in log_text, \
        "the recorder did not switch Wireless debugging off after On's start"
    return ("adbd stopped with USB debugging, all of it back on, recorder started again; Wireless debugging "
            "on for the start and off after")


def _quietly(attempt):
    """[attempt]'s answer, or False where it timed out: for a step tried again."""
    try:
        return attempt()
    except TimeoutError:
        return False


def _questions():
    """Android's "Allow wireless debugging on this network?" questions open:
    their activity records, told apart by id - Android 12 lists one as
    "Hist #0: ActivityRecord{id u0 ...WifiDebuggingActivity t390}", 13 as
    "Hist  #0: ActivityRecord{id u0 ...WifiDebuggingActivity} t375}"."""
    return len(set(re.findall(r"ActivityRecord\{(\w+) u0 com\.android\.systemui/\.wifi\.WifiDebuggingActivity",
                              sh("dumpsys activity activities"))))


def _answer_questions(allow):
    """Cancel each of those questions on screen - or tick Always allow on this
    network and Allow."""
    for _ in range(6):
        if not _questions():
            return
        if not on_screen("Allow wireless debugging on this network?"):
            # Behind JemRec's own screen, which the check opened after it:
            # Back closes that, and the question is in front again (the 12
            # AVD, 2026-10-28).
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
            continue
        if allow:
            tap("Always allow on this network")
        tap("Allow" if allow else "Cancel")
        time.sleep(2)
    raise RuntimeError("Android's Wi-Fi question would not go")


def _refused_wifi_left_alone(port):
    """On a Wi-Fi Android has not allowed Wireless debugging on, with the
    switch on and the recorder up: the revive for the port asks once at most,
    and after Cancel no keep-alive asks again or opens the port - Android 12
    keeps the switch off after Cancel, and each one asked again before 0.25
    (the 12 AVD, 2026-10-28). Then the Wi-Fi is allowed, as for a phone.

    Each revive is the debug op "check", answered when it is done: a kick to
    the keep-alive is not taken while it waits out a retry, and a run begun
    before Android made the Wi-Fi active ends at once - each failed a driver
    of this on the 12 AVD, 2026-10-28."""
    _answer_questions(allow=False)
    diag("check", wait=120)  # the first ask here, if set_up's revive had not made it
    _answer_questions(allow=False)
    for _ in range(2):
        diag("check", wait=120)
        time.sleep(2)
        assert not _questions(), "asked again after Cancel"
        assert port() != "5555", "opened the port on a Wi-Fi Android refused"
    sh("settings put global adb_wifi_enabled 1")
    wait_for(_questions, 15, "Android's question, to allow this Wi-Fi")
    _answer_questions(allow=True)
    wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 10, "Wireless debugging allowed")


ADB_KEYS = "/data/misc/adb/adb_temp_keys.xml"


def _adb_keys():
    """Android's list of computers' keys and allowed Wi-Fi networks, as text:
    binary XML ("ABX" first) where persist.sys.binary_xml has it, text
    otherwise. Empty where there is none yet."""
    binary = su(f"head -c 3 {ADB_KEYS} 2>/dev/null").startswith("ABX")
    return su(f"abx2xml {ADB_KEYS} - 2>/dev/null" if binary else f"cat {ADB_KEYS} 2>/dev/null")


def _wifi_allowed():
    """Whether Android lists a Wi-Fi as allowed for Wireless debugging - by
    Always allow on this network (AdbDebuggingManager keeps it as wifiAP)."""
    return "<wifiAP" in _adb_keys()


def allow_wifi():
    """The AVD's Wi-Fi allowed for Wireless debugging - Always allow, as a
    phone's user does at setup - and read back from Android's list; then
    Wireless debugging off, as JemRec leaves it between starts. Since 1.5
    every start switches it on, and on a Wi-Fi not allowed each switch-on
    asks again: on the Android 12 and 13 AVDs, 2026-11-21, nineteen
    questions had stacked up from repeated switch-ons
    (tmp/wd-off-between-starts, 0a). allow_this_wifi and start_without_wifi
    take the allowance back for their own part, and give it again."""
    wait_wifi_connected(what="Wi-Fi connected, to allow it")
    if not _wifi_allowed():
        sh("settings put global adb_wifi_enabled 1")

        def allowed():
            _answer_questions(allow=True)
            return _wifi_allowed()

        wait_for(allowed, 45, "the AVD's Wi-Fi allowed for Wireless debugging")
        for _ in range(4):  # a question that comes after the list says so
            time.sleep(0.5)
            _answer_questions(allow=True)
    sh("settings put global adb_wifi_enabled 0")
    wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 10, "Wireless debugging off")


def _forget_wifi_allowed():
    """The AVD Wi-Fi's "Always allow on this network" taken back, for a Wi-Fi
    Android has not allowed at every run: allow_this_wifi's, on every Android
    version, and the refusal part of start_without_wifi, on Android 12, where
    the switch shows a refusal. The AVDs used to boot back into a snapshot
    taken before it was allowed; booted cold since 2026-10-28 (the snapshots
    had kept the 17 AVD on JemRec 0.9 for eight days), an allow from one run
    stays. Android keeps
    the allowed networks in adb_temp_keys.xml - beside the computers' keys,
    which stay - and in system_server's memory, which writes them back at any
    change, so system_server is stopped while the line goes. True if there
    was one to take back."""
    keys = ADB_KEYS
    binary = su(f"head -c 3 {keys}").startswith("ABX")
    read_keys = _adb_keys
    if "<wifiAP" not in read_keys():
        return False
    tmp = "/data/local/tmp/jemrec-keys"
    rewrite = (f"abx2xml {keys} {tmp}.xml && grep -v '<wifiAP' {tmp}.xml > {tmp}-2.xml && xml2abx {tmp}-2.xml {keys}"
               if binary else f"grep -v '<wifiAP' {keys} > {tmp}-2.xml && cat {tmp}-2.xml > {keys}")
    su(f"stop; {rewrite}; chown system:shell {keys}; chmod 600 {keys}; restorecon {keys}; "
       f"rm -f {tmp}.xml {tmp}-2.xml; start")
    time.sleep(5)
    # "Service activity: found", or "...: not found" while it restarts.
    wait_for(lambda: sh("service check activity").strip().endswith(": found") and "package:" in sh("pm path android"),
             180, "Android back after system_server's restart")
    wait_wifi_connected(90, "Wi-Fi connected after the restart")
    assert "<wifiAP" not in read_keys(), "the Wi-Fi is still allowed"
    return True


def refused_grant():
    """Setup's last step where the phone refuses the grant, as an OPPO,
    OnePlus or realme does until Disable system optimization is on: issue #64
    quoted the refusal, from a Nord CE5 on OxygenOS 16, and #61, a OnePlus
    10R, was told to look for a Xiaomi switch. An emulator grants, so the
    debug build answers with #64's refusal (ShellGrant.actAsRefused); with
    the app's ADB session dropped, the grant is not blamed on a switch; Try
    again, without the fake, grants for real, and the grant is read back from the
    package's own record - the read-back being the part that changed on every
    phone, so the part this runs on each Android. The permission is taken
    away first, as on a phone not yet set up, and live again at the end: a
    grant reaches the app only once it starts again (Setup.isComplete)."""
    permission = "android.permission.WRITE_SECURE_SETTINGS"

    def held():
        # Fixed strings, as the app's own read-back (ShellGrant.readBackCommand).
        return f"{permission}: granted=true" in sh(f"dumpsys package {PKG} | grep -F {permission}")

    sh(f"pm revoke {PKG} {permission}")
    assert not held(), "the permission was not taken away"
    try:
        start_recorder()
        diag("refusegrant", "--ez", "on", "true")
        refused = diag("finish")
        assert "Disable system optimization" in refused and "Xiaomi" not in refused, refused
        assert "setup: grant -> REFUSED" in log(), "the refusal not logged as one"
        assert not held(), "granted while refused"
        diag("refusegrant", "--ez", "on", "false")
        # No session at all: said as that, not blamed on a switch.
        diag("disconnect")
        lost = diag("finish")
        assert "lost its connection" in lost and "Disable system optimization" not in lost, lost
        connect_app()
        again = diag("finish")
        assert "finished" in again, again
        assert "setup: grant -> GRANTED" in log(), "the grant not read back as granted"
        assert held(), "Try again finished without the permission granted"
    finally:
        # Put back whatever happened: without the permission the app cannot
        # switch debugging back on, and start-without-wifi, after this, turns
        # all of it off (an Android 12 AVD stayed locked out that way, see
        # usb_debugging). The fake dies with the process.
        if not held():
            sh(f"pm grant {PKG} {permission}")
        sh(f"am force-stop {PKG}")
        start_recorder_after()
    return ("refused: told Disable system optimization, not set up; no session: told so; "
            "Try again: granted, read back")


PAIRING_TIMEOUT = "adb_allowed_connection_time"
PAIRING_RECORD = "/data/local/tmp/jemrec-pairing-timeout"


def pairing_timeout():
    return sh(f"settings get global {PAIRING_TIMEOUT}").strip()


def keep_paired_set(on):
    """Keep JemRec paired set through the app, and what it says became of
    it: (outcome, Android's timeout after)."""
    said = diag("keeppaired", "--ez", "on", "true" if on else "false")
    m = re.search(r"keep paired \w+: (\w+), timeout (-?\d+)", said)
    assert m, said
    return m.group(1), m.group(2)


def keep_paired():
    """Keep JemRec paired, as the switch in Settings sets it (the
    `keeppaired` diag op, KeepPaired.choose): the recorder turns Android's
    pairing timeout off and records what it was in its own file - what it
    puts back when the switch goes off, and when JemRec is uninstalled
    (Custodian, not checked here: it looks every five minutes) - and puts
    it back from that file. A timeout off by the user's own hand stays off.
    That off keeps a pairing was measured on this AVD by hand, 2026-11-15:
    with the timeout at two minutes, JemRec's pairing went a second after it
    had connected, and at 0 it stayed."""
    original = pairing_timeout()
    try:
        # From a timeout on: one left off - a run cut short here - would be
        # taken for the user's own, and nothing recorded.
        if original == "0":
            sh(f"settings delete global {PAIRING_TIMEOUT}")
        before = pairing_timeout()
        assert keep_paired_set(True) == ("DONE", "0"), pairing_timeout()
        recorded = sh(f"cat {PAIRING_RECORD}").strip()
        assert recorded == before, f"recorded {recorded!r}, was {before!r}"
        outcome, _ = keep_paired_set(False)
        assert outcome == "DONE", outcome
        assert pairing_timeout() == before, f"put back as {pairing_timeout()!r}, was {before!r}"
        assert "No such file" in sh(f"ls {PAIRING_RECORD} 2>&1"), "the record outlived the put back"
        # The user's own off: nothing recorded, nothing put back.
        sh(f"settings put global {PAIRING_TIMEOUT} 0")
        assert keep_paired_set(True) == ("DONE", "0")
        assert "No such file" in sh(f"ls {PAIRING_RECORD} 2>&1"), "an off by hand was recorded as JemRec's"
        assert keep_paired_set(False) == ("DONE", "0"), "an off by hand was put back"
    finally:
        sh(f"rm -f {PAIRING_RECORD}")
        sh(f"settings delete global {PAIRING_TIMEOUT}" if original == "null"
           else f"settings put global {PAIRING_TIMEOUT} {original}")
    return f"timeout off and put back to {before}; an off by hand left alone"


PAIRING_CARD = 2  # PairingNotification.NOTIFICATION_ID


def pairing_card():
    """A pairing code that is not six digits, twenty times: the pairing card
    ends on what was wrong, with the field for the next code, and its service
    ends (PairingService). The service is started by root with no code, which
    is what a code of another length comes to there; the script types none.
    No pairing is tried and none is touched. Android shows a foreground
    service's card from a queue, which a notify() does not wait for: until
    1.8.1 "Pairing…" came out of it last and stayed, with no field, 27 of 100
    on the Android 17 AVD and 3 of 100 on 13 beside it, 71 of 150 on 13 under
    load (shrunk 1.8, 2026-11-25). The card is left up between the codes, as a
    user's is, and each one's update waited for."""
    said = "That was not six digits. Try again."
    rounds = 20

    def card():
        record = rf"NotificationRecord\([^\n]*pkg={re.escape(PKG)} [^\n]* id={PAIRING_CARD} tag=null"
        found = re.search(rf"{record}.*?(?=NotificationRecord\(|\Z)", sh("dumpsys notification --noredact"), re.S)
        return found.group(0) if found else ""

    def updated():
        at = re.search(r"mUpdateTimeMs=(\d+)", card())
        return int(at.group(1)) if at else 0

    quietly(diag, "clearnotes")
    try:
        for i in range(1, rounds + 1):
            before = updated()
            su(f"am start-foreground-service -n {PKG}/.ui.PairingService")
            wait_for(lambda: updated() > before, 5, f"the pairing card after code {i}")
            # Long enough for the queue to have shown all it holds.
            time.sleep(1)
            words = re.search(r"android\.text=String \((.*?)\)\n", card())
            assert words and words.group(1) == said, f"code {i}: the card says {words and words.group(1)!r}"
            assert '"Enter code"' in card(), f"code {i}: no field for the next code"
        wait_for(lambda: "PairingService" not in sh(f"dumpsys activity services {PKG}"), 5, "the service to end")
        # By name on newer Androids, a hexadecimal number on 12 and 13.
        flags = re.search(r"flags=(\S+)", card()).group(1)
        still = int(flags, 16) & 0x40 if flags.startswith("0x") else "FOREGROUND_SERVICE" in flags
        assert not still, f"the card is still a foreground service's: {flags}"
    finally:
        quietly(diag, "clearnotes")
    return f"{rounds} codes of no six digits: the card said so each time, with the field"


def listener_grant():
    """Notification access for the app's listener, granted by the recorder
    (Wire.COMMAND_ALLOW_LISTENER), as Settings' Show who called and Hide the
    debugging notification ask for it: taken away first, the app's ADB
    session closed, then the app's grant (the debug op grantlistener, which
    runs Setup.grantNotificationAccess). The recorder's own line says it
    allowed it, the app's own line says it took the recorder's answer, and
    Android's list holds it. The grant used to open an ADB session, which
    needs Wireless debugging, and on the OnePlus, 2026-11-12, LineageOS had
    switched that off by itself: Show who called said "it needs Wi-Fi", on
    Wi-Fi. Android writes its list a moment after a grant or a withdrawal,
    so it is waited for. Then again with the recorder killed first: no
    recorder answers, so the grant starts one, as Tap to start again does,
    and the recorder then up grants it (1.5; until then a session of its
    own granted it, which needs Wireless debugging - off between starts
    now). What it was before is put back."""
    component = f"{PKG}/{PKG}.capture.DebugNotificationCleaner"

    def listed():
        return component in sh("settings get secure enabled_notification_listeners")

    before = listed()
    sh(f"cmd notification disallow_listener {component}")
    try:
        wait_for(lambda: not listed(), 5, "the access taken away")
        start_recorder()
        diag("disconnect")
        said = diag("grantlistener")
        assert "granted" in said and "not granted" not in said, said
        assert "listener: notification access allowed for the app" in log(), "the recorder did not say it allowed it"
        assert "settings: notif listener -> the recorder said ok" in log(), "the app did not take the recorder's answer"
        wait_for(listed, 5, "Android listing it")

        # No recorder: started by the grant, and asked.
        sh(f"cmd notification disallow_listener {component}")
        wait_for(lambda: not listed(), 5, "the access taken away again")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: daemon_pid() is None, 10, "the recorder gone")
        said = diag("grantlistener", wait=120)
        assert "granted" in said and "not granted" not in said, said
        assert "settings: notif listener -> no recorder answered; started: true" in log(), \
            "the grant did not start the recorder"
        assert "settings: notif listener -> the recorder said ok" in log(), "the started recorder did not grant it"
        wait_for(listed, 5, "Android listing it")
        assert recorder_answers(), "no recorder after the grant"
    finally:
        sh(f"cmd notification {'allow' if before else 'disallow'}_listener {component}")
        start_recorder_after()
    return ("taken away, then allowed by the recorder with no ADB session, and in Android's list; with the "
            "recorder killed, the grant started it, and it allowed it")


# Telegram's other builds (issue #93): Telegram's source builds them as
# these (applicationIdSuffix ".web" in TMessagesProj_AppStandalone, ".beta"
# in TMessagesProj_AppHockeyApp, read 2026-11-14).
TELEGRAM_BUILDS = {"org.telegram.messenger.web": "Telegram (telegram.org)",
                   "org.telegram.messenger.beta": "Telegram Beta"}


def stub_apk(package, into, application=None, permissions=(), dex=None):
    """A tiny app named [package], with no code: all Android needs to list
    it as installed. Built with the SDK's aapt2 and signed with the debug
    key, so no real app is downloaded or installed. With [dex], the code it
    runs, and the manifest's [application] and [permissions] for it."""
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    tools = os.path.join(sdk, "build-tools", os.environ.get("BUILD_TOOLS", "36.0.0"))
    platform = os.path.join(sdk, "platforms", "android-36", "android.jar")
    manifest = os.path.join(into, f"{package}.xml")
    application = application or '<application android:label="Stub" android:hasCode="false"/>'
    uses = "".join(f'<uses-permission android:name="android.permission.{p}"/>' for p in permissions)
    with open(manifest, "w") as f:
        f.write('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                f'package="{package}" android:versionCode="1" android:versionName="stub">'
                f'{uses}{application}</manifest>')
    unaligned, aligned, signed = (os.path.join(into, f"{package}.{n}.apk") for n in ("u", "a", "s"))
    subprocess.run([os.path.join(tools, "aapt2"), "link", "--manifest", manifest, "-I", platform,
                    "--min-sdk-version", "31", "--target-sdk-version", "36", "-o", unaligned],
                   check=True, capture_output=True, timeout=120)
    if dex:
        import zipfile
        with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as apk:
            apk.write(dex, "classes.dex")
    for command in (
        [os.path.join(tools, "zipalign"), "-p", "-f", "4", unaligned, aligned],
        [os.path.join(tools, "apksigner"), "sign", "--ks", os.path.expanduser("~/.android/debug.keystore"),
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned],
    ):
        subprocess.run(command, check=True, capture_output=True, timeout=120)
    return signed


STANDIN = "org.telegram.messenger.web"
STANDIN_DIR = os.path.join(PROBE_DIR, "standin")
STANDIN_CALL = "com.jemcik.jemrec.standin.StandInCall"


def standin_apk(into):
    """The stand-in messenger (shellserver/probe/standin): an app named as
    Telegram's telegram.org build, whose one activity holds a call as a
    messenger's does - compiled here with javac and the SDK's d8."""
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    tools = os.path.join(sdk, "build-tools", os.environ.get("BUILD_TOOLS", "36.0.0"))
    platform = os.path.join(sdk, "platforms", "android-36", "android.jar")
    java_home = os.environ.get("JEMREC_JAVA_HOME")
    javac = os.path.join(java_home, "bin", "javac") if java_home else "javac"
    sources = [os.path.join(d, f) for d, _, files in os.walk(STANDIN_DIR) for f in files if f.endswith(".java")]
    classes = os.path.join(into, "standin-classes")
    os.makedirs(classes, exist_ok=True)
    subprocess.run([javac, "-nowarn", "--release", "17", "-encoding", "UTF-8", "-cp", platform, "-d", classes, *sources],
                   check=True, capture_output=True, timeout=120)
    compiled = [os.path.join(d, f) for d, _, files in os.walk(classes) for f in files if f.endswith(".class")]
    subprocess.run([os.path.join(tools, "d8"), "--min-api", "31", "--lib", platform, "--output", into, *compiled],
                   check=True, capture_output=True, timeout=120)
    return stub_apk(
        STANDIN, into,
        application='<application android:label="Stand-in call">'
                    f'<activity android:name="{STANDIN_CALL}" android:exported="true"/></application>',
        permissions=("RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS"),
        dex=os.path.join(into, "classes.dex"),
    )


def visible_to_jemrec():
    """The packages Android lets JemRec see because its manifest names them
    (`dumpsys package queries`, "queries via package name")."""
    out = sh("dumpsys package queries")
    block = out.split("queries via package name:", 1)[-1].split("queries via", 1)[0]
    seen, mine = set(), False
    for line in block.splitlines():
        stripped = line.strip()
        if stripped.endswith(":"):
            mine = stripped == f"{PKG}:"
        elif mine and stripped:
            seen.add(stripped)
    return seen


def telegram_builds():
    """Issue #93: Telegram from telegram.org (org.telegram.messenger.web)
    was called not installed - no <queries> named it, so Android hid it
    from JemRec. Stand-ins of both builds' package names are installed:
    Android's own list says JemRec may see them; and where messenger calls
    can be recorded at all - Android 14 and later - Settings' Messenger
    calls lists each by the name that tells it from the Play Store's.
    Removed after."""
    installed = []
    workdir = tempfile.mkdtemp(prefix="jemrec-stubs-")
    try:
        for package in TELEGRAM_BUILDS:
            adb("install", "-r", stub_apk(package, workdir), check=True)
            installed.append(package)
        missing = set(TELEGRAM_BUILDS) - visible_to_jemrec()
        assert not missing, f"hidden from JemRec: {sorted(missing)}"
        if int(sh("getprop ro.build.version.sdk").strip()) < 34:
            return "visible to JemRec; messenger calls need Android 14, so Settings lists none here"
        open_settings_row("Messenger calls", "Record calls in", "Messenger calls")
        names = list(TELEGRAM_BUILDS.values())
        for name in names:
            scroll_to(name)
            assert on_screen(name), f"{name} not listed"
        return f"visible to JemRec, and listed as {' and '.join(names)}"
    finally:
        for package in installed:
            adb("uninstall", package)
        shutil.rmtree(workdir, ignore_errors=True)


def newest_version():
    """Discussion #91: Settings' About shows the installed version - as the
    package says it - and Newest version hands GitHub's newest release to
    the browser: Android's own record of the start, its address shortened
    there to the host. JemRec asks no server anything itself."""
    version = re.search(r"versionName=(\S+)", sh(f"dumpsys package {PKG}")).group(1)
    sh("logcat -c")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {PKG}/.MainActivity")
    tap_until("Settings", "Back", 30, "the Settings gear")
    scroll_to("Newest version")
    assert on_screen("JemRec version") and on_screen(version), f"JemRec version {version} not shown"

    def started():
        return re.search(r"START u0 \{act=android\.intent\.action\.VIEW dat=https://github\.com/\S*",
                         adb("logcat", "-d", "-s", "ActivityTaskManager:I"))

    found = tap_until("Newest version", started, 15, "the newest release handed to the browser")
    # The app that took it, from the same START line: JemRec's own START,
    # earlier in the log, names JemRec.
    taken = re.search(r"dat=https://github\.com/\S*.*?cmp=([\w.]+)/", adb("logcat", "-d", "-s", "ActivityTaskManager:I"))
    if taken:
        sh(f"am force-stop {taken.group(1)}")
    return f"JemRec version {version}; {found.group(0)}"


def allow_this_wifi():
    """The header's Tap to allow this Wi-Fi, end to end, as 0.25.1 rewrote it:
    on a Wi-Fi Android has not allowed, with the recorder down and port 5555
    shut to the app - so that nothing but Wireless debugging can bring the
    recorder back - the header asks; the tap puts Android's question up, and
    after Always allow and Allow the tap's own revive has the recorder back.
    Before, the tap only switched Wireless debugging off and on, and the
    recorder waited for the screen's re-check or a keep-alive: on the Honor
    after a restart, 2026-10-28, a minute. The allowance is taken back first
    (_forget_wifi_allowed), and the tap's Allow gives it again, as a phone's
    user would. Questions the screen's own revive puts up before the tap are
    cancelled: the header is what is checked."""
    label = "Tap to allow this Wi-Fi"  # Memo's wording; set below
    _forget_wifi_allowed()
    start_recorder()
    # "userId=10185" on the 13 AVD, "appId=10231" on the 16 (2026-10-28).
    uid = re.search(r"(?:userId|appId)=(\d+)", sh(f"dumpsys package {PKG}")).group(1)
    rule = f"OUTPUT -p tcp --dport 5555 -m owner --uid-owner {uid} -j REJECT"
    su(f"iptables -I {rule}")
    try:
        set_prefs(look="MEMO")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        sh(f"am force-stop {PKG}")  # the screen opened afresh, and its revive with it
        sh(f"am start -n {PKG}/.MainActivity")

        def asking():
            _answer_questions(allow=False)
            if on_screen(label):
                return True
            # A keep-alive's question from before JemRec opened sat behind
            # it, and answering it closed JemRec with Back (_answer_questions).
            sh(f"am start -n {PKG}/.MainActivity")
            return False

        try:
            wait_for(asking, 90, "the header asking to allow this Wi-Fi")
        except TimeoutError:
            print(where_things_stood())  # before the finally below moves it on
            raise
        log_before = revive_log()
        # Tapped until Android asks: a tap at the first dump can be lost (#49),
        # and while the tap's revive runs the header says Starting instead.
        end = time.time() + 40
        while not _questions():
            assert time.time() < end, "the tap never put Android's question up"
            if on_screen(label):
                tap(label)
            time.sleep(1)
        asked = time.time()
        _answer_questions(allow=True)
        answered_in = time.time() - asked
        wait_for(recorder_answers, 45, "the recorder back after Allow")
        # The tap's revive looks for adbd for about 23 s from the question, and
        # an answer later than that is the next revive's. On the Android 13 AVD,
        # 2026-11-05, this check's own Allow took 33 s, the system starting a
        # dozen apps at the time, and the recorder was back 11 s after it.
        if answered_in > 23:
            return f"asked on a Wi-Fi not allowed; Allow took {answered_in:.0f} s, past the tap's revive, and the next had the recorder back"

        # That revive's own end: the first end line after its CYCLE. Revives
        # queued behind it end after it, under the revive lock. Written a
        # moment after the recorder answers (pushMode, standDown): on the 17
        # AVD, 2026-10-29, a read at once came before it.
        def tap_revive_end():
            text = revive_since(log_before)
            if "revive: Wireless debugging CYCLE" not in text:
                return None
            after = text[text.index("revive: Wireless debugging CYCLE"):]
            line = re.search(r"revive: recorder (?:up|down) \(connect \w+\)", after)
            return line.group(0) if line else None

        try:
            end_line = wait_for(tap_revive_end, 30, "")
        except TimeoutError:
            end_line = None
        assert end_line, "the tap's revive never switched Wireless debugging off and on, or never ended"
        assert end_line == "revive: recorder up (connect CONNECTED)", f"the tap's revive ended {end_line}"
        # Switched on by JemRec - a revive's from off, or the tap's - and off
        # again by the recorder once it is up (1.5).
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off again after the start")
        return ("asked on a Wi-Fi not allowed; the tap put the question up, Allow had the recorder back by the "
                "tap's revive, and Wireless debugging off again after it")
    finally:
        quietly(su, f"iptables -D {rule}")
        try:
            _answer_questions(allow=True)
            start_recorder()
        except Exception as e:  # not in place of whatever failed the check
            print(f"     (the recorder could not be started again afterwards: {e})")


def _tls_ports():
    """adbd's Wireless debugging ports as the kernel lists adbd's own
    listeners (not 5555): one while it is on, none while it is off. Another
    process's listener is not counted (review round 1)."""
    return sorted({int(p) for line in su("ss -ltnp").splitlines() if '"adbd"' in line
                   for p in re.findall(r"(?:\*|\[::\]):(\d+)\s", line) if int(p) != 5555})


def _look_over_wireless_debugging():
    """Wireless debugging on, the app's own connect over it (the debug op
    connect: AdbTransport.autoConnect) and its session closed: what a start
    or setup's check leaves in Android's mDNS cache. Port 5555 must be shut
    to the app already, or the connect could land there."""
    sh("settings put global adb_wifi_enabled 1")
    wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 10, "Wireless debugging on")
    _answer_questions(allow=True)
    wait_for(_tls_ports, 15, "adbd's Wireless debugging port")
    # start_recorder's session on 5555 stays open, and with it the connect
    # would say "already connected" and look for nothing (review round 1).
    diag("disconnect")
    said = diag("connect")
    assert "diag[connect] connected" in said, f"the look before did not connect: {said}"
    diag("disconnect")


_MDNS_DROP = "OUTPUT -p udp --dport 5353 -j DROP"


def _mdns_off(dropped):
    """mDNS on the AVD dropped: UDP 5353 going out, IPv4 and IPv6 - adbd's
    announcements and Android's own queries alike, a multicast looped back
    to the phone itself included (it passes OUTPUT first). Each table is
    noted in [dropped] as its rule goes in, so the cleanup takes back exactly
    those (review round 1)."""
    for tables in ("iptables", "ip6tables"):
        su(f"{tables} -I {_MDNS_DROP}")
        dropped.append(tables)


def _mdns_on(dropped):
    for tables in dropped:
        quietly(su, f"{tables} -D {_MDNS_DROP}")


def mdns_silent():
    """Wireless debugging's port found with no mDNS answer to be had (1.9,
    #140): a look over Wireless debugging first, which leaves its port in
    Android's mDNS cache; then mDNS dropped on the AVD and Wireless debugging
    switched off and on - a new port nobody announces - with port 5555 shut
    to the app. Setup's connect, and Try again's, must land on adbd's new
    port, found by the loopback probe (the app's own line says so); then the
    recorder killed with Wireless debugging off, and the start - which
    switches it on, another new port - must have it back.

    What a phone met by chance (an old port from NsdService's cache, or adbd's
    announcement lost: tmp/issue-140/PLAN.md), made certain: on the Android 16
    AVD the cache gave the old port in one run and the live one in the next
    (2026-11-26), so a check that waited for it could pass on 1.8.1."""
    assert _wifi_allowed(), "the AVD's Wi-Fi is not allowed for Wireless debugging (set_up's allow_wifi)"
    start_recorder()
    uid = re.search(r"(?:userId|appId)=(\d+)", sh(f"dumpsys package {PKG}")).group(1)
    rule = f"OUTPUT -p tcp --dport 5555 -m owner --uid-owner {uid} -j REJECT"
    su(f"iptables -I {rule}")
    dropped = []
    try:
        _look_over_wireless_debugging()
        _mdns_off(dropped)
        before = _tls_ports()
        sh("settings put global adb_wifi_enabled 0")
        wait_for(lambda: not _tls_ports(), 15, "adbd's Wireless debugging port closed")
        sh("settings put global adb_wifi_enabled 1")
        wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 10, "Wireless debugging on again")
        _answer_questions(allow=True)
        now = wait_for(_tls_ports, 15, "adbd's new Wireless debugging port")
        assert now != before, f"adbd kept its port {before}: nothing to test"
        said = diag("connect")
        line = next((l for l in log().splitlines() if "autoConnect: " in l), "")
        assert "diag[connect] connected" in said, f"setup's connect found nothing with mDNS silent: {line or said}"
        got = re.search(r"autoConnect: CONNECTED on (\d+)", line)
        assert got and int(got.group(1)) in now, f"connected on the wrong port, adbd is on {now}: {line}"
        assert "found by the probe" in line, f"with mDNS silent, found otherwise: {line}"
        diag("disconnect")

        # The start: Wireless debugging off, as JemRec leaves it, and the
        # recorder killed; the revive switches it on - a port nobody
        # announces - and must find it all the same.
        sh("settings put global adb_wifi_enabled 0")
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 10, "Wireless debugging off")
        log_before = revive_log()
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: daemon_pid() is None, 10, "the recorder gone")
        diag("check", wait=120)
        text = revive_since(log_before)
        assert "revive: recorder up (connect CONNECTED)" in text, \
            "the recorder did not come back with mDNS silent: " + " | ".join(text.splitlines()[-4:])
        return f"setup's connect on adbd's new port {got.group(1)} by the probe; the start brought the recorder back"
    finally:
        _mdns_on(dropped)
        quietly(diag, "disconnect")
        quietly(su, f"iptables -D {rule}")
        quietly(sh, "settings put global adb_wifi_enabled 0")
        start_recorder_after()


def wireless_between_starts():
    """Wireless debugging on only while JemRec starts its recorder (1.5,
    tmp/wd-off-between-starts/PLAN.md): port 5555 shut to the app, so that
    only Wireless debugging can start the recorder, on the AVD's Wi-Fi
    allowed (set_up's allow_wifi, read back here). The recorder killed with
    Wireless debugging off, as JemRec leaves it: the revive switches it on
    (TURN_ON), starts the recorder, and the recorder switches it off again -
    it reads 0 within 15 s, and the recorder keeps its process 10 s after,
    USB debugging keeping adbd up. On Android 12 and 13 the kill comes after
    15 s with nobody browsing mDNS, past NsdService's 10 s stop of mdnsd:
    the start's switch-on must announce adbd afresh and the app's held look
    find it (AdbTransport.holdMdns). Then Wireless debugging switched on from
    the shell - the user's, for a computer - and the recorder killed again:
    back, and the switch still on."""
    assert _wifi_allowed(), "the AVD's Wi-Fi is not allowed for Wireless debugging (set_up's allow_wifi)"
    sdk = int(sh("getprop ro.build.version.sdk").strip())
    start_recorder()
    uid = re.search(r"(?:userId|appId)=(\d+)", sh(f"dumpsys package {PKG}")).group(1)
    rule = f"OUTPUT -p tcp --dport 5555 -m owner --uid-owner {uid} -j REJECT"
    su(f"iptables -I {rule}")
    try:
        sh("settings put global adb_wifi_enabled 0")
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 10, "Wireless debugging off")
        diag("disconnect")
        if sdk <= 33:
            time.sleep(15)  # past NsdService's CLEANUP_DELAY_MS, nobody browsing
        log_before = revive_log()
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: daemon_pid() is None, 10, "the recorder gone")
        diag("check", wait=120)
        text = revive_since(log_before)
        assert "revive: Wireless debugging TURN_ON" in text, "the revive did not switch Wireless debugging on"
        assert "revive: recorder up (connect CONNECTED)" in text, "the recorder did not come back over Wireless debugging"
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off again after the start")
        assert "revive: Wireless debugging off - the recorder says SWITCHED_OFF" in revive_since(log_before), \
            "the recorder was not the one to switch it off"
        assert "session: Wireless debugging off after a start - WIRELESS off" in \
            sh("cat /data/local/tmp/jemrec-capture.out"), "the recorder's own log does not say it"
        pid = daemon_pid()
        assert global_setting("adb_enabled") == "1", "USB debugging is off"
        time.sleep(10)
        assert daemon_pid() == pid and recorder_answers(), "the recorder went after Wireless debugging went off"

        # The user's: switched on by hand, and left on by the next start.
        sh("settings put global adb_wifi_enabled 1")
        wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 10, "Wireless debugging on")
        _answer_questions(allow=True)
        log_user = revive_log()
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: daemon_pid() is None, 10, "the recorder gone")
        diag("check", wait=120)
        text = revive_since(log_user)
        assert "revive: recorder up (connect CONNECTED)" in text, "the recorder did not come back with the user's on"
        assert "revive: Wireless debugging LEAVE" in text, "the user's Wireless debugging was switched"
        assert "the recorder says" not in text, "the recorder was asked to switch the user's off"
        time.sleep(5)
        assert global_setting("adb_wifi_enabled") == "1", "the user's Wireless debugging went off"
        return (f"switched on for the start{' after 15 s with nobody browsing' if sdk <= 33 else ''}, "
                f"off again by the recorder, which kept its process (pid {pid}); the user's left on")
    finally:
        quietly(su, f"iptables -D {rule}")
        quietly(sh, "settings put global adb_wifi_enabled 0")
        start_recorder_after()


def _last_usb_state():
    """The extras of the newest USB_STATE in Android's broadcast history, by
    its enqueue time - a dump may list a queue's records apart from another's
    (review round 1) - or "" where it has none left (the boot's, long gone:
    no data function in it)."""
    records = re.findall(r"act=android\.hardware\.usb\.action\.USB_STATE[^\n]*\n\s*extras: Bundle\[\{([^}]*)\}\]"
                         r"(?:(?!act=)[\s\S])*?enqueueClockTime=(\S+ \S+)",
                         sh("dumpsys activity broadcasts history"))
    return max(records, key=lambda r: r[1])[0] if records else ""


def usb_mode():
    """A USB mode chosen with the cable in, after the recorder's own cable
    watch (1.6, tmp/usb-mode-change/PLAN.md): Android's UsbDeviceManager sets
    the USB configuration to none first, init stops adbd, and the recorder,
    in adbd's cgroup, goes with it - the platform's own kill, made by `svc usb
    setFunctions` as Settings' chooser makes it (setCurrentFunctions), not
    by pkill as in cable. The emulator has no port for the recorder to see, so
    nothing but Android's USB_STATE tells JemRec. JemRec's process ended first
    (`am kill`), as Android ends one for memory: the broadcast starts it, its
    run watches and brings the recorder back over Wireless debugging - port
    5555 shut to the app, as in wireless_between_starts - and the recorder
    switches Wireless debugging off again after. The function is one the
    last USB_STATE did not name: Android sends none whose extras match the
    last (UsbDeviceManager.isUsbStateChanged), and the emulator's own reset
    back to adb is not broadcast (tmp/usb-mode-change/0a). Up to 1.5 nothing
    brought the recorder back here in 40 s on all four AVDs (2026-11-22).

    On Android 12 and 13 port 5555 stays open, and the run's revive comes
    over it: there the emulator's adbd, which always listens on 5555, starts
    mdnsd itself as it starts ("Setup mdns on port= 5555"), netd's mDNS side
    then finds it running when NsdService starts it ("Failed to start:
    Service already running"), and every app's search fails after
    (DNSServiceBrowse -65540) - Wireless debugging's announcement is found by
    no one (the 13 AVD, 2026-11-22). A phone's adbd listens on no port but
    with Start without Wi-Fi, whose port the revive then uses; and the S20
    (Android 13) was found again after its adbd restarted (ARCHITECTURE,
    "Unplugged from a computer")."""
    assert _wifi_allowed(), "the AVD's Wi-Fi is not allowed for Wireless debugging (set_up's allow_wifi)"
    shut = int(sh("getprop ro.build.version.sdk").strip()) > 33
    start_recorder()
    uid = re.search(r"(?:userId|appId)=(\d+)", sh(f"dumpsys package {PKG}")).group(1)
    rule = f"OUTPUT -p tcp --dport 5555 -m owner --uid-owner {uid} -j REJECT"
    if shut:
        su(f"iptables -I {rule}")
    try:
        sh("settings put global adb_wifi_enabled 0")
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 10, "Wireless debugging off")
        diag("disconnect")
        before = daemon_pid()
        log_before = revive_log()
        sh("input keyevent KEYCODE_HOME")
        time.sleep(2)
        app = sh(f"pidof {PKG}").strip()
        sh(f"am kill {PKG}")
        wait_for(lambda: sh(f"pidof {PKG}").strip() != app, 10, "JemRec's process ended")
        function = "ptp" if "mtp=true" in _last_usb_state() else "mtp"
        try:
            adb("shell", f"svc usb setFunctions {function}", timeout=20)
        except subprocess.TimeoutExpired:
            pass  # adbd going away under the command is the point
        adb("wait-for-device", timeout=60)
        wait_for(adb_ready, 60, "adb back after the change")
        assert daemon_pid() != before, f"setFunctions {function} left the recorder up: nothing was tested"
        began = time.time()
        wait_for(lambda: daemon_pid() not in (None, before) and recorder_answers(), 40,
                 "the recorder back after the USB mode change")
        took = time.time() - began
        text = revive_since(log_before)
        assert "cable: USB state changed; watching" in text, "the change did not wake JemRec's run"
        # The run notes it once the recorder's off is asked, a moment after
        # the recorder answers (the 16 and 17 AVDs, 2026-11-22: 0.3 s).
        wait_for(lambda: "cable: back (attempt" in revive_since(log_before), 15, "the run's own revive")
        text = revive_since(log_before)
        if shut:
            assert "revive: recorder up (connect CONNECTED)" in text, "the recorder did not come back over Wireless debugging"
        wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off again after the start")
        assert "revive: Wireless debugging off - the recorder says SWITCHED_OFF" in revive_since(log_before), \
            "the recorder was not the one to switch it off"
        return (f"setFunctions {function}: the recorder killed with adbd, back {took:.0f} s after adb was, "
                f"{'over Wireless debugging' if shut else 'over port 5555'}, Wireless debugging off again after")
    finally:
        if shut:
            quietly(su, f"iptables -D {rule}")
        quietly(sh, "settings put global adb_wifi_enabled 0")
        start_recorder_after()



LOCK_PIN = "1234"


def usb_default(functions=""):
    """Developer options' Default USB configuration set as Settings sets it
    (`svc usb setScreenUnlockedFunctions`, empty for No data transfer), in an
    adb call of its own: the change can restart adbd under the shell that
    runs it, which cut the commands after it in one call (2026-11-23)."""
    try:
        adb("shell", f"svc usb setScreenUnlockedFunctions {functions}".strip(), timeout=15)
    except subprocess.TimeoutExpired:
        pass  # adbd going away under the command is the point
    adb("wait-for-device", timeout=60)
    wait_for(adb_ready, 60, "adb back after the USB default's change")


def usb_default_now():
    """The Default USB configuration as Android dumps it - no line for none."""
    m = re.search(r"screen_unlocked_functions=([A-Z_,]+)", sh("dumpsys usb"))
    return m.group(1) if m else "none"


def usb_locked(locked=True):
    """Whether Android's USB service takes the phone for locked - only with a
    secure lock (UsbDeviceManager.onKeyguardStateChanged) - and so switches
    the USB functions."""
    return f"screen_locked={'true' if locked else 'false'}" in sh("dumpsys usb")


def lock_now():
    """The power key, as a person locks the phone - only with the screen on,
    where it puts it off - and the PIN lock screen up."""
    if "mWakefulness=Awake" in sh("dumpsys power | grep -m1 mWakefulness="):
        sh("input keyevent KEYCODE_POWER")
    adb("wait-for-device", timeout=60)
    wait_for(adb_ready, 60, "adb back after the lock")
    wait_for(usb_locked, 15, "Android to take the phone for locked")


def unlock_now():
    """The phone woken and the PIN typed, as a person unlocks it."""
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    sh("wm dismiss-keyguard")
    time.sleep(1)
    sh(f"input text {LOCK_PIN}")
    sh("input keyevent KEYCODE_ENTER")
    adb("wait-for-device", timeout=60)
    wait_for(adb_ready, 60, "adb back after the unlock")
    wait_for(lambda: usb_locked(False), 15, "Android to take the phone for unlocked")


BUTTON_WINDOW = "JemRecButton"  # CallButtonWindow.TITLE


def button_up():
    """Ask first's button over the call (CallButtonWindow): its window's
    bounds, or None with none drawn on the screen - Android keeps one hidden
    by the lock screen, not drawn."""
    window = re.search(rf"Window #\d+ Window\{{\w+ u0 {BUTTON_WINDOW}\}}:(.*?)(?=\n  Window #|\Z)",
                       sh("dumpsys window windows"), re.S)
    if not window or "isOnScreen=true" not in window.group(1) or "isVisible=true" not in window.group(1):
        return None
    # "mFrame" on Android 12, "frame" since.
    at = re.search(r"(?:\bframe|mFrame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", window.group(1))
    return tuple(int(v) for v in at.groups())


def question_banners():
    """How many times Ask first's question came up as a banner since the
    event log was cleared, as SystemUI logs every one (HeadsUpManager's
    sysui_heads_up_status, key and 1; the same on Android 12 to 17 and the
    Honor). Not from a dump of the screen: that is the call's own window,
    the banner up or not (2026-11-25, on all four AVDs)."""
    return len(re.findall(rf"sysui_heads_up_status: \[0\|{re.escape(PKG)}\|3\|[^\]]*,1\]", sh("logcat -b events -d")))


def question_posted():
    """Whether the question is there at all, in the shade or over the call:
    a record of now's (those gone are in the same dump, archived, but not as
    one)."""
    return re.search(rf"NotificationRecord\(.*pkg={PKG} .*id=3 ", sh("dumpsys notification --noredact")) is not None


def tap_button(cross=False):
    """Record on the button, or its cross: as tall as it is, the cross is the
    last of it, Record the first."""
    x1, y1, x2, y2 = wait_for(button_up, 10, "the button over the call")
    tall = y2 - y1
    sh(f"input tap {x2 - tall // 2 if cross else x1 + tall} {(y1 + y2) // 2}")


def answer_locked():
    """A call ringing on a locked phone answered with the call key, pressed
    until telephony says so: a press before the call's screen is up does
    nothing, as hang_up's. One press was not enough on the Android 16 AVD
    (2026-11-24, usb_default_lock's and ask_button's, a run apart)."""
    for _ in range(5):
        sh("input keyevent KEYCODE_CALL")
        try:
            return wait_for(lambda: "mCallState=2" in sh("dumpsys telephony.registry | grep -m1 mCallState="), 4, "")
        except TimeoutError:
            pass
    raise RuntimeError("the locked call would not be answered")


def lock_screen():
    """The phone woken and locked by its power key, whatever it was doing,
    until Android says the lock screen is up. lock_now()'s word is the USB
    service's, which once did not come within 15 s, for a phone asleep since
    the call before (the Android 16 AVD, 2026-11-24; inferred)."""
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    sh("input keyevent KEYCODE_POWER")
    wait_for(lambda: re.search(r"\bshowing=true", sh("dumpsys window policy")), 15, "the lock screen")


def unlock_screen():
    """The phone woken and its PIN typed, until the lock screen is gone; a
    phone not locked is left as it is."""
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)

    def gone():
        if not re.search(r"\bshowing=true", sh("dumpsys window policy")):
            return True
        sh("wm dismiss-keyguard")
        time.sleep(1)
        sh(f"input text {LOCK_PIN}")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(1)
        return False
    wait_for(gone, 20, "the lock screen gone")


def in_front(activity):
    """Whether [activity] is the one resumed: "topResumedActivity=" since
    Android 13, "ResumedActivity: " on 12 (its AVD, 2026-11-24)."""
    return re.search(rf"esumedActivity[=:].*{activity}", sh("dumpsys activity activities")) is not None


def ask_button():
    """Ask first's question and its ways to say Record (1.8,
    tmp/ask-first-button/PLAN.md), asked for by a Galaxy A17's owner whose
    phone's pop-ups go by themselves. The button over the call, "display over
    other apps" allowed by the recorder: over the call's screen, dragged and
    kept there, Record recording the call (exact); its cross a Not now, after
    which Record on the call's own notification still records; over a call
    answered on a PIN-locked phone, and not on the lock screen itself; the
    question in the shade and no banner while it is up, unlocked and locked
    (the Honor's owner, 2026-11-25: both were a clutter); not shown once the permission is taken back, the question then a banner that
    stays, which the power key makes a window over the call's screen - a tap
    outside its dialog gives the call's back. The PIN, the permission and the
    mode put back after."""
    lock_was_off = sh("locksettings get-disabled").strip() == "true"
    said = []

    def no_banner():
        """The question waits in the shade, two seconds after it was asked:
        a banner of the ordinary kind came with the button at first."""
        time.sleep(2)
        assert question_banners() == 0, "a banner over the call, the button up"
        assert question_posted(), "no question in the shade, the button up"

    def asked_out(button=True):
        """A call placed and answered, asked about."""
        sh("logcat -c")
        sh("logcat -b events -c")
        sh(f"am start -a android.intent.action.CALL -d tel:{STRANGER_OUT}")
        wait_log(r"asking whether to record", 20, "Ask first to ask")
        if button:
            wait_for(button_up, 10, "the button over the call")
            no_banner()
        time.sleep(1)
        adb("emu", "gsm", "accept", STRANGER_OUT)
        time.sleep(2)

    set_prefs(recording_mode="ON_DEMAND")
    start_recorder()
    try:
        set_prefs(call_button_place=None)
        got = diag("callbutton", "--ez", "on", "true")
        assert "recorder true, allowed true" in got, got
        sh("input keyevent KEYCODE_HOME")

        asked_out()
        x1, y1, x2, y2 = button_up()
        sh(f"input swipe {(x1 + x2) // 2} {(y1 + y2) // 2} {(x1 + x2) // 2} {(y1 + y2) // 2 + 300} 600")
        moved = wait_for(lambda: (button_up() or (0, y1))[1] - y1 > 200 and button_up(), 5, "the button to be dragged")
        assert count(r"on-demand recording started") == 0, "a drag was taken for Record"
        tap_button()
        wait_log(r"monitor: on-demand recording started", 15, "the button's Record")
        wait_for(lambda: button_up() is None, 10, "the button to go, the call being recorded")
        time.sleep(4)
        hang_up()
        _, seconds, _ = saved()
        assert seconds > 4, f"{seconds:.1f} s of a call recorded for more than 4"
        said.append(f"dragged {moved[1] - y1} px and kept, Record recorded {seconds:.1f} s")

        asked_out()
        assert abs(button_up()[1] - moved[1]) < 4, f"the button not where it was left: {button_up()} for {moved}"
        tap_button(cross=True)
        wait_log(r"monitor: user declined to record this call", 10, "the cross to say Not now")
        wait_for(lambda: button_up() is None, 10, "the button to go after Not now")
        got = diag("pressaction", "--es", "title", "'Not recording this call'", "--es", "action", "Record")
        assert "pressed Record" in got, got
        wait_log(r"monitor: on-demand recording started", 15, "the call's own notification's Record")
        time.sleep(4)
        hang_up()
        saved()
        said.append("after Not now the call's notification's Record recorded")

        sh("locksettings set-disabled false")
        assert "Pin set" in sh(f"locksettings set-pin {LOCK_PIN}"), "the PIN was not set"
        lock_screen()
        sh("logcat -c")
        sh("logcat -b events -c")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"callwatch: ringing", 15, "the call to ring")
        time.sleep(3)
        assert button_up() is None, "the button over a call nobody has answered"
        answer_locked()
        wait_for(button_up, 15, "the button over the locked call's screen")
        no_banner()
        sh("input keyevent KEYCODE_HOME")
        wait_for(lambda: button_up() is None, 10, "the button hidden on the lock screen itself")
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_for(phone_idle, 10, "the locked call to end")
        wait_for(lambda: button_up() is None, 10, "the button to go with the call")
        said.append("shown over a PIN-locked call's screen, hidden on the lock screen, and no banner with it")

        # The permission taken back on Android's page: the question as it was.
        sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW deny")
        sh("logcat -c")
        sh("logcat -b events -c")
        lock_screen()
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"callwatch: ringing", 15, "the call to ring")
        time.sleep(3)
        answer_locked()
        wait_log(r"asking whether to record", 15, "Ask first to ask without the button")
        time.sleep(2)
        assert button_up() is None, "the button shown with its permission taken back"
        wait_for(lambda: in_front("InCallActivity"), 10, "the call's own screen in front")
        wait_for(question_banners, 10, "the question a banner over it, without the button")
        sh("input keyevent KEYCODE_SLEEP")
        time.sleep(3)
        sh("input keyevent KEYCODE_WAKEUP")
        wait_for(lambda: in_front("RecordPromptActivity"), 10,
                 "the question's window, the screen put off under its banner")
        time.sleep(1)
        width = int(re.search(r"(\d+)x\d+", sh("wm size")).group(1))
        sh(f"input tap {width // 2} 300")
        wait_for(lambda: in_front("InCallActivity"), 10, "the call's screen back, the question's dialog closed by a tap outside it")
        assert count(r"user declined") == 0 and count(r"on-demand recording started") == 0, \
            "a tap outside the dialog was taken for an answer"
        said.append("with the permission taken back the question's window is a dialog a tap outside closes")
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_for(phone_idle, 10, "the call to end")

        # Allowed again by the recorder, over the denial, and taken back by it.
        got = diag("callbutton", "--ez", "on", "true")
        assert "recorder true, allowed true" in got, got
        got = diag("callbutton", "--ez", "on", "false")
        assert "recorder true, allowed false" in got, got
        said.append("allowed again and taken back by the recorder")
        return "; ".join(said)
    finally:
        quietly(no_call_left)
        # Unlocked with its PIN before the PIN goes: cleared under the lock
        # screen, the Android 12 AVD went on asking for it, and the three
        # checks after this one that tap through Settings found its keypad
        # (2026-11-24; each passed on a fresh boot).
        quietly(unlock_screen)
        quietly(sh, f"locksettings clear --old {LOCK_PIN}")
        if lock_was_off:
            quietly(sh, "locksettings set-disabled true")
        quietly(diag, "callbutton", "--ez", "on", "false")
        quietly(sh, f"appops set {PKG} SYSTEM_ALERT_WINDOW default")
        quietly(set_prefs, recording_mode="AUTOMATIC", call_button=None, call_button_place=None)


def usb_default_lock():
    """A screen lock with a data mode as the Default USB configuration (1.7,
    #131, tmp/issue-131/PLAN.md). With a PIN and no cable, AOSP's
    UsbDeviceManager switches the USB functions to charging at every lock and
    back to the default at every unlock ("We are unlocked when the keyguard
    is down or non-secure", MSG_UPDATE_SCREEN_LOCK); with File transfer as
    the default each switch restarts adbd, and the recorder, in adbd's
    cgroup, goes with it - and Android sends no USB_STATE for either, so
    1.6's watch never runs. On the Android 12, 16 and 17 AVDs, 2026-11-23,
    on 1.6: the recorder gone 5 s after the lock and still down 60 s on; a
    lock 5 s into a call saved 5.64 s of a 15.80 s call.

    Here: the kill made first, so the check is seen to see it; then home's
    card (UsbDefault, the recorder's answer and a secure lock); its Set to No
    data transfer, which has the recorder write none and queues the
    USB-state watch, as the write itself restarts adbd here; and then what
    the card is for - locks that keep the recorder, a call while locked and
    a lock during a call recorded whole (exact). The PIN, the lock screen's
    switch and the default put back after, each in an adb call of its own."""
    lock_was_off = sh("locksettings get-disabled").strip() == "true"
    said = []
    start_recorder()
    log_start = revive_log()
    try:
        sh("locksettings set-disabled false")
        assert "Pin set" in sh(f"locksettings set-pin {LOCK_PIN}"), "the PIN was not set"
        usb_default("mtp")
        assert usb_default_now() == "MTP", f"the default reads {usb_default_now()}, not MTP"
        # The change itself can end the recorder (a mode change, 1.6's watch
        # brings it back); opened, JemRec starts it in any case.
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(recorder_answers, 60, "the recorder up with File transfer as the default", step=1)

        # The kill the card is about, first: no check can pass that does not
        # see it.
        before = daemon_pid()
        sh("input keyevent KEYCODE_HOME")
        lock_now()
        wait_for(lambda: daemon_pid() != before or not recorder_answers(), 15, "the lock to end the recorder")
        said.append("the lock ended the recorder")
        unlock_now()

        # Home's card, once a reading has the recorder's answer: opened again
        # until it lands (the open starts the recorder, the reading asks it).
        def card(shown):
            if on_screen("Locking your phone stops the recorder") == shown:
                return True
            sh(f"am start -n {PKG}/.MainActivity")
            return False

        wait_for(lambda: card(True), 60, "home's card about the USB default", step=3)
        assert on_screen("Not now"), "the card has no Not now"
        wait_for(recorder_answers, 30, "the recorder up under the card", step=1)

        # Its button: the recorder writes none after it answers, and is
        # brought back by the watch the tap queues.
        before = daemon_pid()
        sh("logcat -c")
        log_before = revive_log()
        tapped = time.time()
        assert tap("Set to No data transfer"), "the card's button is not on screen"
        wait_for(lambda: usb_default_now() == "none", 20, "the default none after the tap", step=1)
        wait_for(lambda: daemon_pid() not in (None, before) and recorder_answers(), 40,
                 "the recorder back after the tap", step=1)
        took = time.time() - tapped
        assert "cable: USB state changed; watching" in revive_since(log_before), "the tap queued no USB-state watch"
        # Logged before the write: the write can end the recorder at once.
        assert "usb: Default USB configuration asked (1) - USB setting" in log(), "the recorder did not take the set"
        wait_for(lambda: card(False), 30, "the card gone once the default is none", step=3)
        said.append(f"the tap: none, the recorder back {took:.0f} s after")

        # What it is for.
        kept = daemon_pid()
        for _ in range(2):
            lock_now()
            unlock_now()
        assert daemon_pid() == kept and recorder_answers(), f"a lock ended the recorder ({kept} -> {daemon_pid()})"
        said.append("two locks and unlocks kept it")
        sh("input keyevent KEYCODE_HOME")
        lock_now()

        def call(lock_during):
            """An incoming call answered, [lock_during] it locked 3 s in,
            and ended by the caller - the network's end, as a key may do
            nothing on a locked phone."""
            sh("logcat -c")
            rings, answers = count(r"callwatch: ringing"), count(r"off-hook \(")
            adb("emu", "gsm", "call", STRANGER_IN)
            wait_more(r"callwatch: ringing", rings, 15, "the call to ring")
            time.sleep(2)
            answer_locked()
            wait_more(r"off-hook \(", answers, 15, "the call to be answered")
            time.sleep(3)
            if lock_during:
                lock_now()
            time.sleep(5)
            adb("emu", "gsm", "cancel", STRANGER_IN)
            wait_for(phone_idle, 15, "the call to end")
            return exact("_in_")

        said.append(f"a call while locked: {call(lock_during=False)}")
        unlock_now()
        time.sleep(10)  # no early skip within 10 s of the last call's end (rules)
        said.append(f"a lock during a call: {call(lock_during=True)}")
        assert daemon_pid() == kept, f"the lock during the call ended the recorder ({kept} -> {daemon_pid()})"
        # The guard after an off must not blame the off for a USB change: a
        # lock, an unlock and the default set restart adbd. Before 1.7 one
        # loss was noted for good, and it tripped on all four AVDs in this
        # check's lock and unlock (2026-11-23). Now none is counted here -
        # adbd runs again - and none is kept.
        guard = [l for l in revive_since(log_start).splitlines()
                 if any(said_ in l for said_ in ("it stays on on this phone from now",
                                                 "a second in a row keeps it on here",
                                                 "on this phone it keeps adbd up"))]
        assert not guard, f"the guard blamed the off: {guard[0]}"
        spared = revive_since(log_start).count("not its doing")
        said.append(f"{spared} losses not blamed on the off")
        return "; ".join(said)
    finally:
        quietly(no_call_left)
        # Unlocked with the PIN, not the PIN cleared under a locked phone: on
        # Android 12 that left the keyguard up for good, `wm dismiss-keyguard`
        # and all, with Android's USB service still taking the phone for
        # locked - and the checks after it, newest_version, folder_lost and
        # killed_mid_save, found the lock screen where Settings' gear should
        # be (2026-11-23). On 13, 16 and 17 the same cleared without it.
        if quietly(usb_locked):
            quietly(unlock_now)
        quietly(sh, "input keyevent KEYCODE_WAKEUP")
        quietly(sh, f"locksettings clear --old {LOCK_PIN}")
        if lock_was_off:
            quietly(sh, "locksettings set-disabled true")
        quietly(sh, "wm dismiss-keyguard")
        if quietly(usb_default_now) != "none":
            quietly(usb_default)
        start_recorder_after()

def wifi_on(on):
    sh(f"cmd wifi set-wifi-enabled {'enabled' if on else 'disabled'}")


def wait_wifi_off():
    wait_for(lambda: "Wi-Fi is disabled" in sh("dumpsys wifi | grep -m1 'Wi-Fi is'"), 20, "Wi-Fi off")


def wait_wifi_connected(timeout=60, what="Wi-Fi connected"):
    wait_for(lambda: 'connected to "' in sh("cmd wifi status"), timeout, what)


def ogg_problem(path):
    """What is wrong with the Ogg Opus file at [path] on the device, by RFC
    7845 and RFC 3533 - held to JemRec's own writing, stricter than the RFC:
    no end trimming, no start past 0 - or None: every page's CRC (RFC 3533); each audio page's position
    the 48 kHz samples of every
    packet ended on it and before (from each packet's TOC, RFC 6716 3.1), -1
    on a page where none ends, the stream's last page - and only it - marked
    its end; and, libopusfile's reason to refuse a file, a first audio page
    whose position is less than what plays on it (4.5). And the stream's
    frame (RFC 3533 6; RFC 7845 3): one serial number, the pages numbered on
    from 0 without a gap - how a lost page is told - the first alone marked
    the stream's start, a page marked as carrying a packet on exactly when
    the page before ended mid-packet, OpusHead alone on the first page, and
    OpusTags ending its page, the audio beginning on a new one (review,
    2026-11-18). Read here without the app's code."""
    data = adb("exec-out", f"cat '{path}'", text=False)
    pages, at = [], 0
    while at + 27 <= len(data) and data[at:at + 4] == b"OggS":
        segments = data[at + 26]
        lacing = data[at + 27:at + 27 + segments]
        body = at + 27 + segments
        pages.append((data[at + 5], int.from_bytes(data[at + 6:at + 14], "little", signed=True), lacing, body))
        at = body + sum(lacing)
    if at != len(data) or len(pages) < 3:
        return f"not whole Ogg pages ({len(pages)} read, {len(data) - at} bytes over)"
    # The frame, from each page's header: its start is its body less its
    # 27 bytes and lacing.
    serials, carried = set(), False
    for n, (flags, _, lacing, body) in enumerate(pages):
        head = body - len(lacing) - 27
        serials.add(int.from_bytes(data[head + 14:head + 18], "little"))
        sequence = int.from_bytes(data[head + 18:head + 22], "little")
        if sequence != n:
            return f"page {n} is numbered {sequence}"
        if bool(flags & 2) != (n == 0):
            return f"page {n} {'is' if flags & 2 else 'is not'} marked the stream's start"
        if bool(flags & 1) != carried:
            return f"page {n} {'says' if flags & 1 else 'does not say'} it carries a packet on"
        carried = len(lacing) > 0 and lacing[-1] == 255
    if len(serials) != 1:
        return f"{len(serials)} serial numbers in one stream"
    head0 = pages[0]
    if len(head0[2]) != 1 or data[head0[3]:head0[3] + 8] != b"OpusHead":
        return "the first page is not OpusHead alone"
    # Each page's CRC (RFC 3533), worked out here: the pass over older
    # recordings rewrites their page headers in place (review, 2026-11-18).
    broken = [n for n, (_, _, _, ok) in enumerate(ogg_pages(data)) if not ok]
    if broken:
        return f"page {broken[0]} of {len(pages)} fails its CRC ({len(broken)} in all)"
    packets, total, partial, first_end, first_plays = 0, 0, b"", None, 0
    tags_ended_on = None
    for n, (flags, position, lacing, body) in enumerate(pages):
        off, ended = body, False
        for lace in lacing:
            if tags_ended_on == n:
                return f"page {n}: audio on OpusTags' page - RFC 7845 3 begins it on a new one"
            partial += data[off:off + lace]
            off += lace
            if lace == 255:
                continue
            packets += 1
            if packets == 2:
                tags_ended_on = n
            if packets > 2:  # after OpusHead and OpusTags
                toc = partial[0]
                frames = 1 if toc & 3 == 0 else 2 if toc & 3 in (1, 2) else partial[1] & 0x3F
                config = toc >> 3
                units = [4, 8, 16, 24][config & 3] if config < 12 else [4, 8][config & 1] if config < 16 else [1, 2, 4, 8][config & 3]
                total += frames * units * 120
                if first_end is None:
                    first_plays += frames * units * 120
                ended = True
            partial = b""
        if packets <= 2 and position != 0:
            return f"page {n}, a header's, says {position}: RFC 7845 4 has a header page at 0"
        if packets > 2:
            want = total if ended else -1
            if position != want:
                return f"page {n} says {position}, its packets end at {want}"
            if ended and first_end is None:
                first_end = position
        if bool(flags & 4) != (n == len(pages) - 1):
            return f"page {n} of {len(pages)} {'is' if flags & 4 else 'is not'} marked the stream's end"
    if first_end is not None and first_end < first_plays:
        return f"its first audio page ends at {first_end}, less than the {first_plays} on it"
    return None


def muxer_paged_recording(packets=100):
    """An Ogg Opus recording paged as Android's muxer paged every one before
    1.2: each page's position where its packet starts, not ends, and no page
    marked the stream's end - for the pass over older recordings to mend.
    Its audio packets are 20 ms CELT frames (TOC 0xF8) of nothing: the pass
    reads only their TOC."""
    import struct

    def crc(page):
        c = 0
        for b in page:
            c ^= b << 24
            for _ in range(8):
                c = ((c << 1) ^ 0x04C11DB7) if c & 0x80000000 else c << 1
            c &= 0xFFFFFFFF
        return c

    def page(flags, position, sequence, packet):
        lacing = bytes([255] * (len(packet) // 255) + [len(packet) % 255])
        head = (b"OggS" + bytes([0, flags]) + struct.pack("<q", position) + struct.pack("<I", 0x4A524543)
                + struct.pack("<I", sequence) + b"\0\0\0\0" + bytes([len(lacing)]) + lacing)
        whole = bytearray(head + packet)
        whole[22:26] = struct.pack("<I", crc(bytes(whole)))
        return bytes(whole)

    opus_head = b"OpusHead" + bytes([1, 2]) + struct.pack("<H", 312) + struct.pack("<I", 48000) + b"\0\0\0"
    opus_tags = b"OpusTags" + struct.pack("<I", 6) + b"JemRec" + struct.pack("<I", 0)
    pages = [page(2, 0, 0, opus_head), page(0, 0, 1, opus_tags)]
    for n in range(packets):
        pages.append(page(0, n * 960, n + 2, bytes([0xF8]) + bytes(10)))
    return b"".join(pages)


def opus_valid():
    """A call's recording is a valid Ogg Opus stream by RFC 7845 and RFC 3533
    (ogg_problem: its page positions where its packets end, its last page
    marked, the stream's frame) - which Android's muxer wrote neither of - and
    the pass over recordings saved before 1.2 (OldRecordingPages, the debug
    build's oldpages) mends one: a recording paged as the muxer paged them,
    planted as the app's own and deleted after, and leaves every one this
    install may write - not empty - so too. Saved with the muxer's, libopusfile
    would refuse them (read in its source), and Android's own decoder cut 6.5
    ms out 13.5 ms in (OggPages)."""
    import base64
    sh("logcat -c")
    dial(CONTACT, ring_s=2, talk_s=5)
    _, _, name = saved()
    wrong = ogg_problem(f"{RECORDINGS}/{name}")
    assert wrong is None, f"the new recording {name}: {wrong}"
    # One paged as the muxer paged them, planted as the app's own: after the
    # reinstall check nothing old is this install's, and the pass had nothing
    # to mend - this passed whatever the pass did (review, 2026-11-18).
    planted = time.strftime("20200103_%H%M%S_out.ogg")
    store(planted, recordings=True, data=base64.b64encode(muxer_paged_recording()).decode())
    try:
        before = ogg_problem(f"{RECORDINGS}/{planted}")
        assert before is not None, "the planted recording is not paged as the muxer paged"
        said = diag("oldpages", wait=240)
        assert "every recording gone through" in said, said
        after = ogg_problem(f"{RECORDINGS}/{planted}")
        assert after is None, f"the planted recording, after the pass: {after} (before it: {before})"
    finally:
        # For good, not to Recently deleted: the next run's Restore all
        # brought every earlier one back (review, 2026-11-18).
        quietly(sh, "content delete --uri content://media/external/audio/media "
                    f"--where \"_display_name='{planted}'\"")
    # The ones this install may write, as the pass does: after a reinstall
    # (the reinstall check) Android counts the old install's files as
    # nobody's, and those are left as they play. MediaStore says whose.
    # Empty ones aside: published with nothing in them, each a save the
    # emulator was killed seconds after - a power cut to it - before saves
    # were synced (1.2, RecordingTags.synced): 11 on the Android 12 AVD,
    # 2026-11-17, the newest made by a build before the sync.
    rows = sh("content query --uri content://media/external/audio/media "
              "--projection _display_name:_size:owner_package_name:relative_path")
    found = [(m.group(1), int(m.group(2))) for m in re.finditer(
        r"_display_name=(.+?\.ogg), _size=(\d+), owner_package_name=" + re.escape(PKG) + r", relative_path=Recordings/JemRec/", rows)]
    ours = [f for f, size in found if size > 0]
    empty = len(found) - len(ours)
    bad = [(f, w) for f in ours if (w := ogg_problem(f"{RECORDINGS}/{f}"))]
    assert not bad, f"{len(bad)} of this install's {len(ours)} after the pass: {bad[:3]}"
    return f"{name} valid; all {len(ours)} of this install's recordings valid after the pass over older ones" + (
        f" ({empty} empty from before saves were synced)" if empty else "")


def notifications_off():
    """Notifications taken back after setup - in Android's settings, or by
    Android from an app left unused - leave JemRec usable: home, its
    recordings and Settings, with a notice saying what is off; in Ask first,
    whose question is a notification, the header says no call can be
    recorded. Until 1.2 they put it back on setup's first screen. From
    Android 13, where notifications are a permission. And from Android 14,
    full-screen notifications turned off - an app op the user can turn off
    (Android 14 behaviour changes) - leave Ask first's question on screen
    for a minute, not until it is answered (measured, RecordPromptActivity):
    home says so, its Turn on on that page."""
    sdk = int(sh("getprop ro.build.version.sdk").strip())
    if sdk < 33:
        return "Android 12: no notification permission to take back"
    said = "home with the notice; in Ask first, the header says it cannot record"
    try:
        sh(f"pm revoke {PKG} android.permission.POST_NOTIFICATIONS")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: on_screen("Notifications are off"), 20, "home's notice")
        assert on_screen("Recordings"), "home's Recordings tab not there"
        set_prefs(recording_mode="ON_DEMAND")
        sh(f"am force-stop {PKG}")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: on_screen("Tap to turn on notifications"), 20, "the header's way back")
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
        if sdk >= 34:
            # What this install was given - the op's default after set_up -
            # and with it no notice: a false warning on every phone would
            # pass a check that looked only for the notice (review round 9).
            given = sh(f"appops get {PKG} USE_FULL_SCREEN_INTENT").strip()
            sh(f"am force-stop {PKG}")
            sh(f"am start -n {PKG}/.MainActivity")
            # The header's "Ready to record" comes once home has read the
            # settings; "Recordings", the tab, is there from the first frame
            # and would pass the absence before anything was read (review
            # round 10).
            wait_for(lambda: header_says("Ready to record"), 20, "home in Ask first, its settings read")
            time.sleep(2)
            assert not on_screen("Ask first's question will not stay up"), f"said with full-screen allowed ({given})"
            sh(f"appops set {PKG} USE_FULL_SCREEN_INTENT deny")
            sh(f"am force-stop {PKG}")
            sh(f"am start -n {PKG}/.MainActivity")
            wait_for(lambda: on_screen("Ask first's question will not stay up"), 20, "home's notice of full-screen off")
            tap("Turn on")
            # The page itself, by its intent's action - not any Settings
            # screen (review round 9).
            wait_for(lambda: "act=android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT" in
                     sh("dumpsys activity activities | grep -m3 'act=android.settings'"), 10,
                     "Android's full-screen notifications page")
            sh("input keyevent KEYCODE_BACK")
            sh(f"appops set {PKG} USE_FULL_SCREEN_INTENT allow")
            sh(f"am force-stop {PKG}")
            sh(f"am start -n {PKG}/.MainActivity")
            wait_for(lambda: header_says("Ready to record"), 20, "home again, its settings read")
            time.sleep(2)
            assert not on_screen("Ask first's question will not stay up"), "still said once allowed again"
            said += f"; full-screen notifications off said in Ask first, Turn on on their page, gone once allowed (given: {given})"
    finally:
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
        if sdk >= 34:
            quietly(sh, f"appops set {PKG} USE_FULL_SCREEN_INTENT default")
        quietly(set_prefs, recording_mode="AUTOMATIC")
        start_recorder_after()
    return said


def folder_lost():
    """A folder picked for recordings, then deleted: the next call is saved
    in the standard folder, a notification says so once, and home says the
    folder cannot be reached (FolderAccess). Until 1.2 the save fell back
    without a word."""
    folder = f"/sdcard/{PICKED}"
    sh(f"rm -rf {folder}; mkdir -p {folder}")
    try:
        pick_folder(PICKED)
        sh(f"rm -rf {folder}")
        sh(f"am force-stop {PKG}")
        start_recorder()
        # What the standard folder holds before: a new file there is the
        # call's. The newest of many said nothing - a full run leaves dozens,
        # and a fall back to app storage passed too (review, 2026-11-18).
        before = set(sh(f"ls {RECORDINGS}/").split())
        sh("logcat -c")
        dial(STRANGER_OUT, ring_s=2, talk_s=4)
        saved()
        new = sorted(set(sh(f"ls {RECORDINGS}/").split()) - before)
        assert len(new) == 1 and new[0].endswith(".ogg"), f"not saved in {RECORDINGS}: new there {new}"
        name = new[0]
        notes = sh("dumpsys notification --noredact")
        assert "Recording saved elsewhere" in notes, "no notification that it went elsewhere"
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: on_screen("Your recordings folder cannot be reached"), 20, "home's notice")
    finally:
        for _ in range(3):
            if "documentsui" in sh("dumpsys window | grep mCurrentFocus"):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1)
        quietly(set_prefs, recordings_tree_uri=None)
        sh(f"am force-stop {PKG}")
        sh(f"rm -rf {folder}")
        start_recorder_after()
    return f"{name} saved in the standard folder, said in a notification and on home"


def stopped_early():
    """The recorder ended in the middle of a call, two ways. Brought back:
    the call's own service watches it (RecorderWatch), says so in the call's
    notification, starts it again while the call is up, and the new recorder
    records the rest - both parts saved, and neither said to have stopped
    early, the second carrying the first on. Measured before the watch on
    the Android 17 AVD (2026-11-18): started again by hand mid-call, a
    recorder recorded the rest; nothing started it. And not brought back -
    Wi-Fi off, so no revive can reach Wireless debugging: the call's
    notification goes once the call is over, though no recorder said so
    (until 1.2's reviews it stayed until the next call), and what it wrote,
    collected once it is back, is said to have stopped early (StoppedEarly) -
    Telecom's end of the call well after its last packet - where until 1.2 it
    was found out by listening. And brought back during an incoming call:
    the new recorder joins it, and its part is saved as that call's,
    incoming, not as no call's "_out" (review round 10). And lost unnoticed
    just before a hang-up, and a redial: the redial a call of its own, the
    call before's file a part of its call (review rounds 24, 25) - three
    times, each with one rule the one that decides: two that tell the calls
    apart, one that marks the part (redial_pass; round 30). With Wi-Fi off, the call's end is read from the audio mode at
    once (rounds 19 to 25)."""
    said = []
    notes = lambda: sh("dumpsys notification --noredact")

    def call_with_recorder_killed():
        before = count(r"off-hook \(")
        sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
        wait_more(r"off-hook \(", before, 15, f"the call to {CONTACT} to go off-hook")
        time.sleep(2)
        adb("emu", "gsm", "accept", CONTACT)
        time.sleep(3)

    def part_marks():
        # Not "parts": the incoming pass below names its files so, and a
        # nested function reads the enclosing name at the call.
        m = re.search(r'<set name="part_recordings">(.*?)</set>', app_file(PREFS), re.S)
        return m.group(1) if m else ""

    def redial_pass(how):
        """The recorder lost unnoticed just before a hang-up, and a redial: two
        calls, each told, the first's file a part. [how] picks the rule that
        decides - the split for the first two, the part for the third:
        - "telecom": exact call tracking on, redialled the instant the phone
          reads idle - within the end-of-call tone, the mode never normal
          between - so Telecom's list emptying is what ends the call before
          (round 29). One normal between, and the mode could have decided:
          once more.
        - "mode": tracking off, so Telecom binds no call service of JemRec's
          and its list says nothing; redialled a second after the mode goes
          normal (round 24's rule).
        - "started": tracking on, a recorder started again between the calls -
          as another path can (the app opened, the heartbeat, the Wi-Fi revive,
          which on the AVDs fires at most dials and hang-ups) - so the redial's
          start is a fresh one, not joined: only a recorder started since the
          call before's file began says that file is cut (round 30,
          DaemonLauncher.launchedAtMs; saved as a whole call before). In the
          "telecom" and "mode" passes the part is marked by a joined start
          and a recorder started since both (and in "telecom" mostly by the
          loss seen too, on 12 and 17): a recorder that joins was started with a call up,
          after the one before was gone - joined means started since, but for
          a clock set back - so the joined rule alone is unit-tested only
          (aJoinedNextStartMakesTheCallBeforesFileAPart); either fixes lc17,
          where the Wi-Fi revive's recorder, started 5-9 s after the hang-up,
          joined the redial unseen (2026-11-19; review round 32)."""
        try:
            if how == "mode":
                set_prefs(call_tracking=False)
                start_recorder()
            for attempt in (1, 2):
                time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
                sh("logcat -c")
                files_before = set(sh(f"ls {RECORDINGS}/").split())
                call_with_recorder_killed()
                # Telecom's list in play or not, as the pass says: with tracking
                # off, Telecom binds none of JemRec's (the "mode" pass is the
                # mode's alone); on, it does - read the same way, so the reading
                # is known to work (review round 31).
                bound = tracker_bound()
                assert bound == (how != "mode"), \
                    f"Telecom {'bound' if bound else 'did not bind'} JemRec's call service in the {how} pass"
                sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
                normals = count(r"monitor: audio mode 0")
                # The call screen's key, as round 25's pass: the far side's
                # cancel does not end this outgoing call on the AVDs (round 26).
                hang_up()
                skip = None
                redialled = False
                # Counted before each dial: a recorder up by then says the
                # redial's off-hook a fraction of a second after it (review round
                # 31: counted after, the line was in the count, and the wait for
                # one more ran out). The call before's own off-hook is in it.
                starts = None
                if how == "telecom":
                    # At once: hang_up returned the moment the phone read idle.
                    starts = count(r"callwatch: off-hook \(")
                    sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
                else:
                    # The call's mode left - about 1.6 s after Telecom's end on
                    # the 12 AVD (2026-11-19), the end-of-call tone - before the
                    # redial, as a person's.
                    wait_log(r"monitor: audio mode 0", 10, "the call's mode to go normal")
                    # The path this is for: the loss not found before the mode
                    # left (looked at here, where the code decides - round 29).
                    # Found before, the mode ends the call and the redial is the
                    # next call whatever the rule (round 26).
                    if count(r"monitor: the recorder is gone mid-call"):
                        skip = "the loss was found before the mode left"
                    elif how == "started":
                        # Another path's start, through DaemonLauncher, at once:
                        # the app's own - on the AVDs the Wi-Fi revive's often
                        # comes first, about 5 s after the hang-up (rounds 30-32),
                        # and the app's is then a no-op (RecorderLifecycle runs
                        # one start at a time). Not start_recorder(): its pkill
                        # could kill the revive's as it started (round 32). Not
                        # after a wait: by then the call's watch had ended the
                        # call (two looks with no recorder), and the bootstrap's
                        # log clear hid it from the guard below (review round 33).
                        # Both clear the log: before any end of the watch's.
                        connect_app()
                        diag("bootstrap")
                        wait_for(recorder_answers, 30, "the recorder started between the calls", step=1)
                    if not skip:
                        time.sleep(1)
                        # "started": the log perhaps cleared by its start since.
                        starts = count(r"callwatch: off-hook \(")
                        sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
                if not skip:
                    redialled = True
                    time.sleep(2)
                    adb("emu", "gsm", "accept", CONTACT)
                    # Not through call_with_recorder_killed: with no recorder,
                    # no off-hook line comes to wait for. A fresh start where the
                    # recorder was up before the redial.
                    wait_more(r"callwatch: off-hook \(", starts, 90, "the recorder's start of the redial")
                    # The call before ended by the watch's looks before the
                    # redial: the redial is the next call whatever the rule
                    # (review round 30). In "started" the log was cleared by its
                    # start, a few seconds after the mode left - two looks with
                    # no recorder need more (5 s apart, round 33).
                    if count(r"monitor: the call is over and the recorder never said so"):
                        skip = "the watch ended the call before the redial"
                    elif how == "telecom" and count(r"monitor: audio mode 0") > normals:
                        skip = "the mode went normal between the calls"
                    elif how != "started" and not count(r"callwatch: off-hook \(joined mid-call\)"):
                        skip = "the redial's start was not a joined one"
                    elif how == "started" and count(r"callwatch: off-hook \(joined mid-call\)"):
                        skip = "the redial's start was a joined one"
                    time.sleep(4)
                    hang_up()
                if not skip:
                    break
                if attempt == 2:
                    raise AssertionError(f"{skip}, twice: not the case this pass is for ({how})")
                print(f"     ({skip}; the redial pass once more)")
                wait_for(recorder_answers, 90, "the recorder back after the first try", step=1)
                # The first try collected and told in full before the second
                # counts files and notices (review round 27: one late, it made a
                # broken build's second try pass). Its notices may be one, a
                # broken build's - waited for, not asserted.
                wait_for(lambda: "jemrec_rec_" not in sh("ls /data/local/tmp"), 120, "the first try's calls collected", step=2)
                try:
                    wait_for(lambda: count(r"aftercall: posted for") >= (2 if redialled else 1), 60, "")
                except TimeoutError:
                    pass
                time.sleep(3)

            def two_new():
                new = sorted(set(sh(f"ls {RECORDINGS}/").split()) - files_before)
                return new if len(new) >= 2 else None
            calls = wait_for(two_new, 120, "the call before and the redial both saved", step=2)
            wait_for(lambda: f"<string>{calls[0]}</string>" in part_marks(), 10,
                     f"{calls[0]}, the call before's, to be marked a part of its call")
            # Two calls, told apart: taken as one, the two were saved together
            # and told once. (The redial's file is a part too, rightly, when its
            # recorder joined it part way - "_joined"; round 25's first cut of
            # this check asserted otherwise and failed a healthy run.)
            wait_for(lambda: count(r"aftercall: posted for") >= 2, 30, "each call's after-call notice - two calls")
            return f"lost unnoticed before a hang-up, a redial ({how}): two calls, the first's file a part"
        finally:
            if how == "mode":
                # Not into a call left up by a failure (review round 32).
                quietly(end_any_call)
                quietly(set_prefs, call_tracking=True)
                start_recorder_after()

    try:
        diag("clearnotes")
        # Brought back during the call.
        sh("logcat -c")
        call_with_recorder_killed()
        saves = count(r"save: wrote \d+ packets")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        killed = time.time()
        wait_for(lambda: "Trying again - this part of the call is not recorded" in notes(), 30,
                 "the call's notification to say the recorder is gone")
        wait_for(recorder_answers, 90, "the recorder brought back during the call", step=1)
        back = time.time() - killed
        wait_for(lambda: "Recording call" in notes(), 30, "the call's notification back to recording")
        assert not phone_idle(), "the call ended before the recorder was back"
        time.sleep(4)
        hang_up()
        wait_more(r"save: wrote \d+ packets", saves + 1, 90, "the call's two parts to be saved")
        # The early end is told after the publish, after "save: wrote": read
        # once the call's notice is posted, the last of its saves' work, or
        # a broken run passes whenever the read wins (review round 13).
        wait_log(r"aftercall: posted for", 60, "the call's after-call notice")
        # One notice for the call, of both its parts (1.4: until then the
        # longest part's length alone, Play and Delete on that part).
        told = re.search(r"aftercall: posted for \S+ \([^)]*, (\d+) parts", log())
        assert told and told.group(1) == "2", "the call's notice not told of its two parts"
        assert f"in {told.group(1)} parts" in notes(), "the call's notice does not say the call is in two parts"
        # Anchored: the line a part carried on by the next says ends
        # "; another recording carries it on" and is no telling (review
        # round 9).
        assert not re.search(r"save: the recording stopped \d+ ms before the call ended(?!;)", log()), \
            "a part carried on by the next was said to have stopped early"
        said.append(f"the recorder back {back:.0f} s after its end, mid-call, said; both parts saved")
        # Brought back during an incoming call: the new recorder joins it -
        # the off-hook the first state it is told, no ring seen - and its
        # part is matched to the call up at its start: incoming, as the
        # first. Until round 10 it was "_out", no call's, whatever the rules.
        time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
        sh("logcat -c")
        files_before = set(sh(f"ls {RECORDINGS}/").split())
        before = count(r"off-hook \(")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"callwatch: ringing", 15, "the ring")
        time.sleep(1)
        sh("input keyevent KEYCODE_CALL")
        wait_more(r"off-hook \(", before, 15, "the incoming call answered")
        time.sleep(3)
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_log(r"callwatch: off-hook \(joined mid-call\)", 90, "the brought-back recorder to join the call")
        time.sleep(4)
        adb("emu", "gsm", "cancel", STRANGER_IN)
        wait_for(phone_idle, 15, "the incoming call to end")
        def two_new():
            new = sorted(set(sh(f"ls {RECORDINGS}/").split()) - files_before)
            return new if len(new) >= 2 else None
        parts = wait_for(two_new, 90, "both parts of the incoming call saved", step=2)
        assert all("_in" in f and "_out" not in f for f in parts), f"a part not saved as incoming: {parts}"
        wait_log(r"aftercall: posted for", 60, "the call's after-call notice")
        assert not re.search(r"save: the recording stopped \d+ ms before the call ended(?!;)", log()), \
            "a part of the joined call was said to have stopped early"
        said.append(f"brought back mid-way through an incoming call: joined, both parts saved incoming ({len(parts)})")
        # One notification id for every early stop: the next pass reads its
        # own, not one left by these (review round 11).
        diag("clearnotes")
        # The recorder lost unnoticed just before a hang-up, and a redial:
        # the redial a call of its own - not the call before's, as its
        # revived recorder's joined start said until 1.2's round 24 - the
        # call before's file a part of its call, alone (rounds 24, 25). Once
        # for each rule, each made the one that decides: with exact call
        # tracking on, a redial a second after the mode went normal was told
        # apart by the mode and by Telecom's list both, so a pass with either
        # planted out would still pass (review round 30; read from the code,
        # not run).
        for how in ("telecom", "mode", "started"):
            said.append(redial_pass(how))
        # Not brought back: Wi-Fi off before the kill. Its own notices only:
        # the redial pass's part may have been told as stopped early (round
        # 26: the early stop read below took that one).
        diag("clearnotes")
        time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
        sh("logcat -c")
        call_with_recorder_killed()
        wifi_on(False)
        wait_wifi_off()
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: "Trying again - this part of the call is not recorded" in notes(), 30,
                 "the call's notification to say the recorder is gone")
        time.sleep(4)
        hang_up()
        # Ended from the audio mode at once - a phone call's normal comes only
        # with no call active - not the watch's looks later (review rounds
        # 19 to 25: no check had shown it for a phone call). Timed from the
        # mode, not the hang-up: the mode goes normal after the end-of-call
        # tone, about 1.6 s after Telecom's end on the 12 AVD and once 6.2 s
        # on a loaded 17, the call ended 4 ms after it (round 33; a 5 s wait
        # from the hang-up failed that run).
        wait_log(r"monitor: audio mode 0", 15, "the call's mode to go normal")
        wait_log(r"monitor: out of a call, the recorder still lost; the call is over", 2,
                 "the call's end read from the audio mode at once")
        wait_for(lambda: "Trying again - this part" not in notes() and "Recording call" not in notes(), 40,
                 "the call's notification gone after the call, no recorder having said it ended")
        wifi_on(True)
        wait_wifi_connected()
        # Wi-Fi's own run collects the call within seconds and says it - and
        # start_recorder's diag() calls clear the log, the line with it
        # (review round 10). The notification stays: it is what is waited
        # for, the recorder started only if Wi-Fi's run has not said it.
        early = lambda: re.search(r"stopped about (\d+) s before", notes())
        own_run = True
        try:
            m = wait_for(early, 45, "the early stop said by Wi-Fi's own run", step=2)
        except TimeoutError:
            own_run = False
            start_recorder()
            m = wait_for(early, 120, "the early stop to be said", step=2)
        seconds = int(m.group(1))
        # One notice for the call: its after-call notice in the early one's
        # place, not beside it (1.4: the Honor, 2026-11-19, gave "Recording
        # stopped early" and "Last call recorded, but silent" at once).
        wait_log(r"aftercall: posted for \S+ \([^)]*, stopped early", 60, "the call's notice, in the early one's place")
        assert not after_call_up(), "an after-call notice beside the early one: two notices for one call"
        line = re.search(r"save: the recording stopped (\d+) ms before the call ended(?!;)", log())
        # Said by Wi-Fi's own run, nothing cleared the log since: its line is
        # there (review round 11: skipped unseen when absent).
        assert line or not own_run, "the early stop said, its log line not written"
        if line:
            assert (int(line.group(1)) + 500) // 1000 == seconds, f"said {seconds} s, logged {line.group(1)} ms"
        said.append(f"not brought back (no Wi-Fi): the call's notification gone after it, "
                    f"stopped about {seconds} s before the call ended, said")
        return "; ".join(said)
    finally:
        # Failed half way, the call stays up - every call after it call
        # waiting - Wi-Fi off, and the recorder killed for the checks after
        # (review, 2026-11-18).
        quietly(end_any_call)
        quietly(adb, "emu", "gsm", "cancel", CONTACT)
        # And the incoming pass's caller (review round 11).
        quietly(no_call_left)
        quietly(wifi_on, True)
        quietly(wait_wifi_connected)
        start_recorder_after()


def killed_mid_save():
    """The app's process killed in the middle of saving a call, as Android
    kills one it needs the memory of. CLAUDE.md lists "a process killed
    mid-transfer" among what Android does to a feature, and no check had
    produced it (review, 2026-11-18). A save takes well under a second, so
    the debug build holds the next one at a point of it (holdsave,
    SaveHold) and the process is killed there as root, SIGKILL as the
    low-memory killer sends: a second into the transfer, written but not
    published, and published with the recorder's copy not yet deleted; and
    the process ending itself straight after the journal's writes - what
    publishing will do, nothing published yet, and the recorder's copy
    deleted with the journal not yet told - so a write not on disk yet is
    lost, as a kill loses it. In the standard folder and in one picked
    through Android's picker, where a file is made under its own name at
    once. Each time the recorder keeps its copy (gone already at the last),
    nothing half written shows in the standard folder, and opening JemRec
    saves the call whole - once, under its own name, nothing left pending -
    and the recorder's copy goes. Before the save journal (SaveJournal), the
    third was saved twice, the second as "(1)" (the Android 17 AVD,
    2026-11-18)."""
    picked = f"/sdcard/{PICKED}"
    said = []
    try:
        for folder in (RECORDINGS, picked):
            if folder == picked:
                sh(f"rm -rf {picked}; mkdir -p {picked}")
                pick_folder(PICKED)
                sh("input keyevent KEYCODE_HOME")
            # And straight after the journal's write - what publishing will
            # do, nothing published, a MediaStore row still pending - and
            # after the recorder's copy went, the journal not yet told: each
            # with the process ending itself there (review round 9).
            for at, die in (("transfer", False), ("publish", False), ("publishing", True), ("published", False),
                            ("done", True)):
                said.append(("picked, " if folder == picked else "") + killed_saving(folder, at, die))
        return "; ".join(said)
    finally:
        quietly(diag, "holdsave", "--es", "at", "none")
        # dial's own hang-up may have failed, a call to CONTACT left up.
        quietly(end_any_call)
        quietly(adb, "emu", "gsm", "cancel", CONTACT)
        quietly(no_call_left)
        for _ in range(3):
            if "documentsui" in sh("dumpsys window | grep mCurrentFocus"):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1)
        quietly(set_prefs, recordings_tree_uri=None)
        sh(f"rm -rf {picked}")


def killed_saving(folder, at, die=False):
    """killed_mid_save's one kill, at [at], saving into [folder]: by SIGKILL
    from here while the save is held, or - [die] - the process ending itself
    at once there, straight after the journal's write, so a write not yet on
    disk would be lost as a kill loses it (SaveHold.die; review round 9)."""
    shown = lambda: {n.strip() for n in sh(f"ls {folder}/").splitlines() if n.strip()}
    pending = lambda: {n.strip() for n in sh(f"ls -a {folder}/").splitlines() if n.startswith(".pending-")}
    copies = lambda: {n.strip() for n in sh("ls /data/local/tmp/").splitlines() if n.startswith("jemrec_rec_")}
    held = diag("holdsave", "--es", "at", at, *(("--ez", "die", "true") if die else ()))
    assert f"held at {at}" in held, held
    files, waiting = shown(), pending()
    pid = sh(f"pidof {PKG}").strip()
    sh("logcat -c")
    dial(CONTACT, ring_s=2, talk_s=6)
    m = wait_log(rf"save: held at {at} for (jemrec_rec_\S+\.dat)", 30, f"the save held at {at}")
    recfile = m.group(1)
    if die:
        wait_for(lambda: sh(f"pidof {PKG}").strip() != pid, 20, "the app's process ended at the hold")
    else:
        pid = sh(f"pidof {PKG}").strip()
        assert pid, "no app process to kill"
        su(f"kill -9 {pid}")
        wait_for(lambda: sh(f"pidof {PKG}").strip() != pid, 10, "the app's process gone")
    # Killed before the recorder's copy was deleted, it is kept; at `done`
    # it is gone already, and the reconcile's own pick-up finishes it.
    if at == "done":
        assert recfile not in copies(), f"killed at done: the recorder still has {recfile}"
    else:
        assert recfile in copies(), f"killed at {at}: the recorder's copy {recfile} went with the kill"
    half = shown() - files
    if folder == RECORDINGS and at in ("transfer", "publish", "publishing"):
        assert not half, f"killed at {at}: {sorted(half)} shows in the folder"
    sh("logcat -c")
    sh(f"am start -n {PKG}/.MainActivity")
    f = re.escape(recfile)
    m = wait_log(rf"save: (?:wrote \d+ packets, ([\d.]+)s from {f}|{f} was cut short while publishing; \d+ file)", 90,
                 f"killed at {at}: the call collected")
    wait_for(lambda: recfile not in copies(), 20, f"killed at {at}: the recorder's copy deleted")
    new = shown() - files
    assert len(new) == 1, f"killed at {at}: {sorted(new)} in the folder, not one recording"
    name = new.pop()
    assert " (1)" not in name, f"killed at {at}: saved again as {name}"
    size = int(sh(f"stat -c %s '{folder}/{name}'").strip() or 0)
    assert size > 0, f"killed at {at}: {name} is empty"
    left = pending() - waiting
    assert not left, f"killed at {at}: left pending {sorted(left)}"
    sh("input keyevent KEYCODE_HOME")
    if m.group(1):
        seconds = float(m.group(1))
        assert seconds > 5, f"killed at {at}: {seconds:.1f} s of a 6 s call"
        return f"{at}: {'a file showed, ' if half else ''}saved whole, {seconds:.1f} s"
    return f"{at}: finished, saved once"


def doze():
    """Doze, as Android's own test command puts a phone in it (`dumpsys
    deviceidle force-idle`, developer.android.com, Test power-related issues),
    held while it runs: an incoming call is recorded and saved at once -
    Telecom's bind and the recorder's wake start the call's service in the
    foreground - and the recorder ended, a kick brings it back: expedited
    work, which Doze lets run, with the network it asks for ("bypass Doze,
    app standby, and battery saver network restrictions (if the job has a
    setRequiredNetwork(NetworkRequest))", JobInfo.Builder.setExpedited).
    CLAUDE.md lists Doze among what Android does to a feature, and no check
    had produced it (review, 2026-11-18). Let out of Doze after, whatever
    failed."""
    start_recorder()
    said = []
    try:
        sh("dumpsys battery unplug")
        sh("input keyevent KEYCODE_SLEEP")
        forced = sh("dumpsys deviceidle force-idle")
        assert "deep idle" in forced or "Now forced" in forced, f"not put in Doze: {forced.strip()}"
        state = sh("dumpsys deviceidle get deep").strip()
        assert state == "IDLE", f"Doze says {state}"
        sh("logcat -c")
        ring_in(STRANGER_IN, ring_s=2, talk_s=6)
        _, seconds, _ = saved()
        assert seconds > 4, f"in Doze: saved {seconds:.1f} s of a 6 s call"
        said.append(f"a call in Doze saved at once, {seconds:.1f} s")
        # The ringing woke the screen; Doze held all the same (forced).
        sh("input keyevent KEYCODE_SLEEP")
        sh("dumpsys deviceidle force-idle")
        state = sh("dumpsys deviceidle get deep").strip()
        assert state == "IDLE", f"Doze says {state} before the kick"
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: not recorder_answers(), 10, "the recorder gone")
        diag("monitor")
        began = time.time()
        wait_for(recorder_answers, 60, "in Doze: the recorder back by a kick", step=1)
        said.append(f"the recorder ended, back {time.time() - began:.0f} s after a kick")
        return "; ".join(said)
    finally:
        quietly(sh, "dumpsys deviceidle unforce")
        quietly(sh, "dumpsys battery reset")
        quietly(sh, "input keyevent KEYCODE_WAKEUP")
        quietly(sh, "wm dismiss-keyguard")
        quietly(no_call_left)
        start_recorder_after()


def early_end():
    """The recorder's encoder ended in the middle of a call - its file's
    write failing, the phone's storage filled as root for a few seconds -
    with exact call tracking off, so Telecom gives the app no end of the
    call: the recorder names the file with what it missed (`_early<ms>`,
    RecordingSession.Ending), and the app says the recording stopped early
    from that alone (StoppedEarly.marked). Without it, a messenger call's
    recording, or one with tracking off, that stopped early was never said
    (review, 2026-11-18). Produced first on the Android 17 AVD the same day:
    "Audio encoding error", 16 s missed, said. And during the call, its own
    notification says the rest of it is not recorded (the recorder's
    RECORDING_STOPPED); and a message the answering machine is taking whose
    recording ends the same way is hung up on at once, what was kept saved -
    its silence watch, reading the caller from that recording, would take a
    caller still talking for silence and hang up 30 s on (read in review,
    2026-11-18; not produced)."""
    fill = "/data/local/tmp/jemrec-fill"
    capture_log = lambda: sh("cat /data/local/tmp/jemrec-capture.out")
    try:
        # Inside the try: a recorder that would not start left tracking off
        # for the rest of the run (review, 2026-11-18).
        set_prefs(call_tracking=False)
        start_recorder()
        diag("clearnotes")
        sh("logcat -c")
        before = count(r"off-hook \(")
        sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
        wait_more(r"off-hook \(", before, 15, f"the call to {CONTACT} to go off-hook")
        time.sleep(2)
        adb("emu", "gsm", "accept", CONTACT)
        # The recording under way before its file is filled - not a sleep
        # that may end before it starts (review round 9).
        wait_for(lambda: "recording: started" in capture_log(), 15, "the recording to start")
        time.sleep(2)
        free_k = int(su("df -k /data | tail -1").split()[3])
        su(f"fallocate -l {free_k}k {fill}")
        # Said during the call, in its own notification.
        wait_for(lambda: "The rest of this call is not recorded" in sh("dumpsys notification --noredact"), 15,
                 "the call's notification to say the recording stopped")
        assert not phone_idle(), "the call ended before its notification said so"
        # Past the stall watch's 5 s: its "Trying again" once turned the
        # encoder's end for good into a stop tried again (review round 11).
        time.sleep(7)
        said = sh("dumpsys notification --noredact")
        assert "The rest of this call is not recorded" in said and "Trying again - this part" not in said, \
            "the encoder's end for good said as a stop tried again"
        su(f"rm -f {fill}")
        time.sleep(8)
        hang_up()
        m = wait_log(r"save: the recording stopped (\d+) ms before the call ended(?!;)", 60, "the early end to be said")
        named = re.search(r"wake: com\.jemcik\.jemrec\.CALL_ENDED \(jemrec_rec_\d+_early(\d+)_", capture_log())
        assert named, "the recorder did not name the file as ended early"
        assert "tracking: off" in capture_log(), "exact call tracking was on: Telecom's end, not the mark, may have said it"
        # Its seconds as the log's: the notice says the stop this call's save
        # measured (review, 2026-11-18).
        seconds = (int(m.group(1)) + 500) // 1000
        assert f"stopped about {seconds} s before" in sh("dumpsys notification --noredact"), \
            f"no notification that it stopped about {seconds} s early"
        # Said once, the call's notice in the early one's place (1.4).
        wait_log(r"aftercall: posted for \S+ \([^)]*, stopped early", 30, "the call's notice, in the early one's place")
        assert not after_call_up(), "an after-call notice beside the early one: two notices for one call"
        said = (f"said during the call; the file named _early{named.group(1)}; said "
                f"{int(m.group(1)) / 1000:.1f} s early, tracking off")
        # A message whose recording ends the same way: hung up on at once.
        # The machine answers on tracking's word (ARCHITECTURE, Exact call
        # tracking): tracking on again, and a recorder that has it.
        set_prefs(call_tracking=True)
        start_recorder()
        answering_on()
        time.sleep(10)  # past TelecomAccount's RECENT_END_MS from the call before
        lines = len(capture_log().splitlines())

        def since():
            return "\n".join(capture_log().splitlines()[lines:])

        sh("logcat -c")
        adb("emu", "gsm", "call", STRANGER_IN)
        wait_log(r"answer: greeting played", 30, "the greeting to play")
        time.sleep(2)
        free_k = int(su("df -k /data | tail -1").split()[3])
        su(f"fallocate -l {free_k}k {fill}")
        began = time.time()
        wait_for(lambda: "answer: the recording stopped - nothing more of the message can be kept; hanging up" in since(),
                 20, "the answering machine to hang up on a message it can no longer keep")
        took = time.time() - began
        su(f"rm -f {fill}")
        wait_for(phone_idle, 15, "the message's call to end")
        # At once, as said: the silence watch would have waited 30 s.
        assert took < 10, f"hung up {took:.0f} s after the fill"
        # What was kept is saved.
        wait_log(r"save: wrote \d+ packets", 60, "the message kept to be saved")
        return said + f"; a message hung up on {took:.0f} s after its recording ended, what was kept saved"
    finally:
        # The phone's storage given back first, whatever failed.
        quietly(su, f"rm -f {fill}")
        quietly(end_any_call)
        quietly(adb, "emu", "gsm", "cancel", CONTACT)
        quietly(wait_for, phone_idle, 10, "the phone to be idle")
        quietly(set_prefs, call_tracking=True)
        quietly(no_call_left)
        # The machine off again: on, it answers the checks' calls after this
        # one at 5 s - doze's ring_in answers at 2 (review round 9).
        quietly(diag, "answer", "--ez", "on", "false")
        start_recorder_after()


def audio_server_restart():
    """The audio server ended in the middle of a call - as Android ends and
    restarts it itself, 10 to 16 times a run on the Android 17 AVD - and the
    recording goes on, the call saved whole but for the gap. Produced, not
    waited for: the server killed as root, which init starts again.

    What carries it on is Android's own AudioRecord, which restores itself
    after the server's death: on the Android 12 and 17 AVDs (2026-11-18) the
    capture read no error, and the recording had one gap of 1.1 to 1.5 s - 4.7
    s once on the 12 beside another AVD under load - its sound after it as
    before; the gap held under 6 s. A read that does fail is the recorder's
    to make anew (Restarting, RestartPolicy) - said in its log, and here, if
    it came to that."""
    talk_s = 14
    # The recorder up: the Wi-Fi checks before this one may have left it down.
    start_recorder()
    try:
        return _audio_server_restart(talk_s)
    finally:
        # Failed half way, the call stays up - every call after it call
        # waiting (review, 2026-11-18).
        quietly(end_any_call)
        quietly(adb, "emu", "gsm", "cancel", CONTACT)
        quietly(wait_for, phone_idle, 10, "the phone to be idle")


def stall_told():
    """The audio server held down mid-call - stopped (SIGSTOP), as measured
    by hand on the Android 12 and 17 AVDs (2026-11-18): AudioRecord's reads
    only block, none fails, and the restart loop never sees it. The stall
    watch says it as any stop (StallWatch, review round 11): the call's
    notification "Recording stopped - Trying again" within seconds, and back
    to "Recording call" once reads have flowed for 5 s after the server goes
    on. The same recorder throughout, and the recorder's own line of a stop
    (Restarting.Told's): a recorder that died and came back says the same
    words (the call's watch; review round 12). And the file is no early end:
    back before the hang-up, the stall is no loss (RecordingLoss.back)."""
    notes = lambda: sh("dumpsys notification --noredact")
    capture_log = lambda: sh("cat /data/local/tmp/jemrec-capture.out")
    start_recorder()
    server = None
    try:
        diag("clearnotes")
        sh("logcat -c")
        recorder = daemon_pid()
        assert recorder, "no recorder running"
        lines = len(capture_log().splitlines())
        since = lambda: "\n".join(capture_log().splitlines()[lines:])
        before = count(r"off-hook \(")
        sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
        wait_more(r"off-hook \(", before, 15, f"the call to {CONTACT} to go off-hook")
        time.sleep(2)
        adb("emu", "gsm", "accept", CONTACT)
        wait_for(lambda: "Recording call" in notes(), 20, "the call's notification recording")
        time.sleep(3)
        server = sh("pidof audioserver").strip()
        assert server, "no audio server to hold"
        su(f"kill -STOP {server}")
        held = time.time()
        wait_for(lambda: "Trying again - this part of the call is not recorded" in notes(), 20,
                 "the stall said in the call's notification")
        said_after = time.time() - held
        su(f"kill -CONT {server}")
        server = None
        wait_for(lambda: "Recording call" in notes() and "Trying again - this part" not in notes(), 30,
                 "the call's notification back to recording")
        assert not phone_idle(), "the call ended before the recording went on"
        assert daemon_pid() == recorder, "the recorder was replaced: its watch said it, not the stall watch"
        assert "recording: stopped mid-call; telling the app" in since(), \
            "the recorder never said the stall itself"
        hang_up()
        saved()
        wait_log(r"aftercall: posted for", 60, "the call's after-call notice")
        ended = re.search(r"wake: com\.jemcik\.jemrec\.CALL_ENDED \(jemrec_rec_(\S+)", since())
        assert ended and "_early" not in ended.group(1), f"back before the end, named an early end: {ended and ended.group(1)}"
        assert not re.search(r"save: the recording stopped \d+ ms before the call ended(?!;)", log()), \
            "back before the end, said as stopped early"
        return f"the audio server held down: said {said_after:.0f} s in, back once reads flowed, no early end"
    finally:
        if server:
            quietly(su, f"kill -CONT {server}")
        quietly(end_any_call)
        quietly(adb, "emu", "gsm", "cancel", CONTACT)
        quietly(wait_for, phone_idle, 10, "the phone to be idle")


def _audio_server_restart(talk_s):
    sh("logcat -c")
    before = count(r"off-hook \(")
    sh(f"am start -a android.intent.action.CALL -d tel:{CONTACT}")
    wait_more(r"off-hook \(", before, 15, f"the call to {CONTACT} to go off-hook")
    time.sleep(2)
    adb("emu", "gsm", "accept", CONTACT)
    time.sleep(4)
    server = sh("pidof audioserver").strip()
    assert server, "no audio server to end"
    killed_at = int(sh("date +%s%3N").strip())  # the phone's clock, as the recording's start is stamped
    su(f"kill -9 {server}")
    wait_for(lambda: sh("pidof audioserver").strip() not in ("", server), 15, "the audio server back")
    time.sleep(talk_s - 4)
    hang_up()
    _, seconds, name = saved()
    daemon_log = "cat /data/local/tmp/jemrec-capture.out"
    assert "the recording ends here" not in sh(daemon_log), "the recorder gave its capture up"
    # Recorded past the kill: more than the 4 s before it, most of the call.
    assert seconds > talk_s / 2, f"saved {seconds:.1f} s of a {talk_s} s call"
    # And heard after it, as before it - where a capture brought back silent
    # would have nothing after the kill. Read from the app's own levels,
    # which cover the whole file: the saved file's check (AudioCheck) stops
    # counting once each side has had 3 s of sound, here before the kill - it
    # measured only the call's start, and the first version of this check
    # read it. The emulator's caller is a tone on and off about every 0.6 s
    # (its levels, both AVDs, 2026-11-18): sound in about half of them,
    # before and after alike - so the share after is held to the share
    # before, and no silence after may outlast the tone's own pauses.
    gap_m = re.search(r"then \d+ gap\(s\), ([\d.]+) s", log())
    # Every phone call's save says it - 0 gaps too: missing, the bound below
    # would hold nothing (review, 2026-11-18).
    assert gap_m, "the save did not say the recording's gaps"
    gap = float(gap_m.group(1))
    # Bounded: 1.1 to 1.5 s measured on each AVD alone, 4.7 s once on the
    # Android 12 AVD beside another under load (2026-11-18) - the emulator's
    # own restart taking longer. A capture back only after much longer than
    # that is not the restore measured (review, 2026-11-18).
    assert gap < 6.0, f"a gap of {gap:.2f} s at the restart, where 1.1 to 4.7 s was measured"
    # Where the kill is in the file: from Telecom's answer, where the saved
    # call begins - an outgoing call's ringing is not kept (SavePlan) - and
    # with no gap added, which is not in the file: the recording is shorter
    # than its call by it (RecordingSaver). Placed from the dialling and the
    # gap added, as the first version did, `after` began some 4 s past the
    # restart, and a capture back but silent that long passed (review,
    # 2026-11-18).
    _, _, answered, _ = wait_for(tracked, 5, "Telecom's account of the call")[-1]
    assert answered, "no answer in Telecom's account of the call"
    levels = waveform_levels(name)
    level_s = seconds / len(levels)
    at_s = (killed_at - answered) / 1000
    killed = int(at_s / level_s)
    first = int((at_s + 0.5) / level_s)
    before = levels[:killed]
    after = levels[first:len(levels) - len(levels) // 20]  # not the hang-up's last moments
    assert len(before) > len(levels) // 10 and len(after) > len(levels) // 4, \
        f"too little before or after the kill to judge: {len(before)} and {len(after)} levels"
    heard_before = sum(1 for v in before if v > 0) / len(before)
    heard = sum(1 for v in after if v > 0) / len(after)
    quiet = longest = 0
    for v in after:
        quiet = quiet + 1 if v == 0 else 0
        longest = max(longest, quiet)
    # The whole file, a digit a level (0 silent to 9 loudest), every 4th:
    # what a failure says, so a quiet caller can be told from a capture
    # brought back silent.
    profile = "".join(str(min(9, int(v * 10))) for v in levels[::4])
    assert heard > 0.7 * heard_before and longest * level_s < 2, (
        f"sound in {heard:.0%} of the levels after the kill against {heard_before:.0%} before, "
        f"its longest silence {longest * level_s:.1f} s: silent after the server came back? levels: {profile}")
    tries = len(re.findall(r"making it anew, \d+ of", sh(daemon_log)))
    how = f"its capture made anew {tries}x" if tries else "Android's AudioRecord restored itself"
    return (f"the audio server ended 4 s in; {how}; {seconds:.1f} s of {talk_s} s saved"
            + (f", a gap of {gap:.2f} s" if gap_m else "")
            + f"; sound in {heard:.0%} of the file after it, {heard_before:.0%} before, "
              f"its longest silence after {longest * level_s:.1f} s")


def battery_restricted():
    """JemRec held back by Android's own battery rules, as stock Android can
    hold any app: the user's Battery setting "Restricted" (its appop,
    RUN_ANY_IN_BACKGROUND ignore) and the restricted standby bucket (an app
    left unused for days). Each hold is read back before its call. The
    recorder, the shell's, wakes the app for each call with
    `am start-foreground-service` (Wakes).

    A Restricted app's "foreground services cannot be started unless an app
    activity is in the foreground" (ActivityManager.isBackgroundRestricted):
    with exact call tracking off, Android refuses both of the call's wakes
    ("no service started"), and the call, recorded by the recorder, is saved
    only once JemRec opens - home and a notification then saying why
    (BatteryHealth); in Ask first the question cannot be put, nothing is
    recorded, and the header says it cannot record. With tracking on,
    Telecom binds JemRec's call service for a phone call (InCallController,
    BIND_FOREGROUND_SERVICE), which brings it out of the background before
    the wake, and the call is saved at once. Restricted runs no job: the
    keep-alive's wait on BACKGROUND_NOT_RESTRICTED. The restricted bucket
    holds back jobs and the network of an app in the background, not this
    start, whose service runs in the foreground: saved at once either way;
    and the keep-alive's runs ask for a network, which Android gives a job
    there, so a kick brings an ended recorder back. Measured so on the
    Android 12 and 17 AVDs, 2026-11-18.

    Made to be in force, not only set: on battery (Android's "Test
    power-related issues" unplugs first) and with JemRec's uid idle
    (`am make-uid-idle`): an app counts as active for a minute after it
    leaves the screen (ActivityManagerConstants' background settle time), and
    the Restricted setting drops a start only for one in the background
    (ActiveServices, "due to FAS"). Both holds apply on the charger too (the
    Javadoc; the App Standby Buckets page); unplugged is only the more
    conservative. The first version of this check pressed Home and dialled
    2 s later, the uid still active, and so measured neither (review,
    2026-11-18)."""
    uid = app_uid()
    said = []
    capture_log = lambda: sh("cat /data/local/tmp/jemrec-capture.out")
    # The hang-up's wake, which carries the recording, refused.
    refused = lambda: len(re.findall(r"CALL_ENDED.*no service started", capture_log()))
    restricted = f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND ignore"
    bucket = f"am set-standby-bucket {PKG} restricted"

    def held(hold):
        """[hold] read back from Android, not only set."""
        if hold == restricted:
            return "ignore" in sh(f"cmd appops get {PKG} RUN_ANY_IN_BACKGROUND")
        return sh(f"am get-standby-bucket {PKG}").strip() == "45"

    def to_background(label):
        sh("logcat -c")
        sh("input keyevent KEYCODE_HOME")

        # Idled once Android has the uid in the background: before that,
        # `am make-uid-idle` passes it over (ActivityManagerService's
        # makePackageIdle takes only a uid with a background time) - on
        # the Android 17 AVD, 2026-11-18, the screen's activity was still
        # stopping 2 s after Home, and the uid stayed active.
        def idled():
            sh(f"am make-uid-idle {PKG}")
            return uid_idle(uid)

        try:
            wait_for(idled, 15, "")
        except TimeoutError:
            raise AssertionError(f"{label}: JemRec's uid is not idle - {uid_record(uid)}")

    def tracking(on):
        """Off as the app's switch turns it off: the recorder started anew
        with it off (JEMREC_TRACK=0), which takes the permission back
        (Tracking.applyTracking). Set by hand alone, the recorder's own look
        every two minutes allowed it again (Tracking.ensureTracking; review,
        2026-11-18)."""
        set_prefs(call_tracking=on)
        start_recorder()
        if not on:
            wait_for(lambda: "tracking: off" in sh("cat /data/local/tmp/jemrec-capture.out"), 15,
                     "the recorder to take tracking off")

    def release():
        sh(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow")
        sh(f"am set-standby-bucket {PKG} active")

    def keepalive_jobs():
        """What JobScheduler says of each of the app's WorkManager jobs - the
        keep-alive's and the USB watch's: what it waits on, and whether the
        background restriction holds it. From Android 15 a job's line names
        its worker (WorkManager 2.12.0 sets a trace tag from API 35,
        SystemJobInfoConverter); before, only WorkManager's service, so it
        goes by that. The hold is written two ways: Android 17 lists
        BACKGROUND_NOT_RESTRICTED among the unsatisfied constraints, Android
        12 only as "readyNotRestrictedInBg: false" under the job's implicit
        ones (both AVDs, 2026-11-18)."""
        dump = sh(f"dumpsys jobscheduler {PKG}")
        blocks = [b for b in re.split(r"\n\s*JOB ", dump) if PKG in b.split("\n", 1)[0] and "androidx.work" in b]
        jobs = []
        for b in blocks:
            waits = re.search(r"Unsatisfied constraints: ([^\[\n]*)", b)
            waits = waits.group(1).strip() if waits else ""
            jobs.append((waits, "BACKGROUND_NOT_RESTRICTED" in waits or "readyNotRestrictedInBg: false" in b))
        return jobs

    # The recorder up: the Wi-Fi checks before this one may have left it down.
    start_recorder()
    try:
        sh("dumpsys battery unplug")
        for label, hold, on, at_once in (
            ("Battery Restricted", restricted, True, True),
            ("the restricted standby bucket", bucket, True, True),
            ("Battery Restricted, tracking off", restricted, False, False),
            ("the restricted standby bucket, tracking off", bucket, False, True),
        ):
            if not on and "tracking: off" not in sh("cat /data/local/tmp/jemrec-capture.out"):
                tracking(False)
            sh(hold)
            assert held(hold), f"{label}: not held back - {hold} did not take"
            to_background(label)
            refused_before = refused()
            try:
                dial(CONTACT, ring_s=2, talk_s=6)
                if at_once:
                    _, seconds, _ = saved()
                    assert seconds > 4, f"{label}: saved {seconds:.1f} s of a 6 s call"
                    said.append(f"{label}: {seconds:.1f} s saved at once")
                else:
                    wait_for(lambda: refused() > refused_before, 40, "Android to refuse the hang-up's wake")
                    # Past the wake's last try (3, 2 s apart, each up to 10 s:
                    # Wakes) before asking whether it saved (review, 2026-11-18).
                    wait_for(lambda: "wake: com.jemcik.jemrec.CALL_ENDED" in capture_log()
                             and len(re.findall(r"CALL_ENDED.*on attempt 3|CALL_ENDED.*-> exit 0",
                                                capture_log())) > 0, 40, "the hang-up's wake to give up")
                    assert not re.search(r"save: wrote", log()), "saved, though Android refused the wake"
                    # Opened by a new process, as when the user next opens
                    # it: its start says so once (BatteryHealth).
                    sh(f"am kill {PKG}")
                    sh(f"am start -n {PKG}/.MainActivity")
                    _, seconds, _ = saved()
                    assert seconds > 4, f"{label}: saved {seconds:.1f} s of a 6 s call"
                    wait_for(lambda: on_screen("Battery use is Restricted"), 20, "home to say the battery use is Restricted")
                    assert "Battery use is Restricted" in sh("dumpsys notification --noredact"), \
                        "no notification that the battery use is Restricted"
                    said.append(f"{label}: the wake refused by Android, {seconds:.1f} s saved once JemRec opened, "
                                f"home and a notification saying why")
            except (AssertionError, TimeoutError) as e:
                wakes = [row for row in capture_log().splitlines() if "wake:" in row]
                raise AssertionError(f"{label}: {e} - the recorder's last wakes: {wakes[-4:]}")
            release()

        # Ask first, Restricted, tracking off: no question can be put.
        set_prefs(recording_mode="ON_DEMAND")
        start_recorder()
        wait_for(lambda: "tracking: off" in sh("cat /data/local/tmp/jemrec-capture.out"), 15,
                 "the recorder to keep tracking off")
        sh(restricted)
        assert held(restricted), "Ask first: Battery Restricted did not take"
        sh(f"am force-stop {PKG}")
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: re.search(r'text="Battery Restricted . tap to allow"', ui()), 20,
                 "the header to say Ask first cannot ask, the battery Restricted")
        to_background("Ask first, Battery Restricted, tracking off")
        dial(CONTACT, ring_s=2, talk_s=6)
        time.sleep(5)
        assert "asking whether to record" not in log(), "Ask first asked, though Android refused its wake"
        # Nothing recorded at all: the recorder's own word, not only no save
        # by some moment after the open (review, 2026-11-18).
        assert not re.search(r"recording: started at", sh("cat /data/local/tmp/jemrec-capture.out")), \
            "the recorder recorded a call Ask first could not ask about"
        sh(f"am start -n {PKG}/.MainActivity")
        time.sleep(8)
        assert not re.search(r"save: wrote", log()), "a call Ask first could not ask about was saved"
        said.append("Ask first, Restricted, tracking off: not asked, nothing recorded, the header saying so")
        # And with tracking on: Telecom binds JemRec's call service for the
        # call, which lets the question through - the header says Ready on
        # that (cannotAsk), so it is produced here, not inferred.
        tracking(True)
        sh(restricted)
        to_background("Ask first, Battery Restricted, tracking on")
        dial(CONTACT, ring_s=2, talk_s=6)
        assert "asking whether to record" in log(), "Ask first did not ask, Restricted with tracking on"
        said.append("Ask first, Restricted, tracking on: asked")
        set_prefs(recording_mode="AUTOMATIC")
        release()

        # Restricted runs no job of the app's: the keep-alive's heartbeat and
        # the runs that bring a stopped recorder back wait, as JobScheduler
        # itself says. (Ending the recorder and turning Wi-Fi off and on
        # shows nothing: the Wi-Fi watch is armed only by a run off Wi-Fi.)
        start_recorder()
        sh(restricted)
        to_background("Battery Restricted, the keep-alive")
        # Waited for: JobScheduler takes the uid's change from Android's own
        # callbacks, a moment after it.
        held_jobs = lambda: (lambda jobs: jobs if jobs and all(held for _, held in jobs) else None)(keepalive_jobs())
        try:
            wait_for(held_jobs, 20, "", step=1)
        except TimeoutError:
            raise AssertionError(f"Battery Restricted: the keep-alive's jobs not all held: {keepalive_jobs()}")
        said.append("Battery Restricted: the keep-alive's jobs held (BACKGROUND_NOT_RESTRICTED)")
        release()
        # The restricted bucket: a kick - as an open, a restart or a cable's
        # fallback makes it (the debug build's monitor op) - runs as an
        # expedited job asking for a network, which Android gives it there:
        # the recorder ended comes back. Without the network asked, the run
        # started and its revive timed out at every step - an app idle in
        # that bucket has none, 127.0.0.1 included (RecorderKeepAlive).
        start_recorder()
        sh(bucket)
        assert held(bucket), "the restricted bucket did not take"
        to_background("the restricted standby bucket, a kick")
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: not recorder_answers(), 10, "the recorder gone")
        diag("monitor")
        began = time.time()
        wait_for(recorder_answers, 60, "the restricted bucket: the recorder back by a kick", step=1)
        said.append(f"the restricted bucket: the recorder ended, back {time.time() - began:.0f} s after a kick")
        release()
    finally:
        release()
        sh("dumpsys battery reset")
        quietly(set_prefs, call_tracking=True, recording_mode="AUTOMATIC")
        start_recorder_after()
    return "on battery, uid idle - " + "; ".join(said)


def uid_record(uid):
    """Android's running record of [uid] (a number): its line under "UID
    states" in `dumpsys activity processes` (the active uids), or None with
    no process of it alive. The "UID validation" list after it is a copy of
    what was last told to observers (ValidateUidRecord), so it is left out."""
    name = f"u0a{int(uid) - 10000}"
    dump = sh("dumpsys activity processes")
    states = dump.split("UID states:", 1)[-1].split("UID validation:", 1)[0]
    return next((row.strip() for row in states.splitlines() if "UidRecord{" in row and name in row), None)


def uid_idle(uid):
    """Whether Android judges a start for [uid] as for an idle app: its
    record says "idle" - in the background long enough, or made so (`am
    make-uid-idle`) - or it has none, no process of it alive, which a start
    is judged the same as (ActivityManagerService.getAppStartModeLOSP,
    "uidRec == null || ... uidRec.isIdle()")."""
    record = uid_record(uid)
    return record is None or re.search(r"\bidle\b", record.split(" change:")[0]) is not None


# WorkManager's first backoff for the keep-alive's retry (RecorderKeepAlive,
# the default exponential 30 s).
RETRY_BACKOFF_S = 30


def dump_seconds(text):
    """A duration as `dumpsys jobscheduler` prints it (Android's
    TimeUtils.formatDuration): "+1d2h3m4s5ms" or "-32s12ms", leading parts
    left out when 0, "0" for none at all. None for anything else ("none")."""
    m = re.match(r"([+-]?)(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(\d+)ms$", text)
    if text in ("0", "+0", "-0"):
        return 0.0
    if not m:
        return None
    d, h, mi, sec, ms = (int(x or 0) for x in m.groups()[1:])
    value = ((d * 24 + h) * 60 + mi) * 60 + sec + ms / 1000
    return -value if m[1] == "-" else value


def keepalive_retry_job(within_s):
    """The keep-alive's first retry, just queued in JobScheduler: (its
    namespace, or None before Android 14, its job id, and the seconds until
    it is due by JobScheduler's "Run time: earliest", or None if it does not
    say) - WorkManager's job
    for RecorderKeepAlive with a minimum latency of the first backoff
    (RETRY_BACKOFF_S) and queued in the last [within_s] seconds. Both, as
    neither alone is the retry's: the heartbeat, put anew with a latency of
    whatever is left of its 15 minutes when wifi_back force-stops the app
    (SystemJobInfoConverter), can be queued as recently, or have the latency.
    None if there is none.

    Read it only while the retry's delay still runs. A read of JobScheduler's
    state once a delay is over, before the delay's own alarm has fired - an
    inexact one that does not wake the device - marks the job ready without
    telling the scheduler and, from Android 14, stops tracking it, so the
    alarm finds nothing to report (TimeController.evaluateStateLocked): on the
    16 and 17 AVDs, 2026-11-19, read every 10 s, both retries sat "Ready:
    true" for 4 minutes and more; read not at all, five of six ran within
    their alarm's window."""
    dump = sh(f"dumpsys jobscheduler {PKG}")
    found = []
    # Android 14 and later name the worker in the job's line; before, every
    # WorkManager job is the same service, and the latency and the time it
    # was queued pick the retry (the USB watch has no latency): the Android
    # 13 AVD's "JOB #u0a223/186: 40a91fa com.jemcik.jemrec/androidx.work...".
    for m in re.finditer(r"JOB (?:([\w.]+):)?#?u\d+a\d+/(\d+): \S+ (?:#RecorderKeepAlive#|"
                         r"com\.jemcik\.jemrec/androidx\.work\.impl\.background\.systemjob\.SystemJobService)", dump):
        block = re.split(r"\n\s+JOB ", dump[m.end():m.end() + 3000])[0]
        latency = re.search(r"Minimum latency: (\S+)", block)
        queued = re.search(r"Enqueue time: (\S+)", block)
        due = re.search(r"Run time: earliest=([^,\s]+)", block)
        latency_s = dump_seconds(latency[1]) if latency else None
        queued_s = dump_seconds(queued[1]) if queued else None
        if latency_s is not None and queued_s is not None and RETRY_BACKOFF_S - 5 <= latency_s <= RETRY_BACKOFF_S + 1 \
                and -queued_s <= within_s:
            found.append((-queued_s, m[1], int(m[2]), dump_seconds(due[1]) if due else None))
    return min(found)[1:] if found else None


def wifi_back():
    """Wi-Fi coming back starts the recorder within seconds, though a kick of
    the keep-alive is waiting out a long retry. A kick that runs off Wi-Fi -
    the app opened away from home, a cable's or USB debugging's fallback -
    fails and tries again with backoff: 30 s, 60, 120 and on. Until 0.25.3
    the Wi-Fi watch's own kick was the same unique work, KEEP, so it was not
    taken while that retry waited, and the recorder came back on the retry:
    21 s after Wi-Fi on the Honor (2026-10-31, Wi-Fi back 14 s into the
    kick's first 30 s wait); the waits double, so after a long time away
    (inferred, not measured) the heartbeat came first.

    The app is force-stopped first: no screen, whose own Wi-Fi callback
    revives (MainScreen), and no periodic job or watch but what the kick
    arms. A force-stopped app takes an explicit broadcast only when asked to,
    and delivering it takes the app out of the stopped state, so its jobs
    and the watch it arms then work as on any phone. Force-stopped again at
    the end, for the screen: the kick's retry still waiting is the next
    nothing_pending's to end.

    It waits for a retry of the kick to fail, whose next is 60 s off at the
    least, and then reads from the log that Wi-Fi's run came first - not for
    any time JobScheduler keeps: on the Android 17 AVD, 2026-10-31, the third
    try came 114 s after the second, not 60, and on the Android 16 AVD,
    2026-11-05, the second came 189 s after the first, as the app was opened.
    Any retry, not the second: a kick joins one already waiting (KEEP), and
    with one left by an earlier check the first try seen here was number 3
    (Android 12 AVD, the same day).

    The retry's 30 s end loosely: JobScheduler's alarm for them is inexact
    (up to 75% of the wait late) and does not wake the device, and
    WorkManager's own timer runs only while JemRec's process is up and not
    frozen (GreedyScheduler) - and on the 17 AVD, 2026-11-19, the screen on, a
    60 s wait once ran after 299 s, which neither explains. And a read of JobScheduler's
    state once the delay is over can lose the job for minutes
    (keepalive_retry_job), as this check's own reads did in 1.2.1: it was
    written down then as JobScheduler's connectivity batching, whose flag is
    off on these AVDs and the Honor, with JemRec in the ACTIVE bucket there
    (EXEMPTED on the Honor), which the other batching path leaves alone. So the retry is read once, while its
    delay still runs, the delay waited out by the clock, and the retry run
    with Android's own tool for testing jobs (`cmd jobscheduler run -f`) -
    not sooner: WorkManager puts off a retry started before its time
    ("Delaying execution ... because it is being executed before schedule",
    WorkerWrapper)."""
    set_prefs(start_without_wifi=False)
    log_before = revive_log()
    try:
        sh(f"am force-stop {PKG}")
        wifi_on(False)
        wait_wifi_off()
        sh("pkill -f '[c]om.jemcik.jemrec.shell.Main'")
        wait_for(lambda: not recorder_answers(), 10, "the recorder gone")
        diag("monitor", "--include-stopped-packages")  # the kick, as an open off Wi-Fi makes it

        def failed_retry():
            since = revive_since(log_before)
            for tried in re.finditer(r"keepalive: run \(attempt (\d+)\)", since):
                if int(tried[1]) > 1 and "recorder down" in since[tried.end():]:
                    return int(tried[1])
            return None

        def failed_try():
            since = revive_since(log_before)
            tried = re.search(r"keepalive: run \(attempt (\d+)\)", since)
            return tried is not None and "recorder down" in since[tried.end():]

        wait_for(failed_try, 120, "the kick to fail off Wi-Fi", step=2)
        failed_at = time.time()
        # A retry that failed already - the first try seen here a later one:
        # a kick joins one already waiting (KEEP) - is all this waits for.
        failed = failed_retry()
        if failed is None:
            # Its retry, read now - queued a moment after the try that failed,
            # its delay still running - and not again (keepalive_retry_job).
            namespace, job_id, due_in = wait_for(lambda: keepalive_retry_job(time.time() - failed_at + 10), 15,
                                                 "the kick's retry queued in JobScheduler", step=1)
            read_at = time.time()
            # Until it is due, by JobScheduler's own word, and a little more:
            # WorkManager puts off a retry started before its time.
            due_at = read_at + due_in if due_in is not None else failed_at + RETRY_BACKOFF_S
            time.sleep(max(0.0, due_at + 3 - time.time()))
            failed = failed_retry()
        if failed is None:
            # Its answer kept for a failure's message only: a retry that ran
            # on its own meanwhile is what failed_retry finds either way.
            said = sh(f"cmd jobscheduler run -f {f'-n {namespace} ' if namespace else ''}{PKG} {job_id}").strip()
            failed = wait_for(failed_retry, 60, f"the kick's retry, run once it was due, to fail off "
                              f"Wi-Fi (run -f said: {said[:80] or 'nothing'})", step=2)
        wifi_on(True)
        on_at = time.time()
        wait_wifi_connected()
        wait_for(recorder_answers, 45, "the recorder back while the kick waits out its retry", step=1)
        took = time.time() - on_at
        since = revive_since(log_before)
        assert "keepalive: Wi-Fi run" in since, "not started by the Wi-Fi watch's run"
        assert f"keepalive: run (attempt {failed + 1})" not in since, "brought back by the kick's next try"
        return f"back {took:.0f} s after Wi-Fi, before the kick's next try"
    finally:
        wifi_on(True)
        sh(f"am force-stop {PKG}")
        try:
            wait_wifi_connected()
            start_recorder()  # the open schedules the periodic job and the watches again
        except Exception as e:  # not in place of whatever failed the check
            print(f"     (the recorder could not be started again afterwards: {e})")


def start_without_wifi():
    """Start without Wi-Fi: on Wi-Fi, a revive has adbd open its classic port,
    5555 (`tcpip:5555`, which sets service.adb.tcp.port - the property is what
    is checked, since an emulator's adbd listens on 5555 whatever it says).
    Then, with Wi-Fi off, Turn all debugging off and back on (TurnOnLater, as
    in debugging_off) has the recorder started over that port; before 0.25
    that revive stopped at "not on Wi-Fi". Last, the switch off closes the
    port again on the next revive on Wi-Fi - over Wireless debugging only:
    where the revive comes over 5555 itself, closing it would cut the only
    line on a phone, so the port must be left open, and that is checked
    instead. And first, where Android has not allowed Wireless debugging on
    the AVD's Wi-Fi - on Android 12 always, its allowance taken back first
    (_forget_wifi_allowed) - that a revive for the port alone does not ask
    again once the question was cancelled, and leaves the port; then the
    Wi-Fi is allowed for the rest. Guarded by the snapshot as debugging_off
    is.

    Each revive here is the debug op "check", sent and not waited on: a
    kick to the keep-alive is not taken while it waits out a retry (as for
    _refused_wifi_left_alone), and the port's change restarts adbd under
    any wait for the op's reply (diag_cut_off)."""
    if int(sh("getprop ro.build.version.sdk").strip()) < 33:
        _forget_wifi_allowed()
    log_before = revive_log()
    port = lambda: sh("getprop service.adb.tcp.port").strip()

    # Before the switch goes on: from then, any revive - the keep-alive's own
    # included - may open it, and rightly. One open already is an earlier
    # run's: a switch-off written just before the emulator was killed can
    # be lost with the guest's unwritten cache, and set_up's first revive
    # then opened the port before set_up switched it off (the 12 AVD,
    # 2026-10-28). Closed from here, as a computer's `adb usb` does.
    if port() == "5555":
        adb("usb", timeout=30)
        adb("wait-for-device", timeout=60)
        wait_for(lambda: adb_ready() and port() != "5555", 30, "an earlier run's port closed")
    # Started before the switch goes on, and after any close above (adbd's
    # restart took the recorder). Opened with the switch on, JemRec has its
    # keep-alive open the port, restarting adbd under start_recorder's own
    # commands: since 1.5 that comes once Wireless debugging is switched on,
    # 4 s in, and took the bootstrap's broadcast with it (the 13, 16 and 17
    # AVDs, 2026-11-22). The port is opened below, by a revive asked for.
    start_recorder()
    set_prefs(off_mode="ALL_DEBUGGING_OFF", start_without_wifi=True)
    try:
        sh("settings put global development_settings_enabled 1")
        # A revive on Wi-Fi opens the port - once Wi-Fi is joined, so one is
        # asked for until it has, as for the close below. On a freshly booted
        # Android 12 AVD, 2026-10-28, the revives three minutes after boot
        # returned without opening it: Android had mobile data as its default
        # network, and the app then took the joined Wi-Fi for none - fixed
        # since (NetworkRevive.onWifi); the retries stay for a slow revive.
        wait_wifi_connected()
        # Asked of Android's list, not the switch: since 1.5 Wireless
        # debugging is off between starts on an allowed Wi-Fi too.
        refused = not _wifi_allowed()
        if refused:
            _refused_wifi_left_alone(port)

        def opened():
            diag_cut_off("check")
            # adbd restarts for it, taking this adb connection a moment.
            return wait_for(lambda: adb_ready() and port() == "5555", 30, "")

        wait_for(lambda: _quietly(opened), 90, "adbd's classic port set", step=0)
        wait_for(recorder_answers, 60, "the recorder back after adbd's restart")
        # Not adbd's own answer, which its restart can cut off (on the Honor
        # and the 17 AVD, 2026-10-28): the property read back after it.
        assert "revive: port 5555 opened" in revive_since(log_before), \
            "the app never opened the port"
        before = daemon_pid()
        wifi_on(False)
        wait_wifi_off()
        if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
            raise RuntimeError("no snapshot to come back to; not switching debugging off")
        try:
            diag_cut_off("switch", "--ez", "on", "false", "--ei", "back_after_s", "30")
            time.sleep(5)

            try:
                wait_for(lambda: adb_ready() and global_setting("adb_enabled") == "1", TURN_ON_LATER_S, "")
            except TimeoutError:
                back_from_snapshot("debugging never came back on")
        finally:
            adb("emu", "avd", "snapshot", "delete", USB_GUARD)
        after = wait_for(daemon_pid, 60, "a recorder process")
        wait_for(recorder_answers, 30, "the recorder answering")
        text = revive_since(log_before)
        assert "revive: connecting on port 5555, no Wi-Fi" in text, "the On did not come back over the port"
        assert after != before, "the same recorder: it never went"
        assert "Wi-Fi is disabled" in sh("dumpsys wifi | grep -m1 'Wi-Fi is'"), "Wi-Fi came back on during the check"
        assert port() == "5555", "the port did not outlive debugging going off and on"
    finally:
        # Not in place of whatever failed the check: with adb gone, these
        # would raise and hide it (as hide_developer_options guards its own).
        try:
            wifi_on(True)
            quietly(set_prefs, off_mode=None, start_without_wifi=False)
        except Exception as e:
            print(f"     (Wi-Fi or the switch could not be put back afterwards: {e})")
    # Switched off, the port it opened is closed at the next revive on Wi-Fi,
    # or left open where that revive came over the port itself. Wi-Fi
    # "connected" can come a moment before Android lists the network a revive
    # looks for, and then that revive rightly leaves the port for a later one
    # - so a revive is asked for again until one has decided.
    wait_wifi_connected(what="Wi-Fi connected again")
    log_close = revive_log()

    def decided():
        if not adb_ready():
            return False
        if port() == "0":
            return "closed"
        return "left" if "left open: connected over it" in revive_since(log_close) else False

    def settled():
        diag_cut_off("check")
        return wait_for(decided, 30, "")

    outcome = wait_for(lambda: _quietly(settled), 90, "the port closed, or left over its own connection", step=0)
    wait_for(recorder_answers, 60, "the recorder up after the switch went off")
    # That revive switched Wireless debugging on, on the Wi-Fi joined again,
    # and the recorder off once it was up (1.5).
    wait_for(lambda: adb_ready() and global_setting("adb_wifi_enabled") == "0", 30,
             "Wireless debugging off again after the last start")
    first = ("on a Wi-Fi not allowed, asked no more after Cancel and left the port; allowed, "
             if refused else "")
    if outcome == "left":
        assert port() == "5555", "left open, yet the port is gone"
        return (first + "port opened on Wi-Fi; with Wi-Fi off, all debugging off and back on over 5555; switched "
                "off, left open: the revive came over 5555 itself (no Wireless debugging on this AVD's Wi-Fi)")
    return first + "port opened on Wi-Fi; with Wi-Fi off, all debugging off and back on over 5555; closed when switched off"


def background():
    """The daemon watches `dumpsys activity exit-info` for this phone killing
    JemRec in the background, and wakes the app to warn when it sees a new one
    (Checks.backgroundKillStamp picks a system kill out of the record).

    What is checkable here is that the watch runs and parses the device's real
    exit-info at this API level - the log line with its baseline. The kill
    itself cannot be simulated: a stock emulator never kills an app the way a
    vendor power manager does (reason OTHER/FREEZER), and the app-side warning
    is gated on there being a vendor auto-start screen, which an emulator has
    none of. The filter that passes over the user's own Force stop is
    unit-tested (ChecksTest), and the parser reads the Honor's real exit
    records (the 13:52 kill, 2026-10-25). The stop WARNING itself has not been
    seen on a phone yet: in every later try the Honor froze JemRec instead of
    killing it - the freeze warning is the one shown live.

    From Android 15, the open check's other source too: the app's own start
    record says whether it began stopped. A Force stop makes one to read,
    and checks it is not taken for the phone's doing."""
    daemon_log = "cat /data/local/tmp/jemrec-capture.out"
    start_recorder()  # the kill-watch starts with the daemon, like the others
    wait_for(lambda: "killwatch: watching" in sh(daemon_log), 15,
             "the kill-watch to read exit-info")
    line = next(l for l in sh(daemon_log).splitlines() if "killwatch: watching" in l)
    watching = line.split("] ", 1)[-1] if "] " in line else line.strip()
    if int(sh("getprop ro.build.version.sdk").strip()) < 35:
        return watching + "; no start records before Android 15"
    # Android 15's record of the app's own start, which the open check reads
    # to tell a stop that left JemRec stopped (BackgroundKill.startedStopped).
    # A Force stop leaves it stopped too, so the next open is marked - and as
    # the user's own (reason 10) it must not be warned of.
    opens = lambda: [l for l in revive_log().splitlines() if "backgroundkill: on open" in l]
    before = len(opens())
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {PKG}/.MainActivity")
    wait_for(lambda: len(opens()) > before, 20, "the open check to run")
    seen = opens()[-1]
    assert "started stopped true" in seen and "newest exit reason 10 " in seen and "nothing new" in seen, seen
    return watching + "; a Force stop's open read as started stopped, not warned of"


def off_just_left():
    """Off from the background, JemRec 20 s off the screen and no call up: the
    process lives. CallMonitorService.stop is a plain start, and the service
    went to the foreground at every start, which Android refused:
    ForegroundServiceStartNotAllowedException, and the process gone, on all
    four AVDs, 2026-11-05 (first in a full run on Android 12, 28 s off the
    screen). 2 s off the screen it was allowed, and so was a tap on the tile
    20 s off, on 12 and 17. An Off that waited behind a revive, the app closed
    from Recents meanwhile, has neither: read from the code, not run."""
    def in_front():
        return PKG in sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:'")

    sh(f"am start -n {PKG}/.MainActivity")
    wait_for(in_front, 15, "JemRec on the screen")
    sh("input keyevent KEYCODE_HOME")
    wait_for(lambda: not in_front(), 15, "JemRec off the screen")
    time.sleep(20)
    process = sh(f"pidof {PKG}").strip()
    assert process, "no process to watch"
    try:
        diag("switch", "--ez", "on", "false")
        time.sleep(3)
        assert sh(f"pidof {PKG}").strip() == process, "the process went"
    finally:
        quietly(diag, "switch", "--ez", "on", "true")
    return "the process lived"


def self_test():
    report = diag("selftest")
    assert "PASS" in report, report
    assert "tracking:  on" in report, report
    return next(l.split("] ", 1)[1] for l in report.splitlines() if "PASS" in l)


CHECKS = [ringing, ringing_gap, unanswered, incoming, rules, succession, caller_id, number_names, self_test,
          answering, greeting_hangup, message_recorder_lost, answer_rules, answer_during_voip,
          answer_after_emergency, answer_takeover_ask_first, test_call_wifi, greeting_moves, app_call, mode_switch, app_call_in_front,
          messenger_recorder_lost, waveform, quality,
          background_playback, both_ears, raise_to_listen, seek_preroll, one_window, process_death,
          recently_deleted, recently_deleted_folder, name_taken, looks, reinstall, cable, usb_debugging, adbd_stopped,
          hide_developer_options,
          debugging_off, off_just_left, background, allow_this_wifi, wireless_between_starts, mdns_silent, usb_mode,
          usb_default_lock, ask_button,
          refused_grant,
          listener_grant, keep_paired, pairing_card,
          telegram_builds, newest_version, opus_valid, notifications_off, folder_lost,
          killed_mid_save, early_end, doze,
          # Late, these three: they turn the emulator's Wi-Fi off and on, and
          # a simulated Wi-Fi that does not come back would fail every check
          # after them - so the three after them start the recorder themselves.
          stopped_early, wifi_back, start_without_wifi,
          # And after them, this one. Put here when wifi_back, two checks
          # after it, saw a kick's 30 s retry come after 5 minutes on the
          # Android 16 and 17 AVDs (2026-11-17) and passed run alone - put
          # down then to the restricted standby bucket's aftermath, not shown.
          # Since 1.2.1 wifi_back runs that retry itself (Android's own
          # timing measured loose), so the order no longer guards it; kept,
          # as it costs nothing. It needs only the recorder up.
          battery_restricted,
          # The audio server ended mid-call: last, as the server it brings
          # back is a new one for every check after it - and held down.
          audio_server_restart, stall_told]


def let_android_wait():
    """Android's "<app> isn't responding" dialog, if one is up, answered
    Wait. On an emulator under load System UI stops answering now and then -
    the Android 16 AVD in a rerun of 2026-11-17, with mode_switch under way -
    and its dialog holds the screen from the check after it. Wait, not Close
    app: System UI closed starts again, and that takes more from a check.
    JemRec's own is answered as well, and fails its check: app_crashes counts
    it."""
    try:
        if "Application Not Responding" not in sh("dumpsys window | grep -m1 mCurrentFocus"):
            return
        ui = sh("uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml")
    except subprocess.TimeoutExpired:
        return
    # Which app, as the dialog's title says: JemRec's own is answered too, so
    # the check under way can finish, and fails it (app_crashes).
    who = re.search(r'text="([^"]*) (?:isn\'t|isn&apos;t) responding"', ui)
    for node in ui.split("<node")[1:]:
        box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if 'text="Wait"' in node and box:
            x1, y1, x2, y2 = map(int, box.groups())
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            print(f"     ({who.group(1) if who else 'an app'} was not responding: Android's dialog answered Wait)")
            time.sleep(1)
            return


def watch_for_dialogs(every=5):
    """For the whole run, [let_android_wait] every few seconds, from a thread
    of its own: a dialog that comes up in the middle of a check holds its
    screen until the check gives up, and before each check is too late for
    that one. It was the user who saw it, twice, on 2026-11-17."""
    import threading

    def watch():
        while True:
            try:
                let_android_wait()
            except Exception:  # adb gone for a moment: look again next time
                pass
            time.sleep(every)

    threading.Thread(target=watch, name="dialogs", daemon=True).start()


def nothing_pending():
    """Wait for the call before to be collected, so a check that failed
    leaves nothing for the next one to find, end any keep-alive retry it left,
    and then clear the log. A force-stop does not end a retry: WorkManager
    schedules it again at the next start, and on every full run of 2026-11-05
    wifi_back's ran a revive in the middle of the check after it (46 s in on the
    Android 16 AVD, 71 s on the Android 12)."""
    let_android_wait()
    try:
        diag("forgetkicks", "--include-stopped-packages", wait=15)
    except TimeoutError:
        print("     (no answer to end a retry left waiting)")
    try:
        wait_for(lambda: "jemrec_rec_" not in sh("ls /data/local/tmp"), 20, "")
    except TimeoutError:
        print("     (a recording from before was still on the daemon; deleted)")
        sh("rm -f /data/local/tmp/jemrec_rec_*")
    sh("logcat -c")


def what_happened():
    """The call lines from the daemon and the app, for a check that failed."""
    keep = re.compile(r"callwatch|recording:|rules leave|calltrack|save:|reconcile|never answered|answer:|monitor:")
    try:
        lines = [l for l in log().splitlines() if keep.search(l)]
    except subprocess.TimeoutExpired:  # adb hung: the check is still reported, and the next still run
        return "       (no log: adb did not answer)"
    return "\n".join("       " + l[6:170] for l in lines[-25:])


def app_crashes():
    """The crashes and "isn't responding"s of the app Android's DropBox
    holds - each by its entry's own header, its time and kind - or None with
    no adb to ask over, or a dump this cannot read. A check reads what it is
    after, so a crash beside that passes it: the app died in a full run on
    the Android 12 AVD that reported 34 of 34, 2026-11-05, and only a read of
    the logcat found it. DropBox and not the log's crash buffer, which every
    `logcat -c` here clears. Its "isn't responding"s too, which
    watch_for_dialogs answers Wait and the check would otherwise outlive:
    Android files both for an app it did not ship under data_app_<kind>
    (ActivityManagerService.addErrorToDropBox). Entries, not a count: DropBox
    prunes its oldest, and a count taken across that hid a new one (review
    round 9)."""
    entries = set()
    for kind in ("crash", "anr"):
        try:
            said = adb("shell", f"dumpsys dropbox --print data_app_{kind}", timeout=30)
        except subprocess.TimeoutExpired:
            return None
        head = None
        found = 0
        ours = 0
        for line in said.splitlines():
            m = re.match(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) data_app_" + kind + r"\b", line)
            if m:
                head = m.group(1)
                continue
            if line.startswith(f"Process: {PKG}"):
                ours += 1
                if head is not None:
                    n = 1
                    while (kind, head, n) in entries:
                        n += 1
                    entries.add((kind, head, n))
                    found += 1
                    head = None
        if found != ours:
            # A dump this does not read as it should: no answer, rather
            # than a crash missed in silence.
            print(f"     (DropBox's {kind} entries not read: {ours} of the app, {found} placed)")
            return None
    return entries


def where_things_stood():
    """What a failed check saw, read now: what_happened has the call lines
    alone. On the Android 16 AVD, 2026-11-05, allow_this_wifi and
    start_without_wifi failed in a full run and passed alone, and neither
    left a trace of why: the first's cleanup had moved the screen on, and the
    second's snapshot took the log back. So these checks call this before
    either, and it says so when there is no adb to read anything over."""
    def read(command):
        try:
            return adb("shell", command, timeout=15).strip()
        except subprocess.TimeoutExpired:
            return f"(no answer in 15 s: {command})"

    if not adb_ready():
        return "       no adb connection, nothing to read over"
    state = {
        "on screen": " | ".join(re.findall(r'text="([^"]+)"', read("uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml"))),
        "adb_enabled": read("settings get global adb_enabled"),
        "adb_wifi_enabled": read("settings get global adb_wifi_enabled"),
        "adbd": read("getprop init.svc.adbd"),
        "port": read("getprop service.adb.tcp.port"),
        "wifi": read("cmd wifi status | head -2").replace("\n", " "),
        "standby bucket": read(f"am get-standby-bucket {PKG}"),
    }
    jobs = read(f"dumpsys jobscheduler | awk '/^ *JOB /{{on=index($0,\"{PKG}\")>0}} on' | "
                "grep -E 'JOB |[Cc]onstraints|Backoff|Minimum latency|Earliest|Ready|Pending' | "
                "grep -v 'Num Required Constraints' | head -60")
    revive = read(f"su 0 tail -n 30 {APP_DIR}/files/revive.log")
    lines = [f"{k}: {v[:600]}" for k, v in state.items()] + jobs.splitlines() + revive.splitlines()
    return "\n".join("       " + l[:170] for l in lines)


def back_from_snapshot(what):
    """A check that took adb away and did not get it back: the emulator put
    back, so that the checks after it can run, and [what] raised. Where things
    stood is read first: the snapshot takes that back too."""
    print(where_things_stood())
    adb("emu", "avd", "snapshot", "load", USB_GUARD)
    adb("wait-for-device", timeout=60)
    raise TimeoutError(f"{what}; the emulator was put back from the snapshot")


SHRUNK_APK = "app/build/outputs/apk/shrunk/app-shrunk.apk"


def build_shrunk():
    """The shrunk APK, made from the sources as they are now, and copied for
    this run alone. It is built only when asked for, unlike debug's at every
    routine build, so a run that took the one on disk could check yesterday's
    code and pass. Gradle knows every input (the daemon's build.sh included)
    and redoes only what changed. Under the build lock, so two runs started
    together build once; the copy, so a build another run starts later
    cannot change this one's APK under it - reinstall installs it again."""
    try:
        with build_lock():
            print("building the shrunk APK (./gradlew :app:assembleShrunk)")
            done = subprocess.run(["./gradlew", "-q", ":app:assembleShrunk"], cwd=ROOT,
                                  timeout=GRADLE_TIMEOUT_S)
            if done.returncode != 0:
                sys.exit("./gradlew :app:assembleShrunk failed")
            run_dir = tempfile.mkdtemp(prefix="jemrec-checks-")
            atexit.register(shutil.rmtree, run_dir, ignore_errors=True)
            copy = os.path.join(run_dir, "app-shrunk.apk")
            shutil.copyfile(os.path.join(ROOT, SHRUNK_APK), copy)
            return copy
    except (TimeoutError, subprocess.TimeoutExpired) as e:
        sys.exit(f"no shrunk APK to check: {e}")


# --- booting an AVD --------------------------------------------------------------

BOOT_TIMEOUT_S = 6 * 60
SETTLE_TIMEOUT_S = 3 * 60
# How an AVD is started for the checks, by --avds and by a fresh-boot retry:
#   -no-snapshot  cold, from nothing. Never -no-snapshot-save alone, which
#                 loads the quick-boot snapshot at every start and kept the
#                 Android 17 AVD on JemRec 0.9 for eight days (2026-10-28).
#   -memory 4096  twice the 2 GB the AVDs were made with (hw.ramSize): 8 of
#                 the Mac's 32 GB for the two run at once. Whether it makes
#                 System UI stop answering less often (2026-11-17) is for
#                 the runs to show; nothing measured it yet.
#   -no-boot-anim and no cameras: nothing a check looks at, and work for
#                 the emulator while another one runs its checks.
#   -no-window    nor its window on the Mac: the checks read the screen
#                 through adb. Three of the ten crash reports between
#                 2026-11-12 and 2026-11-18 were that window's own (Qt's
#                 menu bar and timers; two at once as the windows were
#                 brought to the front, 2026-11-15).
# -no-audio: no sound on the Mac at all. A run is two hours of calls,
# ringing and recordings on the Mac's speakers, through the user's meetings,
# and a call's own sound - a tone the emulator's audio HAL makes - played in
# full whatever the volumes (QUIET), Android's master mute, or
# QEMU_AUDIO_OUT_DRV=none, which this emulator ignores (each tried, the run
# still heard, 2026-11-20). Without its sound the Android 17 AVD once played
# too slowly to check - answering (the greeting never played),
# background_playback (2.9 s in 5 s) and both_ears failed twice each,
# 2026-11-18 - but measured again, the audio server started afresh before the
# playback checks (fresh_audio_server, since 1.2.1), background_playback
# played 5030 ms in 5 s and the three playback checks passed, the emulator
# holding none of the Mac's audio (2026-11-20). Its CoreAudio output also
# deadlocked now and then, the emulator's hang detector ending it (four of
# the ten crash reports to 2026-11-18: why_emulator_ended) - none left to
# deadlock; three more were its GPU renderer freeing what a process that
# ended had drawn with (gfxstream's cleanupProcGLObjects). A run stops at an
# emulator that ended, and its retry boots it again.
# -crash-report-mode never: an emulator that crashed or hung before leaves a
# report, and the next to start waits on a dialog on the Mac's screen asking
# whether to send it - every boot, until it is answered (both AVDs waited out
# the boot timeout so on 2026-11-18). Never sent, never asked (the
# emulator's own -help); boot() still says so at once if one asks anyway.
EMULATOR_FLAGS = ["-no-snapshot", "-memory", "4096", "-no-boot-anim", "-camera-back", "none", "-camera-front", "none",
                  "-no-window", "-crash-report-mode", "never", "-no-audio"]


def emulator_binary():
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    binary = shutil.which("emulator") or os.path.join(sdk, "emulator", "emulator")
    if not os.path.exists(binary):
        raise RuntimeError(f"no emulator to boot it with ({binary})")
    return binary


def adb_devices():
    return subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=30).stdout


def on(emulator, command, timeout=15):
    """[command] in the shell of [emulator], a serial; "" if it did not answer."""
    try:
        return subprocess.run(["adb", "-s", emulator, "shell", command], capture_output=True, text=True,
                              timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def avd_process(name):
    """The pattern for AVD [name]'s process: "-avd name", or the emulator's
    shorthand "@name" - the launcher passes either on to qemu as given
    (review round 10). Bracketed, a pattern starting with - is no option."""
    return f"([-]avd |[@]){name}( |$)"


def avd_stopped(name):
    # pgrep says 1 for no match.
    return subprocess.run(["pgrep", "-f", avd_process(name)], capture_output=True).returncode == 1


def shut_down(emulator, name):
    """AVD [name], on [emulator], shut down, and its process gone: it holds
    the port the next one is started on. Killed if it will not go."""
    subprocess.run(["adb", "-s", emulator, "emu", "kill"], capture_output=True, timeout=30)
    try:
        wait_for(lambda: avd_stopped(name), 60, "", step=1)
    except TimeoutError:
        subprocess.run(["pkill", "-9", "-f", avd_process(name)], capture_output=True)
        wait_for(lambda: avd_stopped(name), 30, f"{name}'s process to end", step=1)
    wait_for(lambda: emulator not in adb_devices(), 60, f"{name} to leave adb", step=1)


def settle(emulator, name):
    """Android goes on starting things for a while after it says it has
    booted, and setting up in the middle of it, the Android 13 AVD, just
    booted, had no recorder within 20 s (2026-11-17). So: the one-minute load
    average below the emulator's CPU count - nothing waiting for a CPU - at
    most SETTLE_TIMEOUT_S, and how long it took said, for the runs to show
    whether it is the right wait."""
    began, read = time.time(), []

    def calm():
        said = on(emulator, "cat /proc/loadavg; nproc").split()
        read[:] = said[:1] + said[-1:]
        return len(said) >= 6 and float(said[0]) < int(said[-1])
    try:
        wait_for(calm, SETTLE_TIMEOUT_S, "", step=5)
        print(f"{emulator}: {name} settled {time.time() - began:.0f} s after its boot (load {read[0]}, {read[1]} CPUs)")
    except TimeoutError:
        load = on(emulator, "cat /proc/loadavg").split()[:1]
        print(f"{emulator}: {name} not settled after {SETTLE_TIMEOUT_S} s (load {load}): going on")


def boot(name, port, wipe=False):
    """AVD [name] booted from nothing on [port], with EMULATOR_FLAGS, and
    settled; its serial. Its data stays, as across any boot - or with [wipe],
    goes: -wipe-data, the phone as it came (--avds --wipe), so a run is not
    shaped by the runs before it: 11 empty recordings and hundreds of an old
    install's, nobody's to write, had gathered on the Android 12 AVD by
    2026-11-17. Asked for, never the default: it wipes an AVD's data."""
    emulator = f"emulator-{port}"
    boot_log = os.path.join(tempfile.gettempdir(), f"jemrec-checks-{name}-boot.log")
    with open(boot_log, "w") as out:
        started = subprocess.Popen([emulator_binary(), "-avd", name, *EMULATOR_FLAGS, *(["-wipe-data"] if wipe else []),
                                    "-port", str(port)],
                                   stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                   start_new_session=True)

    def up():
        if started.poll() is not None:
            raise RuntimeError(f"{name}'s emulator ended as it started (exit {started.returncode}): see {boot_log}")
        # An emulator that crashed or hung before leaves its report, and the
        # next one to start waits for a dialog on the Mac's screen asking
        # whether to send it - every boot, until it is answered: both AVDs
        # waited out the boot timeout so on 2026-11-18, after the Android 17
        # AVD's QEMU hung. Said at once, not after the timeout.
        with open(boot_log) as emulators_own:
            if "Showing crashdialog to get consent" in emulators_own.read():
                # Its whole session: the launcher's QEMU child holds the
                # port, and killed alone the launcher left it there.
                os.killpg(started.pid, signal.SIGKILL)
                raise RuntimeError(
                    f"{name}'s emulator waits for its crash-report dialog: an emulator crashed or hung "
                    f"before. Answer the dialog, or move the reports out of "
                    f"/tmp/android-$USER/emu-crash-*.db/completed, and run again (see {boot_log})")
        return on(emulator, "getprop sys.boot_completed", timeout=10).strip() == "1"
    began = time.time()
    wait_for(up, BOOT_TIMEOUT_S, f"{name} to boot", step=2)
    print(f"{emulator}: {name} booted from nothing in {time.time() - began:.0f} s")
    settle(emulator, name)
    return emulator


# This run's AVD, asked once at its start (main): an emulator that crashed
# cannot say it, and the retry's fresh boot needs it - asked only then, a
# crash left every failure "not run again" (found 2026-11-18).
avd = ""
# Whether its process can be found by that name (avd_process); and the
# process ids it had, for its crash report: with two AVDs at once the newest
# report may be the other's (review round 10).
avd_watched = False
avd_pids = set()


def note_avd():
    """This run's AVD process found, its ids kept: at the start, and after
    every boot of its own."""
    global avd_watched
    found = subprocess.run(["pgrep", "-f", avd_process(avd)], capture_output=True, text=True).stdout.split() \
        if avd else []
    avd_pids.update(int(p) for p in found if p.isdigit())
    avd_watched = bool(found)


def avd_name():
    said = adb("emu", "avd", "name", timeout=15).splitlines()
    name = said[0].strip() if said else ""
    return "" if name == "KO" else name


def cold_boot():
    """This emulator shut down and booted again from nothing, on its own port
    - or, crashed, booted again."""
    name = avd or avd_name()
    if not name:
        raise RuntimeError("the emulator did not say which AVD it is")
    emulator_binary()  # before shutting it down: none to boot it again with
    shut_down(serial, name)
    boot(name, int(serial.removeprefix("emulator-")))
    note_avd()


def emulator_gone():
    """Whether this run's emulator has ended - crashed, or killed by its own
    hang detector: its process gone. Asked between checks, when nothing of
    a check restarts adbd, but by the process, not adb, which a check's own
    adbd restart takes away for seconds."""
    return avd_watched and avd_stopped(avd)


def why_emulator_ended(since, wait=15):
    """What ended the emulator, from the Mac's crash report written after
    [since] (a time.time()) by one of this AVD's processes (avd_pids), or
    None - looked for [wait] s, as macOS writes it after the process is gone.
    Four of the ten reports between 2026-11-12 and 2026-11-18 were one
    deadlock: a virtual CPU starting the Mac's sound output
    (AudioDeviceStart) under qemu's lock and its voice lock, CoreAudio's
    thread waiting for that voice lock in qemu's own callback, and every
    other thread for qemu's lock - until the emulator's hang detector ended
    it (GenerateDumpAndDie)."""
    import glob
    import json

    def report():
        for path in sorted(glob.glob(os.path.expanduser("~/Library/Logs/DiagnosticReports/qemu-system-*.ips")),
                           key=os.path.getmtime, reverse=True):
            if os.path.getmtime(path) < since:
                return None
            try:
                with open(path) as f:
                    f.readline()  # the report's header line, then its body
                    body = json.load(f)
            except (OSError, ValueError):
                continue
            if not avd_pids or body.get("pid") in avd_pids:
                return path, body
        return None
    found = None
    deadline = time.time() + wait
    while found is None:
        found = report()
        if found is None and time.time() >= deadline:
            return None
        if found is None:
            time.sleep(1)
    path, body = found
    try:
        symbols = [" ".join(frame.get("symbol", "") for frame in thread.get("frames", []))
                   for thread in body.get("threads", [])]
        faulting = symbols[body.get("faultingThread", 0)] if symbols else ""
    except (OSError, ValueError, IndexError):
        return f"a crash report it left: {path}"
    if "HangDetector" in faulting:
        if any("AudioDeviceStart" in s and "ca_update_voice_running_state_locked" in s for s in symbols):
            why = "its hang detector ended it: its sound output on the Mac deadlocked (CoreAudio)"
        else:
            why = "its hang detector ended it: a thread stopped answering"
    elif "cleanupProcGLObjects" in faulting:
        why = "its GPU renderer crashed freeing what a process that ended had drawn with (gfxstream)"
    elif "QCocoa" in faulting or "QCoreApplication" in faulting:
        why = "its window on the Mac crashed (Qt)"
    else:
        why = f"it crashed ({body.get('exception', {}).get('type', '?')})"
    return f"{why} - {path}"


# --- what each run found, kept across runs ---------------------------------------

HISTORY = os.path.join(os.path.expanduser("~"), ".cache", "jemrec", "emulator-checks.tsv")
CHRONIC_OF = 3     # the last runs looked at
CHRONIC_FAILS = 2  # passes only on a retry in them that make a check chronic

# The checks during which JemRec crashed or stopped responding, in any try:
# such a check fails the run though its retry passed - a retry is for the
# emulator's own bad moments, not the app's (review, 2026-11-18; Gradle's
# test-retry plugin keeps failOnPassedAfterRetry for the same).
CRASHED = []

# The checks of the first pass that failed, or never ran, because the
# emulator ended: run again on the fresh boot, and kept in the history as
# LOST, not FAIL, so a pass on that retry is not counted as the check's own
# flake (each still fills one of the CHRONIC_OF runs looked at).
LOST = []


def tree_said():
    """The tree a run checked: its commit, and whether it had changes."""
    try:
        sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
        return sha + ("+changes" if dirty else "")
    except OSError:
        return "?"


def remember(sdk, checks, first_failed, still_failed, lost):
    """Each check's first try and, where it was run again, its retry, kept
    in HISTORY across runs: a retry that passes says nothing of a check that
    fails its first try run after run, and Android's own guidance is to
    "keep track of and fix flaky tests" (developer.android.com, Test
    stability). Three "flakes" here were bugs - answer_during_voip on the
    Android 12 AVD failed 4 runs of 5 (0.24). [lost]: the first pass's
    checks the emulator ended under, LOST - its crash, not theirs."""
    os.makedirs(os.path.dirname(HISTORY), exist_ok=True)
    stamp, tree = time.strftime("%Y-%m-%d %H:%M"), tree_said()
    with open(HISTORY, "a") as out:
        for check in checks:
            name = check.__name__
            first = ("LOST" if name in lost else "FAIL") if name in first_failed else "PASS"
            again = ("FAIL" if name in still_failed else "PASS") if name in first_failed else "-"
            out.write(f"{stamp}\t{tree}\t{sdk}\t{name}\t{first}\t{again}\n")


def chronic(sdk, name):
    """In how many of [name]'s last CHRONIC_OF runs on API [sdk] it passed
    only on its retry, and how many runs there were. A run where it failed
    both times failed as it was, and is not a flake (review, 2026-11-18)."""
    try:
        rows = [line.rstrip("\n").split("\t") for line in open(HISTORY)]
    except OSError:
        return 0, 0
    mine = [r for r in rows if len(r) == 6 and r[2] == str(sdk) and r[3] == name][-CHRONIC_OF:]
    return sum(1 for r in mine if r[4] == "FAIL" and r[5] == "PASS"), len(mine)


# --- every AVD, booted and checked here ------------------------------------------

AT_ONCE = 2  # more at once makes calls flaky (CLAUDE.md)
RUN_TIMEOUT_S = 3 * 60 * 60  # a full run with its retries takes 25-60 min
LOG_DIR = os.path.join(ROOT, "app", "build", "emulator-checks")


def running_emulators():
    """{serial: AVD name} of every emulator adb sees."""
    found = {}
    for line in adb_devices().splitlines():
        emulator = line.split("\t")[0]
        if emulator.startswith("emulator-"):
            try:
                said = subprocess.run(["adb", "-s", emulator, "emu", "avd", "name"], capture_output=True,
                                      text=True, timeout=15).stdout.splitlines()
            except subprocess.TimeoutExpired:
                said = []
            found[emulator] = said[0].strip() if said else ""
    return found


def check_avds(names, apk, passed_on, wipe=False):
    """Each AVD of [names] booted from nothing, checked by a run of this
    script of its own, and shut down - [AT_ONCE] at a time, each line of
    theirs printed as it comes, after its AVD's name, and kept in LOG_DIR.
    [passed_on]: the arguments each run is given. Exit status: 0 if every
    check on every one passed."""
    import queue
    import threading
    known = subprocess.run([emulator_binary(), "-list-avds"], capture_output=True, text=True).stdout.split()
    unknown = [n for n in names if n not in known]
    if unknown:
        sys.exit(f"no such AVD: {', '.join(unknown)} (there are: {', '.join(known)})")
    # Booted cold, each one: one already up is shut down first. One of any
    # other name keeps its port, and is not touched.
    for emulator, name in running_emulators().items():
        if name in names:
            print(f"{emulator}: {name} is up: shut down, to be booted from nothing")
            shut_down(emulator, name)
    for name in names:
        if not avd_stopped(name):  # up, but not on adb
            subprocess.run(["pkill", "-9", "-f", avd_process(name)], capture_output=True)
            wait_for(lambda: avd_stopped(name), 30, f"{name}'s process to end", step=1)
    taken = running_emulators()
    ports = queue.Queue()
    for port in [p for p in range(5554, 5586, 2) if f"emulator-{p}" not in taken][:AT_ONCE]:
        ports.put(port)
    os.makedirs(LOG_DIR, exist_ok=True)
    printing = threading.Lock()
    results = {}

    def say(name, line):
        with printing:
            print(f"[{name}] {line}", end="" if line.endswith("\n") else "\n")

    def check(name):
        port = ports.get()
        emulator = f"emulator-{port}"
        began = time.time()
        summary, code = None, None
        try:
            boot(name, port, wipe=wipe)
            with open(os.path.join(LOG_DIR, f"{name}.log"), "w") as kept:
                checking = subprocess.Popen([sys.executable, "-u", os.path.abspath(__file__), "--serial", emulator,
                                         "--apk", apk, *passed_on],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=ROOT)
                timer = threading.Timer(RUN_TIMEOUT_S, checking.kill)
                timer.start()
                for line in checking.stdout:
                    kept.write(line)
                    kept.flush()
                    say(name, line)
                    if re.match(r"API \d+: \d+ of \d+ passed", line):
                        summary = line.strip()
                code = checking.wait()
                timer.cancel()
        except Exception as e:  # every AVD reported, whatever one does
            say(name, f"not checked: {e}")
            summary = f"not checked: {e}"
        finally:
            try:
                shut_down(emulator, name)
            except Exception as e:
                say(name, f"(not shut down: {e})")
            ports.put(port)
        results[name] = (code, summary, (time.time() - began) / 60)

    if ports.empty():
        sys.exit(f"no emulator port free of {', '.join(taken)}")
    started = []
    for name in names:
        # In the order given, AT_ONCE at a time: the next starts as one ends.
        while sum(w.is_alive() for w in started) >= AT_ONCE:
            time.sleep(1)
        worker = threading.Thread(target=check, args=(name,), name=name)
        worker.start()
        started.append(worker)
    for worker in started:
        worker.join()
    print("\n== every AVD")
    for name in names:
        code, summary, minutes = results.get(name, (None, "not checked", 0))
        print(f"{name:20} {summary or f'no count (exit {code})'}   {minutes:.0f} min")
    print(f"logs: {os.path.relpath(LOG_DIR, ROOT)}/")
    return 0 if all(results.get(n, (1,))[0] == 0 for n in names) else 1


def run(checks, only):
    """Each of [checks] run and reported, PASS or FAIL; the names of those
    that failed. [only]: the names asked for, None for a full run."""
    failed = []
    # The count the last check ended at: the next starts from it, so a crash
    # or "isn't responding" filed between two checks - an ANR is filed
    # seconds after the fact - is the next one's, not no one's (review,
    # 2026-11-18).
    seen = None
    began = time.time()
    ended = None  # what ended the emulator, once read
    for at, check in enumerate(checks):
        if emulator_gone():
            # Every check left would fail its waits out, minutes each, on an
            # emulator that is not there: said once, and left to the retry's
            # fresh boot, as the emulator's, not the checks'.
            left = [c.__name__ for c in checks[at:]]
            ended = ended or why_emulator_ended(began) or "no crash report found"
            print(f"GONE        the emulator ended before {check.__name__}: {ended}; not run: {', '.join(left)}")
            failed.extend(left)
            LOST.extend(left)
            break
        if only and check is succession and "rules" not in only:
            # What the rules check leaves behind, which succession starts
            # from - set just before it: before the run, it left out the
            # stranger of a check picked to run ahead of it (incoming).
            set_prefs(rules_other_numbers=False)
            start_recorder()
        if only and checks.index(check) > 0 and checks[checks.index(check) - 1] is succession \
                and "caller_id" not in only:
            # And taken off again after it, as caller_id, next in a full run,
            # does: left on, they left out the stranger of a check picked to
            # run after it (greeting_hangup, 2026-11-13).
            set_prefs(rules_other_numbers=True)
            start_recorder()
        crashes = None
        try:
            nothing_pending()
            crashes = seen if seen is not None else app_crashes()
            result = check()
            after = app_crashes()
            seen = after
            assert crashes is None or after is None or not (after - crashes), \
                f"the app crashed or stopped responding during it: {sorted(after - crashes)}: {result}"
            print(f"PASS {check.__name__:11} {result}")
        except Exception as e:  # report every check, whatever one does
            failed.append(check.__name__)
            if emulator_gone():
                # Failed because the emulator ended under it: its own crash
                # is no crash of JemRec's, and not one for the history.
                LOST.append(check.__name__)
                ended = why_emulator_ended(began) or "no crash report found"
                print(f"FAIL {check.__name__:11} {e}\n       -- the emulator ended during it: {ended}")
                continue
            # A crash or "isn't responding" of JemRec's own is no bad moment
            # of the emulator's: kept, so a retry that passes does not hide
            # it (main) - found or not by the check's own assertion.
            try:
                after = app_crashes()
                seen = after
                if crashes is not None and after is not None and after - crashes:
                    CRASHED.append(check.__name__)
            except Exception:  # not in place of the check's own failure
                seen = None
            print(f"FAIL {check.__name__:11} {e}" + ("" if "\n" in str(e) else f"\n{what_happened()}"))
            # After its cleanup: a check that prints it as it fails prints it
            # above this, and a snapshot's load has taken the phone back.
            print("       -- now, the check's cleanup done:")
            print(where_things_stood())
    return failed


def main():
    global serial, avd
    # Each line as it is printed, into a log file too: Python holds output
    # going to a file until it exits, and a run of an hour showed nothing of
    # where it was (2026-11-17).
    sys.stdout.reconfigure(line_buffering=True)
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", default="emulator-5554", help="an emulator already up, to check")
    parser.add_argument("--avds", help="comma-separated AVDs to boot from nothing, check and shut down, "
                                       f"{AT_ONCE} at a time, e.g. jemrec-api33,jemrec-api36")
    parser.add_argument("--apk", help=f"install this instead of building {SHRUNK_APK}")
    parser.add_argument("--only", help="comma-separated checks to run, e.g. succession,caller_id")
    parser.add_argument("--wipe", action="store_true",
                        help="with --avds, boot each AVD with its data wiped (-wipe-data): the phone as it came")
    parser.add_argument("--no-retry", action="store_true",
                        help="report a failed check as it is, without running it again on a fresh boot")
    args = parser.parse_args()
    if args.avds:
        passed_on = (["--only", args.only] if args.only else []) + (["--no-retry"] if args.no_retry else [])
        sys.exit(check_avds(args.avds.split(","), args.apk or build_shrunk(), passed_on, wipe=args.wipe))
    serial = args.serial
    only_an_emulator()
    avd = avd_name()
    note_avd()
    if avd and not avd_watched:
        print(f"{serial}: {avd}'s process not found by its name: an emulator that ends mid-run will not be "
              f"told apart from a failed check")
    watch_for_dialogs()
    apk = args.apk or build_shrunk()
    sdk = sh("getprop ro.build.version.sdk").strip()
    print(f"{serial}: Android API {sdk}")
    try:
        set_up(apk)
    except Exception as e:
        # The same emulator trouble, before any check: on 2026-11-17 the
        # Android 13 AVD, just booted, had no recorder up within 20 s.
        if args.no_retry:
            raise
        print(f"-- setting up failed ({e}): again on {serial} booted afresh")
        cold_boot()
        only_an_emulator()
        set_up(apk)
    reinstall.apk = apk
    only = args.only.split(",") if args.only else None
    checks = [c for c in CHECKS if not only or c.__name__ in only]
    failed = run(checks, only)
    first_failed, first_lost = list(failed), list(LOST)
    passed_again = []
    if failed and not args.no_retry:
        # The emulator's own call network, Wi-Fi and timing give way now and
        # then under load, and a check fails with nothing wrong in JemRec:
        # in a full run on 2026-11-17 the Android 17 AVD failed caller_id,
        # app_call, mode_switch and allow_this_wifi, and the Android 12
        # start_without_wifi and ringing_gap. So what failed runs again, on
        # the emulator booted afresh, and fails only if it fails twice; its
        # first failure stays printed above.
        print(f"\n-- {len(failed)} failed: run again on {serial} booted afresh: {', '.join(failed)}")
        try:
            cold_boot()
            only_an_emulator()
            set_up(apk)
            still = run([c for c in CHECKS if c.__name__ in failed], failed)
        except Exception as e:  # no fresh boot: the first failures stand
            print(f"     (not run again: {e})")
            still = failed
        passed_again = [name for name in failed if name not in still]
        failed = still
    try:
        set_prefs(rules_other_numbers=True)
    except (TimeoutError, subprocess.TimeoutExpired) as e:  # after every check has reported: not in place of the count
        print(f"     (the rules could not be put back: {e})")
    # Not a run with --no-retry: its first failures had no retry to pass,
    # and working on a check fails it often (review, 2026-11-18).
    if not args.no_retry:
        remember(sdk, checks, first_failed, failed, first_lost)
    # A check passed only on its retry, run after run, fails the run: the
    # retry is there for the emulator's own bad moments, not to keep a
    # flaky check - or a bug - out of sight.
    flaky = []
    for name in [n for n in passed_again if n not in first_lost]:
        fails, runs = chronic(sdk, name)
        flaky.append(f"{name} (passed only on its retry in {fails} of the last {runs} runs)")
        if fails >= CHRONIC_FAILS:
            failed.append(name)
    crashed = sorted(set(CRASHED))
    for name in crashed:
        if name not in failed:
            failed.append(name)
    lost_passed = [n for n in passed_again if n in first_lost]
    if lost_passed:
        flaky.append(f"after the emulator ended: {', '.join(lost_passed)}")
    again = f" ({len(passed_again)} on a fresh boot: {'; '.join(flaky)})" if passed_again else ""
    passed = len(checks) - len(set(failed))
    print(f"API {sdk}: {passed} of {len(checks)} passed{again}")
    chronic_now = [n for n in passed_again if n in failed]
    if chronic_now:
        print(f"API {sdk}: failed as chronic - passed only on a retry in {CHRONIC_FAILS} or more of the last "
              f"{CHRONIC_OF} runs: {', '.join(chronic_now)}")
    if crashed:
        print(f"API {sdk}: failed - JemRec crashed or stopped responding during: {', '.join(crashed)}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
