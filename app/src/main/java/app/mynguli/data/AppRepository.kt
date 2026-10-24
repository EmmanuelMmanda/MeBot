package app.mynguli.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import app.mynguli.core.AppState
import app.mynguli.core.BackupCodec
import app.mynguli.core.StateValidation
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppRepository(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val store: DataStore<AppState> = DataStoreFactory.create(
        serializer = StateSerializer,
        scope = scope,
        produceFile = { context.applicationContext.filesDir.resolve("mynguli/state.json") }
    )
    val state: Flow<AppState> = store.data

    suspend fun current(): AppState = store.data.first()
    suspend fun update(transform: (AppState) -> AppState): AppState = store.updateData(transform)
    suspend fun export(): String = BackupCodec.encode(current())
    suspend fun import(text: String): AppState {
        val decoded = BackupCodec.decode(text)
        // Imported alarms remain disabled until the owner reviews this device's setup.
        val reviewed = decoded.copy(alarms = decoded.alarms.map { it.copy(enabled = false) }, focus = null)
        return store.updateData { reviewed }
    }
    suspend fun erase(): AppState = store.updateData { AppState() }
}

private object StateSerializer : Serializer<AppState> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue = AppState()
    override suspend fun readFrom(input: InputStream): AppState {
        // Fail visibly on corruption rather than silently replacing personal records.
        val text = input.readBytes().toString(Charsets.UTF_8)
        return StateValidation.validate(json.decodeFromString<AppState>(text))
    }
    override suspend fun writeTo(t: AppState, output: OutputStream) {
        output.write(json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}
