package com.jemcik.jemrec.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The setup decision, against a fake world. Each test is one of the
 * regressions the function's own comments describe, in the order the code
 * checks them; the fake records what was asked so the tests can also say
 * what must NOT happen (a connect attempt with Wireless debugging off, a
 * daemon retired that was ours).
 */
class SetupStepTest {

    private class World(
        var permissionsMissing: Boolean = false,
        var recorderOn: Boolean = true,
        var complete: Boolean = false,
        var identity: Boolean = false,
        var daemon: Boolean = false,
        var connected: Boolean = false,
        var connectSucceeds: Boolean = false,
        var wirelessDebugging: Boolean = false,
        var developerOptions: Boolean = true,
        var paired: Boolean = false,
    ) : Setup.Probe {
        var connectAttempts = 0
        var retired = 0
        var markedComplete = 0

        override fun missingPermissions() = permissionsMissing
        override fun recorderOn() = recorderOn
        override fun isComplete() = complete
        override fun markComplete() { markedComplete++; complete = true }
        override fun hasIdentity() = identity
        override suspend fun daemonRunning() = daemon
        override suspend fun retireDaemon() { retired++; daemon = false }
        override fun adbConnected() = connected
        override suspend fun connect() { connectAttempts++; if (connectSucceeds) connected = true }
        override fun wirelessDebuggingOn() = wirelessDebugging
        override fun developerOptionsOn() = developerOptions
        override fun hasPaired() = paired
    }

    private fun stepOf(world: World): SetupStep = runBlocking { Setup.decide(world) }

    @Test fun permissionsComeBeforeEverythingElse() {
        val world = World(permissionsMissing = true, complete = true, identity = true, daemon = true, connected = true)
        assertEquals(SetupStep.NEEDS_PERMISSIONS, stepOf(world))
        assertEquals(0, world.connectAttempts)
    }

    @Test fun switchedOffIsReadyOnlyOnceSetupHasFinished() {
        assertEquals(SetupStep.READY, stepOf(World(recorderOn = false, complete = true)))
        // A keypair alone used to pass here; it is not proof of setup.
        val neverFinished = World(recorderOn = false, complete = false, identity = true)
        assertNotEquals(SetupStep.READY, stepOf(neverFinished))
    }

    @Test fun aRunningRecorderWithAnIdentityIsReadyAndMigratesTheFlag() {
        val world = World(daemon = true, identity = true, complete = false)
        assertEquals(SetupStep.READY, stepOf(world))
        assertEquals(1, world.markedComplete)
        assertEquals(0, world.connectAttempts)
    }

    @Test fun aRunningRecorderWithNoIdentityIsRetiredAndSetupGoesOn() {
        val world = World(daemon = true, identity = false, developerOptions = true)
        val step = stepOf(world)
        assertEquals(1, world.retired)
        assertNotEquals(SetupStep.READY, step)
        assertEquals(SetupStep.NEEDS_PAIRING, step)
    }

    @Test fun setUpWithTheRecorderDownIsReadyWithoutTouchingAdb() {
        val world = World(identity = true, complete = true, daemon = false, wirelessDebugging = true)
        assertEquals(SetupStep.READY, stepOf(world))
        assertEquals(0, world.connectAttempts)
    }

    @Test fun noConnectIsAttemptedWhileWirelessDebuggingIsOff() {
        val world = World(wirelessDebugging = false, developerOptions = true)
        assertEquals(SetupStep.NEEDS_PAIRING, stepOf(world))
        assertEquals(0, world.connectAttempts)
    }

    @Test fun developerOptionsOffIsItsOwnStepWhateverElseIsTrue() {
        assertEquals(SetupStep.NEEDS_DEVELOPER_OPTIONS, stepOf(World(developerOptions = false)))
        assertEquals(SetupStep.NEEDS_DEVELOPER_OPTIONS, stepOf(World(developerOptions = false, paired = true)))
    }

    @Test fun pairedButUnreachableNeedsAConnectionNotAnotherPairing() {
        val world = World(wirelessDebugging = true, connectSucceeds = false, paired = true)
        assertEquals(SetupStep.NEEDS_CONNECTION, stepOf(world))
        assertEquals(1, world.connectAttempts)
        assertEquals(SetupStep.NEEDS_PAIRING, stepOf(World(wirelessDebugging = true, paired = false)))
    }

    @Test fun aConnectThatLandsFinishesSetup() {
        val world = World(wirelessDebugging = true, connectSucceeds = true)
        assertEquals(SetupStep.FINISHING, stepOf(world))
        assertEquals(1, world.connectAttempts)
        val already = World(connected = true)
        assertEquals(SetupStep.FINISHING, stepOf(already))
        assertEquals(0, already.connectAttempts)
    }
}
