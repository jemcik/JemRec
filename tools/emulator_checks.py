#!/usr/bin/env python3
"""Release checks on an Android emulator: real call handling, end to end.

    python3 tools/emulator_checks.py [--serial emulator-5554] [--apk PATH]

Installs the debug APK, sets JemRec up from nothing, and runs simulated calls
through the real app and daemon, checking what each one leaves behind:

  ringing     an outgoing call is saved from its answer to its end, exactly
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
  waveform    a call's 800 levels are worked out and kept as it is saved,
              so its row has its waveform the first time it is drawn
  background-playback  a recording played from its row goes on with the
              screen off, called only "Call recording" while the phone is
              locked and by its caller again once unlocked; a call ringing
              in holds it back, and it plays on once the call is over
  recently-deleted  a recording deleted is brought back by Restore all in
              Recently deleted; deleted again, Delete all for good removes
              it, from Android's own trash as well, its file with it
  looks       the app, started again in Deck (dark), Multitrack and Memo,
              opens in each, its own header drawn

Every call is the emulator's own simulation - nothing leaves the machine -
and the script refuses to touch anything that is not an emulator. It needs a
google_apis image (adb root, for the caller-id check). Run it on each Android
version a release claims; see docs/DEVELOPMENT.md, Tests.

Exit status: 0 if every check passed.
"""
import argparse
import hashlib
import re
import subprocess
import sys
import time

PKG = "com.jemcik.jemrec"
PREFS = "shared_prefs/jemrec.xml"
CONTACT = "5557777"      # made a contact here, as "TestKept"
STRANGER_IN = "5554321"  # rings in; no contact
STRANGER_OUT = "5551234"  # dialled; no contact
CALLER_ID = ("3034997111", "NIST Radio Station WWV")  # issue #30's call

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


def daemon_pid():
    out = sh("ps -A -o PID,USER,ARGS | grep com.jemcik.jemrec.shell.Main | grep -v grep").split()
    return out[0] if out else None


def start_recorder():
    """Open the app (a never-opened app may not reach 127.0.0.1:5555), connect it
    to the emulator's adbd, and start a fresh daemon from this build."""
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(3)
    if "connected" not in diag("connect", "--ei", "port", "5555"):
        raise RuntimeError("the app could not connect to adbd on 5555")
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


def set_up(apk):
    print(f"installing {apk}")
    adb("install", "-r", apk, check=True)
    for permission in ("READ_CALL_LOG", "READ_CONTACTS", "POST_NOTIFICATIONS"):
        sh(f"pm grant {PKG} android.permission.{permission}")  # NOTIFICATIONS fails before 13: fine
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
    # And the screenshots' invented calls off (tools/shoot.py, DemoRecordings):
    # left on, the waveforms are the invented ones and nothing is kept - the
    # waveform check failed on the Android 12 and 13 AVDs, 2026-10-23.
    diag("demo", "--ez", "on", "false")
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
        if len(p) == 5:
            answered = [int(a) for a in p[3].split(",") if a]
            calls.append((int(p[0]), p[1] == "in", answered[0] if answered else 0, int(p[4])))
    return sorted(calls)


RECORDINGS = "/sdcard/Recordings/JemRec"


def saved():
    """The call just over, as saved: (packets, seconds, file name). The log
    line leaves the name out (a name with the caller in it is personal), so
    the name is the newest file in the recordings folder."""
    m = wait_log(r"save: wrote (\d+) packets, ([\d.]+)s from \S+", 30, "the recording to be saved")
    newest = sh(f"ls -t {RECORDINGS}/").splitlines()
    return int(m.group(1)), float(m.group(2)), (newest[0].strip() if newest else "")


def exact(name_part):
    """Saved, named with [name_part], and as long as Telecom says the call was."""
    packets, _, name = saved()
    seconds = packets * 0.020  # one Opus packet, 20 ms
    # Waited for: read at once, the app's account came back empty once, the
    # first call on the Android 12 AVD in a run beside another emulator
    # (2026-10-24) - the call saved, its end seen, and it passed run again.
    _, _, answered, ended = wait_for(tracked, 5, "Telecom's account of the call in the app's preferences")[-1]
    talk = (ended - answered) / 1000
    assert name_part in name, f"saved as {name}, wanted {name_part!r} in it"
    assert abs(seconds - talk) <= 0.15, f"{seconds:.2f} s saved for {talk:.2f} s of call"
    return f"{name}: {seconds:.2f} s for {talk:.2f} s answered"


# --- the checks --------------------------------------------------------------------

