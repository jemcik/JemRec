package com.jemcik.jemrec.calls

import android.net.Uri
import android.telephony.PhoneNumberUtils
import java.util.Locale

/**
 * Phone numbers as JemRec reads and writes them, each way once. Four ideas
 * of "the same number" live side by side, each for its own question: a
 * name's [digits], exact; Telecom's and the call log's spellings, by
 * [sameNumber]'s last seven; the contacts provider's own matching
 * (CallLogLookup.contactFor); and Android's own emergency list
 * ([emergencyDigits], EmergencyPause).
 */
internal object PhoneNumbers {

    /** The number of a call Telecom has - its handle's - or null. */
    fun of(handle: Uri?): String? = handle?.schemeSpecificPart

    /**
     * A number with its country code, +380501234567, as the number format
     * writes it. The call log has a number as it was dialled (Android:
     * CallLog.Calls.NUMBER, "as the user entered it"), and an incoming one as
     * the network sent it - all 60 incoming calls on the Honor began with +,
     * 2026-11-01 - so one person can be 0501234567 one way and +380501234567
     * the other. [country] reads the first: the call log keeps one with each
     * call (UA on all 178 of the Honor's). Null where Android does not take
     * it for a valid number - a short code like 477, no country to read a
     * local number in, or a range newer than the phone's own copy of the
     * numbering plan - and the name then has its [digits].
     */
    fun international(number: String?, country: String?): String? {
        if (number.isNullOrBlank()) return null
        return runCatching { PhoneNumberUtils.formatNumberToE164(number, country?.uppercase(Locale.ROOT)) }.getOrNull()
    }

    /**
     * The number a call was dialled to, as a name may carry it. What follows a
     * pause (,) or a wait (;) is not the number but an extension, dialled once
     * the call is up - the network part ends there, as
     * PhoneNumberUtils.extractNetworkPortion has it - and "With the caller"
     * named 0501234567,123 as 0501234567123 (FileNamesTest). Caller ID
     * hidden or shown for one call is dialled in front of the number, #31# or
     * *31# (3GPP TS 22.030): the number is what follows. Any other * or #
     * makes it a code - *86 - whose digits were never a number (Android gives
     * it no country code either): null rather than those.
     */
    fun dialled(number: String?): String? =
        number?.substringBefore(PhoneNumberUtils.PAUSE)?.substringBefore(PhoneNumberUtils.WAIT)?.trim()
            ?.removePrefix("#31#")?.removePrefix("*31#")?.takeIf { '*' !in it && '#' !in it }

    /** A number as it goes into a name: digits and a leading +, nothing else. */
    fun digits(number: String?): String? {
        val n = number?.trim() ?: return null
        val kept = n.filter { it.isDigit() }
        if (kept.isEmpty()) return null
        return if (n.startsWith("+")) "+$kept" else kept
    }

    /**
     * Whether two ways of writing a number are the same number - Telecom's
     * "+13034997111" and the call log's "3034997111", say. Loosely, the way
     * Android matches numbers to contacts: the digits alone, and the last
     * seven of them when both have that many.
     */
    fun sameNumber(a: String?, b: String?): Boolean {
        val x = a?.filter { it.isDigit() }.orEmpty()
        val y = b?.filter { it.isDigit() }.orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.length < MIN_MATCH || y.length < MIN_MATCH) return x == y
        return x.takeLast(MIN_MATCH) == y.takeLast(MIN_MATCH)
    }

    /** Android's own loose match for numbers looks at the last seven digits. */
    private const val MIN_MATCH = 7

    /** A number as Android's emergency list is asked about it: its digits and any +. */
    fun emergencyDigits(number: String): String = number.filter { it.isDigit() || it == '+' }
}
