package com.jemcik.jemrec.contract

import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recordings.FolderAccess
import com.jemcik.jemrec.system.BatteryHealth
import com.jemcik.jemrec.system.UsbDefault
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The problem notifications said once each read a flag, post, then write
 * the flag - and are taken back the same way. They are called from more
 * than one thread: a process's start thread, the switch, a keep-alive run,
 * a reading, two revives ending together (RecorderHealth.onRevive runs after
 * the recorder's lock is let go). Unlocked, two could both find the flag
 * clear and both post: one notification, two sounds (1.7's review round 1).
 * BackgroundKill.warnNewKill has been under its object's lock since 0.21 for
 * the same reason. A race is not produced reliably in a test, so this holds
 * each to the lock itself.
 */
class TellOnceLockTest {

    private val locked = mapOf(
        // Each asks Android and builds its notification first, and holds the
        // lock only to check, notify and set the flag (review round 3: lock
        // contention with the main thread is an ANR cause).
        UsbDefault::class.java to listOf("postLocked", "takeBack", "dismiss"),
        BatteryHealth::class.java to listOf("postOnce", "repost", "takeBack", "dismiss"),
        // Its words and PendingIntents are made before the lock (the
        // PendingIntents ask Android's activity manager, which reached(), on
        // the main thread, must not wait on); the telling is under it.
        FolderAccess::class.java to listOf("tellOnce", "reached"),
        RecorderHealth::class.java to listOf("judge", "callOverLocked", "seen", "standingDown", "pairingAgain"),
    )

    @Test fun eachToldOnceNotificationIsSaidAndTakenBackUnderItsObjectsLock() {
        val unlocked = locked.flatMap { (type, names) ->
            names.mapNotNull { name ->
                val methods = methodsNamed(type, name)
                when {
                    methods.isEmpty() -> "${type.simpleName}.$name: not found"
                    methods.any { !Modifier.isSynchronized(it.modifiers) } -> "${type.simpleName}.$name"
                    else -> null
                }
            }
        }
        assertTrue("Not under its object's lock:\n" + unlocked.joinToString("\n"), unlocked.isEmpty())
    }

    /** And what asks Android - a setting, the network, the audio mode,
     *  PendingIntents - does so before the lock: these hold none themselves,
     *  so Not now or a resume, on the main thread, never waits on a binder
     *  call another thread makes under it (review round 3). */
    @Test fun whatAsksAndroidHoldsNoLock() {
        val free = mapOf(
            UsbDefault::class.java to listOf("tellIfAtRisk", "tell"),
            BatteryHealth::class.java to listOf("tellIfRestricted", "refresh"),
            FolderAccess::class.java to listOf("savedElsewhere"),
            RecorderHealth::class.java to listOf("onRevive", "callOver"),
        )
        val held = free.flatMap { (type, names) ->
            names.mapNotNull { name ->
                val methods = methodsNamed(type, name)
                when {
                    methods.isEmpty() -> "${type.simpleName}.$name: not found"
                    methods.any { Modifier.isSynchronized(it.modifiers) } -> "${type.simpleName}.$name"
                    else -> null
                }
            }
        }
        assertTrue("Holds its object's lock across Android's calls:\n" + held.joinToString("\n"), held.isEmpty())
    }

    /** Each locked telling is handed its notification built: built under
     *  the lock, its PendingIntents would ask Android's activity manager
     *  there (review rounds 3 and 4). */
    @Test fun eachLockedTellingIsHandedItsNotificationBuilt() {
        val tellings = mapOf(
            UsbDefault::class.java to listOf("postLocked"),
            BatteryHealth::class.java to listOf("postOnce", "repost"),
            FolderAccess::class.java to listOf("tellOnce"),
        )
        val building = tellings.flatMap { (type, names) ->
            names.mapNotNull { name ->
                val methods = methodsNamed(type, name)
                when {
                    methods.isEmpty() -> "${type.simpleName}.$name: not found"
                    methods.any { m -> m.parameterTypes.none { it == android.app.Notification::class.java } } -> "${type.simpleName}.$name"
                    else -> null
                }
            }
        }
        assertTrue("Not handed a built notification:\n" + building.joinToString("\n"), building.isEmpty())
    }

    /** [name]'s JVM methods: a function returning Result (runCatching)
     *  carries a mangled suffix after a dash, an internal one its module's
     *  after a dollar. */
    private fun methodsNamed(type: Class<*>, name: String) =
        type.declaredMethods.filter { it.name == name || it.name.startsWith("$name-") || it.name.startsWith("$name$") }
}
