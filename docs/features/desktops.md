---
layout: default
title: Desktops
---

# Desktops

On-device desktops: a GPU-accelerated native Wayland compositor that runs
inside Tidegate, and a
multi-distro manager for full Linux desktops on the phone. For the local *shell*
side of the on-device distros, see [Local Linux](local-linux.md).

## Native Wayland Desktop

GPU-accelerated Wayland compositor (labwc) running natively inside Haven. Full interactive terminal with keyboard input, mouse interaction, server-side window decorations, pinch-to-zoom, and fullscreen mode with corner overlay menu. The GPU pipeline renders via GLES2 on the device's GPU (AHardwareBuffer allocator, ASurfaceControl zero-copy presentation). Native Wayland clients can render 3D content — includes a built-in GLES2 benchmark (rotating lit cube at 60fps on Mali-G715). **Display-scale / resolution control** — pick the compositor's output resolution from the toolbar or fullscreen menu; it reflows windows at the new resolution rather than just visually zooming. Configurable shell (/bin/sh, bash, zsh, fish) and shared keyboard toolbar (Esc, Tab, Ctrl, Alt, arrows, function keys, plus a Super/Mod4 key for compositor keybinds). External Wayland clients can connect via Shizuku (symlinks the socket to `/data/local/tmp/haven-wayland/`). No root required — runs in PRoot with an Alpine Linux rootfs.

## GPU-accelerated Linux GL apps

A single Linux GUI app can run in a **cage** (single-app kiosk compositor) with the device GPU passed through — no `/dev/dri`, no root. A host-side [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer) broker hands the **Mali** GPU to the guest:

- **virgl** (default) translates the guest's OpenGL onto the host GPU — OpenGL 2.1, on by default for cage apps (`GL_RENDERER = virgl (Mali-G715)`).
- **venus + zink** (experimental, toggled in Settings) routes the guest's Vulkan straight to the GPU and runs **zink** on top for modern OpenGL (~3.2 core), for apps that need newer GL than virgl exposes. Needs `mesa-vulkan-drivers` in the guest; off falls back to virgl. Verified on Mali-G715 with vkcube and a geometry-animating GL demo (the clip on the [landing page](../index.md)) running accelerated.

## Local Desktops (multi-distro manager)

A **Desktop → Manage** view installs and runs full Linux desktops on-device via PRoot, with no root. Pick a distro — **Alpine** (APK), **Debian 12** (APT), **Arch Linux ARM** (PACMAN), or **Void** (XBPS) — and install them side-by-side; each carries its native package manager. For each installed distro you can install, start, and stop desktop environments, open a shell into it, and read a Room-backed install log that names the layer that broke if a package install fails.

The native labwc and Xwayland desktops run here.

---

[← All features](../FEATURES.md)
