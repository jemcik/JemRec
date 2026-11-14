package com.jemcik.jemrec.contract

import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.system.AutoStart
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every app JemRec looks for is in the manifest's <queries>. Android 11 and
 * later hide any other app from it, and one looked for and hidden reads as
 * not installed: Settings called Telegram from telegram.org not installed
 * (issue #93), its package known to nobody in the manifest.
 */
class PackageQueriesTest {

    @Test
    fun everyAppLookedForIsDeclared() {
        val lookedFor = VoipApps.KNOWN.map { it.pkg } +
            AutoStart.CANDIDATES.map { it.first } +
            FileNames.HONOR_DIALER +
            // VoipApps.supported asks for the shell's permission.
            "com.android.shell"
        assertEquals(emptyList<String>(), lookedFor.filter { it !in declared() })
    }

    private fun declared(): Set<String> {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(Repo.file("app/src/main/AndroidManifest.xml"))
        val queries = document.getElementsByTagName("queries")
        return (0 until queries.length).flatMap { q ->
            val children = queries.item(q).childNodes
            (0 until children.length).map { children.item(it) }
                .filter { it.nodeName == "package" }
                .mapNotNull { it.attributes.getNamedItemNS(ANDROID, "name")?.nodeValue }
        }.toSet()
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
