# Third-party notices

JemRec is licensed under the Apache License, Version 2.0.

## libadb-android

Copyright 2021 Muntashir Al-Islam.
Dual licensed GPL-3.0-or-later **OR** Apache-2.0. JemRec takes it under
**Apache-2.0**, which the author explicitly offers ("Use whatever license you
need for your project").

<https://github.com/MuntashirAkon/libadb-android>

Note the library's own caveat, repeated here rather than buried: it has never
had a security audit.

Pulled in transitively: `spake2-android` (BoringSSL's SPAKE2, Apache-2.0 /
OpenSSL licence) for the pairing exchange.

## Bouncy Castle

Copyright 2000-2025 The Legion of the Bouncy Castle Inc. MIT-style licence.
Used only to assemble the ASN.1 of the self-signed X.509 certificate that
carries this app's ADB public key. <https://www.bouncycastle.org/licence.html>

## Conscrypt

Copyright The Android Open Source Project, Apache-2.0. Bundled because SPAKE2
binds the pairing exchange to the TLS channel with RFC 5705 exported keying
material, and the platform's own Conscrypt is behind the hidden-API blocklist -
so without this dependency `exportKeyingMaterial` simply does not exist and
pairing cannot complete. Bundling it is what lets JemRec avoid hidden-API
exemptions altogether. <https://github.com/google/conscrypt>

## scrcpy

Copyright 2018 Genymobile, Apache-2.0. Vendored, as source, in `shellserver/`:
an audio-only fork of `scrcpy-server` at tag **v4.1**, 23 of its 90 server
files, built from source by `shellserver/build.sh`. Package names are left as
`com.genymobile.scrcpy` so that a diff against upstream is a diff.

Every modification is listed in [shellserver/PATCHES.md](shellserver/PATCHES.md)
and marked `PATCHED:` at the site. The full licence text is in
`shellserver/LICENSE-scrcpy`.

<https://github.com/Genymobile/scrcpy>

## ShizuCallRecorder - reference only, no code taken

`kitsumed/ShizuCallRecorder` is GPL-3.0. It is a **reference for the approach
only**; no code from it is copied into JemRec, which is why JemRec can be
Apache-2.0. <https://github.com/kitsumed/ShizuCallRecorder>
