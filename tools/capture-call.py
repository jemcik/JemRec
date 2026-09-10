#!/usr/bin/env python3
"""Capture from the shell-side daemon and say whether BOTH call directions are there.

Milestone 3's question is not "did audio arrive". It is whether the far end is
present, because a recorder that captures only your own voice is worthless and
looks fine until you play it back. On this device the audio HAL is supposed to
put the two directions on the two channels of a stereo stream, so the honest
test is arithmetic on each channel separately rather than an impression.

Run in raw mode, so what is measured is PCM and not the output of a decoder:

    adb shell 'nohup sh -c "CLASSPATH=/data/local/tmp/jemrec-capture.jar \\
        exec app_process / com.jemcik.jemrec.shell.Main 28472 raw" \\
        > /data/local/tmp/jemrec-capture.out 2>&1 &'
    adb forward tcp:28472 tcp:28472
    tools/capture-call.py --seconds 25 --out call.wav

Wire format, unchanged from scrcpy:
    4 bytes   codec id, ASCII
    repeating:
      8 bytes pts, big endian, flag bits in the top 3
      4 bytes payload length
      n bytes payload
"""
import argparse
import socket
import struct
import sys
import time
import wave

FLAG_CONFIG = 1 << 62
FLAG_KEY_FRAME = 1 << 61
FLAG_SESSION = 1 << 63

SAMPLE_RATE = 48000
CHANNELS = 2
SAMPLE_WIDTH = 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=28472)
    ap.add_argument('--seconds', type=float, default=25.0)
    ap.add_argument('--out', default='call.wav')
    args = ap.parse_args()

    sock = socket.create_connection((args.host, args.port), timeout=10)
    sock.settimeout(5)
    buf = bytearray()

    def need(n):
        while len(buf) < n:
            chunk = sock.recv(1 << 16)
            if not chunk:
                raise EOFError('daemon closed the connection')
            buf.extend(chunk)

    def take(n):
        need(n)
        out = bytes(buf[:n])
        del buf[:n]
        return out

    codec = take(4).decode('ascii', 'replace')
    print('codec id: %s' % codec)
    if codec != 'raw\x00' and codec != '\x00raw':
        print('note: expected the raw codec id; got %r. Channel analysis below '
              'is only meaningful on raw PCM.' % codec)

    pcm = bytearray()
    packets = 0
    deadline = time.time() + args.seconds
    try:
        while time.time() < deadline:
            pts_raw, length = struct.unpack('>QI', take(12))
            payload = take(length)
            if pts_raw & FLAG_CONFIG:
                print('config packet: %d bytes' % length)
                continue
            packets += 1
            pcm.extend(payload)
    except (EOFError, socket.timeout) as e:
        print('stream ended: %s' % e)
    finally:
        sock.close()

    if not pcm:
        print('NO AUDIO RECEIVED')
        return 1

    with wave.open(args.out, 'wb') as w:
        w.setnchannels(CHANNELS)
        w.setsampwidth(SAMPLE_WIDTH)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(bytes(pcm))

    frames = len(pcm) // (CHANNELS * SAMPLE_WIDTH)
    print('packets: %d, frames: %d, duration: %.2f s -> %s'
          % (packets, frames, frames / SAMPLE_RATE, args.out))

    samples = struct.unpack('<%dh' % (frames * CHANNELS), bytes(pcm[:frames * CHANNELS * SAMPLE_WIDTH]))
    left = samples[0::2]
    right = samples[1::2]

    def rms(xs):
        if not xs:
            return 0.0
        return (sum(x * x for x in xs) / len(xs)) ** 0.5

    def peak(xs):
        return max((abs(x) for x in xs), default=0)

    print()
    print('  channel   rms     peak    active')
    verdicts = {}
    for name, ch in (('left', left), ('right', right)):
        r, p = rms(ch), peak(ch)
        # A channel carrying speech sits far above the noise floor of a muted
        # or unrouted one. This threshold separates "someone is talking" from
        # "dither and rounding", not loud speech from quiet speech.
        active = p > 500 and r > 30
        verdicts[name] = active
        print('  %-8s  %7.1f %7d  %s' % (name, r, p, 'YES' if active else 'no'))

    print()
    if verdicts['left'] and verdicts['right']:
        print('BOTH CHANNELS CARRY AUDIO.')
        print('If the two are different voices, this device exposes both call')
        print('directions to the voice-call source and JemRec can work on it.')
        print('Listen to %s to confirm they are different people.' % args.out)
        return 0
    if not verdicts['left'] and not verdicts['right']:
        print('SILENCE ON BOTH CHANNELS. Expected if no call was active.')
        return 2
    print('ONLY ONE CHANNEL CARRIES AUDIO.')
    print("That is the failure mode the README warns about: this device's HAL")
    print('does not put the far end on the voice-call source, and no app can')
    print('fix it. Verify independently with the one-line scrcpy check.')
    return 3


if __name__ == '__main__':
    sys.exit(main())