def ringing():
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=3, talk_s=5)
    return exact("_out_")


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


def fake_call_up():
    """A stand-in for a messenger call, run as the shell: the audio mode at
    MODE_IN_COMMUNICATION, call audio playing, the microphone open."""
    import os
    if not os.path.exists(PROBE_JAR) or os.path.getmtime(PROBE_JAR) < os.path.getmtime(PROBE_SOURCE):
        built = subprocess.run(["bash", "shellserver/probe/build-probe.sh"], capture_output=True, text=True)
        if built.returncode != 0:
            raise RuntimeError("could not build the stand-in call (JDK 21? set JEMREC_JAVA_HOME):\n"
                               + built.stdout[-800:] + built.stderr[-800:])
    adb("push", PROBE_JAR, "/data/local/tmp/voip-probe.jar", check=True)
    sh(f"rm -f {FAKE_CALL_LOG}")
    sh("CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / "
       f"com.jemcik.jemrec.probe.FakeCallProbe 60 < /dev/null > {FAKE_CALL_LOG} 2>&1 &")
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
    """Tap the middle of the first node on screen whose text holds [digits_in]."""
    for m in re.finditer(r'<node [^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui()):
        if digits_in in digits(m.group(1)):
            x1, y1, x2, y2 = (int(v) for v in m.groups()[1:])
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return True
    return False


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


def tap(label):
    """Tap the middle of the first node on screen whose text or content
    description is [label]."""
    for node in re.findall(r"<node [^>]*>", ui()):
        said = re.findall(r'(?:text|content-desc)="([^"]*)"', node)
        if label in said:
            x1, y1, x2, y2 = (int(v) for v in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return True
    return False


def on_screen(label):
    return f'"{label}"' in ui()


def open_trash():
    """Recently deleted, reached as a person reaches it: the gear, then its
    row in Settings, scrolled to."""
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {PKG}/.MainActivity")
    wait_for(lambda: tap("Settings"), 30, "the Settings gear")
    width, height = (int(v) for v in re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1])
    for _ in range(8):
        if tap("Recently deleted"):
            return wait_for(lambda: on_screen("Restore all"), 15, "Recently deleted, with Restore all")
        sh(f"input swipe {width // 2} {height * 3 // 4} {width // 2} {height // 4} 300")
        time.sleep(0.5)
    raise TimeoutError("Recently deleted in Settings")


def recently_deleted():
    """A recording moved to Recently deleted (the debug build's diag 'trash',
    as the list's delete moves it) comes back with Restore all, tapped there;
    moved again, Delete all for good and its question remove it - from
    MediaStore's trash, which Robolectric cannot run, and its file with it."""
    sh("logcat -c")
    dial(STRANGER_OUT, ring_s=2, talk_s=4)
    _, _, name = saved()

    def where(move=False):
        said = diag("trash", "--es", "name", name, *(("--ez", "move", "true") if move else ()))
        m = re.search(r"listed=(\w+) trashed=(\w+)", said)
        assert m, said
        return (m.group(1) == "true", m.group(2) == "true"), said

    def files():
        return [f for f in sh(f"ls -a {RECORDINGS}/").split() if name in f]

    try:
        place, said = where(move=True)
        assert place == (False, True), f"not moved to the trash: {said}"
        open_trash()
        tap("Restore all")
        wait_for(lambda: where()[0] == (True, False), 20, "Restore all to bring it back")
        assert files() == [name], f"restored, its files are {files()}"
        place, said = where(move=True)
        assert place == (False, True), f"not moved to the trash again: {said}"
        trashed = files()
        open_trash()
        tap("Delete all for good")
        wait_for(lambda: on_screen("Delete for good"), 10, "the question")
        tap("Delete for good")
        wait_for(lambda: where()[0] == (False, False), 20, "Delete all for good to remove it")
        assert not files(), f"deleted for good, its files are {files()}"
    finally:
        sh(f"am force-stop {PKG}")
    return f"restored by Restore all; deleted for good, {trashed[0] if trashed else '?'} gone"


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


def self_test():
    report = diag("selftest")
    assert "PASS" in report, report
    assert "tracking:  on" in report, report
    return next(l.split("] ", 1)[1] for l in report.splitlines() if "PASS" in l)


CHECKS = [ringing, unanswered, incoming, rules, succession, caller_id, self_test,
          answering, greeting_hangup, answer_rules, answer_during_voip,
          answer_after_emergency, answer_takeover_ask_first, waveform, background_playback,
          recently_deleted, looks]


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
