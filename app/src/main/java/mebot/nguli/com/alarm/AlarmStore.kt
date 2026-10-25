package mebot.nguli.com.alarm

import android.content.Context
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.MathChallenge
import mebot.nguli.com.core.MathDifficulty
import mebot.nguli.com.core.MathQuestion
import mebot.nguli.com.core.MathQuestions
import mebot.nguli.com.core.StateValidation
import mebot.nguli.com.core.effectiveDismissMode
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduledAlarm(
    val token: String,
    val atMillis: Long,
    val plan: AlarmPlan,
    val isTest: Boolean = false,
)

/** Only operational alarm data is mirrored into device-protected storage. */
internal class AlarmStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("mebot_alarm_v1", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) { write("language", value.takeIf { it in setOf("en", "sw", "system") } ?: "en") }

    var plans: List<AlarmPlan>
        get() = readArray("plans").objects().mapNotNull { runCatching { planFromJson(it) }.getOrNull() }
        set(value) { write("plans", JSONArray().apply { value.forEach { put(planToJson(it)) } }.toString()) }

    var scheduled: List<ScheduledAlarm>
        get() = readArray("scheduled").objects().mapNotNull { item ->
            runCatching { ScheduledAlarm(item.getString("token"), item.getLong("at"), planFromJson(item.getJSONObject("plan")), item.optBoolean("test")) }.getOrNull()
        }
        set(value) {
            write("scheduled", JSONArray().apply {
                value.forEach { put(JSONObject().put("token", it.token).put("at", it.atMillis).put("plan", planToJson(it.plan)).put("test", it.isTest)) }
            }.toString())
        }

    var pending: List<ScheduledAlarm>
        get() = readArray("pending").objects().mapNotNull { item ->
            runCatching { ScheduledAlarm(item.getString("token"), item.getLong("at"), planFromJson(item.getJSONObject("plan")), item.optBoolean("test")) }.getOrNull()
        }
        set(value) {
            write("pending", JSONArray().apply {
                value.take(20).forEach { put(JSONObject().put("token", it.token).put("at", it.atMillis).put("plan", planToJson(it.plan)).put("test", it.isTest)) }
            }.toString())
        }

    var session: AlarmSession?
        get() = synchronized(AlarmController.lock) {
            val raw = prefs.getString("session", null) ?: return@synchronized null
            val parsed = runCatching { sessionFromJson(JSONObject(raw)) }.getOrNull() ?: return@synchronized null
            if (parsed.plan.effectiveDismissMode != AlarmDismissMode.MATH || parsed.mathChallenge != null) return@synchronized parsed
            // A damaged challenge must not erase an otherwise valid ringing/snoozed occurrence.
            // Persist its replacement once, so subsequent UI/service reads use the same IDs.
            val recovered = parsed.copy(mathChallenge = MathQuestions.create(parsed.plan.mathDifficulty, parsed.plan.mathQuestionCount))
            write("session", sessionToJson(recovered).toString())
            log("math_challenge_recovered", parsed.plan.id)
            recovered
        }
        set(value) {
            synchronized(AlarmController.lock) {
                if (value == null) remove("session") else write("session", sessionToJson(value).toString())
            }
        }

    var consumed: Map<String, String>
        get() = runCatching {
            val value = JSONObject(prefs.getString("consumed", "{}") ?: "{}")
            value.keys().asSequence().associateWith { value.getString(it) }
        }.getOrDefault(emptyMap())
        set(value) { write("consumed", JSONObject(value).toString()) }

    var originalVolume: Int?
        get() = if (prefs.contains("original_volume")) prefs.getInt("original_volume", 0) else null
        set(value) {
            if (value == null) remove("original_volume") else check(prefs.edit().putInt("original_volume", value).commit()) { "Could not save alarm audio state" }
        }

    var appliedVolume: Int?
        get() = if (prefs.contains("applied_volume")) prefs.getInt("applied_volume", 0) else null
        set(value) {
            if (value == null) remove("applied_volume") else check(prefs.edit().putInt("applied_volume", value).commit()) { "Could not save alarm audio state" }
        }

    fun events(): List<AlarmEvent> = readArray("events").objects().mapNotNull { item ->
        runCatching { AlarmEvent(item.getLong("at"), item.getString("kind"), item.getString("alarm"), item.optString("detail")) }.getOrNull()
    }

    fun log(kind: String, alarmId: String, detail: String = "") {
        val next = events().takeLast(199) + AlarmEvent(System.currentTimeMillis(), kind.take(64), alarmId.take(160), detail.take(300))
        write("events", JSONArray().apply { next.forEach { put(JSONObject().put("at", it.atMillis).put("kind", it.kind).put("alarm", it.alarmId).put("detail", it.detail)) } }.toString())
    }

    fun clear() { check(prefs.edit().clear().commit()) { "Could not clear alarm state" } }

    private fun readArray(key: String): JSONArray = runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrElse { JSONArray() }
    private fun write(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) { "Could not persist alarm state" } }
    private fun remove(key: String) { check(prefs.edit().remove(key).commit()) { "Could not update alarm state" } }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun planToJson(plan: AlarmPlan): JSONObject = JSONObject()
    .put("id", plan.id).put("label", plan.label).put("hour", plan.hour).put("minute", plan.minute)
    .put("days", JSONArray(plan.days.sorted())).put("enabled", plan.enabled)
    .put("snoozeMinutes", plan.snoozeMinutes).put("maxSnoozes", plan.maxSnoozes)
    .put("requireCheckpoint", plan.requireCheckpoint).put("checkpointCode", plan.checkpointCode)
    .put("checkpointLabel", plan.checkpointLabel).put("followUpMinutes", plan.followUpMinutes)
    .put("volumePercent", plan.volumePercent).put("rampSeconds", plan.rampSeconds).put("vibrate", plan.vibrate)
    .put("dismissMode", plan.dismissMode.name).put("mathDifficulty", plan.mathDifficulty.name).put("mathQuestionCount", plan.mathQuestionCount)
    .put("soundId", plan.soundId).put("soundUri", plan.soundUri).put("soundName", plan.soundName)
    .put("speechEnabled", plan.speechEnabled).put("speechMessage", plan.speechMessage)

