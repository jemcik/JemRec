package com.jemcik.jemrec.recordings

/**
 * The name a recording goes by in the trash, in the two places that trash by
 * renaming: a chosen folder and app storage. Pure: it is the one thing that
 * says which file is in the trash and when it goes, and an expiry read wrong
 * is a recording deleted early.
 */
internal object TrashNames {

    private const val PREFIX = ".trashed-"

    /**
     * The trashed name of [name], expiring at [expiresAtMillis].
     *
     * With no @ in it. Honor's Phone app plays any file in its folder whose
     * name holds "@<number>_<time>" - tested: a leading dot does not stop
     * it, and nor does anything after the time - so a recording deleted from
     * there (FileNames.Format.HONOR) would stay playable from the call
     * history for its 30 days here. The @ is written %40, and % as %25 so
     * that reads back unchanged; names from before this had neither.
     */
    internal fun nameFor(name: String, expiresAtMillis: Long): String =
        "$PREFIX${expiresAtMillis / 1000}-${name.replace("%", "%25").replace("@", "%40")}"

    /** The original name and expiry of a trashed name, or null if it is not one. */
    internal fun parse(trashedName: String): Pair<String, Long>? {
        if (!trashedName.startsWith(PREFIX)) return null
        val rest = trashedName.removePrefix(PREFIX)
        val seconds = rest.substringBefore('-', "").toLongOrNull() ?: return null
        val name = rest.substringAfter('-', "").takeIf { it.isNotEmpty() } ?: return null
        return unescape(name) to seconds * 1000
    }

    private fun unescape(name: String) = ESCAPED.replace(name) { if (it.value == "%40") "@" else "%" }

    private val ESCAPED = Regex("%40|%25")

    /**
     * Whether a file in the folder the user picked is in the trash: its
     * original name and expiry, the expiry null if nothing says it.
     *
     * Its name says so, as given, on the Android 12 and 13 emulators - and,
     * with no trash in folders before Android 17, on 14 to 16. Android 17
     * has one, and its folder provider reads the name JemRec gives a deleted
     * file as one of its own: ".trashed-1795000000-<name>" is reported as
     * "<name>", the prefix stripped and a %40 left as it is, and marked
     * [restorable] - the Android 17 emulator and the Honor, 2026-10-26
     * (issue #51: deleted recordings stayed in the list). The name as given
     * is still at the end of the document ID there. An ID is the provider's
     * own to shape, so where it does not say, the mark alone still keeps the
     * file out of the list.
     */
    internal fun inFolder(name: String, documentId: String, restorable: Boolean): Pair<String, Long?>? =
        parse(name)
            // "primary:Calls/<name>", or "primary:<name>" at a volume's root.
            ?: parse(documentId.substringAfterLast('/', documentId.substringAfter(':')))
            ?: if (restorable) unescape(name) to null else null
}
