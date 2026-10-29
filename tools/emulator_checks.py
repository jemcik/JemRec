#!/usr/bin/env python3
"""Release checks on an Android emulator: real call handling, end to end.

    python3 tools/emulator_checks.py [--serial emulator-5554] [--apk PATH]

Installs the debug APK, sets JemRec up from nothing, and runs simulated calls
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
  self-test   PASS, with exact call tracking on
  answering   the answering machine answers a stranger after its delay, plays
              the greeting, and saves the message marked as one, with the
              microphone given back at the end
  greeting-hangup  a caller who hangs up during the greeting: only what they
              said over it is kept (on a phone, silence leaves nothing)
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
  waveform    a call's 800 levels are worked out and kept as it is saved,
              so its row has its waveform the first time it is drawn
  background-playback  a recording played from its row goes on with the
              screen off, called only "Call recording" while the phone is
              locked and by its caller again once unlocked; a call ringing
              in holds it back, and it plays on once the call is over
  recently-deleted  a recording deleted is brought back by Restore all in
              Recently deleted; deleted again, Delete all for good removes
              it, from Android's own trash as well, its file with it
  recently-deleted-folder  the same in a folder picked in Settings through
              Android's own picker, where a deleted recording waits renamed
              in its folder (issue #51: Android 17 reports that file under
              its old name); and one there whose 30 days are up is removed
              by the daily pass
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
  start-without-wifi  on Wi-Fi, a revive has adbd open its classic port,
              5555; with Wi-Fi off, Turn all debugging off and back on has
              the recorder started over that port; switched off, the port is
              closed again on Wi-Fi - or left open where that revive came
              over the port itself; and first, on a Wi-Fi Android has not
              allowed, no asking again after Cancel
  background  the daemon's watch for this phone stopping JemRec in the
              background runs and reads the device's own exit records; from
              Android 15, a Force stop's open is read from the app's own
              start record and not taken for the phone's doing

Every call is the emulator's own simulation - nothing leaves the machine -
and the script refuses to touch anything that is not an emulator. It needs a
google_apis image (adb root, for the caller-id check). Run it on each Android
version a release claims; see docs/DEVELOPMENT.md, Tests.

Exit status: 0 if every check passed.
"""
import argparse
import hashlib
import re
import shlex
import subprocess
import sys
import time

PKG = "com.jemcik.jemrec"
PREFS = "shared_prefs/jemrec.xml"
CONTACT = "5557777"      # made a contact here, as "TestKept"
STRANGER_IN = "5554321"  # rings in; no contact
STRANGER_OUT = "5551234"  # dialled; no contact
CALLER_ID = ("3034997111", "NIST Radio Station WWV")  # issue #30's call
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
        else:
            said = diag("pref", "--es", "key", name, "--es", "value", str(value))
        assert f"{name} = " in said, said


def diag(op, *extra, wait=60):
    token = diag.token
    sh("logcat -c")
    adb("shell", "am", "broadcast", "-n", f"{PKG}/.diag.SpikeReceiver", "--es", "token", token, "--es", "op", op, *extra)
    wait_log(rf"diag\[{op}\] <<<", wait, f"diag {op}")
    return "\n".join(l for l in log().splitlines() if f"diag[{op}]" in l)


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
    sh("pkill -f com.jemcik.jemrec.shell.Main")
    time.sleep(1)
    diag("bootstrap")
    wait_for(daemon_pid, 20, "the daemon to start")


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
# ring, music, alarm and notification turned down a little - about half of
# the AVDs' own 5 of 7 (music 5 of 15) - as they play on the Mac's speakers
# through a whole run (the user's ask, 2026-10-28: "quite loud", but "do not
# mute them"). The voice call stream is left as it is: checks measure what
# the recorder captures of a call.
QUIET = {1: 3, 2: 3, 3: 4, 4: 3, 5: 3}


def quieten():
    """Turn the emulator's sounds down for the run; a boot can reset them.
    Through the app while it is in front (the debug build's diag 'volume'):
    Android 17's audio hardening ignores the shell's `cmd media_session
    volume`, though 12, 13 and 16 take it."""
    said = diag("volume", "--es", "set", ",".join(f"{s}:{i}" for s, i in QUIET.items()))
    if "failed" in said:
        print(f"     (sounds not turned down: {said.strip()})")