internal fun planFromJson(json: JSONObject): AlarmPlan = AlarmPlan(
    id = json.getString("id"), label = json.optString("label"), hour = json.getInt("hour"), minute = json.getInt("minute"),
    days = json.getJSONArray("days").let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() },
    enabled = json.optBoolean("enabled", true), snoozeMinutes = json.optInt("snoozeMinutes", 3), maxSnoozes = json.optInt("maxSnoozes", 1),
    requireCheckpoint = json.optBoolean("requireCheckpoint"), checkpointCode = json.optString("checkpointCode"),
    checkpointLabel = json.optString("checkpointLabel"), followUpMinutes = json.optInt("followUpMinutes", 3),
    volumePercent = json.optInt("volumePercent", 80), rampSeconds = json.optInt("rampSeconds", 20), vibrate = json.optBoolean("vibrate", true),
    dismissMode = AlarmDismissMode.valueOf(json.optString("dismissMode", AlarmDismissMode.STANDARD.name)),
    mathDifficulty = MathDifficulty.valueOf(json.optString("mathDifficulty", MathDifficulty.EASY.name)),
    mathQuestionCount = json.optInt("mathQuestionCount", 3),
    soundId = json.optString("soundId", mebot.nguli.com.core.AlarmAudioSettings.LEGACY_SOUND),
    soundUri = json.optString("soundUri"), soundName = json.optString("soundName"),
    speechEnabled = json.optBoolean("speechEnabled"), speechMessage = json.optString("speechMessage"),
)

internal fun sessionToJson(session: AlarmSession): JSONObject = JSONObject()
    .put("id", session.id).put("plan", planToJson(session.plan)).put("occurrence", session.occurrenceId)
    .put("startedAt", session.startedAtMillis).put("phase", session.phase.name).put("snoozes", session.snoozesUsed)
    .put("walkUsed", session.walkUsed).put("stage", session.verificationStage)
    .put("deadline", session.deadlineAtMillis ?: JSONObject.NULL)
    .put("elapsedDeadline", session.deadlineElapsedMillis ?: JSONObject.NULL).put("boot", session.bootCount)
    .put("isTest", session.isTest)
    .put("mathChallenge", session.mathChallenge?.let { challenge ->
        JSONObject().put("answered", challenge.answered).put("questions", JSONArray().apply {
            challenge.questions.forEach { question ->
                put(JSONObject().put("id", question.id).put("expression", question.expression).put("answer", question.answer))
            }
        })
    } ?: JSONObject.NULL)

internal fun sessionFromJson(json: JSONObject): AlarmSession {
    val plan = planFromJson(json.getJSONObject("plan")).also(StateValidation::alarm)
    return AlarmSession(
        id = json.getString("id"), plan = plan, occurrenceId = json.getString("occurrence"),
        startedAtMillis = json.getLong("startedAt"), phase = AlarmPhase.valueOf(json.getString("phase")),
        snoozesUsed = json.optInt("snoozes"), walkUsed = json.optBoolean("walkUsed"), verificationStage = json.optInt("stage"),
        deadlineAtMillis = if (json.isNull("deadline")) null else json.getLong("deadline"),
        deadlineElapsedMillis = if (json.isNull("elapsedDeadline")) null else json.getLong("elapsedDeadline"), bootCount = json.optInt("boot", -1),
        isTest = json.optBoolean("isTest"),
        mathChallenge = if (plan.effectiveDismissMode == AlarmDismissMode.MATH) decodeMathChallenge(json.optJSONObject("mathChallenge"), plan.mathQuestionCount) else null,
    )
}

/** Challenge corruption is recoverable independently from the session's scheduling envelope. */
private fun decodeMathChallenge(challenge: JSONObject?, expectedCount: Int): MathChallenge? = runCatching {
    challenge?.let {
        val array = it.getJSONArray("questions")
        require(array.length() == expectedCount && expectedCount in 1..5)
        MathChallenge(
            questions = (0 until array.length()).map { index ->
                val question = array.getJSONObject(index)
                val id = question.get("id")
                val expression = question.get("expression")
                require(id is String && expression is String)
                MathQuestion(id, expression, question.strictInt("answer"))
            },
            answered = it.strictInt("answered"),
        ).also {
            require(it.answered in 0 until it.questions.size)
            require(it.questions.map(MathQuestion::id).distinct().size == it.questions.size)
            require(it.questions.all(MathQuestions::isValid))
        }
    }
}.getOrNull()

private fun JSONObject.strictInt(name: String): Int {
    val value = get(name)
    require(value is Int || value is Long)
    val number = (value as Number).toLong()
    require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return number.toInt()
}
