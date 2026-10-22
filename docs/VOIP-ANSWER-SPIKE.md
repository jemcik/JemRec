# Can the answering machine take messenger calls?

A spike, 2026-10-22: measured, not built. The answering machine in 0.17 takes
phone calls only. This is whether it could take WhatsApp, Viber and Telegram
calls too, and what a feature that did would have to get right.

Measured on an Honor Magic 8 Pro (BKQ-N49, Snapdragon SM8850, MagicOS 11,
Android 17), with the debug build of 0.17 installed, a caller on another phone,
and two probes: `shellserver/probe/com/jemcik/jemrec/probe/VoipInjectProbe.java`
(the greeting, run as the shell) and `app/src/debug/java/com/jemcik/jemrec/diag/VoipAnswerSpike.kt`
(the answer, in the app).

**Verdict: yes, on this phone, for WhatsApp, Viber and Telegram** - answered
without the user, the greeting heard from its first word, nothing of the room
sent, and the microphone handed back mid-call for *Talk to them*. Telegram's
answer is a screen, which Android will not let the app open from the
background; the recorder, as the shell, may. Signal was not tried.

| | WhatsApp | Viber | Telegram |
| --- | --- | --- | --- |
| Answered by JemRec | yes | yes | from the shell only |
| Greeting heard by the caller | yes | yes | yes |
| The room sent | no | no | no |
| Microphone back mid-call | - | yes | yes |
| JemRec's own recording ran meanwhile | yes | yes | yes |

## 1. Answering

Messengers do not put their calls through Telecom here: `dumpsys telecom`
lists self-managed phone accounts for Gmail, Meet and Slack, and none for
WhatsApp, Telegram or Viber. So `TelecomManager.acceptRingingCall()` - how the
recorder answers a phone call - cannot reach them.

What both do post is a standard call notification, which JemRec's notification
listener can read (it is bound whenever the user lets JemRec hide the debugging
notification or name messenger callers):

```
pkg=com.whatsapp   category=call template=Notification$CallStyle fullScreen=true answerIntent=true actions=Decline|Answer
pkg=com.viber.voip category=call template=Notification$CallStyle fullScreen=true answerIntent=true actions=Decline|Answer
pkg=org.telegram.messenger category=call template=Notification$CallStyle fullScreen=true answerIntent=true actions=Decline|Answer
```

