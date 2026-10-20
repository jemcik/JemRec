package com.jemcik.jemrec

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * A notification's way into a particular place in the app: play this
 * recording, share it, run the self-test, pair again, allow this Wi-Fi.
 *
 * GUARDED, BECAUSE THE DOOR IS OPEN
 *
 * These arrive at MainActivity, and MainActivity is exported - it is the
 * launcher's entry point - so any installed app can start it with any extras
 * it likes. Nothing here is as dangerous as recording a call was (see
 * GHSA-hf4v-7hwh-hx7w), but "open the share sheet on this person's call" or
 * "forget the pairing" are not things another app should be able to make
 * JemRec do. So every link carries a per-install secret that only this app's
 * own PendingIntents hold, and a link without it is ignored.
 *
 * Handed to the screen through a flow rather than read from the intent in
 * Compose: the activity sees the intent, the ViewModel acts on it, and a link
 * is consumed once - not again on rotation.
 */
object DeepLink {

    enum class Kind { PLAY, SHARE, SELF_TEST, PAIR_AGAIN, ALLOW_WIFI }

    data class Link(val kind: Kind, val name: String?)

    const val ACTION = "com.jemcik.jemrec.OPEN"
    private const val EXTRA_KIND = "link"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_TOKEN = "link_token"
    private const val KEY_TOKEN = "link_token"
    private val random = SecureRandom()

    private val _pending = MutableStateFlow<Link?>(null)
    val pending: StateFlow<Link?> = _pending.asStateFlow()

    fun intent(context: Context, kind: Kind, name: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_TOKEN, token(context))
            // Into the activity that is already up, if there is one, rather
            // than a second copy stacked on it.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    /** Called by the activity with every intent it is started or re-entered with. */
    fun offer(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val presented = intent.getStringExtra(EXTRA_TOKEN)
        if (presented.isNullOrEmpty() || presented != token(context)) return
        val kind = runCatching { Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }.getOrNull() ?: return
        _pending.value = Link(kind, intent.getStringExtra(EXTRA_NAME))
    }

    /** The screen has acted on it. */
    fun consumed() {
        _pending.value = null
    }

    @Synchronized
    private fun token(context: Context): String {
        val prefs = Prefs.of(context)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val fresh = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }
}
