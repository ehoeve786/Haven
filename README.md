<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="80" alt="Tidegate SSH icon" />
</p>

<h1 align="center">Tidegate SSH</h1>

<p align="center">
  Free, open-source remote access &amp; mobile workspace for Android —<br/>
  SSH · Mosh · SFTP · SMB · email · cloud storage, a local Linux shell, mesh networking, and a consent-gated AI-agent endpoint
</p>

<p align="center">
  <a href="https://github.com/ehoeve786/Tidegate/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://github.com/ehoeve786/Tidegate/actions/workflows/ci.yml/badge.svg?branch=main" alt="Build" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

<p align="center">
  <img src="docs/haven-transparency.webp" width="300" alt="Terminal transparency over a live device wallpaper" />
</p>

---

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="140" />
  &nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="140" />
</p>

---

## At a glance

- **[Terminal](docs/features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux-aware session restore, configurable keyboard toolbar, OSC 7/8/9/52/133/777 integration.
- **[Desktops](docs/features/desktops.md)** — a GPU-accelerated native Wayland compositor and a multi-distro local-desktop manager.
- **[Files & cloud](docs/features/files-and-cloud.md)** — unified browser for SFTP/SCP, SMB, and 60+ cloud providers; cross-filesystem copy/move, editor and image tools; plus on-device FFmpeg transcode, HLS streaming, and DLNA.
- **[Connections](docs/features/connections.md)** — port forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard & Tailscale tunnels, port knocking and fwknop SPA, and SSH keys (incl. FIDO2/SK).
- **[Email](docs/features/email.md)** — ProtonMail (bridge protocol) and any IMAP/SMTP mailbox; compose / reply / forward, multi-account, attachments; plus **Mail Rules** inbound automation.
- **[AI chat](docs/features/chat.md)** — chat with self-hosted or API models (OpenAI-compatible, Ollama, Anthropic, Gemini) over per-profile tunnel routing; image attach for vision models, clipboard copy/paste both ways, opt-in encrypted saved transcripts.
- **[Local Linux](docs/features/local-linux.md)** — a Linux userland via PRoot (no root, any Android 8+ device): Alpine, Debian, Arch, or Void, side-by-side.
- **[USB forwarding](docs/features/usb.md)** — broker an attached USB device through Android and re-expose it to the agent, into the Linux guest, or over USB/IP to a **remote host** (e.g. a phone-hosted YubiKey, touch on the phone).
- **[Reticulum](docs/features/reticulum.md)** — rnsh shell, file transfer, and `-L`/`-D` port forwarding over Reticulum mesh, pure Kotlin. The one transport that keeps working with no internet at all.
- **[Agent transport (MCP)](docs/mcp-tools.md)** — an optional MCP server exposing ~130 consent-gated, audited tools; the agent can even **see and operate Tidegate itself** for a self-hosting build → install → verify loop. It can also be added to claude.ai as a [custom connector](docs/features/claude-connector.md) over Tailscale Funnel.
- **[Security](docs/features/security.md)** — biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

See [docs/FEATURES.md](docs/FEATURES.md) for the full feature index.

## Why one app

The list above is the parts; the point is how they compose. Each of these is one
flow inside Tidegate — no second app, no `curl | ssh` incantation:

- Tap a 4K MKV in Google Drive → FFmpeg transcodes it over HTTP and the result
  lands back in the same Drive folder, never touching local disk.
- Cut a log directory from an S3 bucket, switch tabs, paste it onto an SFTP
  server — rclone does the server-side copy when it can, otherwise Tidegate streams
  it through.
- Run your agent CLI in the on-device Linux shell; it pushes over the SSH agent
  you forwarded from your laptop while you watch on the same screen.
- Cast a cloud video to the TV across the room over HLS, copy the LAN URL from
  the snackbar, send it to a friend so they can watch too.

The phone is the thin client, Tidegate is the thin-client OS, and the cloud, your
servers, and your agents are the computer. Width is sufficient; composition is
the point. ([Vision](VISION.md).)

## Languages

Available in 12 languages: English, Chinese (simplified), Spanish, Hindi, Arabic (with RTL support), Portuguese, Bengali, Russian, Japanese, Korean, French, and German. The UI follows the device language.

**Want to help translate?** See [Languages & translation](docs/features/i18n.md).

## Install

Tidegate SSH isn't published to an app store. Build it from source (below) or grab an APK from [GitHub Releases](https://github.com/ehoeve786/Tidegate/releases) when one is published.

It installs as `app.tidegate`, alongside (not over) upstream Haven. To move your data over, export an encrypted backup in Haven (**Settings → Backup**) and restore it in Tidegate SSH.

## Build from source

Requires [Rust](https://rustup.rs/) with Android targets, `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, and `gomobile`:

```bash
# Rust (native libraries)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for rclone cloud storage)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/ehoeve786/Tidegate.git
cd Tidegate
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/haven-*-debug.apk`

## Documentation

- [Features](docs/FEATURES.md) — detailed feature descriptions.
- [Backup file format](docs/backup-format.md) — wire format, the
  PBKDF2/AES-GCM envelope, and a Python recipe for manual decryption
  if the in-app importer fails.
- [Release process](RELEASE.md) — versioning and tagging (inherited from Haven).
- [Privacy policy](PRIVACY_POLICY.md).
- [Vision](VISION.md).

## Third-party libraries

| Library | Purpose | License |
|---------|---------|---------|
| [rclone](https://rclone.org) | Cloud storage engine (60+ providers) | MIT |
| [JSch](https://github.com/mwiede/jsch) | SSH/SFTP protocol | BSD |
| [smbj](https://github.com/hierynomus/smbj) | SMB/CIFS protocol | Apache-2.0 |
| [ConnectBot termlib](https://github.com/connectbot/connectbot) | Terminal emulator | Apache-2.0 |
| [reticulum-kt](https://github.com/GlassOnTin/reticulum-kt) | Reticulum mesh network transport (Kotlin) | MPL-2.0 |
| [rnsh-kt](https://github.com/GlassOnTin/rnsh-kt) | Reticulum remote shell client (Kotlin) | AGPL-3.0 |
| [FFmpeg](https://ffmpeg.org) | Media conversion and streaming | LGPL-2.1 / GPL-2.0 |
| [PRoot](https://proot-me.github.io) | Local Linux shell (userspace chroot) | GPL-2.0 |
| [labwc](https://labwc.github.io) | Wayland compositor (native desktop) | GPL-2.0 |
| [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) | Wayland compositor library | MIT |
| [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer) | GPU virtualization (OpenGL passthrough to PRoot apps) | MIT |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI toolkit | Apache-2.0 |

## Credits

Tidegate SSH is a fork of [Haven](https://github.com/GlassHaven/Haven) by
GlassOnTin, trimmed down to SSH, SFTP and tunnels, with a claude.ai
connector added. The heavy lifting was done upstream and in the projects
listed above. To support the original author, see
[Ko-fi](https://ko-fi.com/glassontin) or [Liberapay](https://liberapay.com/GlassOnTin).

## License

[AGPLv3](LICENSE)
