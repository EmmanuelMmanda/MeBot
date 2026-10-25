package mebot.nguli.com.core

import java.net.URI

/** Transport configuration only. Credentials never enter portable state. */
object AiProviders {
    val modes = setOf("direct", "chatgpt", "service", "claude", "gemini", "openrouter", "compatible")
    val alternatives = setOf("claude", "gemini", "openrouter", "compatible")
    fun isAlternative(c: AiConnection) = c.mode in alternatives
    fun supportsWeb(c: AiConnection) = c.webSearch && c.mode in setOf("direct", "chatgpt", "claude", "openrouter")
    fun defaultModel(mode: String) = when (mode) {
        "openrouter" -> "openrouter/auto"
        "direct" -> "gpt-6-luna"
        else -> ""
    }
    fun validate(c: AiConnection) {
        require(c.mode in modes)
        require(c.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")))
        require(!c.model.contains("..") && !c.model.contains("//"))
        require(c.accountId.length <= 200 && c.accountId.none(Char::isISOControl))
        require(c.mode == "chatgpt" || c.accountId.isBlank())
        require(!c.allowLocalHttp || c.mode == "compatible")
        when(c.mode) {
            "compatible" -> require(validEndpoint(c.serviceUrl, c.allowLocalHttp))
            "service" -> require(FeedLogic.safeUrl(c.serviceUrl, true))
            else -> require(c.serviceUrl.isBlank())
        }
    }
    fun validEndpoint(url: String, allowLocalHttp: Boolean): Boolean = runCatching {
        val u=URI(url); val host=u.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        url.length in 8..500 && u.userInfo==null && u.query==null && u.fragment==null &&
            u.port in -1..65535 && u.port != 0 && !host.isBlank() &&
            (u.scheme == "https" || (u.scheme == "http" && allowLocalHttp && isLocalHost(host))) &&
            !u.path.contains("..") && !u.path.contains("\\")
    }.getOrDefault(false)
    /** Literal LAN addresses or localhost only; plaintext public endpoints are rejected. */
    fun isLocalHost(host: String): Boolean {
        if(host in setOf("localhost", "::1")) return true
        val n=host.split('.').map { it.toIntOrNull() ?: return false }
        if(n.size!=4 || n.any { it !in 0..255 }) return false
        return n[0]==127 || n[0]==10 || (n[0]==192 && n[1]==168) || (n[0]==172 && n[1] in 16..31)
    }
    fun baseUrl(c: AiConnection): String = when(c.mode) {
        "claude" -> "https://api.anthropic.com/v1"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
        "openrouter" -> "https://openrouter.ai/api/v1"
        "compatible" -> c.serviceUrl.trimEnd('/')
        else -> throw IllegalArgumentException("Unsupported adapter")
    }
}
