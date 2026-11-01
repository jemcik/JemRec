package com.jemcik.jemrec.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CallLog
import org.robolectric.Robolectric
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's call log, for Robolectric: a provider on the call log's own
 * authority over an in-memory SQLite table with the real column names. Each
 * query's selection, arguments and order run as SQL, as they do against the
 * real provider, so a type filter is tested as written, not as imitated here.
 * The type column is an INTEGER, as the real one is: the queries pass the
 * types as strings, and SQLite compares them as numbers only because of that.
 *
 * [hold] makes the next query wait until [release]: a save that reads the log
 * can then be stopped half way at a known point, with no change to the app.
 */
class FakeCallLog : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    @Volatile private var gate: CountDownLatch? = null
    @Volatile private var entered: CountDownLatch? = null

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE calls (_id INTEGER PRIMARY KEY, " +
                "${CallLog.Calls.DATE} INTEGER, " +
                "${CallLog.Calls.DURATION} INTEGER, " +
                "${CallLog.Calls.TYPE} INTEGER NOT NULL, " +
                "${CallLog.Calls.NUMBER} TEXT, " +
                "${CallLog.Calls.CACHED_NAME} TEXT, " +
                "${CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME} TEXT, " +
                "${CallLog.Calls.COUNTRY_ISO} TEXT)",
        )
        return true
    }

    /** One row of the log, a phone call's unless [account] says otherwise. */
    fun add(
        at: Long,
        type: Int,
        seconds: Int = 0,
        number: String? = null,
        name: String? = null,
        account: String? = TELEPHONY,
        country: String? = null,
    ) {
        db.insert(
            "calls", null,
            ContentValues().apply {
                put(CallLog.Calls.DATE, at)
                put(CallLog.Calls.DURATION, seconds)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.CACHED_NAME, name)
                put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, account)
                put(CallLog.Calls.COUNTRY_ISO, country)
            },
        )
    }

    /** The next query waits for [release]; [awaitHeld] says when one does. */
    fun hold() {
        entered = CountDownLatch(1)
        gate = CountDownLatch(1)
    }

    fun awaitHeld() {
        check(entered!!.await(WAIT_S, TimeUnit.SECONDS)) { "nothing read the call log" }
    }

    fun release() {
        gate?.countDown()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        gate?.let { held ->
            entered?.countDown()
            held.await(WAIT_S, TimeUnit.SECONDS)
        }
        return db.query("calls", projection, selection, selectionArgs, null, null, sortOrder)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Telephony's own account, as the Honor's call log has it (2026-10-26). */
        const val TELEPHONY = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"

        /** Google Meet's, which logs its calls with the other person's number. */
        const val MEET =
            "com.google.android.apps.tachyon/com.google.android.apps.tachyon.telecom.TachyonTelecomConnectionService"

        /** A vendor's own type for an answered call: not one of Android's. */
        const val VENDOR_TYPE = 100

        private const val WAIT_S = 10L

        fun install(): FakeCallLog = Robolectric.setupContentProvider(FakeCallLog::class.java, CallLog.AUTHORITY)
    }
}
