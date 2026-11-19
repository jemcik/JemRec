package com.jemcik.jemrec.recordings

/**
 * The names Honor's Phone app matches to a call in its history
 * (FileNames.Format.HONOR, which writes them): "<name>@<number>_<time>" for
 * a caller with a name, "<number>_<time>" for one without (see
 * FileNames.build for why the second). Pure, and in one place, so the trash
 * (TrashNames) knows them as FileNames writes and reads them.
 */
internal object HonorNames {

    /** "<name>@<number>_<time>.<ext>": the caller, the number, the time. */
    val WITH_NAME = Regex("""^(.*)@(\+?\d+)_(\d{14})\.\w+$""")

    /** "<number>_<time>.<ext>": the number, the time. */
    val NUMBER_ONLY = Regex("""^(\+?\d+)_(\d{14})\.\w+$""")

    /** Whether the Phone app could match [name] to a call - with a
     *  provider's " (1)" after the time too, which it reads past. */
    fun playable(name: String): Boolean = '@' in name || NUMBER_FIRST.containsMatchIn(name)

    private val NUMBER_FIRST = Regex("""^\+?\d+_\d{14}""")
}
