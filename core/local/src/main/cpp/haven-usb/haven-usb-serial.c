/*
 * haven-usb-serial — bridge a brokered CDC-ACM USB device to a kernel PTY so
 * UNMODIFIED serial apps (LIRC's lircd/mode2, etc.) inside the proot guest can
 * use it with no kernel cdc_acm driver and no LD_PRELOAD.
 *
 * This is the serial counterpart to the HID/hidraw personality of
 * libhaven_usb.so: the USB broker's "file descriptor bridged into the runtime"
 * (VISION.md), extended from HID to CDC-ACM. Where the hidraw shim fakes a
 * /dev/hidraw node in-process via LD_PRELOAD, a serial port is better served by
 * a real kernel pty: forking daemons, libc/Java serial libraries, real termios
 * and poll() all then work unchanged.
 *
 * It connects TWICE to Haven's USB proxy on the abstract socket "\0haven-usb"
 * (one connection per direction — the proxy multiplexes both onto the same
 * UsbDeviceConnection), allocates a pty, prints the slave path, and pumps bytes
 * full-duplex:
 *   writer thread (main): read(pty master) -> BULK OUT to the device
 *   reader thread:        BULK IN from the device -> write(pty master)
 * Full-duplex with low write-detection latency matters: a half-duplex pump
 * misses tight init handshakes (the irtoy driver gives its version/sample reads
 * a 500 ms budget).
 *
 * Wire protocol (UsbProxyProtocol.kt): frame = u32 len (BE) . u8 opcode .
 * payload. BULK opcode = 0x04, payload = u8 endpoint, u32 length (BE), u32
 * timeoutMs (BE), then OUT data. Response = i32 status (BE) followed by
 * `status` data bytes (status<0 on error, e.g. -1 device timeout).
 *
 * Usage: haven-usb-serial [epOut] [epIn]   (defaults 0x02 / 0x82; strtol base 0)
 * Built static per-ABI (glibc/musl) like haven-usb-probe (build-haven-usb.sh).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

#define SOCK_NAME "haven-usb"
#define OP_BULK 0x04
#define MAX_FRAME (1u << 20)
#define ERR_PROXY (-100) /* framing/transport error (<= this means proxy dead) */

static int g_ep_out = 0x02;
static int g_ep_in = 0x82;
static int g_master = -1;

static int read_full(int fd, void *buf, size_t n) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) return -1;
        got += (size_t)r;
    }
    return 0;
}

static int write_full(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t put = 0;
    while (put < n) {
        ssize_t w = write(fd, p + put, n - put);
        if (w <= 0) return -1;
        put += (size_t)w;
    }
    return 0;
}

static uint32_t be32(uint32_t v) {
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) |
           ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF);
}

static int proxy_connect(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; /* abstract namespace */
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof(SOCK_NAME) - 1);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(SOCK_NAME) - 1);
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Read one response frame. Returns status (>=0 bytes, <0 device error, or
 * ERR_PROXY on framing failure). For IN reads, copies up to `cap` bytes into
 * `inbuf` and sets *got. */
static int recv_response(int fd, uint8_t *inbuf, uint32_t cap, uint32_t *got) {
    uint32_t rlen_be;
    if (read_full(fd, &rlen_be, 4) < 0) return ERR_PROXY;
    uint32_t rlen = be32(rlen_be);
    if (rlen < 4 || rlen > MAX_FRAME) return ERR_PROXY;
    uint32_t st_be;
    if (read_full(fd, &st_be, 4) < 0) return ERR_PROXY;
    int32_t status = (int32_t)be32(st_be);
    uint32_t datalen = rlen - 4;
    uint32_t n = 0;
    while (n < datalen) {
        uint8_t tmp[512];
        uint32_t chunk = datalen - n;
        if (chunk > sizeof(tmp)) chunk = sizeof(tmp);
        if (read_full(fd, tmp, chunk) < 0) return ERR_PROXY;
        if (inbuf && n < cap) {
            uint32_t c = (n + chunk <= cap) ? chunk : (cap - n);
            memcpy(inbuf + n, tmp, c);
        }
        n += chunk;
    }
    if (got) *got = (datalen < cap) ? datalen : cap;
    return status;
}

static int bulk_out(int fd, int ep, const uint8_t *data, uint32_t len, uint32_t timeout_ms) {
    uint8_t hdr[10];
    hdr[0] = OP_BULK;
    hdr[1] = (uint8_t)ep;
    uint32_t zero = be32(0); /* length field unused for OUT (data carries it) */
    memcpy(hdr + 2, &zero, 4);
    uint32_t t = be32(timeout_ms);
    memcpy(hdr + 6, &t, 4);
    uint32_t flen = be32(10 + len);
    if (write_full(fd, &flen, 4) < 0) return ERR_PROXY;
    if (write_full(fd, hdr, 10) < 0) return ERR_PROXY;
    if (len && write_full(fd, data, len) < 0) return ERR_PROXY;
    return recv_response(fd, NULL, 0, NULL);
}

