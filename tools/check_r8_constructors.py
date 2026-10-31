"""Fail if R8 removed a constructor that something builds a class by name with.

WHY. R8 removes code nothing calls, and cannot see a class made from its name
at run time. Keep rules say what to leave, and a rule that keeps a class but
not its constructor leaves a class that cannot be made. Nothing fails until
the moment it is made, and then it may fail silently. That is issue #69:
WorkManager 2.9.1's rule `-keep class * extends androidx.work.InputMerger` let
R8 remove OverwritingInputMerger's constructor, which WorkManager reflects on
before every one-time run, so every one ended FAILED without running - in
every release from 0.5 to 0.25.2 (measured in the 0.8 APK and a build of
0.25.2's code, 2026-10-31). Room 2.5's rule did the same to WorkDatabase_Impl
in #8. The debug build is not shrunk, and the emulator checks only see a path
they happen to run; this reads the APK itself.

WHAT IS CHECKED, in the APK's own manifest and code:

  - every activity, service, receiver and provider in the manifest, and the
    application's class, AppComponentFactory, backup agent and zygote
    preload class: Android makes them by name, with no arguments;
  - every androidx.startup initializer the manifest names: made by name, with
    no arguments;
  - every class listed in META-INF/services (ServiceLoader): the same;
  - every concrete ListenableWorker: WorkManager's default factory makes it by
    name with (Context, WorkerParameters);
  - every concrete InputMerger and RoomDatabase: made by name, with no
    arguments;
  - and by name, the ones WorkManager and Room make from a string
    (OverwritingInputMerger, WorkDatabase_Impl), whatever their base: R8
    merges a base class into its only subclass, and in 0.25.3's release
    OverwritingInputMerger's superclass reads java.lang.Object - found by
    a first version of this script, which knew it only by its base and so
    passed without looking at it (2026-10-31). A base in BASES that is not
    in the APK fails the run unless NAMED lists what is made from it.

Each must be in the APK and still have that constructor. It fails, too, when
the manifest or the code cannot be read, rather than pass on what it could.
Not checked: methods and fields reached by name (libadb's getMethod into
Conscrypt is kept by `{ *; }` in app/proguard-rules.pro; a Parcelable's
CREATOR, which Android reads by name, by AGP's default rules), and any other
library's own reflection - add it here when one comes in.

Needs dexdump and aapt2 from the SDK's build-tools: ANDROID_HOME (or
ANDROID_SDK_ROOT, or local.properties' sdk.dir) and BUILD_TOOLS (default
36.0.0, what app/build.gradle.kts pins).

Run:  python3 tools/check_r8_constructors.py app/build/outputs/apk/release/app-release-unsigned.apk
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

NO_ARGS = "()V"
WORKER_ARGS = "(Landroid/content/Context;Landroidx/work/WorkerParameters;)V"

# Listed as made by name, yet never made that way: not checked at all, in the
# APK or not. Each says why, so a new one fails until someone looks.
EXPECTED = {
    # Measured: no constructor in the 0.25.2 and 0.25.3 release APKs, and
    # Dispatchers.Main works in both (the app would not start without it),
    # 2026-10-31. Inferred, from R8's documented rewriting of ServiceLoader
    # lookups: R8 makes it with a direct `new` and inlines that, and its
    # services file is left, unread.
    "kotlinx.coroutines.android.AndroidDispatcherFactory",
}

# Made by name from a string, so looked for by name, base or no base - and
# the base each stands for when R8 has merged that base away.
NAMED = {
    "androidx.work.OverwritingInputMerger":
        (NO_ARGS, "WorkManager makes it by name before every one-time run", "androidx.work.InputMerger"),
    "androidx.work.impl.WorkDatabase_Impl":
        (NO_ARGS, "Room makes WorkManager's database by name", "androidx.room.RoomDatabase"),
}

# Bases whose concrete subclasses a library makes by name, and how.
BASES = {
    "androidx.work.ListenableWorker": (WORKER_ARGS, "WorkManager makes it by name with (Context, WorkerParameters)"),
    "androidx.work.InputMerger": (NO_ARGS, "WorkManager makes it by name before every one-time run"),
    "androidx.room.RoomDatabase": (NO_ARGS, "Room makes its _Impl by name"),
}


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        here = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "local.properties")
        if os.path.exists(here):
            for line in open(here):
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip()
    if not sdk:
        sys.exit("no Android SDK: set ANDROID_HOME")
    tools = os.path.join(sdk, "build-tools", os.environ.get("BUILD_TOOLS", "36.0.0"))
    for tool in ("dexdump", "aapt2"):
        if not os.access(os.path.join(tools, tool), os.X_OK):
            sys.exit(f"no {tool} in {tools}")
    return tools


def classes(apk, dexdump):
    """Every class in the APK's dex files: {name: (superclass, abstract,
    {constructor descriptors})}, names in Java form."""
    found = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as zf:
        for entry in zf.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            out = subprocess.run([dexdump, zf.extract(entry, tmp)], capture_output=True,
                                 text=True, check=True).stdout
            name = section = method = None
            for line in out.splitlines():
                if m := re.match(r"\s+Class descriptor\s+: 'L(.+);'", line):
                    name, section, method = m.group(1).replace("/", "."), None, None
                    found[name] = [None, False, set()]
                elif name is None:
                    continue
                elif (m := re.match(r"\s+Access flags\s+: 0x[0-9a-f]+ \((.*)\)", line)) and section is None:
                    found[name][1] = "ABSTRACT" in m.group(1) or "INTERFACE" in m.group(1)
                elif m := re.match(r"\s+Superclass\s+: 'L(.+);'", line):
                    found[name][0] = m.group(1).replace("/", ".")
                elif re.match(r"\s+Direct methods\s+-", line):
                    section = "direct"
                elif re.match(r"\s+(Virtual methods|source_file_idx)", line):
                    section = None
                elif section == "direct" and (m := re.match(r"\s+name\s+: '(.+)'", line)):
                    method = m.group(1)
                elif section == "direct" and method == "<init>" and (m := re.match(r"\s+type\s+: '(.+)'", line)):
                    found[name][2].add(m.group(1))
    return {k: tuple(v) for k, v in found.items()}


def by_name_in_manifest(apk, aapt2):
    """(class, why) for every class the manifest has Android make by name."""
    out = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                         capture_output=True, text=True, check=True).stdout
    package, elements = None, []  # (tag, {attribute: value})
    for line in out.splitlines():
        if m := re.match(r"\s*E: (\S+)", line):
            elements.append((m.group(1), {}))
        elif m := re.match(r'\s*A: package="([^"]+)"', line):
            package = m.group(1)
        elif (m := re.match(r'\s*A: (?:http://schemas.android.com/apk/res/android:)?(\w+)\(0x[0-9a-f]+\)="([^"]*)"',
                            line)) and elements:
            elements[-1][1][m.group(1)] = m.group(2)

    if not package or not any(tag == "application" for tag, _ in elements):
        sys.exit("could not read the manifest's package and application from aapt2's output")

    def full(name):
        return package + name if name.startswith(".") else name if "." in name else f"{package}.{name}"

    wanted = []
    for tag, attrs in elements:
        if tag in ("activity", "service", "receiver", "provider") and "name" in attrs:
            wanted.append((full(attrs["name"]), f"the manifest's {tag}"))
        elif tag == "application":
            for key in ("name", "appComponentFactory", "backupAgent", "zygotePreloadName"):
                if key in attrs:
                    wanted.append((full(attrs[key]), f"the manifest's application {key}"))
        elif tag == "meta-data" and attrs.get("value") == "androidx.startup" and "name" in attrs:
            wanted.append((attrs["name"], "an androidx.startup initializer"))
    if not any(why.startswith("the manifest's activity") for _, why in wanted):
        sys.exit("read no activity from the manifest: has aapt2's output changed?")
    return wanted


def by_name_in_services(apk):
    with zipfile.ZipFile(apk) as zf:
        for entry in zf.namelist():
            if entry.startswith("META-INF/services/") and not entry.endswith("/"):
                for line in zf.read(entry).decode().splitlines():
                    line = line.split("#", 1)[0].strip()
                    if line:
                        yield line, f"a ServiceLoader provider ({entry})"


def main(apk):
    tools = build_tools()
    code = classes(apk, os.path.join(tools, "dexdump"))
    if not code:
        sys.exit("read no class from the APK's dex files: has dexdump's output changed?")

    def ancestors(name):
        seen = []
        while name in code and code[name][0]:
            name = code[name][0]
            seen.append(name)
        return seen

    manifest = [(name, NO_ARGS, why) for name, why in by_name_in_manifest(apk, os.path.join(tools, "aapt2"))]
    services = [(name, NO_ARGS, why) for name, why in by_name_in_services(apk)]
    wanted = manifest + services + [(name, args, why) for name, (args, why, _) in NAMED.items()]
    for name, (_, abstract, _) in code.items():
        if abstract:
            continue
        for base in ancestors(name):
            if base in BASES:
                wanted.append((name, *BASES[base]))

    bad = 0
    for base in BASES:
        if base not in code and not any(b == base for _, _, b in NAMED.values()):
            bad += 1
            print(f"FAIL {base} is not in the APK: R8 merged it into a subclass, so what is made by "
                  "name from it cannot be found by its base - list each such class in NAMED")
    for name, args, why in sorted(set(wanted)):
        if name in EXPECTED:
            continue
        if name not in code:
            problem = "is not in the APK"
        elif args not in code[name][2]:
            problem = f"has no constructor {args}"
        else:
            continue
        bad += 1
        print(f"FAIL {name} {problem}: {why}")
    checked = len(set(name for name, _, _ in wanted))
    counts = (f"{len(set(n for n, _, _ in manifest))} from the manifest, {len(set(n for n, _, _ in services))} "
              f"ServiceLoader providers, {checked - len(set(n for n, _, _ in manifest + services))} by name or base")
    if bad:
        print(f"{bad} problem(s) in {checked} classes made by name ({counts}). How a keep rule "
              "keeps a class and loses its constructor: app/proguard-rules.pro's header.")
        return 1
    print(f"ok   {checked} classes made by name ({counts}), each with its constructor")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python3 tools/check_r8_constructors.py APK")
    sys.exit(main(sys.argv[1]))