def set_up(apk):
    print(f"installing {apk}")
    adb("install", "-r", apk, check=True)
    for permission in ("READ_CALL_LOG", "READ_CONTACTS", "POST_NOTIFICATIONS"):
        sh(f"pm grant {PKG} android.permission.{permission}")  # NOTIFICATIONS fails before 13: fine
    # Setup grants this to the app over its own ADB session, and these checks
    # skip setup. Without it the app cannot put USB debugging back: the
    # usb_debugging check locked the Android 12 and 13 AVDs out until this
    # line (2026-10-26, "cannot write adb_enabled - SecurityException").
    sh(f"pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS")
    # A contact, if this emulator has none with the number yet.
    if CONTACT not in sh("content query --uri content://com.android.contacts/data/phones --projection data1"):
        sh("content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:")
        raw = max(int(x) for x in re.findall(r"_id=(\d+)", sh("content query --uri content://com.android.contacts/raw_contacts --projection _id")))
        sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{raw} "
           f"--bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:TestKept")
        sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{raw} "
           f"--bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:{CONTACT} --bind data2:i:2")
    # The diag receiver's token: refused once, it says what it wants.
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    sh("logcat -c")
    sh(f"am broadcast -n {PKG}/.diag.SpikeReceiver --es op selftest")
    diag.token = wait_log(r"diag token: (\S+)", 20, "the diag token").group(1)
    set_prefs(setup_complete=True, hide_debug_notification=False, file_names="DATE_AND_CALLER",
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
    start_recorder()


# --- calls -------------------------------------------------------------------------

def count(pattern):
    return len(re.findall(pattern, log()))


def wait_more(pattern, before, timeout, what):
    """Wait for one more line matching [pattern] than the [before] there were."""
    wait_for(lambda: count(pattern) > before, timeout, what)


def phone_idle():
    return "mCallState=0" in sh("dumpsys telephony.registry | grep -m1 mCallState=")


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
    xml = adb("exec-out", f"run-as {PKG} cat {PREFS}")
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
    cut can put back. The Android 13 emulator's capture loses ~0.7 s just
    after an incoming call goes off-hook, every call (2026-10-24: the
    stamps jump, and the beep's one-second rhythm shows a merged tone);
    0.19.2, placing packets by counting, filled it with audio from after
    the hang-up and came out the right length."""
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
    assert abs(seconds + lost - talk) <= 0.15, f"{seconds:.2f} s saved{gone} for {talk:.2f} s of call"
    return f"{name}: {seconds:.2f} s{gone} for {talk:.2f} s answered" + (f" ({agreed})" if lost else "")


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
    sh("logcat -c")
    dial("5559876", ring_s=1.2, talk_s=0)
    wait_log(r"never answered", 30, "the unanswered call to be dropped")
    assert "save: wrote" not in log(), "it was saved"
    return "not saved"


def incoming():
    sh("logcat -c")
    ring_in(STRANGER_IN, ring_s=3, talk_s=6)
    return exact("_in_")


def rules():
    set_prefs(rules_other_numbers=False)
    # No early skip within 10 s of another call's end (CallTracker,
    # RECENT_END_MS) - the saver would leave it out instead. Wait it out.
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


def caller_id():
    number, name = CALLER_ID
    set_prefs(rules_other_numbers=True)
    # The emulator has no caller ID; the name is written into the call log
    # the way Google Phone writes it. For that to come first, the save at
    # hang-up is turned off - the backup collector saves 5 s later.
    as_root(f"pm disable {PKG}/.capture.CallMonitorService")
    try:
        start = sh("date +%s").strip()
        sh("logcat -c")
        dial(number, ring_s=2, talk_s=4)
        wait_for(lambda: "Row" in sh(f"content query --uri content://call_log/calls --projection _id --where \"date>={start}000\""),
                 20, "the call log entry")
        sh(f"content update --uri content://call_log/calls --bind 'name:s:{name}' --where 'date>={start}000'")
        return exact(f"_out_{name}")
    except Exception as e:
        # Before the finally: putting the service back restarts the daemon.
        raise RuntimeError(f"{e}\n{what_happened()}") from e
    finally:
        as_root(f"pm enable {PKG}/.capture.CallMonitorService")


GREETING = "/data/local/tmp/jemrec-test-greeting.pcm"


def no_call_left():
    """A check that failed half way can leave a call ringing - and every call
    after it is then call waiting, which is never answered."""
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_for(phone_idle, 10, "the phone to be idle")


def answering_on():
    """A three-second greeting (two tones), the answering machine on after
    five seconds, and both given to the recorder through the app."""
    no_call_left()
    import math
    import struct
    import tempfile
    rate = 48000
    frames = [int(6000 * math.sin(2 * math.pi * (440 if i < rate * 1.5 else 660) * i / rate)) for i in range(rate * 3)]
    with tempfile.NamedTemporaryFile(suffix=".pcm", delete=False) as f:
        f.write(struct.pack("<%dh" % len(frames), *frames))
    adb("push", f.name, GREETING, check=True)
    sh(f"chmod 644 {GREETING}")
    set_prefs(answer_delay_s=5)
    said = diag("answer", "--es", "greeting", GREETING)
    assert "recorder took it: true" in said and "wire 1," in said, said


def mic_unmuted():
    return "FromApi=false" in sh("dumpsys audio | grep -m1 'mic mute'")


def answering():
    try:
        return _answering()
    finally:
        no_call_left()


def _answering():
    answering_on()
    time.sleep(10)  # past CallTracker's RECENT_END_MS from the call before
    sh("logcat -c")
    adb("emu", "gsm", "call", STRANGER_IN)
    wait_log(r"answer: answering", 20, "the answering machine to answer")
    wait_log(r"answer: greeting played", 20, "the greeting to play")
    time.sleep(3)
    adb("emu", "gsm", "cancel", STRANGER_IN)
    wait_log(r"save: wrote", 40, "the message to be saved")
    wait_for(mic_unmuted, 10, "the microphone to be given back")
    # This message's own mark: the marks outlive runs (install -r keeps the
    # app's data), so any "_in" there could be an earlier run's.
    _, _, name = saved()

    def marked():
        marks = adb("exec-out", f"run-as {PKG} cat {PREFS}")
        m = re.search(r'<set name="message_recordings">(.*?)</set>', marks, re.S)
        return bool(m) and f"<string>{name}</string>" in m.group(1)

    wait_for(marked, 5, f"{name} to be marked as a message")
    return "message saved, marked, microphone back"


def greeting_hangup():
    try:
        return _greeting_hangup()
    finally:
        no_call_left()


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
        set_prefs(rules_other_numbers=True)
        diag("answer", "--ez", "on", "false")
    return "rang on, unanswered"


PROBE_JAR = "shellserver/probe/voip-probe.jar"
PROBE_SOURCE = "shellserver/probe/com/jemcik/jemrec/probe/FakeCallProbe.java"
FAKE_CALL_LOG = "/data/local/tmp/fakecall.txt"


def fake_call_up(tone=False):
    """A stand-in for a messenger call, run as the shell: the audio mode at
    MODE_IN_COMMUNICATION, call audio playing - silence, or a tone - the
    microphone open."""
    import os
    if not os.path.exists(PROBE_JAR) or os.path.getmtime(PROBE_JAR) < os.path.getmtime(PROBE_SOURCE):
        built = subprocess.run(["bash", "shellserver/probe/build-probe.sh"], capture_output=True, text=True)
        if built.returncode != 0:
            raise RuntimeError("could not build the stand-in call (JDK 21? set JEMREC_JAVA_HOME):\n"
                               + built.stdout[-800:] + built.stderr[-800:])
    adb("push", PROBE_JAR, "/data/local/tmp/voip-probe.jar", check=True)
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
        fake_call_down()
        no_call_left()
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
        end_app_call()
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
        fake_call_down()
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
        end_app_call()
        adb("emu", "gsm", "cancel", CONTACT)
        try:
            wait_for(phone_idle, 10, "")
        except TimeoutError:
            print("     (the call would not end after app_call_in_front)")
    return "the phone call kept on the call capture under the app's call" + ("" if copies else " (no copy before 14)")


def device_ms():
    """The emulator's own clock, in milliseconds, as the app reads it."""
    return int(sh("date +%s").strip()) * 1000


def answer_after_emergency():
    try:
        return _answer_after_emergency()
    finally:
        set_prefs(emergency_call_at=None)
        adb("emu", "gsm", "cancel", STRANGER_IN)
        no_call_left()


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
        set_prefs(recording_mode="AUTOMATIC")
        diag("answer")  # the mode back on the recorder
        no_call_left()


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


def waveform():
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=4)
    _, _, name = saved()
    # Waveforms.cacheFile: the first 12 bytes of the name's SHA-256, in hex.
    cached = f"cache/waveforms-v2/{hashlib.sha256(name.encode()).digest()[:12].hex()}.lvl"
    wait_for(lambda: sh(f"run-as {PKG} stat -c %s {cached}").strip() == "800", 30,
             "the call's 800 levels to be kept")
    return f"{name}: 800 levels kept"


# PlaybackState's states by number: Android 13 and older print the number
# alone ("state=3"), Android 17 its name too ("state=PLAYING(3)").
PLAYBACK_STATES = {"0": "NONE", "1": "STOPPED", "2": "PAUSED", "3": "PLAYING", "6": "BUFFERING", "7": "ERROR"}


def session():
    """JemRec's media session as `dumpsys media_session` reports it: its
    state (PLAYING, PAUSED, ...), position in ms, and title - or None."""
    for block in sh("dumpsys media_session").split("package=")[1:]:
        if not block.startswith(PKG):
            continue
        state = re.search(r"state=PlaybackState \{state=(?:\w+\()?(\d+)\)?, position=(\d+)", block)
        title = re.search(r"description=([^,\n]*)", block)
        if state:
            return (PLAYBACK_STATES.get(state.group(1), state.group(1)), int(state.group(2)),
                    title.group(1).strip() if title else "")
    return None


def session_is(state):
    s = session()
    return s if s and s[0] == state else None


def digits(text):
    return re.sub(r"\D", "", text or "")


def tap_text(digits_in):
    """Tap the first node on screen whose text holds [digits_in]."""
    return tap_node(lambda node: digits_in in digits(re.search(r' text="([^"]*)"', node).group(1)))


def background_playback():
    """Played from its row, a recording goes on in PlaybackService with the
    screen off, named for the lock screen; a call holds it back."""
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=30)
    saved()
    try:
        sh(f"am start -n {PKG}/.MainActivity")
        wait_for(lambda: tap_text(STRANGER_OUT), 30, "the recording's row to tap")
        wait_for(lambda: session_is("PLAYING"), 10, "the recording to play")
        sh("input keyevent KEYCODE_SLEEP")
        time.sleep(2)
        _, before, title = session()
        time.sleep(5)
        state, after, _ = session()
        assert state == "PLAYING" and after - before >= 4000, \
            f"with the screen off it went {state}, {before} -> {after} ms in 5 s"
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
    finally:
        sh(f"am force-stop {PKG}")
    return f"{after - before} ms played in 5 s with the screen off, as \"{title}\"; held back by a call, then on"


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


