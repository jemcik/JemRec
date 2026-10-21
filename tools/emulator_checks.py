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

Every call is the emulator's own simulation - nothing leaves the machine -
and the script refuses to touch anything that is not an emulator. It needs a
google_apis image (adb root, for the caller-id check). Run it on each Android
version a release claims; see README, Tests.

Exit status: 0 if every check passed.
"""
import argparse
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


def saved():
    """The save line of the call just over: (packets, seconds, file name)."""
    m = wait_log(r"save: wrote (\d+) packets, ([\d.]+)s -> (.+)", 30, "the recording to be saved")
    return int(m.group(1)), float(m.group(2)), m.group(3).strip()


def exact(name_part):
    """Saved, named with [name_part], and as long as Telecom says the call was."""
    packets, _, name = saved()
    seconds = packets * 0.020  # one Opus packet, 20 ms
    _, _, answered, ended = tracked()[-1]
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


def self_test():
    report = diag("selftest")
    assert "PASS" in report, report
    assert "tracking:  on" in report, report
    return next(l.split("] ", 1)[1] for l in report.splitlines() if "PASS" in l)


CHECKS = [ringing, unanswered, incoming, rules, succession, caller_id, self_test]


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
    keep = re.compile(r"callwatch|recording:|rules leave|calltrack|save:|reconcile|never answered")
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
