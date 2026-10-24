package app.mynguli.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Parses and validates a complete candidate before callers replace any persisted data. */
object BackupCodec {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun encode(state: AppState): String {
        StateValidation.validate(state)
        return json.encodeToString(state).also(::checkSize)
    }

    fun decode(text: String): AppState {
        checkSize(text)
        try {
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("A MeBot backup must contain an object.")
            val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("This file does not identify a MeBot backup version.")
            require(version == AppLimits.SCHEMA_VERSION) {
                "Unsupported backup version $version. This app supports version ${AppLimits.SCHEMA_VERSION}."
            }
            return StateValidation.validate(json.decodeFromString<AppState>(text))
        } catch (error: SerializationException) {
            throw IllegalArgumentException("This file is not a valid MeBot backup.", error)
        }
    }

    private fun checkSize(text: String) {
        require(text.length <= AppLimits.BACKUP_BYTES && text.toByteArray(Charsets.UTF_8).size <= AppLimits.BACKUP_BYTES) {
            "The backup is too large. The limit is ${AppLimits.BACKUP_BYTES / 1024 / 1024} MiB."
        }
    }
}
