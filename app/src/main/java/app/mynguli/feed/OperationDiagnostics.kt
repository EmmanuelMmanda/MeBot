package app.mynguli.feed

import android.util.Log
import java.util.UUID

data class OperationDiagnostic(
    val operation: String,
    val code: String,
    val moduleId: String? = null,
    val correlationId: String = UUID.randomUUID().toString().take(8),
    val atMillis: Long = System.currentTimeMillis(),
)

/** Local diagnostic metadata only: never exception messages, URLs, text or credentials. */
internal fun operationDiagnostic(operation: String, error: Exception, moduleId: String? = null): OperationDiagnostic {
    val allowedOperation = operation.takeIf { it in setOf("discovery", "source", "inference", "media", "conversation", "context_import", "backup_import", "attachment") } ?: "inference"
    val candidate = (error as? FeedFailure)?.code ?: if (error is IllegalArgumentException) "invalid_input" else "provider_error"
    val code = candidate.takeIf { it.matches(Regex("[a-z_]{1,48}")) } ?: "provider_error"
    val diagnostic = OperationDiagnostic(allowedOperation, code, moduleId)
    val frames = error.stackTrace.take(5).joinToString(" > ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    Log.w("MeBot", "operation=$allowedOperation code=$code correlation=${diagnostic.correlationId} type=${error.javaClass.simpleName} frames=$frames")
    return diagnostic
}
