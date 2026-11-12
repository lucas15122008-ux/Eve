package app.eve.assistente.mercadolivre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Onde o login fica guardado (no celular). */
interface AuthStore {
    /** De qual servidor são os dados abaixo (ex.: https://jr-conector-ml...workers.dev). */
    var server: String?
    var clientId: String?
    var accessToken: String?
    var refreshToken: String?
    /** Quando o token vence (milissegundos), ou 0 se não se sabe. */
    var expiresAt: Long
    var pendingState: String?
    var pendingVerifier: String?
}

/**
 * Login da Eve no servidor MCP (OAuth 2.1 com PKCE), o mesmo tipo de login que o Claude faz:
 * 1. [startLogin] registra a Eve no servidor (uma vez) e devolve o endereço para abrir no navegador.
 * 2. Você entra pelo navegador, e ele volta para a Eve com um código.
 * 3. [finishLogin] troca o código pelo "crachá" (token), que fica guardado no celular.
 * Depois disso, [token] entrega o crachá e renova sozinho quando vence.
 */
class McpAuth(
    private val mcpUrl: String,
    private val store: AuthStore,
    private val redirectUri: String = REDIRECT_URI,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** https://servidor (sem o caminho), onde ficam as informações de login. */
    val origin: String = URI(mcpUrl.trim()).let { u ->
        "${u.scheme}://${u.host}" + if (u.port != -1) ":${u.port}" else ""
    }

    val isLoggedIn: Boolean get() = store.server == origin && (store.accessToken != null || store.refreshToken != null)

    suspend fun startLogin(): String = withContext(Dispatchers.IO) {
        val meta = discover()
        if (store.server != origin || store.clientId == null) {
            clearAll()
            store.clientId = register(meta)
            store.server = origin
        }
        val verifier = randomToken(48)
        val state = randomToken(18)
        store.pendingVerifier = verifier
        store.pendingState = state
        meta.authorize + (if ('?' in meta.authorize) "&" else "?") + form(
            "response_type" to "code",
            "client_id" to store.clientId!!,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to mcpUrl.trim(),
        )
    }

    suspend fun finishLogin(code: String?, state: String?, error: String?) = withContext(Dispatchers.IO) {
        if (error != null) throw McpException("O login não foi concluído ($error). Tente entrar de novo.")
        val verifier = store.pendingVerifier
        if (code.isNullOrBlank() || state == null || state != store.pendingState || verifier == null) {
            throw McpException("O login voltou incompleto. Tente entrar de novo.")
        }
        val meta = discover()
        val json = postForm(
            meta.token,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to store.clientId.orEmpty(),
            "code_verifier" to verifier,
            "resource" to mcpUrl.trim(),
        ) ?: throw McpException("O servidor recusou o login. Tente entrar de novo.")
        lock.withLock { saveTokens(json) }
        store.pendingState = null
        store.pendingVerifier = null
    }

    /**
     * O token para usar agora. [rejected] = o token que o servidor acabou de recusar: aí renova.
     * Devolve null se a Eve não está logada (ou a renovação não deu certo).
     */
    suspend fun token(rejected: String? = null): String? = lock.withLock {
        if (store.server != origin) return@withLock null
        val current = store.accessToken
        val expired = store.expiresAt > 0 && store.expiresAt <= now() + 60_000
        if (current != null && current != rejected && !expired) return@withLock current
        val refresh = store.refreshToken ?: return@withLock null
        val json = withContext(Dispatchers.IO) {
            postForm(
                discover().token,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to store.clientId.orEmpty(),
                "resource" to mcpUrl.trim(),
            )
        }
        if (json == null) {
            // O servidor não aceitou mais: precisa entrar de novo.
            store.accessToken = null
            store.refreshToken = null
            return@withLock null
        }
        saveTokens(json)
        store.accessToken
    }

    fun logout() = clearAll()

    private fun clearAll() {
        store.server = null
        store.clientId = null
        store.accessToken = null
        store.refreshToken = null
        store.expiresAt = 0
        store.pendingState = null
        store.pendingVerifier = null
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.optString("access_token").ifBlank { null }
            ?: throw McpException("O servidor não devolveu o acesso. Tente entrar de novo.")
        store.accessToken = access
        json.optString("refresh_token").ifBlank { null }?.let { store.refreshToken = it }
        val expiresIn = json.optLong("expires_in", 0)
        store.expiresAt = if (expiresIn > 0) now() + expiresIn * 1000 else 0
    }

    private class Endpoints(val authorize: String, val token: String, val register: String?)

    private fun discover(): Endpoints {
        val (code, text) = get("$origin/.well-known/oauth-authorization-server")
        val json = if (code in 200..299) runCatching { JSONObject(text) }.getOrNull() else null
        // Sem as informações publicadas, usa os caminhos padrão do MCP.
        return Endpoints(
            authorize = json?.optString("authorization_endpoint")?.ifBlank { null } ?: "$origin/authorize",
            token = json?.optString("token_endpoint")?.ifBlank { null } ?: "$origin/token",
            register = json?.optString("registration_endpoint")?.ifBlank { null } ?: "$origin/register",
        )
    }

    private fun register(meta: Endpoints): String {
        val url = meta.register ?: throw McpException("O servidor não deixa a Eve se registrar para o login.")
        val body = JSONObject()
            .put("client_name", "Eve")
            .put("redirect_uris", JSONArray().put(redirectUri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        val conn = connect(url, "POST", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val id = runCatching { JSONObject(text).optString("client_id") }.getOrNull()
            if (code !in 200..299 || id.isNullOrBlank()) {
                throw McpException("O servidor não aceitou registrar a Eve para o login (erro $code).")
            }
            return id
        } finally {
            conn.disconnect()
        }
    }

    /** POST de formulário; devolve o JSON de resposta, ou null se o servidor recusou (400/401). */
    private fun postForm(url: String, vararg fields: Pair<String, String>): JSONObject? {
        val conn = connect(url, "POST", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(form(*fields).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 400 || code == 401) return null
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw McpException("O servidor do Mercado Livre deu erro no login ($code).")
            return runCatching { JSONObject(text) }.getOrNull()
                ?: throw McpException("O servidor respondeu o login de um jeito que eu não entendi.")
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = connect(url, "GET", null)
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(url: String, method: String, contentType: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (contentType != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "$contentType; charset=utf-8")
        }
        return conn
    }

    companion object {
        /** Para onde o navegador volta depois do login (o app abre esse endereço). */
        const val REDIRECT_URI = "app.eve.assistente://oauth"

        private val random = SecureRandom()

        fun randomToken(bytes: Int): String {
            val b = ByteArray(bytes)
            random.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun form(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
    }
}
