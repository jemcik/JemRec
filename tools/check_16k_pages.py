"""Fail if any native library in an APK would put the app in 16 KB compat mode.

WHY. On a phone with 16 KB memory pages, Android loads a library normally only
if it was laid out for them. If any one is not, the whole app runs in page-size
compat mode, with a warning dialog at first launch. JemRec writes no native
code. All its libraries come prebuilt inside dependencies, so this can change
with nothing but a version bump, which is how Conscrypt 2.5.3 got in. Measured
on the Android 17 QPR2 emulator (16 KB pages): `dumpsys package` said
pageSizeCompat=256 with 2.5.3 and 0 with 2.7.0.

WHAT IS CHECKED, per .so, as Android's guide puts it
(developer.android.com/guide/practices/page-sizes):

  - every LOAD segment aligned to 16 KB, in the file and in memory;
  - the RELRO range ends on a 16 KB boundary, or else the 16 KB page it ends
    in holds no writable data besides it. The loader makes that whole page
    read-only, and writable data sharing the page would fault. The second
    case is a library whose RELRO has a segment of its own: libspake2.so
    ends at 0xb000 and is accepted;
  - stored (uncompressed) in the APK at a 16 KB offset, since Android maps it
    straight out of the APK. This is the check `zipalign -P 16` makes.

64-bit libraries only: 16 KB pages exist only on 64-bit devices.

Run:  python3 tools/check_16k_pages.py app/build/outputs/apk/release/app-release-unsigned.apk
"""
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


def problems(elf: bytes) -> list[str]:
    phoff, = struct.unpack_from("<Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    loads, relros = [], []
    for i in range(phnum):
        kind, flags, offset, vaddr, _, _, memsz, align = struct.unpack_from(
            "<IIQQQQQQ", elf, phoff + i * phentsize)
        if kind == PT_LOAD:
            loads.append((flags, offset, vaddr, memsz, align))
        elif kind == PT_GNU_RELRO:
            relros.append((vaddr, memsz))

    found = []
    for flags, offset, vaddr, memsz, align in loads:
        if align < PAGE or (vaddr - offset) % PAGE:
            found.append(f"LOAD at {vaddr:#x} aligned to {align:#x}, not 16 KB")
    for vaddr, memsz in relros:
        end = vaddr + memsz
        if end % PAGE == 0:
            continue
        page_end = end - end % PAGE + PAGE
        for flags, _, seg_vaddr, seg_memsz, _ in loads:
            if flags & PF_W and seg_vaddr < page_end and seg_vaddr + seg_memsz > end:
                found.append(f"RELRO ends at {end:#x}, inside a 16 KB page that "
                             f"also holds writable data up to {min(seg_vaddr + seg_memsz, page_end):#x}")
                break
    return found


def main(apk: str) -> int:
    bad = 0
    with zipfile.ZipFile(apk) as zf:
        raw = open(apk, "rb").read()
        for info in zf.infolist():
            if not info.filename.endswith(".so"):
                continue
            elf = zf.read(info)
            # 64-bit ELF only: no 16 KB device runs the 32-bit ones.
            if elf[:4] != b"\x7fELF" or elf[4] != 2:
                continue
            issues = problems(elf)
            # Where the stored bytes start: past the local header, whose name
            # and extra field can differ in length from the central directory's.
            name_len, extra_len = struct.unpack_from("<HH", raw, info.header_offset + 26)
            data_at = info.header_offset + 30 + name_len + extra_len
            if info.compress_type == zipfile.ZIP_STORED and data_at % PAGE:
                issues.append(f"stored at offset {data_at:#x}, not 16 KB aligned")
            if issues:
                bad += 1
                print(f"FAIL {info.filename}")
                for issue in issues:
                    print(f"     {issue}")
            else:
                print(f"ok   {info.filename}")
    if bad:
        print(f"{bad} librar{'y' if bad == 1 else 'ies'} would put JemRec in 16 KB "
              "page-size compat mode. Find the dependency that ships it and a "
              "version built for 16 KB pages.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
