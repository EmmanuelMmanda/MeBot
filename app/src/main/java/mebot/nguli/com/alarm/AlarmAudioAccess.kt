package mebot.nguli.com.alarm

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import mebot.nguli.com.core.AlarmAudioSettings
import mebot.nguli.com.core.AlarmPlan
import org.json.JSONObject
import java.util.UUID

/** Own only the READ grants acquired by the alarm picker, never unrelated app grants.
 * This helper does not open documents: a cloud provider cannot stall audio playback here.
 */
object AlarmAudioAccess {
    private const val PREFERENCES = "mebot_alarm_audio_access_v1"
    private const val OWNED = "owned_read_uris"
    private const val HOLDS = "restore_until"
    internal const val RESTORE_GRACE_MILLIS = 24 * 60 * 60_000L
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val lock = Any()
    private data class Draft(var uri: String, var holders: Int = 0, var until: Long = 0)
    private val drafts = mutableMapOf<String, Draft>()
    private val finishedUris = mutableSetOf<String>()

    /** A stable, rememberSaveable token lets old and new compositions overlap safely. */
    fun beginDraft(uri: String, token: String = UUID.randomUUID().toString()): DraftLease = synchronized(lock) {
        require(token.length in 1..120)
        val value = uri.takeIf(AlarmAudioSettings::isContentUri).orEmpty()
        val draft = drafts.getOrPut(token) { Draft(value) }
        draft.uri = value
        draft.holders++
        draft.until = 0
        finishedUris.remove(value)
        DraftLease(token)
    }

    class DraftLease internal constructor(private val token: String) : AutoCloseable {
        private var closed = false
        fun replace(uri: String) = synchronized(lock) {
            if (!closed) {
                val value = uri.takeIf(AlarmAudioSettings::isContentUri).orEmpty()
                drafts[token]?.uri = value
                finishedUris.remove(value)
            }
        }
        /** Disposal may mean rotation or a temporary picker visit, so cleanup is deferred. */
        override fun close() = synchronized(lock) { finish(explicit = false) }
        /** Use only for an explicit canceled edit or after its durable save succeeds. */
        fun discard() = synchronized(lock) { finish(explicit = true) }
        fun committed() = discard()

        private fun finish(explicit: Boolean) {
            if (closed) return
            closed = true
            val draft = drafts[token] ?: return
            draft.holders = (draft.holders - 1).coerceAtLeast(0)
            if (draft.holders == 0) {
                if (explicit || draft.uri.isEmpty()) {
                    if (draft.uri.isNotEmpty()) finishedUris += draft.uri
                    drafts.remove(token)
                } else draft.until = System.currentTimeMillis() + RESTORE_GRACE_MILLIS
            }
        }
    }

    /** Returns false if the provider cannot grant lasting access; callers retain the old choice. */
    fun take(context: Context, uri: Uri, flags: Int): Boolean = synchronized(lock) {
        val value = uri.toString()
        if (!AlarmAudioSettings.isContentUri(value)) return@synchronized false
        var acquired = false
        try {
            val resolver = context.contentResolver
            val preferences = preferences(context)
            val owned = owned(preferences)
            val holds = holds(preferences)
            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                // Existing READ access might belong to another feature. Do not adopt it.
                if (value in owned) {
                    holds[value] = System.currentTimeMillis() + RESTORE_GRACE_MILLIS
                    check(save(preferences, owned, holds))
                }
                return@synchronized true
            }
            if (flags and READ == 0) return@synchronized false
            resolver.takePersistableUriPermission(uri, READ)
            acquired = true
            if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                runCatching { resolver.releasePersistableUriPermission(uri, READ) }
                return@synchronized false
            }
            owned += value
            holds[value] = System.currentTimeMillis() + RESTORE_GRACE_MILLIS
            if (!save(preferences, owned, holds)) {
                // The new READ grant has no other owner here; do not leave it untracked.
                runCatching { resolver.releasePersistableUriPermission(uri, READ) }
                return@synchronized false
            }
            finishedUris.remove(value)
            true
        } catch (_: Exception) {
            if (acquired) runCatching { context.contentResolver.releasePersistableUriPermission(uri, READ) }
            false
        }
    }

    /** Safe after reconciliation, delete, restore and reset; cleanup never invalidates their success. */
    fun prune(context: Context, plans: List<AlarmPlan>) = pruneAt(context, plans, System.currentTimeMillis())

    internal fun pruneAt(context: Context, plans: List<AlarmPlan>, now: Long) {
        try {
            // Keep the operational snapshot stable until grants have been reconciled.
            synchronized(AlarmController.lock) {
                val store = AlarmStore(context)
                val runtimePlans = listOfNotNull(store.session?.plan) +
                    store.pending.map { it.plan } + store.scheduled.map { it.plan }
                val retained = (plans + runtimePlans).map { it.soundUri }
                    .filter(AlarmAudioSettings::isContentUri).toMutableSet()
                synchronized(lock) {
                    val preferences = preferences(context)
                    val owned = owned(preferences)
                    drafts.entries.removeAll { it.value.holders == 0 && it.value.until <= now }
                    if (owned.isEmpty()) { finishedUris.clear(); return }
                    val holds = holds(preferences)
                    finishedUris.forEach { holds.remove(it) }
                    finishedUris.clear()
                    drafts.values.filter { it.uri.isNotEmpty() }.forEach { draft ->
                        retained += draft.uri
                        // Persist a bounded recovery window for process/activity restoration.
                        val until = if (draft.holders > 0) now + RESTORE_GRACE_MILLIS else draft.until
                        holds[draft.uri] = maxOf(holds[draft.uri] ?: 0, until)
                    }
                    retained += holds.filterValues { it > now }.keys
                    val resolver = context.contentResolver
                    val readable = resolver.persistedUriPermissions.filter { it.isReadPermission }
                        .map { it.uri.toString() }.toSet()
                    owned.toList().forEach { value ->
                        if (value !in readable) owned.remove(value)
                        else if (value !in retained && AlarmAudioSettings.isContentUri(value)) {
                            val released = runCatching { resolver.releasePersistableUriPermission(Uri.parse(value), READ) }.isSuccess
                            if (released && resolver.persistedUriPermissions.none { it.uri.toString() == value && it.isReadPermission }) owned.remove(value)
                        }
                    }
                    save(preferences, owned, holds.filterKeys { it in owned }.toMutableMap())
                }
            }
        } catch (_: Exception) {
            // Failure to inspect storage/provider permissions is a reason to retain access.
        }
    }

    /** Explicit app-data erasure ends abandoned editor holds; then prune with the cleared plans. */
    fun forgetDrafts(context: Context) {
        try {
            synchronized(lock) {
                drafts.clear()
                finishedUris.clear()
                val preferences = preferences(context)
                save(preferences, owned(preferences), mutableMapOf())
            }
        } catch (_: Exception) { }
    }

    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun owned(preferences: SharedPreferences) = preferences.getStringSet(OWNED, emptySet()).orEmpty().toMutableSet()
    private fun holds(preferences: SharedPreferences): MutableMap<String, Long> {
        val json = JSONObject(preferences.getString(HOLDS, "{}") ?: "{}")
        return json.keys().asSequence().associateWith { json.getLong(it) }.toMutableMap()
    }
    private fun save(preferences: SharedPreferences, owned: Set<String>, holds: Map<String, Long>) =
        preferences.edit().putStringSet(OWNED, owned).putString(HOLDS, JSONObject(holds).toString()).commit()
}
