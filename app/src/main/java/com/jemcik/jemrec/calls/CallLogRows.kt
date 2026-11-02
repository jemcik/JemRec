package com.jemcik.jemrec.calls

import android.content.Context
import android.database.Cursor
import android.provider.CallLog

/**
 * The phone's call log, row by row, for every query JemRec makes of it: each
 * asks with its own columns, selection and order, and the rows come back read
 * one way - phone calls only (CallLogLookup.isPhoneCall), a blank name,
 * number or country as none.
 */
internal object CallLogRows {

    /** What a person sees for a call with neither a name nor a number: a
     *  withheld number is still worth saying so. */
    const val UNKNOWN = "Unknown number"

    /** One row. A column the query did not ask for reads as 0 or none. */
    data class Row(
        val at: Long,
        val seconds: Int,
        val type: Int,
        val number: String?,
        val name: String?,
        val country: String?,
    ) {
        val incoming: Boolean get() = type == CallLog.Calls.INCOMING_TYPE

        /** A name is what a person recognises; the number is the fallback. */
        val label: String get() = name ?: number ?: UNKNOWN
    }

    /** The phone calls among the rows [selection] picks, in [order]. Throws
     *  as the provider does; no cursor is no rows. */
    fun read(
        context: Context,
        columns: Array<String>,
        selection: String?,
        args: Array<String>?,
        order: String?,
    ): List<Row> {
        val out = mutableListOf<Row>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, columns, selection, args, order)?.use { cursor ->
            // A column asked for and not there is the provider's failure.
            fun column(name: String) = if (name in columns) cursor.getColumnIndexOrThrow(name) else -1
            val date = column(CallLog.Calls.DATE)
            val duration = column(CallLog.Calls.DURATION)
            val type = column(CallLog.Calls.TYPE)
            val number = column(CallLog.Calls.NUMBER)
            val name = column(CallLog.Calls.CACHED_NAME)
            val account = column(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
            val country = column(CallLog.Calls.COUNTRY_ISO)
            while (cursor.moveToNext()) {
                if (!CallLogLookup.isPhoneCall(cursor.text(account))) continue
                out += Row(
                    at = if (date < 0) 0L else cursor.getLong(date),
                    seconds = if (duration < 0) 0 else cursor.getInt(duration),
                    type = if (type < 0) 0 else cursor.getInt(type),
                    number = cursor.text(number)?.takeIf { it.isNotBlank() },
                    name = cursor.text(name)?.takeIf { it.isNotBlank() },
                    country = cursor.text(country)?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out
    }

    private fun Cursor.text(column: Int): String? = if (column < 0) null else getString(column)
}