Firing that notification's answer intent (`Notification.EXTRA_ANSWER_INTENT`)
from the listener, five seconds into the ring, answered WhatsApp and Viber.
On Android 14+ the intent is sent with
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`, as it may start the messenger's call
screen. Three seconds later WhatsApp's notification was its in-call one
(`actions=Hang up`); Viber replaced it with another.

**Telegram's answer intent opens a screen**, and that the app may not do from
the background, whatever it asks for: `dumpsys activity intents` has it as
`startActivity act=voip_answer cmp=org.telegram.messenger/org.telegram.ui.LaunchActivity`,
and the tap left the call ringing, with

```
ActivityTaskManager: Background activity launch blocked! [callingPackage: org.telegram.messenger; ... callingUidHasVisibleActivity: false ...]
```

WhatsApp's and Viber's answer without opening anything, so theirs went
through. The shell holds `START_ACTIVITIES_FROM_BACKGROUND`, and the recorder
starting the same screen with the same action answered Telegram's call:

```
START u0 {act=voip_answer ... cmp=org.telegram.messenger/org.telegram.ui.LaunchActivity} from uid 2000 (com.android.shell) (BAL_ALLOW_PERMISSION)
```

Telegram's capture came from the greeting a second later. The shell
cannot fire another app's PendingIntent, so this needs to know the app's answer
screen and action - from `dumpsys activity intents`, which the shell can read,
or per app. Not tried: whether the app could open a screen of its own first and
fire the intent from there, which a locked phone would make awkward anyway.

## 2. The greeting

The phone-call greeting is Qualcomm's in-call music, mixed into the cellular
uplink. A messenger sends what its own `AudioRecord` reads, so the greeting has
to be what it reads. A dynamic audio policy does that, from the shell, which
holds `MODIFY_AUDIO_ROUTING`:

- `AudioMixingRule` in the injector role (`setTargetMixRole(MIX_ROLE_INJECTOR)`,
  Android 13+), one `RULE_MATCH_UID` per messenger;
- `AudioMix` with `ROUTE_FLAG_LOOP_BACK`, 48 kHz mono 16-bit;
- `AudioManager.registerAudioPolicyStatic` -> 0, then
  `AudioPolicy.createAudioTrackSource(mix)` - the track the greeting is written to.

The messenger's capture (source 7, voice communication) then came from the
policy's remote submix, not the microphone, and Android did not silence it:

```
recorders=[uid=10306 pkg=com.whatsapp   source=7 device=REMOTE_SUBMIX(102348142:ap:3mixr:0) silenced=false]
recorders=[uid=10296 pkg=com.viber.voip source=7 device=REMOTE_SUBMIX(116539075:ap:5mixr:0) silenced=false]
```

The caller heard the greeting; the user spoke near the Honor and was not heard.
No microphone mute was needed: the room never reaches the messenger.

What a feature has to get right, each measured:

- **Register before the messenger opens its capture.** Android routes an input
  when it is opened. Registered first and left in place, the policy took every
  call's capture.
- **Start the greeting when the capture opens, not before.** With no recorder
  attached the remote submix plays on at real-time pace into nothing: armed
  minutes before the call, the greeting was heard from wherever its loop had got
  to. Waiting for the messenger's recording to appear in
  `getActiveRecordingConfigurations()` and starting the lead-in then, the
  greeting was heard from its first word.
- **Feed silence after the beep, not nothing**, so the capture stays on the
  policy for the message.
- **Talk to them is unregistering the policy.** Taken away mid-call, the
  messenger's capture was on the built-in microphone at the next look, 2.5 s
  later, the call still up, and the caller heard the user:
  ```
  16:21:31.746 policy: unregistered
  16:21:34.257 ... recorders=[uid=10296 pkg=com.viber.voip source=7 device=BUILTIN_MIC(bottom) silenced=false]
  ```
- **Two policies matching one app: the first registered wins** (seen when a
  second probe was started while the first still ran).

## 3. Recording the message

JemRec's existing messenger recording (`Voip.java`: a playback-capture policy
for the other side, the microphone for the user's) ran alongside the injection
on both calls and saved them. Its "you" side is the real microphone - the room
- not the greeting, so a message from a messenger call would keep the other
side only, as the phone-call message keeps what the caller says.

## Risks and what was not measured

- **The policy dies with the process that registered it.** If the recorder
  dies mid-message, the messenger's capture goes back to the microphone and the
  caller hears the room - the same thing Talk to them does on purpose. The
  phone-call machine has a mute marker for its equivalent; whether
  `setMicrophoneMute` also silences a remote-submix input (and so would mute the
  greeting) was not measured.
- **While registered, every capture by those apps is redirected** - a voice
  note recorded during the window, too. A feature registers from the ring to
  the call's end, never while idle.
- **Ringing is seen in the app, not the recorder.** The recorder spots a
  messenger call by the audio mode, which changes only once the call is
  answered. The ring is the call notification, which only the app's listener
  sees, so the app would have to tell the recorder to register in time.
- **Who to answer.** Messenger callers are not numbers in the call log, and
  Whose calls to keep does not apply to messenger calls today; a feature would
  need its own rule (per app, or by the name in the notification).
- **Android versions.** Measured on 17 only. The injector role is API 33+;
  CallStyle notifications 31+; the messenger recording JemRec already has is
  14+. Other phones' audio HALs were not tried.
- **Messengers change.** Answering leans on each app posting a CallStyle
  notification with an answer intent; that is theirs to change.

## Reproducing it

Probe jar, as the shell (the greeting is the recorder's own copy):

```
shellserver/probe/build-probe.sh
adb push shellserver/probe/voip-probe.jar /data/local/tmp/
adb shell "CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / \
    com.jemcik.jemrec.probe.VoipInjectProbe uid:com.whatsapp,com.viber.voip \
    /data/local/tmp/jemrec-greeting.pcm 600 once:15 </dev/null >/dev/null 2>&1 &"
adb shell cat /data/local/tmp/voipinject/probe.log
```

Answering, in the debug build (the token is in `shared_prefs/jemrec_diag.xml`,
read with `run-as`; on Honor, JemRec must be set to manage its app launch
manually or the broadcast is dropped):

```
adb shell am broadcast -n com.jemcik.jemrec/.diag.SpikeReceiver \
    --es token <token> --es op voipanswer --ei after 5 --ei minutes 15
adb shell run-as com.jemcik.jemrec cat files/voip-answer.log
```
