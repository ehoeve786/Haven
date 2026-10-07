# Claude connector

Tidegate's MCP endpoint can be added to claude.ai (web, desktop and mobile) as a
custom connector. Tidegate publishes it on the public internet through **Tailscale
Funnel** on its own in-app Tailscale tunnel, so it works on cellular and doesn't
need the Tailscale app or Android's VPN slot.

## Setup

1. **Tailscale admin console**
   - *DNS* → enable **HTTPS Certificates**.
   - *Access controls* → let the Tidegate node use Funnel, for example:
     ```json
     "nodeAttrs": [{ "target": ["autogroup:member"], "attr": ["funnel"] }]
     ```
   - *Settings → Keys* → generate an auth key.
2. **Tidegate** → Tunnels → add a **Tailscale** tunnel with that auth key.
3. **Tidegate** → Settings → MCP → turn on **Claude connector**. Once it's up, the
   row shows the public URL, `https://<node>.<tailnet>.ts.net/mcp`. If it can't
   come up, the row says why.
4. **claude.ai** → Settings → Connectors → *Add custom connector* → paste the
   URL → **Connect**. Tidegate asks you to approve **Claude**. If Tidegate is in the
   background, tap its notification, approve, then press *try again* in the
   browser.

## Security

- Every request needs an access token. Tokens come only from Tidegate's OAuth
  flow, which accepts only Claude's redirect URIs and needs your approval on
  the phone. PKCE (S256) is required.
- The token is an ordinary MCP pairing token for the client **Claude**:
  per-action consent prompts still apply, and un-pairing **Claude** in MCP
  settings revokes it. Connecting again replaces it.
- Tools that need consent prompt on the phone, and fail closed while Tidegate is
  in the background unless that tool's consent level lets it through.
- Turning the toggle off closes the public listener immediately.
