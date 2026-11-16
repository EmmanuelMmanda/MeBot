package mebot.nguli.com.jobs

import android.content.Context
import android.text.Html
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mebot.nguli.com.core.FeedLogic
import mebot.nguli.com.core.jobs.*
import mebot.nguli.com.feed.FeedHttp
import mebot.nguli.com.feed.SecureFeedHttp
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Independent on-device store; no CV, location or search terms are sent to the public source. */
class JobsRepository(context: Context, private val http: FeedHttp = SecureFeedHttp(maxBytes = 8 * 1024 * 1024),
    private val consistencyRepository: (() -> mebot.nguli.com.feed.FeedRepository)? = null,
    private val isOnline: () -> Boolean = {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }) {
    private val mutex = Mutex()
    private val mutationMutex = Mutex()
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val store = DataStoreFactory.create(serializer = JobsSerializer,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.applicationContext.filesDir.resolve("mebot/jobs.json") })
    private suspend fun <T> consistent(action: suspend () -> T): T {
        val coordinator = consistencyRepository?.invoke()
        return if (coordinator != null) coordinator.withJobsConsistency(action) else action()
    }
    // Re-read under the shared journal gate: an earlier queued emission must not expose
    // a Jobs snapshot from before an interrupted cross-store restore.
    val state = store.data.map { consistent { store.data.first() } }.distinctUntilChanged()
    suspend fun current() = consistent { store.data.first() }
    suspend fun erase(): JobsState = consistent { mutationMutex.withLock {
        epoch.incrementAndGet()
        store.updateData { JobsState() }
    } }
    suspend fun update(transform: (JobsState) -> JobsState) = consistent { mutationMutex.withLock { store.updateData(transform) } }
    /** Called only while FeedRepository owns its consistency gate. Never acquire it again. */
    suspend fun restoreFromJournal(value: JobsState): JobsState = mutationMutex.withLock {
        epoch.incrementAndGet()
        store.updateData { JobsBackup.restore(value) }
    }
    suspend fun preferences(value: JobPreferences) = update { it.copy(preferences = value) }
    suspend fun save(id: String) = setSaved(id, current().applications.none { it.jobId == id })
    suspend fun setSaved(id: String, desired: Boolean) = update { s ->
        val existing = s.applications.find { it.jobId == id }
        if (!desired && existing != null && existing.status == "saved" && existing.notes.isBlank() && existing.draft.isBlank())
            s.copy(applications = s.applications.filterNot { it.jobId == id })
        else if (!desired || existing != null) s
        else s.copy(applications = s.applications + JobApplication(id, snapshot = s.jobs.find { it.id == id }, updatedAt = System.currentTimeMillis()))
    }
    suspend fun application(value: JobApplication) = update { s ->
        val old = s.applications.find { it.jobId == value.jobId }
        val now = System.currentTimeMillis()
        val next = value.copy(updatedAt = now, draftUpdatedAt = if (value.draft != old?.draft) now else old?.draftUpdatedAt ?: now, snapshot = value.snapshot ?: old?.snapshot ?: s.jobs.find { it.id == value.jobId })
        s.copy(applications = s.applications.filterNot { it.jobId == value.jobId } + next)
    }
    /** Remotive allows a few requests/day; manual refresh does not bypass the persisted limit. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        val token = epoch.get()
        val before = current()
        if (before.lastAttemptAt > 0 && now - before.lastAttemptAt < 6 * 60 * 60 * 1000L) return@withLock false
        // A disconnected device has not contacted a provider and must not consume its refresh window.
        if (!isOnline()) throw mebot.nguli.com.feed.FeedFailure("offline")
        update { it.copy(lastAttemptAt = now) }
        val failed = mutableListOf<String>()
        val fetched = mutableListOf<JobPosting>()
        suspend fun source(name: String, fetch: suspend () -> List<JobPosting>) {
            try { fetched += fetch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed += name; fetched += before.jobs.filter { it.source == name } }
        }
        source("Remotive") { parseRemotiveJobs(http.request("https://remotive.com/api/remote-jobs"), now) }
        source("Jobicy") {
            val collected = mutableListOf<JobPosting>()
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            repeat(10) {
                val url = "https://jobicy.com/api/jobs?count=100" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
                val page = parseJobicyJobs(http.request(url), now)
                collected += page.first
                cursor = page.second
                if (cursor == null) return@source collected.distinctBy { it.id }
                require(seenCursors.add(cursor!!)) { "Repeated source cursor" }
            }
            // Do not silently replace a complete cache with a truncated provider response.
            error("Job source exceeded bounded page limit")
        }
        update { if (epoch.get() == token) it.copy(jobs = fetched.distinctBy { it.id },
            lastRefreshAt = if (failed.size < 2) now else it.lastRefreshAt, failedSources = failed) else it }
        if (failed.size == 2) throw mebot.nguli.com.feed.FeedFailure("provider_error")
        true
    }
}

internal fun parseRemotiveJobs(raw: String, now: Long): List<JobPosting> {
    val root = JobsJson.parseToJsonElement(raw).jsonObject
    val entries = root["jobs"] as? JsonArray ?: error("Invalid job response")
    val parsed = entries.mapNotNull { element ->
        runCatching {
            val job = element.jsonObject
            fun text(key: String) = job[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = text("url")
            val title = text("title")
            val id = text("id")
            if (id.isBlank() || title.isBlank() || !FeedLogic.safeUrl(url, true)) return@mapNotNull null
            val description = Html.fromHtml(text("description"), Html.FROM_HTML_MODE_LEGACY).toString().trim().take(60_000)
            JobPosting(id = "remotive:$id", title = title.take(500), company = text("company_name").take(500),
                location = text("candidate_required_location").take(500), remote = true,
                employmentType = text("job_type"), description = description, url = url, source = "Remotive",
                postedAt = runCatching { LocalDateTime.parse(text("publication_date")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0),
                fetchedAt = now, salary = text("salary").take(500),
                tags = (job["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.take(30).orEmpty())
        }.getOrNull()
    }.distinctBy { it.id }.take(2000)
    require(entries.isEmpty() || parsed.isNotEmpty()) { "Invalid job rows" }
    return parsed
}
internal fun parseJobicyJobs(raw: String, now: Long): Pair<List<JobPosting>, String?> {
    val root = JobsJson.parseToJsonElement(raw).jsonObject
    require(root["success"]?.jsonPrimitive?.booleanOrNull != false)
    val entries = root["jobs"] as? JsonArray ?: error("Invalid job response")
    val jobs = entries.mapNotNull { element -> runCatching {
        val row = element.jsonObject
        fun text(key: String) = row[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun list(key: String) = (row[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val id = text("id"); val title = text("jobTitle"); val url = text("url")
        if (id.isBlank() || title.isBlank() || !FeedLogic.safeUrl(url, true)) return@mapNotNull null
        JobPosting(id = "jobicy:$id", title = title.take(500), company = text("companyName").take(500),
            location = text("jobGeo").take(500), remote = true, employmentType = list("jobType").firstOrNull().orEmpty(),
            description = Html.fromHtml(text("jobDescription"), Html.FROM_HTML_MODE_LEGACY).toString().trim().take(60000),
            url = url, source = "Jobicy", fetchedAt = now,
            postedAt = runCatching { java.time.OffsetDateTime.parse(text("pubDate")).toInstant().toEpochMilli() }.getOrDefault(0),
            salary = listOf(text("salaryMin"), text("salaryMax"), text("salaryCurrency"), text("salaryPeriod")).filter { it.isNotBlank() }.joinToString(" "),
            tags = list("jobIndustry"),
            salaryMin = text("salaryMin").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            salaryMax = text("salaryMax").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            salaryCurrency = text("salaryCurrency"), salaryPeriod = text("salaryPeriod"))
    }.getOrNull() }.distinctBy { it.id }
    require(entries.isEmpty() || jobs.isNotEmpty())
    val next = if (root["hasMore"]?.jsonPrimitive?.booleanOrNull == true)
        root["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Missing cursor") else null
    return jobs to next
}

private val JobsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private object JobsSerializer : Serializer<JobsState> {
    override val defaultValue = JobsState()
    override suspend fun readFrom(input: InputStream): JobsState {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(buffer.size() + count <= 16 * 1024 * 1024)
            buffer.write(chunk, 0, count)
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= 16 * 1024 * 1024)
        return JobsJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }
    override suspend fun writeTo(t: JobsState, output: OutputStream) {
        val bytes = JobsJson.encodeToString(t).toByteArray()
        require(bytes.size <= 16 * 1024 * 1024)
        output.write(bytes)
    }
}
