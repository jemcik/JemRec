package com.jemcik.jemrec

import kotlin.coroutines.cancellation.CancellationException

/**
 * A suspend call's failure caught, but never its cancellation: that is the
 * coroutine being stopped - the service going, the work stopped - and is
 * thrown on, not logged as a failure or carried on from.
 */
internal fun <T> Result<T>.rethrowingCancellation(): Result<T> = onFailure { if (it is CancellationException) throw it }
