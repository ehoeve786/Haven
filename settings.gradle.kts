pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack hosts the only published artifact of adaptech-cz/Tesseract4Android
        // (used by core:scan for on-device OCR). Scoped to that group so the
        // resolver doesn't fall back to JitPack for any other dependency.
        //
        // NOTE for F-Droid: prebuilt AARs from JitPack are tolerated but
        // not preferred. See project_scan_module memory + the open follow-up
        // to build Tesseract4Android from source in the F-Droid recipe
        // (same shape as the rclone-android / IronRDP source builds).
        maven {
            url = uri("https://jitpack.io")
            content {
                // adaptech-cz/Tesseract4Android is a multi-module project,
                // so JitPack publishes children under com.github.adaptech-cz.*
                // as well as the parent group itself.
                includeGroupByRegex("com\\.github\\.adaptech-cz.*")
                // mik3y/usb-serial-for-android — the USB-serial terminal driver
                // (CDC-ACM + CH34x/FTDI/CP21xx). JitPack-only, not on Central.
                includeGroup("com.github.mik3y")
            }
        }
    }
}

// Build termlib from source (submodule fork with popScrollbackLine fix).
// Drop this includeBuild once the fix is merged upstream and released.
includeBuild("termlib") {
    dependencySubstitution {
        substitute(module("org.connectbot:termlib")).using(project(":lib"))
    }
}

// Prns (Personal Reticulum, Rust engine) JVM SDK — upstream's own Gradle
// project inside the submodule, consumed unmodified so the submodule tracks
// pure upstream trunk (Prns#94: app-supplied Pipe transports). The native
// capsule (libprns_host.so) is cross-built by :core:prns's buildPrnsNative,
// not by this included build.
includeBuild("prns/prns-host/bindings/jvm") {
    dependencySubstitution {
        substitute(module("rs.reticulum:personal-rns")).using(project(":"))
    }
}

// Go bridge compiled via gomobile, single libgojni.so containing:
//   - rcbridge: rclone for cloud storage backends
//   - wgbridge: wireguard-go + gVisor netstack for per-app WireGuard (#102)
// Having both in one gomobile build avoids duplicate `go.Seq` runtime
// classes and duplicate `libgojni.so` collisions.
includeBuild("rclone-android") {
    dependencySubstitution {
        substitute(module("sh.haven:rclone-transport")).using(project(":"))
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:terminal-haven")
include(":core:toolbar")
include(":core:ssh")
include(":core:security")
include(":core:data")
include(":core:redact")
include(":core:tunnel")
include(":core:knock")
include(":core:mcp")
include(":core:spa")
include(":core:stepca")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:chat")
include(":feature:keys")
include(":feature:tunnel")
include(":core:btserial")
include(":core:usbserial")
include(":core:bleserial")
include(":core:prns")
include(":core:smb")
include(":core:rclone")
include(":core:openai")
include(":core:fido")
include(":core:usb")
include(":core:local")
include(":core:wayland")
include(":core:scan")

include(":feature:settings")
include(":feature:editor")
include(":feature:imagetools")
