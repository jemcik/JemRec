package com.jemcik.jemrec.contract

import androidx.work.ListenableWorker
import com.jemcik.jemrec.capture.CallMonitorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The names something outside the APK remembers, unchanged.
 *
 * A rename compiles and passes every other test, and breaks a phone that has
 * kept the old name: WorkManager stores each worker's class name and the
 * periodic run is enqueued with KEEP, so old work is never replaced; the
 * recorder starts `.capture.CallMonitorService` by name, with action and extra
 * names of its own; the Quick Settings tile and the notification listener are
 * granted by component; the tools start `.MainActivity` and the debug
 * receiver by name. The 1.0 restructuring moves classes between packages, and
 * these are the ones that must not move.
 */
class FrozenNamesTest {

    @Test
    fun theManifestDeclaresTheSameComponentsUnderTheSameNames() {
        assertEquals(MAIN_COMPONENTS, components("app/src/main/AndroidManifest.xml"))
        assertEquals(DEBUG_COMPONENTS, components("app/src/debug/AndroidManifest.xml"))
    }

    @Test
    fun everyDeclaredComponentIsAClassThatExists() {
        (MAIN_COMPONENTS + DEBUG_COMPONENTS).forEach { Class.forName(fullName(it)) }
    }

    @Test
    fun theWorkersKeepTheClassNamesWorkManagerStored() {
        WORKERS.forEach {
            assertTrue("$it is not a worker", ListenableWorker::class.java.isAssignableFrom(Class.forName(it)))
        }
    }

    @Test
    fun theRecorderKeepsTheClassNameItIsStartedAndKilledBy() {
        // The app starts it by this name and kills a wedged one by it - an old
        // one too, still running from before an update, which a newer app
        // could not find under a new name - and the tools look for it by it.
        val main = Repo.text("shellserver/src/com/jemcik/jemrec/shell/Main.java")
        assertTrue("package com.jemcik.jemrec.shell;" in main)
        assertTrue("public final class Main " in main)
        assertTrue("const val MAIN_CLASS = \"com.jemcik.jemrec.shell.Main\"" in Repo.app)
        assertTrue("pkill -f '[c]om.jemcik.jemrec.shell.Main'" in Repo.app)
    }

    @Test
    fun theRecorderStartsTheServiceTheAppDeclaresWithTheActionsItHandles() {
        fun constant(name: String) =
            checkNotNull(Regex("""static final String $name = "([^"]*)";""").find(Repo.recorder)) { "$name is gone from the recorder" }
                .groupValues[1]

        assertEquals(APPLICATION_ID, constant("APP_PACKAGE"))
        assertEquals("$APPLICATION_ID/.capture.CallMonitorService", constant("APP_SERVICE"))
        assertTrue(".capture.CallMonitorService" in MAIN_COMPONENTS)
        assertTrue("applicationId = \"$APPLICATION_ID\"" in Repo.text("app/build.gradle.kts"))

        mapOf(
            "ACTION_CALL_STARTED" to CallMonitorService.ACTION_CALL_STARTED,
            "ACTION_CALL_ENDED" to CallMonitorService.ACTION_CALL_ENDED,
            "ACTION_CABLE" to CallMonitorService.ACTION_CABLE,
            "ACTION_BACKGROUND_KILLED" to CallMonitorService.ACTION_BACKGROUND_KILLED,
            "ACTION_MESSAGE_STARTED" to CallMonitorService.ACTION_MESSAGE_STARTED,
            "ACTION_MESSAGE_TAKEN" to CallMonitorService.ACTION_MESSAGE_TAKEN,
        ).forEach { (name, appSide) -> assertEquals(name, appSide, constant(name)) }
    }

    @Test
    fun theRecorderSendsTheExtrasTheServiceReads() {
        listOf(
            "\"--es\", \"token\"" to CallMonitorService.EXTRA_TOKEN,
            "\"--es\", \"recfile\"" to CallMonitorService.EXTRA_RECFILE,
            "\"--ez\", \"incoming\"" to CallMonitorService.EXTRA_INCOMING,
            "command.add(\"app\")" to CallMonitorService.EXTRA_APP,
            "\"--es\", \"cable\"" to CallMonitorService.EXTRA_CABLE,
            "\"--ez\", \"test\"" to CallMonitorService.EXTRA_TEST,
            "\"--el\", \"started\"" to CallMonitorService.EXTRA_STARTED,
        ).forEach { (sent, read) ->
            assertTrue("the recorder no longer sends $sent", sent in Repo.recorder)
            assertTrue("the service reads \"$read\", the recorder sends $sent", "\"$read\"" in sent)
        }
    }

    @Test
    fun everyComponentTheToolsNameIsDeclared() {
        val named = Repo.file("tools").listFiles { f -> f.extension == "py" }!!
            .flatMap { Regex("""(?:\{PKG}|com\.jemcik\.jemrec)/(\.[\w.]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue("the tools name nothing: is the pattern stale?", named.isNotEmpty())
        assertEquals(emptySet<String>(), named - MAIN_COMPONENTS - DEBUG_COMPONENTS)
    }

    private fun components(path: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(Repo.file(path))
        return listOf("application", "activity", "activity-alias", "service", "receiver", "provider")
            .flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map { nodes.item(it).attributes.getNamedItemNS(ANDROID, "name")?.nodeValue }
            }
            .filterNotNull()
            .toSet()
    }

    private fun fullName(name: String) = if (name.startsWith(".")) APPLICATION_ID + name else name

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val APPLICATION_ID = "com.jemcik.jemrec"

        val MAIN_COMPONENTS = setOf(
            ".JemRecApp",
            ".MainActivity",
            ".RecordPromptActivity",
            ".TurnOffQuestionActivity",
            ".capture.AfterCallReceiver",
            ".capture.BootReceiver",
            ".capture.CallMonitorService",
            ".capture.CallTracker",
            ".capture.DebugNotificationCleaner",
            ".capture.NetworkReviveReceiver",
            ".capture.PlaybackService",
            ".ui.PairingService",
            ".ui.RecorderTile",
            "androidx.core.content.FileProvider",
        )

        val DEBUG_COMPONENTS = setOf(
            ".diag.DemoProvider",
            ".diag.FakeAppCallService",
            ".diag.SpikeReceiver",
        )

        val WORKERS = listOf(
            "com.jemcik.jemrec.capture.RecorderKeepAlive",
            "com.jemcik.jemrec.capture.UsbDebuggingWatch",
        )
    }
}
