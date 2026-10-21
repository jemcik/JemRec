package com.jemcik.jemrec.capture

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.jemcik.jemrec.Prefs

/**
 * Whose calls to keep: all of them unless the user narrows it down.
 *
 * Two questions, asked separately because they are separate: which of your
 * CONTACTS ([Contacts]: all, all but some, only some, none), and whether
 * calls with OTHER numbers - not in your contacts, or hidden - are kept.
 * Between them they cover "everyone", "only my contacts", "only strangers",
 * "only these people" and "everyone but these people".
 *
 * AFTER THE CALL, NOT BEFORE
 *
 * The daemon starts recording as a call starts, and at that moment nothing on
 * the phone will tell this app who it is with: the call-state callback gives
 * no number, by design, and only the dialer itself is told. The call log has
 * it - once the call has ended. So every call is recorded, and when it ends
 * RecordingSaver asks the log who it was before saving: a call these rules
 * leave out is deleted from the daemon there and then, and never reaches the
 * recordings folder.
 *
 * KEPT WHEN UNSURE. A call the log does not have, or a phone that no longer
 * lets JemRec read the log, keeps its recording - a recording can be deleted
 * later, a lost one cannot be made again. And a call the user tapped Record
 * on in ask-first mode is kept whatever the rules say: they asked for it.
 *
 * The chosen contacts are stored as the contacts provider's lookup links,
 * which survive a contact being edited, synced or merged; a contact deleted
 * since simply drops out of the list.
 */
object CallRules {

    private const val TAG = "JemRec"

    enum class Contacts { ALL, ALL_BUT, ONLY, NONE }

    private const val KEY_CONTACTS = "rules_contacts"
    private const val KEY_CHOSEN = "rules_chosen"
    private const val KEY_OTHERS = "rules_other_numbers"

    fun contacts(context: Context): Contacts =
        runCatching { Contacts.valueOf(Prefs.of(context).getString(KEY_CONTACTS, null)!!) }
            .getOrDefault(Contacts.ALL)

    fun setContacts(context: Context, contacts: Contacts) {
        Prefs.of(context).edit().putString(KEY_CONTACTS, contacts.name).apply()
    }

    /** Calls with numbers not in the contacts, and hidden ones. */
    fun otherNumbers(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_OTHERS, true)

    fun setOtherNumbers(context: Context, on: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_OTHERS, on).apply()
    }

    /** Whether anything is left out at all. With nothing, a call is saved
     *  without asking the call log first, exactly as before these existed. */
    fun active(context: Context): Boolean = contacts(context) != Contacts.ALL || !otherNumbers(context)

    /**
     * Whether a call with the contact [contactId] is kept - null meaning the
     * number is not in the contacts, or there was no number. Pure.
     */
    internal fun keeps(contacts: Contacts, otherNumbers: Boolean, contactId: Long?, chosen: Set<Long>): Boolean =
        when {
            contactId == null -> otherNumbers
            contacts == Contacts.ALL -> true
            contacts == Contacts.ALL_BUT -> contactId !in chosen
            contacts == Contacts.ONLY -> contactId in chosen
            else -> false
        }

    /**
     * Whether a call with a number belonging to [contactIds] is kept - none
     * meaning a number in no contact, or no number. With several (a shared
     * landline, one person saved twice), kept if any of them is. [chosen]
     * null is the chosen contacts that could not be read: kept, rather than
     * a contact's call left out on a failed lookup. Pure.
     */
    internal fun keeps(contacts: Contacts, otherNumbers: Boolean, contactIds: List<Long>, chosen: Set<Long>?): Boolean =
        when {
            contactIds.isEmpty() -> otherNumbers
            contacts == Contacts.ALL -> true
            contacts == Contacts.NONE -> false
            chosen == null -> true
            else -> contactIds.any { keeps(contacts, otherNumbers, it, chosen) }
        }

    fun keeps(context: Context, contactIds: List<Long>): Boolean {
        val contacts = contacts(context)
        // The chosen ones are only asked for when they matter.
        val chosen = if (contacts == Contacts.ALL_BUT || contacts == Contacts.ONLY) chosenIds(context) else emptySet()
        return keeps(contacts, otherNumbers(context), contactIds, chosen)
    }

    // --- the chosen contacts -----------------------------------------------------

    /** One chosen contact, as Settings lists it. */
    data class Chosen(val key: String, val name: String, val photo: Uri?)

    private fun chosenKeys(context: Context): Set<String> =
        Prefs.of(context).getStringSet(KEY_CHOSEN, emptySet())?.toSet() ?: emptySet()

    /** Add the contact the picker returned. Whether it was added. */
    @Synchronized
    fun add(context: Context, contact: Uri): Boolean {
        val lookup = runCatching {
            ContactsContract.Contacts.getLookupUri(context.contentResolver, contact)
        }.onFailure { Log.w(TAG, "rules: could not read the picked contact", it) }.getOrNull() ?: return false
        Prefs.of(context).edit().putStringSet(KEY_CHOSEN, chosenKeys(context) + lookup.toString()).apply()
        return true
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        Prefs.of(context).edit().putStringSet(KEY_CHOSEN, chosenKeys(context) - key).apply()
    }

    /** The chosen contacts that still exist, by name. Blocking. */
    fun chosen(context: Context): List<Chosen> = chosenKeys(context).mapNotNull { key ->
        runCatching {
            val contact = ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.parse(key))
                ?: return@runCatching null
            context.contentResolver.query(
                contact,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Chosen(
                    key = key,
                    name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "Unnamed contact",
                    photo = cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                )
            }
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    /** The chosen contacts' ids as the provider numbers them now: a merge or
     *  a re-sync can change an id, and the lookup link follows it. A contact
     *  since deleted is simply gone; but a lookup that FAILED makes the whole
     *  set unknown (null) - dropping that contact would leave their calls
     *  out in "only these people". */
    private fun chosenIds(context: Context): Set<Long>? {
        val ids = mutableSetOf<Long>()
        for (key in chosenKeys(context)) {
            val id = runCatching {
                ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.parse(key))
                    ?.let(ContentUris::parseId)
            }.getOrElse { return null }
            if (id != null) ids += id
        }
        return ids
    }
}
