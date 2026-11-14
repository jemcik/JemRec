package com.jemcik.jemrec.diag.ops

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent

// Set one of the app's preferences, for tools/emulator_checks.py:
//   --es op pref --es key file_names --es value DATE_AND_CALLER
//   --es op pref --es key rules_other_numbers --ez bool false
//   --es op pref --es key emergency_call_at --el long 1792680000000
//   --es op pref --es key emergency_call_at   (no value: removed)
// Through the app's own SharedPreferences, because writing the
// file from outside loses to a running app process, which
// writes its own copy back over it (measured on Android 13).
internal object PrefOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val key = intent.getStringExtra("key").orEmpty()
        val edit = com.jemcik.jemrec.Prefs.of(appContext).edit()
        // commit, not apply: the value is on disk before the
        // reply, which the tools act on at once.
        @SuppressLint("ApplySharedPref")
        val text = when {
            key.isEmpty() -> "no key"
            intent.hasExtra("bool") -> {
                edit.putBoolean(key, intent.getBooleanExtra("bool", false)).commit()
                "$key = ${intent.getBooleanExtra("bool", false)}"
            }
            intent.hasExtra("int") -> {
                edit.putInt(key, intent.getIntExtra("int", 0)).commit()
                "$key = ${intent.getIntExtra("int", 0)}"
            }
            intent.hasExtra("long") -> {
                edit.putLong(key, intent.getLongExtra("long", 0L)).commit()
                "$key = ${intent.getLongExtra("long", 0L)}"
            }
            intent.hasExtra("value") -> {
                edit.putString(key, intent.getStringExtra("value")).commit()
                "$key = ${intent.getStringExtra("value")}"
            }
            else -> {
                edit.remove(key).commit()
                "$key removed"
            }
        }
        report(appContext, "pref", text)
    }
}
