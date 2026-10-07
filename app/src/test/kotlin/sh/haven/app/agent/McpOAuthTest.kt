package sh.haven.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.mcp.ParsedHttpRequest
import java.net.URLEncoder

class McpOAuthTest {
    private val base = "https://haven.example.ts.net"
    private val claude = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "dBjftJeZ4CVP-mJ92K27uhbUJU1p1r_wW1gFWFOEjXk"

    private fun get(path: String) = ParsedHttpRequest("GET", path, emptyMap(), "")
    private fun post(path: String, body: String) = ParsedHttpRequest("POST", path, emptyMap(), body)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun body(r: sh.haven.core.mcp.HttpResponse) = JSONObject(String(r.body))

    private fun authorizePath(redirect: String = claude) =
        "/authorize?response_type=code&client_id=claude&redirect_uri=${enc(redirect)}" +
            "&code_challenge=${McpOAuth.s256(verifier)}&code_challenge_method=S256&state=xyz"

    /** Runs authorize and returns the issued code. */
    private fun code(oauth: McpOAuth): String {
        val r = oauth.handle(get(authorizePath()), base)!!
        assertEquals(302, r.status)
        val loc = r.headers.single { it.first == "Location" }.second
        assertTrue(loc.startsWith("$claude?code="))
        assertTrue(loc.endsWith("&state=xyz"))
        return loc.substringAfter("code=").substringBefore('&')
    }

    @Test
    fun `discovery metadata points at this server`() {
        val oauth = McpOAuth({ true }, { "t" })
        assertEquals("$base/mcp", body(oauth.handle(get("/.well-known/oauth-protected-resource/mcp"), base)!!).getString("resource"))
        val asm = body(oauth.handle(get("/.well-known/oauth-authorization-server"), base)!!)
        assertEquals("$base/token", asm.getString("token_endpoint"))
        assertEquals("S256", asm.getJSONArray("code_challenge_methods_supported").getString(0))
        assertNull(oauth.handle(post("/mcp", "{}"), base))
    }

    @Test
    fun `only Claude redirect URIs are accepted`() {
        assertTrue(McpOAuth.isAllowedRedirect(claude))
        assertTrue(McpOAuth.isAllowedRedirect("http://localhost:3118/callback"))
        assertTrue(McpOAuth.isAllowedRedirect("http://127.0.0.1/callback"))
        assertFalse(McpOAuth.isAllowedRedirect("https://evil.example/api/mcp/auth_callback"))
        assertFalse(McpOAuth.isAllowedRedirect("http://localhost.evil.example/callback"))
        val oauth = McpOAuth({ true }, { "t" })
        assertEquals(400, oauth.handle(post("/register", """{"redirect_uris":["https://evil.example/cb"]}"""), base)!!.status)
        assertEquals(201, oauth.handle(post("/register", """{"redirect_uris":["$claude"]}"""), base)!!.status)
        assertEquals(400, oauth.handle(get(authorizePath("https://evil.example/cb")), base)!!.status)
    }

    @Test
    fun `code exchanges once with the right verifier`() {
        var minted = 0
        val oauth = McpOAuth({ true }, { minted++; "token-$minted" })
        val code = code(oauth)
        val form = "grant_type=authorization_code&code=$code&redirect_uri=${enc(claude)}&code_verifier=$verifier"
        val r = oauth.handle(post("/token", form), base)!!
        assertEquals(200, r.status)
        assertEquals("token-1", body(r).getString("access_token"))
        // Codes are single-use.
        assertEquals(400, oauth.handle(post("/token", form), base)!!.status)
        assertEquals(1, minted)
    }

    @Test
    fun `wrong verifier gets no token`() {
        val oauth = McpOAuth({ true }, { error("must not mint") })
        val code = code(oauth)
        val r = oauth.handle(post("/token", "grant_type=authorization_code&code=$code&redirect_uri=${enc(claude)}&code_verifier=nope"), base)!!
        assertEquals("invalid_grant", body(r).getString("error"))
    }

    @Test
    fun `declined approval issues no code`() {
        val r = McpOAuth({ false }, { error("must not mint") }).handle(get(authorizePath()), base)!!
        assertEquals(200, r.status)
        assertTrue(r.headers.none { it.first == "Location" })
    }
}
