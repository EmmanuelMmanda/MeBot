package mebot.nguli.com.feed.auth

import kotlinx.serialization.Serializable

/** Presentation only: no OAuth credential can enter Compose state or an export. */
@Serializable
data class ChatGptModel(val slug: String, val displayName: String)

data class ChatGptAccount(
    val id: String,
    val label: String,
    val email: String,
    val connected: Boolean,
    val planEnabled: Boolean,
    val models: List<ChatGptModel> = emptyList(),
    val planNoticeAcknowledged: Boolean = false,
)

data class ChatGptAuthState(
    val accounts: List<ChatGptAccount> = emptyList(),
    val activeAccountId: String? = null,
    val phase: String = "idle",
    val errorCode: String? = null,
    val lastDiagnostic: ChatGptDiagnostic? = null,
)

/** Support metadata only. Never contains a response body, OAuth URL, or personal context. */
data class ChatGptDiagnostic(
    val stage: String,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val responseShape: String? = null,
    val responseMediaType: String? = null,
    val responseStatus: String? = null,
    val providerCode: String? = null,
    val providerParam: String? = null,
)

/** Codes are safe for diagnostics; exception messages never contain provider response bodies. */
class ChatGptAuthException(val code: String, val diagnostic: ChatGptDiagnostic? = null) : Exception(code)

internal object ChatGptEndpoints {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val MODELS = "$RESOURCE/models"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
}

@Serializable
internal data class ChatGptCredentialRecord(
    val id: String,
    val clientId: String,
    val issuer: String = ChatGptEndpoints.ISSUER,
    val subject: String = "",
    val email: String = "",
    val name: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMillis: Long = 0,
    val earliestRefreshAtMillis: Long = 0,
    val scopes: Set<String> = emptySet(),
    val models: List<ChatGptModel> = emptyList(),
    val modelsUpdatedAtMillis: Long = 0,
    val planNoticeAcknowledged: Boolean = false,
) {
    override fun toString() = "ChatGptCredentialRecord([redacted])"
    fun withoutCredentials() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMillis = 0,
        earliestRefreshAtMillis = 0, scopes = emptySet(), models = emptyList(), modelsUpdatedAtMillis = 0)
    fun publicAccount() = ChatGptAccount(
        id = id,
        label = "${email.ifBlank { name.ifBlank { "ChatGPT" } }} · ${id.takeLast(6)}",
        email = email,
        connected = subject.isNotBlank() && accessToken.isNotBlank(),
        planEnabled = ChatGptEndpoints.DIRECT_SCOPE in scopes && "resource.invoke" in scopes,
        models = models,
        planNoticeAcknowledged = planNoticeAcknowledged,
    )
}

@Serializable
internal data class ChatGptPrivateState(
    val version: Int = 1,
    val hostId: String = "",
    val activeAccountId: String? = null,
    val accounts: List<ChatGptCredentialRecord> = emptyList(),
) {
    override fun toString() = "ChatGptPrivateState([redacted])"
}

internal interface ChatGptCredentialStore {
    fun read(): ChatGptPrivateState?
    fun write(state: ChatGptPrivateState)
    fun clear()
}