def restore_then_delete(name, folder):
    """[name] moved to Recently deleted comes back with Restore all, tapped
    there; moved again, Delete all for good and its question remove it, its
    file in [folder] with it. The file's name while it waited."""
    place, said = trash_place(name, move=True)
    assert place == (False, True), f"not moved to the trash: {said}"
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
    wait_for(lambda: f"tree/primary%3A{name}<" in adb("exec-out", f"run-as {PKG} cat {PREFS}"), 15,
             f"{name} kept as the folder to save to")


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
        try:
            set_prefs(recordings_tree_uri=None)
        except Exception as e:  # whatever went wrong: printed, not raised
            print(f"       {PICKED} still set as the folder to save to: {e}")
        sh(f"am force-stop {PKG}")
        sh(f"rm -rf {folder}")
    return f"in {PICKED}: waited as {trashed}, restored, deleted for good; one expired, gone after the daily pass"


# What each look's header shows uiautomator, with the recorder ready. Deck's
# and Multitrack's headers are one sentence to a screen reader (headerSpeech),
# Memo's its own words; Multitrack's tabs are in capitals.
LOOK_SIGNS = {
    "DECK": lambda x: 'content-desc="Ready to record' in x and 'text="Ready to record"' not in x
    and 'text="CALLS"' not in x,
    "MULTITRACK": lambda x: 'text="CALLS"' in x,
    "MEMO": lambda x: 'text="Ready to record"' in x,
}


