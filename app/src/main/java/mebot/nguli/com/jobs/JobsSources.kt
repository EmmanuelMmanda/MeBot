package mebot.nguli.com.jobs

import kotlinx.serialization.json.*
import mebot.nguli.com.feed.FeedHttp

private val JobsSourceJson = Json { ignoreUnknownKeys = true }

/** Returns source-reported availability, NOT independent employer verification.
 * Missing/invalid rows fail the pass; disappearance from the 7-day feed never proves closure.
 * The caller must apply the persisted source polling window and preserve saved applications.
 */
internal suspend fun fetchJobicyStatuses(http: FeedHttp, jobIds: List<String>): Map<String, String> {
    val sourceIds = jobIds.filter { it.startsWith("jobicy:") }.distinct()
    require(sourceIds.size <= 2000) { "Too many status IDs" }
    sourceIds.forEach { require(validJobicyId(it.removePrefix("jobicy:"))) { "Invalid Jobicy ID" } }
    val result = linkedMapOf<String, String>()
    for (batch in sourceIds.chunked(100)) {
        val rawIds = batch.map { it.removePrefix("jobicy:") }
        val response = http.request("https://jobicy.com/api/jobs/status?ids=" + rawIds.joinToString(","))
        result.putAll(parseJobicyStatuses(response, rawIds.toSet()))
    }
    return result
}

private fun validJobicyId(value: String) = value.matches(Regex("[1-9][0-9]{0,18}")) && value.toLongOrNull() != null

internal fun parseJobicyStatuses(raw: String, requestedIds: Set<String>): Map<String, String> {
    require(requestedIds.size in 1..100 && requestedIds.all(::validJobicyId))
    val root = JobsSourceJson.parseToJsonElement(raw).jsonObject
    require(root["success"]?.jsonPrimitive?.booleanOrNull != false)
    val rows = root["jobs"] as? JsonArray ?: error("Invalid status response")
    require(rows.size == requestedIds.size) { "Incomplete status response" }
    val result = linkedMapOf<String, String>()
    for (element in rows) {
        val row = element.jsonObject
        val id = row["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing status ID")
        val status = row["status"]?.jsonPrimitive?.contentOrNull ?: error("Missing status")
        require(id in requestedIds && status in setOf("active", "closed", "unknown"))
        require(result.put("jobicy:$id", status) == null) { "Duplicate status ID" }
    }
    return result
}
