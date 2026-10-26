package mebot.nguli.com.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI presence only, never credentials. A startup read cannot overwrite a later durable mutation. */
internal class CredentialPresence {
    private val lock = Any()
    private var epoch = 0L
    private val mutable = MutableStateFlow(false)
    val state = mutable.asStateFlow()

    fun snapshotEpoch(): Long = synchronized(lock) { epoch }
    fun publishSnapshot(expectedEpoch: Long, present: Boolean) = synchronized(lock) {
        if (epoch == expectedEpoch) mutable.value = present
    }
    fun definitive(present: Boolean) = synchronized(lock) {
        epoch++ // Bump even when the Boolean is unchanged (e.g. disconnect before initial read returns).
        mutable.value = present
    }
}
