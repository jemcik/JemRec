# Security

<!-- The private report below, the privacy policy's Contact and the pinned
     welcome discussion (#73) all go to security/advisories/new, which works
     only while Private vulnerability reporting is on, in the repository's
     Settings → Advanced Security. Turn that off and every one of them needs
     another way. -->

JemRec records calls, runs its recorder as the shell user through the phone's
own Wireless debugging, and keeps debugging on to do it. A hole in it can
expose someone's calls, so reports are welcome - privately.

## How to report

Use GitHub's
[private report](https://github.com/jemcik/JemRec/security/advisories/new),
not an issue or a discussion: those are public before a fix ships. Say what an
attacker can reach and how, the phone, its Android version and the JemRec
version, and the steps that show it. Please don't attach recordings of real
calls.

JemRec is made by one person, so there is no fixed reply time and no bounty.
The fix goes out as a release, through F-Droid and GitHub Releases like any
other; whether an advisory follows, and with whose name, we agree in the
report.

## Which versions

Only the latest release. A fix is not carried back to older ones: update to
get it.

## What counts

Anything that lets another app on the phone, a website, or another device get
at what JemRec guards: the recordings and what is noted about them, starting
or stopping a recording, the recorder's socket (authenticated in both
directions - see
[ARCHITECTURE.md](docs/ARCHITECTURE.md#the-socket-is-authenticated-in-both-directions)),
or the debugging settings JemRec controls.
[GHSA-hf4v-7hwh-hx7w](https://github.com/jemcik/JemRec/security/advisories/GHSA-hf4v-7hwh-hx7w),
an exported service that let any installed app start a call recording in
*Ask first* mode, is the kind. So is a way into how JemRec is built and
released - the workflows, the signing key, the APKs on GitHub Releases - since
that could put someone else's code into a signed release.

What is already said and chosen is not a hole: debugging stays on while JemRec
is on, and while it is off unless you choose otherwise, so a computer that was
paired with the phone can open a shell on it; and *Start without Wi-Fi* opens
port 5555, where a request to debug still needs a yes on the phone (see
[What stays switched on](docs/ARCHITECTURE.md#what-stays-switched-on)). A way
around that pairing or that yes is a hole, though - report it.
