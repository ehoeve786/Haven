package sh.haven.app.agent

import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.mcp.HttpResponse
import sh.haven.core.mcp.ParsedHttpRequest
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal OAuth 2.1 authorization server that lets claude.ai add Haven as a
 * custom connector over Tailscale Funnel.
 *
 * - Only Claude's redirect URIs are accepted ([isAllowedRedirect]), so no
 *   other site can obtain a token even with the public URL.
 * - Approval is Haven's existing on-device pairing prompt ([approve]).
 * - The access token is an ordinary pairing token for [CLIENT_NAME]
 *   ([mintToken]); un-pairing "Claude" in Settings revokes it. It doesn't
 *   expire, so there is no refresh grant.
 */
internal class McpOAuth(
    private val approve: () -> Boolean,
    private val mintToken: () -> String,
) {
    private class Grant(val challenge: String, val redirectUri: String, val expiresAt: Long)

    private val codes = ConcurrentHashMap<String, Grant>()

    /** The OAuth response for [req], or null when [req] isn't an OAuth route. */
    fun handle(req: ParsedHttpRequest, base: String): HttpResponse? {
        val path = req.path.substringBefore('?')
        return when {
            req.method != "GET" && req.method != "POST" -> null
            path.startsWith("/.well-known/oauth-protected-resource") -> json(
                200,
                JSONObject().put("resource", "$base/mcp").put("authorization_servers", JSONArray().put(base)),
            )
            path == "/.well-known/oauth-authorization-server" -> json(
                200,
                JSONObject()
                    .put("issuer", base)
                    .put("authorization_endpoint", "$base/authorize")
                    .put("token_endpoint", "$base/token")
                    .put("registration_endpoint", "$base/register")
                    .put("response_types_supported", JSONArray().put("code"))
                    .put("grant_types_supported", JSONArray().put("authorization_code"))
                    .put("code_challenge_methods_supported", JSONArray().put("S256"))
                    .put("token_endpoint_auth_methods_supported", JSONArray().put("none")),
            )
            req.method == "POST" && path == "/register" -> register(req.body)
            req.method == "GET" && path == "/authorize" -> authorize(req.path.substringAfter('?', ""))
            req.method == "POST" && path == "/token" -> token(params(req.body))
            else -> null
        }
    }

    private fun register(body: String): HttpResponse {
        val uris = runCatching { JSONObject(body).getJSONArray("redirect_uris") }.getOrNull()
            ?: return error(400, "invalid_client_metadata")
        for (i in 0 until uris.length()) {
            if (!isAllowedRedirect(uris.optString(i))) return error(400, "invalid_redirect_uri")
        }
        return json(
            201,
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("redirect_uris", uris)
                .put("token_endpoint_auth_method", "none")
                .put("grant_types", JSONArray().put("authorization_code"))
                .put("response_types", JSONArray().put("code")),
        )
    }

    private fun authorize(query: String): HttpResponse {
        val p = params(query)
        val redirect = p["redirect_uri"].orEmpty()
        val challenge = p["code_challenge"].orEmpty()
        // Never redirect to an unvalidated URI: a bad request is a plain 400.
        if (!isAllowedRedirect(redirect) || p["response_type"] != "code" ||
            challenge.isEmpty() || p["code_challenge_method"] != "S256"
        ) {
            return HttpResponse(400, "Bad Request", "Invalid authorization request".toByteArray(), "text/plain")
        }
        val state = p["state"]?.let { "&state=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val sep = if ('?' in redirect) '&' else '?'
        if (!approve()) {
            // Haven was in the background (it posted a notification) or the
            // prompt was declined. Let the user approve in Haven and retry.
            val retry = "/authorize?$query".replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            val html = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
                "<p>Open Haven on your phone and approve <b>Claude</b>, then " +
                "<a href=\"$retry\">try again</a>.</p>"
            return HttpResponse(200, "OK", html.toByteArray(), "text/html; charset=utf-8")
        }
        val code = random()
        codes[code] = Grant(challenge, redirect, System.currentTimeMillis() + CODE_TTL_MS)
        return HttpResponse(302, "Found", ByteArray(0), headers = listOf("Location" to "$redirect${sep}code=$code$state"))
    }

    private fun token(p: Map<String, String>): HttpResponse {
        if (p["grant_type"] != "authorization_code") return error(400, "unsupported_grant_type")
        val grant = p["code"]?.let { codes.remove(it) }
        val verifier = p["code_verifier"].orEmpty()
        if (grant == null || grant.expiresAt < System.currentTimeMillis() ||
            grant.redirectUri != p["redirect_uri"] || s256(verifier) != grant.challenge
        ) {
            return error(400, "invalid_grant")
        }
        return json(200, JSONObject().put("access_token", mintToken()).put("token_type", "Bearer"))
    }

    companion object {
        const val CLIENT_NAME = "Claude"
        private const val CLIENT_ID = "claude"
        private const val CODE_TTL_MS = 5 * 60_000L
        private val LOOPBACK = Regex("""http://(localhost|127\.0\.0\.1)(:\d+)?/callback""")

        /** claude.ai's hosted callback, or Claude Code's loopback one. */
        fun isAllowedRedirect(uri: String): Boolean =
            uri == "https://claude.ai/api/mcp/auth_callback" || LOOPBACK.matches(uri)

        fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        /** 401 that points Claude at [base]'s protected-resource metadata. */
        fun unauthorized(base: String) = HttpResponse(
            401, "Unauthorized", ByteArray(0),
            headers = listOf("WWW-Authenticate" to "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource\""),
        )

        private fun params(s: String): Map<String, String> = s.split('&').mapNotNull {
            val k = it.substringBefore('=')
            if (k.isEmpty()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.toMap()

        private fun random(): String = ByteArray(32).let {
            SecureRandom().nextBytes(it)
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

        private fun json(status: Int, body: JSONObject) = HttpResponse(
            status, if (status == 201) "Created" else "OK", body.toString().toByteArray(),
            "application/json", listOf("Cache-Control" to "no-store"),
        )

        private fun error(status: Int, code: String) = HttpResponse(
            status, "Bad Request", JSONObject().put("error", code).toString().toByteArray(),
            "application/json", listOf("Cache-Control" to "no-store"),
        )
    }
}