static int bulk_in(int fd, int ep, uint8_t *buf, uint32_t cap, uint32_t timeout_ms, uint32_t *got) {
    uint8_t hdr[10];
    hdr[0] = OP_BULK;
    hdr[1] = (uint8_t)ep;
    uint32_t L = be32(cap);
    memcpy(hdr + 2, &L, 4);
    uint32_t t = be32(timeout_ms);
    memcpy(hdr + 6, &t, 4);
    uint32_t flen = be32(10);
    if (write_full(fd, &flen, 4) < 0) return ERR_PROXY;
    if (write_full(fd, hdr, 10) < 0) return ERR_PROXY;
    return recv_response(fd, buf, cap, got);
}

static void *reader_thread(void *arg) {
    (void)arg;
    int fd = proxy_connect();
    if (fd < 0) {
        fprintf(stderr, "haven-usb-serial: reader proxy connect failed\n");
        return NULL;
    }
    uint8_t buf[64];
    uint32_t got;
    for (;;) {
        int st = bulk_in(fd, g_ep_in, buf, sizeof(buf), 120, &got);
        if (st <= ERR_PROXY) break;       /* proxy/transport gone */
        if (st > 0 && got > 0) {
            if (write(g_master, buf, got) < 0 && errno != EIO) {
                /* EIO just means no slave opener yet; keep pumping */
            }
        }
        /* st == -1: device-side timeout (no IR / idle) — loop */
    }
    close(fd);
    return NULL;
}

/* Singleton guard: only one bridge per attached device. Two bridges would each
 * poll the same bulk-IN endpoint over their own proxy connection and split the
 * device's stream between them, corrupting every reader's view. Claim an
 * abstract lock socket; if it is already held, another bridge is running, so
 * exit. The abstract name is released automatically when the holder exits (even
 * on crash), so there is never a stale lock. */
static int g_lock_fd = -1;
static int acquire_singleton(void) {
    static const char LOCK[] = "haven-usb-serial.lock";
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, LOCK, sizeof(LOCK) - 1);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(LOCK) - 1);
    if (bind(fd, (struct sockaddr *)&addr, len) < 0) { close(fd); return -1; }
    g_lock_fd = fd; /* held for the process lifetime */
    return fd;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    if (argc > 1) g_ep_out = (int)strtol(argv[1], NULL, 0);
    if (argc > 2) g_ep_in = (int)strtol(argv[2], NULL, 0);

    if (acquire_singleton() < 0) {
        fprintf(stderr, "haven-usb-serial: another bridge is already running for "
                        "this device; exiting.\n");
        return 5;
    }

    g_master = posix_openpt(O_RDWR | O_NOCTTY);
    if (g_master < 0) { perror("posix_openpt"); return 2; }
    if (grantpt(g_master) < 0 || unlockpt(g_master) < 0) { perror("grant/unlockpt"); return 2; }
    char *sname = ptsname(g_master);
    if (!sname) { perror("ptsname"); return 2; }

    /* Raw line discipline so binary IR data passes untouched. */
    struct termios tio;
    if (tcgetattr(g_master, &tio) == 0) {
        cfmakeraw(&tio);
        tcsetattr(g_master, TCSANOW, &tio);
    }
    printf("pts: %s\n", sname);
    fflush(stdout);

    int wfd = proxy_connect();
    if (wfd < 0) {
        fprintf(stderr, "haven-usb-serial: writer proxy connect failed — is the device attached "
                        "via usb_attach_to_guest?\n");
        return 3;
    }

    pthread_t rt;
    if (pthread_create(&rt, NULL, reader_thread, NULL) != 0) {
        perror("pthread_create");
        return 4;
    }

    uint8_t buf[4096];
    for (;;) {
        ssize_t n = read(g_master, buf, sizeof(buf));
        if (n > 0) {
            if (bulk_out(wfd, g_ep_out, buf, (uint32_t)n, 1000) <= ERR_PROXY) break;
        } else if (n < 0) {
            if (errno == EIO) { usleep(20000); continue; }   /* no slave opener yet */
            if (errno == EAGAIN || errno == EINTR) { usleep(2000); continue; }
            break;
        }
        /* n == 0: ignore, keep going */
    }
    close(wfd);
    return 0;
}