def looks():
    """Each look picked and the app started again: it opens in it - four
    fonts and a light or dark of its own, on this Android."""
    try:
        for look, sign in LOOK_SIGNS.items():
            set_prefs(look=look, light_dark="DARK" if look == "DECK" else "PHONE")
            sh(f"am force-stop {PKG}")
            sh(f"am start -n {PKG}/.MainActivity")
            wait_for(lambda: sign(ui()), 30, f"the app to open in {look}")
            assert sh(f"pidof {PKG}").strip(), f"the app stopped in {look}"
    finally:
        set_prefs(look="MEMO", light_dark="PHONE")
        sh(f"am force-stop {PKG}")
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
    xml = adb("exec-out", f"run-as {PKG} cat {PREFS}")
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
    sh("pkill -f com.jemcik.jemrec.shell.Main")
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
        try:
            start_recorder()
        except Exception as e:  # not in place of whatever failed the check
            print(f"     (the recorder could not be started again afterwards: {e})")


def revive_log():
    return sh(f"run-as {PKG} cat files/revive.log")


def revive_since(before):
    """What the app has noted in its revive log since [before] was read. Read
    as what follows it, not by looking for a line: the log outlives a run, and
    an earlier run's lines would pass for this one's. It is appended to, and
    moved to revive.log.1 once past 64 kB (ReviveLog)."""
    now = revive_log()
    if now.startswith(before):
        return now[len(before):]
    older = sh(f"run-as {PKG} cat files/revive.log.1")
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
    sh("input keyevent KEYCODE_HOME")
    if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
        raise RuntimeError("no snapshot to come back to; not switching USB debugging off")
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
            adb("emu", "avd", "snapshot", "load", USB_GUARD)
            adb("wait-for-device", timeout=60)
            raise TimeoutError("the app never switched USB debugging back on; "
                               "the emulator was put back from the snapshot")
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
            adb("emu", "avd", "snapshot", "load", USB_GUARD)
            adb("wait-for-device", timeout=60)
            raise TimeoutError("adbd never came back; the emulator was put back from the snapshot")
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
    options and Wireless debugging off and leaves USB debugging - so adbd and
    the recorder - alone; on puts back what it switched off. On the Honor
    (2026-10-26) the recorder lived through that off the cable, through Wi-Fi
    off and on, a locked phone and a real call; here, that the switches go and
    come back and the recorder keeps its process."""
    set_prefs(off_mode="HIDE_DEVELOPER_OPTIONS")
    try:
        start_recorder()
        sh("settings put global development_settings_enabled 1")  # as on any phone set up
        before = daemon_pid()
        wireless = global_setting("adb_wifi_enabled") == "1"
        diag("switch", "--ez", "on", "false")
        wait_for(lambda: global_setting("development_settings_enabled") == "0", 15, "Developer options off")
        if wireless:
            wait_for(lambda: global_setting("adb_wifi_enabled") == "0", 15, "Wireless debugging off")
        assert global_setting("adb_enabled") == "1", "USB debugging went off too"
        time.sleep(3)
        assert daemon_pid() == before, "the recorder went with them"
        assert recorder_answers(), "the recorder stopped answering"
        diag("switch", "--ez", "on", "true")
        wait_for(lambda: global_setting("development_settings_enabled") == "1", 15, "Developer options back")
        if wireless:
            wait_for(lambda: global_setting("adb_wifi_enabled") == "1", 15, "Wireless debugging back")
        assert daemon_pid() == before, "the recorder was restarted for nothing"
        return (f"Developer options{' and Wireless debugging' if wireless else ''} off and back on, "
                f"the recorder kept (pid {before})")
    finally:
        # On again whatever failed, or every check after this one runs with
        # recording off. A second on puts back nothing and finds the recorder up.
        try:
            diag("switch", "--ez", "on", "true")
        except Exception as e:  # not in place of whatever failed the check
            print(f"     (JemRec could not be turned back on afterwards: {e})")
        set_prefs(off_mode=None)


def debugging_off():
    """When JemRec is off, Turn all debugging off: off has the recorder switch
    Wireless and USB debugging off and go, and adbd - this adb connection with
    it - goes away. On, here a debug-build delay (TurnOnLater) since the adb
    that would send it is gone, puts all of it back and starts the recorder.
    Guarded as usb_debugging is: a snapshot the console loads if adb never
    comes back."""
    log_before = revive_log()
    set_prefs(off_mode="ALL_DEBUGGING_OFF")
    start_recorder()
    sh("settings put global development_settings_enabled 1")
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
            wait_for(all_back, 120, "")
        except TimeoutError:
            adb("emu", "avd", "snapshot", "load", USB_GUARD)
            adb("wait-for-device", timeout=60)
            raise TimeoutError("debugging never came back on; the emulator was put back from the snapshot")
    finally:
        adb("emu", "avd", "snapshot", "delete", USB_GUARD)
        set_prefs(off_mode=None)
    after = wait_for(daemon_pid, 60, "a recorder process")
    wait_for(recorder_answers, 30, "the recorder answering")
    log_text = revive_since(log_before)
    assert "debugging off: the recorder says OFF NOW" in log_text, "the recorder was not the one to switch it off"
    assert "debugging off: Wireless debugging, then USB debugging" in sh("cat /data/local/tmp/jemrec-capture.out.1"), \
        "the old recorder never switched them off"
    assert sh("pidof adbd").strip() != adbd_before, "adbd never stopped: USB debugging did not go off"
    assert "debugging back on: USB_DEBUGGING" in log_text, "On did not put USB debugging back"
    assert after != before, "the same recorder: it never went"
    return "adbd stopped with USB debugging, all of it back on, recorder started again"


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
    keys = "/data/misc/adb/adb_temp_keys.xml"
    # Binary XML ("ABX" first) where persist.sys.binary_xml has it, text otherwise.
    binary = su(f"head -c 3 {keys}").startswith("ABX")
    read_keys = lambda: su(f"abx2xml {keys} -" if binary else f"cat {keys}")
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
    wait_for(lambda: 'connected to "' in sh("cmd wifi status"), 90, "Wi-Fi connected after the restart")
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
        start_recorder()
    return ("refused: told Disable system optimization, not set up; no session: told so; "
            "Try again: granted, read back")


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

        wait_for(asking, 90, "the header asking to allow this Wi-Fi")
        log_before = revive_log()
        # Tapped until Android asks: a tap at the first dump can be lost (#49),
        # and while the tap's revive runs the header says Starting instead.
        end = time.time() + 40
        while not _questions():
            assert time.time() < end, "the tap never put Android's question up"
            if on_screen(label):
                tap(label)
            time.sleep(1)
        _answer_questions(allow=True)
        wait_for(recorder_answers, 45, "the recorder back after Allow")

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
        return "asked on a Wi-Fi not allowed; the tap put the question up, Allow had the recorder back by the tap's revive"
    finally:
        su(f"iptables -D {rule}")
        try:
            _answer_questions(allow=True)
            start_recorder()
        except Exception as e:  # not in place of whatever failed the check
            print(f"     (the recorder could not be started again afterwards: {e})")


def wifi_on(on):
    sh(f"cmd wifi set-wifi-enabled {'enabled' if on else 'disabled'}")


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
        start_recorder()  # adbd's restart took it
    set_prefs(off_mode="ALL_DEBUGGING_OFF", start_without_wifi=True)
    try:
        start_recorder()
        sh("settings put global development_settings_enabled 1")
        # A revive on Wi-Fi opens the port - once Wi-Fi is joined, so one is
        # asked for until it has, as for the close below. On a freshly booted
        # Android 12 AVD, 2026-10-28, the revives three minutes after boot
        # returned without opening it: Android had mobile data as its default
        # network, and the app then took the joined Wi-Fi for none - fixed
        # since (NetworkRevive.onWifi); the retries stay for a slow revive.
        wait_for(lambda: 'connected to "' in sh("cmd wifi status"), 60, "Wi-Fi connected")
        refused = global_setting("adb_wifi_enabled") != "1"
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
        wait_for(lambda: "Wi-Fi is disabled" in sh("dumpsys wifi | grep -m1 'Wi-Fi is'"), 20, "Wi-Fi off")
        if "OK" not in adb("emu", "avd", "snapshot", "save", USB_GUARD):
            raise RuntimeError("no snapshot to come back to; not switching debugging off")
        try:
            diag_cut_off("switch", "--ez", "on", "false", "--ei", "back_after_s", "30")
            time.sleep(5)

            try:
                wait_for(lambda: adb_ready() and global_setting("adb_enabled") == "1", 120, "")
            except TimeoutError:
                adb("emu", "avd", "snapshot", "load", USB_GUARD)
                adb("wait-for-device", timeout=60)
                raise TimeoutError("debugging never came back on; the emulator was put back from the snapshot")
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
            set_prefs(off_mode=None, start_without_wifi=False)
        except Exception as e:
            print(f"     (Wi-Fi or the switch could not be put back afterwards: {e})")
    # Switched off, the port it opened is closed at the next revive on Wi-Fi,
    # or left open where that revive came over the port itself. Wi-Fi
    # "connected" can come a moment before Android lists the network a revive
    # looks for, and then that revive rightly leaves the port for a later one
    # - so a revive is asked for again until one has decided.
    wait_for(lambda: 'connected to "' in sh("cmd wifi status"), 60, "Wi-Fi connected again")
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
    revive = f"run-as {PKG} cat files/revive.log"
    opens = lambda: [l for l in sh(revive).splitlines() if "backgroundkill: on open" in l]
    before = len(opens())
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {PKG}/.MainActivity")
    wait_for(lambda: len(opens()) > before, 20, "the open check to run")
    seen = opens()[-1]
    assert "started stopped true" in seen and "newest exit reason 10 " in seen and "nothing new" in seen, seen
    return watching + "; a Force stop's open read as started stopped, not warned of"


def self_test():
    report = diag("selftest")
    assert "PASS" in report, report
    assert "tracking:  on" in report, report
    return next(l.split("] ", 1)[1] for l in report.splitlines() if "PASS" in l)


CHECKS = [ringing, ringing_gap, unanswered, incoming, rules, succession, caller_id, self_test,
          answering, greeting_hangup, answer_rules, answer_during_voip,
          answer_after_emergency, answer_takeover_ask_first, app_call, mode_switch, app_call_in_front, waveform,
          background_playback,
          recently_deleted, recently_deleted_folder, looks, reinstall, cable, usb_debugging, adbd_stopped,
          hide_developer_options,
          debugging_off, background, allow_this_wifi, refused_grant,
          # Last: it turns the emulator's Wi-Fi off and on, and a simulated
          # Wi-Fi that does not come back would fail every check after it.
          start_without_wifi]


def nothing_pending():
    """Wait for the call before to be collected, so a check that failed
    leaves nothing for the next one to find - and then clear the log."""
    try:
        wait_for(lambda: "jemrec_rec_" not in sh("ls /data/local/tmp"), 20, "")
    except TimeoutError:
        print("     (a recording from before was still on the daemon; deleted)")
        sh("rm -f /data/local/tmp/jemrec_rec_*")
    sh("logcat -c")


def what_happened():
    """The call lines from the daemon and the app, for a check that failed."""
    keep = re.compile(r"callwatch|recording:|rules leave|calltrack|save:|reconcile|never answered|answer:")
    lines = [l for l in log().splitlines() if keep.search(l)]
    return "\n".join("       " + l[6:170] for l in lines[-25:])


def main():
    global serial
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--only", help="comma-separated checks to run, e.g. succession,caller_id")
    args = parser.parse_args()
    serial = args.serial
    only_an_emulator()
    sdk = sh("getprop ro.build.version.sdk").strip()
    print(f"{serial}: Android API {sdk}")
    set_up(args.apk)
    reinstall.apk = args.apk
    checks = [c for c in CHECKS if not args.only or c.__name__ in args.only.split(",")]
    if args.only and "succession" in args.only and "rules" not in args.only:
        set_prefs(rules_other_numbers=False)  # what the rules check leaves behind
        start_recorder()
    failed = 0
    for check in checks:
        nothing_pending()
        try:
            print(f"PASS {check.__name__:11} {check()}")
        except Exception as e:  # report every check, whatever one does
            failed += 1
            print(f"FAIL {check.__name__:11} {e}" + ("" if "\n" in str(e) else f"\n{what_happened()}"))
    set_prefs(rules_other_numbers=True)
    print(f"API {sdk}: {len(checks) - failed} of {len(checks)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
