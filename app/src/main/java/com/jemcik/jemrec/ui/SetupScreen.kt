package com.jemcik.jemrec.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Setup, as three things the user does by hand and nothing else.
 *
 * Allow two ordinary permissions; turn on Wireless debugging; type a six-digit
 * code. The app then grants itself the one PRIVILEGED permission it needs by
 * running `pm grant` over its own ADB session, and starts the recorder. No
 * computer is involved at any point.
 *
 * The pairing PORT is never asked for. It changes every time the dialog opens
 * and means nothing to anyone, so it is discovered over mDNS instead.
 *
 * STEP 3 OFFERS ONE ROUTE, NOT TWO
 *
 * It used to lead with a code box on this screen and mention the notification
 * shade underneath as the easier option. That had it backwards. Typing the code
 * here means leaving Settings, and leaving Settings closes the pairing dialog
 * and kills the code - so the box worked only in split screen, and a user who
 * did not know that typed six correct digits and was told pairing failed. The
 * shade is not the easier route, it is the one that works, so it is now the
 * only one offered. The code box is gone entirely, diagnostics included: a
 * control that cannot work is worse than no control at all.
 */
@Composable
fun SetupScreen(state: UiState, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    // The Settings screens are opened from HERE, the foreground Activity, not
    // through the ViewModel's Application context. A brand-new install has no
    // adb yet - that is the very thing this screen sets up - and MagicOS blocks
    // an app that starts an activity from a non-Activity context until it does,
    // so "Open settings" silently did nothing until a cable was plugged in. A
    // launch from the resumed Activity is a foreground launch, always allowed.
    val context = LocalContext.current
    // Whatever the user chooses, re-derive the step from what is actually
    // granted rather than from the result callback - "denied" and "denied
    // permanently" need different words, and only the real state knows.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshSetup() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // titleLarge, the same style the app bar gives every other screen's
        // title - setup has no app bar of its own, but its heading should not be
        // a size the rest of the app never uses.
        Text("Set up JemRec", style = MaterialTheme.typography.titleLarge)
        Text(
            "A few short steps, all on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (state.step) {
            // Full width like every other card here. Wrapping its content left
            // a small box hugging the left edge, which read as a stray label
            // rather than as this step's card.
            SetupStep.CHECKING -> Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Checking...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            SetupStep.NEEDS_PERMISSIONS -> Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Allow permissions",
                        style = MaterialTheme.typography.titleMedium)
                    // The first screen of setup, so the first impression of how
                    // much work this is going to be. It was four paragraphs to
                    // say "tap Allow three times" - a paragraph per permission,
                    // each explaining itself to someone who has not agreed to
                    // anything yet and only wants to know what happens next.
                    //
                    // The action goes in the body. Why, and the one fact worth
                    // volunteering about a call recorder, go in the small print
                    // underneath - same shape as every other card here.
                    // No count. It was "both questions", which stopped being
                    // true the moment audio access joined them, and is a
                    // different number again on an older Android.
                    Text(
                        "Tap Allow below, then Allow to each question Android asks.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "They let JemRec show you when a call is being recorded, " +
                            "and find recordings it made before. " +
                            "It never uses your microphone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { haptics.tap(); permissionLauncher.launch(Setup.ASK_PERMISSIONS) },
                        enabled = !state.busy,
                    ) { Text("Allow") }

                }
            }

            SetupStep.NEEDS_DEVELOPER_OPTIONS -> Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Turn on Developer options",
                        style = MaterialTheme.typography.titleMedium)
                    // "Developer options" is the most alarming phrase in this
                    // whole flow to someone who is not one. The reassurance goes
                    // FIRST, before the instructions, because a person who is
                    // worried about breaking their phone does not read step 1.
                    // The reassurance earns its line; the explanation of what
                    // Developer options are does not. She is not deciding whether
                    // to want them, she is deciding whether this is safe.
                    Text(
                        "This sounds technical. It is not, and nothing about your " +
                            "phone changes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Steps(
                        "Tap About phone below.",
                        // Where Build number lives is the one instruction here
                        // that is certainly wrong on another make of phone: it is
                        // loose in About phone on this one, tucked inside Software
                        // information on a Samsung, inside Version on a OnePlus -
                        // and on a Xiaomi there is no "Build number" row at all,
                        // you tap "OS version". Naming the likely places, and the
                        // one alternate label, beats naming a single path.
                        "Find Build number and tap it seven times. On some phones " +
                            "it sits inside Software information or Version, or the " +
                            "row itself is called OS version.",
                        "Type your PIN if asked.",
                        // No button to tap here any more. The app re-derives the
                        // step every time it returns to the foreground, so coming
                        // back from Settings is itself what advances this - the old
                        // "I have done it" only re-ran that same check, and sat
                        // greyed while it ran.
                        "Come back to JemRec - it notices on its own.",
                    )
                    Text(
                        "A message saying you are now a developer means it worked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // One button, because there is nothing to confirm - see the
                    // last step. The destination is the label; "Open" was doing
                    // nothing the button shape does not.
                    Button(onClick = { haptics.tap(); Setup.openAboutPhone(context) }) {
                        Text("About phone")
                    }
                }
            }

            // Both debugging switches AND pairing, on one screen and one trip to
            // Settings - all of it happens in Developer options, so there was
            // never a reason to bounce back to the app between them.
            //
            // THE TWO SWITCHES ARE ONE STEP, AND ORDER DOES NOT MATTER. Both must
            // be on: Wireless debugging is the channel, USB debugging (no cable
            // involved) is what keeps adbd - and with it Wireless debugging -
            // from stopping. The steps once prescribed an order, Wireless first
            // then USB, because one run held that way; the next runs needed
            // several attempts in that order too. The toggles simply do not
            // hold reliably on this phone, in either order. What made pairing
            // land is PairingService: after the code it WATCHES both switches,
            // says which one is off, and connects the moment both are on. So
            // the card says "both, any order" and leaves the sequencing to the
            // service. See Setup.usbDebuggingOn.
            SetupStep.NEEDS_PAIRING -> Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val bothOn = state.usbDebuggingOn && state.wirelessDebuggingOn
                    Text(
                        if (bothOn) "Pair with this phone" else "Turn on debugging and pair",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Why the code goes into a notification and not back here,
                    // said once and plainly: a person told to type a code
                    // somewhere other than where it appeared will otherwise
                    // assume they have misread the instruction.
                    Text(
                        "Android shows the pairing code in a small window that " +
                            "closes if you leave Settings, so you type it into the " +
                            "JemRec notification instead.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.wirelessDebuggingOn) {
                        Text(
                            "Be on Wi-Fi first: Wireless debugging only stays on with Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    // The one notification state that actually strands the user:
                    // with notifications off there is nowhere for the code to
                    // land, so say so and offer the fix before the steps that
                    // assume a notification can appear. Whether it also POPS as a
                    // heads-up is neither knowable nor needed here - the steps
                    // say "swipe down".
                    if (state.pairingNotificationsBlocked) {
                        Text(
                            "JemRec's notifications are turned off, so the code box " +
                                "cannot appear. Turn them on first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        FilledTonalButton(
                            onClick = {
                                haptics.tap()
                                PairingNotification.openNotificationSettings(context)
                            },
                        ) { Text("Turn on notifications") }
                    }

                    val skin = DeviceSkin.current()
                    Steps(
                        *buildList {
                            if (bothOn) {
                                add(
                                    "Tap Open settings below, open Wireless debugging " +
                                        "(search Settings for it if needed) and tap Pair " +
                                        "device with pairing code. Leave the code window open."
                                )
                            } else {
                                add(developerOptionsStep(skin))
                                add(debuggingSwitchesStep(state.usbDebuggingOn, state.wirelessDebuggingOn))
                                add(
                                    "Open Wireless debugging and tap Pair device with " +
                                        "pairing code. Leave the code window open."
                                )
                            }
                            add(
                                "Swipe down from the top of the screen, tap Enter code " +
                                    "on the JemRec notification, type the six digits and send."
                            )
                            add(
                                "The notification says what happens next. If a switch " +
                                    "went off, turn it back on - it connects by itself, " +
                                    "no new code needed. When it says done, come back here."
                            )
                        }.toTypedArray()
                    )
                    Text(
                        "The JemRec notification is already waiting for you - if it " +
                            "slides away, swipe down again. You only do this once; " +
                            "the phone remembers it after restarts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                haptics.tap()
                                // Post the code field first, then open Settings -
                                // from the Activity, so it opens without adb.
                                vm.showPairingNotification()
                                Setup.openSettings(context)
                            },
                            enabled = !state.busy,
                        ) { Text("Open settings") }
                        TextButton(
                            onClick = { haptics.tap(); vm.startPairingNotification() },
                            enabled = !state.busy,
                        ) { Text("Show the code field again") }
                    }
                }
            }


            // Paired already - adbd trusts our key - so this is NOT the pairing
            // flow again, only the session that went missing: one or both
            // switches dropped (this phone turns them off by itself), and the
            // app connects on its own once both are back. Shorter than the
            // pairing card, because there is much less left to do.
            SetupStep.NEEDS_CONNECTION -> Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Finish connecting",
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Already paired - JemRec only needs to reach this phone " +
                            "once. You will not pair again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.usbDebuggingOn || !state.wirelessDebuggingOn) {
                        val skin = DeviceSkin.current()
                        Steps(
                            developerOptionsStep(skin),
                            debuggingSwitchesStep(
                                state.usbDebuggingOn, state.wirelessDebuggingOn,
                                mayLookOn = true,
                            ),
                            "Stay on that screen a few seconds so JemRec connects, " +
                                "then come back here.",
                        )
                        // Start connecting in the BACKGROUND, then open Settings -
                        // so the connect lands while the user is still on the
                        // Developer options screen (where the switches hold), not
                        // after they return here (which can drop them).
                        Button(
                            onClick = {
                                haptics.tap()
                                vm.reconnect()
                                Setup.openSettings(context)
                            },
                        ) { Text("Open settings") }
                    } else {
                        // Both switches on but the session did not open - rarer.
                        // Keep trying in the background for a while rather than
                        // taking a single shot.
                        Text(
                            "Both switches are on, but JemRec could not reach this " +
                                "phone just now. Tap Try again.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton(
                            onClick = { haptics.tap(); vm.reconnect() },
                            enabled = !state.busy,
                        ) { Text("Try again") }
                    }
                }
            }

            SetupStep.FINISHING -> Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("Almost done", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Paired. JemRec is starting the recorder now. This takes a " +
                            "few seconds, and there is nothing left for you to do.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            SetupStep.READY -> Unit // HomeScreen takes over.
        }

        if (state.log.isNotEmpty()) {
            Card {
                Text(
                    state.log.trim().lines().takeLast(4).joinToString("\n"),
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // NO DIAGNOSTICS HERE. Setup is the one screen guaranteed to be read by
        // someone who has never seen this app, and "Show diagnostics" asks them
        // to have an opinion about a word that means nothing yet. It is still
        // in Settings, where a person goes looking for it on purpose.
    }
}

/** One trip to Developer options, with the search fallback for any phone. */
private fun developerOptionsStep(skin: DeviceSkin): String =
    "Tap Open settings below and go to ${skin.developerOptionsPath} - or search " +
        "Settings for Developer options."

/**
 * The two debugging switches as ONE step. Both must be on - Wireless debugging
 * is the channel, USB debugging (no cable involved) is what keeps adbd, and
 * with it Wireless debugging, from stopping - and the order does not matter:
 * on this phone the toggles hold unreliably in either order, and it is
 * PairingService that watches them and connects once both are on. Names the
 * one still off when the other is already on, so nobody is told to turn on a
 * switch that is.
 *
 * mayLookOn: on the reconnect card the phone can SHOW a switch on that is not
 * (measured on this Honor), so the step says to flip it off and on.
 */
private fun debuggingSwitchesStep(
    usbOn: Boolean,
    wirelessOn: Boolean,
    mayLookOn: Boolean = false,
): String = buildString {
    append(
        when {
            !usbOn && !wirelessOn ->
                "Turn on USB debugging and Wireless debugging - both, in any order. No cable needed."
            !usbOn -> "Turn on USB debugging too - Wireless debugging is already on. No cable needed."
            else -> "Turn on Wireless debugging too - USB debugging is already on."
        }
    )
    if (mayLookOn) append(" If a switch already looks on, turn it off and on once.")
    append(" If one switches itself off, turn it on again - it holds the second time.")
    if (!wirelessOn) append(" If asked to allow this network, tick Always allow.")
}

/**
 * Numbered instructions.
 *
 * A paragraph is the wrong shape for something performed one action at a time,
 * in another app, against a dialog that closes if you dawdle. Numbers give the
 * user somewhere to put their finger and a place to come back to when they look
 * up from Settings and forget where they were.
 */
@Composable
private fun Steps(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
