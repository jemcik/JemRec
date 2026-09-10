# Milestone 5 - muxer and storage

Until this, recordings were raw Opus packets with no container: provably real
audio that no player would open. This wraps them into `.ogg` and writes them
where the user chooses.

## The container

`MediaMuxer` with `MUXER_OUTPUT_OGG`. Opus needs three pieces of
codec-specific data, and only the first arrives over the wire:

| | contents | where it comes from |
|---|---|---|
| `csd-0` | the 19-byte `OpusHead` | the daemon's config packet |
| `csd-1` | codec delay, nanoseconds | derived from the header's pre-skip |
| `csd-2` | seek pre-roll, nanoseconds | 80 ms by convention |

scrcpy has already done the awkward part. MediaCodec hands out Opus CSD as
three length-prefixed sections - `AOPUSHDR`, `AOPUSDLY`, `AOPUSPRL` - and
`Streamer.fixOpusConfigPacket()` slices out just the first, so a bare
`OpusHead` is what reaches the app.

**csd-1 is derived, not hardcoded.** Pre-skip 312 at 48 kHz is 6,500,000 ns,
which matches the `AOPUSDLY` value in scrcpy's own worked example. Hardcoding
6.5 ms would be right for this device and quietly wrong elsewhere, and the
symptom would be a file that plays with the wrong offset rather than one that
refuses to open.

Timestamps are rebased so the first sample sits at zero. The daemon's clock
starts when capture did, not when the file did, and a container whose first
sample carries a large offset makes players insert that much leading silence.

## Verified

An 8-second test recording, checked three independent ways:

```
file(1):   Ogg data, Opus audio, version 0.1, stereo, 48000 Hz
structure: 7 pages - OpusHead (2ch, pre-skip 312, 48000), OpusTags, audio
afinfo:    2 ch, 48000 Hz, opus | Stereo (L R) | duration 4.159625 sec
```

`afinfo` is macOS's own audio framework, so that last line is an outside party
parsing the file and agreeing about its duration.

## The bug this milestone found

The first working version deleted every recording it made.

`stopRecording()` ends a call by closing the socket, which makes the read
**throw** rather than return null - that is the normal path, not the exceptional
one. The packet count was being assigned inside the `try` block, after the loop,
so it was skipped every time, every recording looked empty, and the "a call that
produced nothing should leave nothing" rule threw it away.

The counter now lives outside the `try`, where the `finally` can see it. Worth
remembering whenever a loop's usual exit is an exception: anything the cleanup
needs must be readable from outside the block.

## Storage

The user picks a folder through the system picker and the permission is
persisted, so it survives reboots. Without `takePersistableUriPermission` the
grant dies with the process and later calls write to the fallback with no
visible complaint.

If no folder is chosen, or the chosen one has been deleted or revoked,
recordings fall back to the app's own external files directory rather than
failing. Mid-call is the worst possible moment to discover a storage problem,
and recording to the wrong place beats not recording. The cost is stated in the
UI: uninstalling the app deletes anything left there.

`"rw"` and not `"w"` when opening the descriptor - MediaMuxer must seek back to
patch headers when it finalises, and a write-only descriptor cannot.

## Filenames

`20260907_150909_out.ogg` - sortable, and says which way the call went. Close to
BCR's convention so `bcr-gui` can browse them, but not identical: BCR puts the
phone number in the name and this app does not have it, because getting it would
mean holding `READ_CALL_LOG` for the sake of a filename.
