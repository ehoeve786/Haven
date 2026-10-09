package sh.haven.core.usb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Server side of the USB/IP wire protocol (mirrors the Linux kernel
 * `drivers/usb/usbip` structs). Haven speaks this as a *userspace* usbip server
 * so a stock `usbip attach` client on a remote Linux host imports a phone-side
 * USB device. The Android kernel has no usbip module and the device is owned by
 * [UsbBroker] in userspace, so the kernel `usbip-host` path is unavailable — we
 * bridge each URB onto [UsbBroker] control/bulk transfers instead.
 *
 * Two phases on one TCP connection (port 3240):
 *  1. op handshake — client sends OP_REQ_IMPORT{busid}; server replies
 *     OP_REP_IMPORT{usbip_usb_device}.
 *  2. URB stream — 48-byte headers, USBIP_CMD_SUBMIT / USBIP_CMD_UNLINK, each
 *     answered with USBIP_RET_SUBMIT / USBIP_RET_UNLINK.
 *
 * All multi-byte header fields are big-endian (network order — the DataInput/
 * Output default). The 8-byte control `setup` packet stays USB-native
 * little-endian and is forwarded verbatim.
 *
 * DEVLIST (for `usbip list -r`) is omitted: `usbip attach -b <busid>` imports
 * directly without it. ponytail: add OP_REQ_DEVLIST when a UI needs remote
 * enumeration.
 */
object UsbIpProtocol {
    const val VERSION = 0x0111

    const val OP_REQ_IMPORT = 0x8003
    const val OP_REP_IMPORT = 0x0003

    const val CMD_SUBMIT = 0x0001
    const val RET_SUBMIT = 0x0003
    const val CMD_UNLINK = 0x0002
    const val RET_UNLINK = 0x0004

    const val DIR_OUT = 0
    const val DIR_IN = 1

    /** usbip_usb_device, as OP_REP_IMPORT carries it (312 packed bytes on the wire). */
    data class Device(
        val busid: String,
        val busnum: Int,
        val devnum: Int,
        val speed: Int,
        val idVendor: Int,
        val idProduct: Int,
        val bcdDevice: Int,
        val deviceClass: Int,
        val deviceSubClass: Int,
        val deviceProtocol: Int,
        val configurationValue: Int,
        val numConfigurations: Int,
        val numInterfaces: Int,
    )

    // ---- Phase 1: op handshake -------------------------------------------

    sealed interface OpRequest {
        data class Import(val busid: String) : OpRequest
    }

    /** Read one op handshake request, or null at clean EOF. */
    fun readOpRequest(input: DataInputStream): OpRequest? {
        val version = try {
            input.readUnsignedShort()
        } catch (_: EOFException) {
            return null
        }
        val code = input.readUnsignedShort()
        input.readInt() // status — unused on requests
        return when (code) {
            OP_REQ_IMPORT -> OpRequest.Import(readFixedString(input, 32))
            else -> throw IllegalArgumentException(
                "unsupported op 0x${code.toString(16)} (peer version 0x${version.toString(16)})",
            )
        }
    }

    /** OP_REP_IMPORT. [device] null => status=1 (import failed; no device body). */
    fun encodeImportReply(device: Device?): ByteArray = buildBytes {
        writeShort(VERSION)
        writeShort(OP_REP_IMPORT)
        writeInt(if (device == null) 1 else 0)
        if (device != null) writeDevice(device)
    }

    private fun DataOutputStream.writeDevice(d: Device) {
        writeFixedString("/haven/${d.busid}", 256) // path (cosmetic to the client)
        writeFixedString(d.busid, 32)
        writeInt(d.busnum)
        writeInt(d.devnum)
        writeInt(d.speed)
        writeShort(d.idVendor)
        writeShort(d.idProduct)
        writeShort(d.bcdDevice)
        writeByte(d.deviceClass)
        writeByte(d.deviceSubClass)
        writeByte(d.deviceProtocol)
        writeByte(d.configurationValue)
        writeByte(d.numConfigurations)
        writeByte(d.numInterfaces)
    }

    // ---- Phase 2: URB stream ---------------------------------------------

    sealed interface Urb {
        /**
         * USBIP_CMD_SUBMIT. [out] is the OUT payload (empty for IN). [setup] is
         * the 8-byte control packet (all-zero for non-control endpoints).
         */
        data class Submit(
            val seqnum: Int,
            val devid: Int,
            val direction: Int,
            val ep: Int,
            val transferFlags: Int,
            val transferBufferLength: Int,
            val interval: Int,
            val setup: ByteArray,
            val out: ByteArray,
        ) : Urb {
            override fun equals(other: Any?) = other is Submit &&
                seqnum == other.seqnum && devid == other.devid &&
                direction == other.direction && ep == other.ep &&
                transferFlags == other.transferFlags &&
                transferBufferLength == other.transferBufferLength &&
                interval == other.interval &&
                setup.contentEquals(other.setup) && out.contentEquals(other.out)

            override fun hashCode(): Int {
                var h = seqnum
                h = 31 * h + devid; h = 31 * h + direction; h = 31 * h + ep
                h = 31 * h + transferFlags; h = 31 * h + transferBufferLength
                h = 31 * h + interval; h = 31 * h + setup.contentHashCode()
                h = 31 * h + out.contentHashCode()
                return h
            }
        }

        /** USBIP_CMD_UNLINK — [unlinkSeqnum] is the SUBMIT seqnum to cancel. */
        data class Unlink(val seqnum: Int, val unlinkSeqnum: Int) : Urb
    }

    /** Read one URB command, or null at clean EOF. */
    fun readUrb(input: DataInputStream): Urb? {
        val command = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        val seqnum = input.readInt()
        val devid = input.readInt()
        val direction = input.readInt()
        val ep = input.readInt()
        return when (command) {
            CMD_SUBMIT -> {
                val transferFlags = input.readInt()
                val transferBufferLength = input.readInt()
                input.readInt() // start_frame
                input.readInt() // number_of_packets
                val interval = input.readInt()
                val setup = ByteArray(8).also { input.readFully(it) }
                val out = if (direction == DIR_OUT && transferBufferLength > 0) {
                    ByteArray(transferBufferLength).also { input.readFully(it) }
                } else {
                    ByteArray(0)
                }
                Urb.Submit(seqnum, devid, direction, ep, transferFlags, transferBufferLength, interval, setup, out)
            }
            CMD_UNLINK -> {
                val unlinkSeqnum = input.readInt()
                repeat(6) { input.readInt() } // rest of the 28-byte cmd area
                Urb.Unlink(seqnum, unlinkSeqnum)
            }
            else -> throw IllegalArgumentException("unknown URB command 0x${command.toString(16)}")
        }
    }

    /**
     * USBIP_RET_SUBMIT. [status] 0 = ok, else -errno. [actualLength] is bytes
     * transferred (the OUT bytes sent, or the IN bytes read). [inData] is the IN
     * payload appended to the reply (empty for OUT; its size must equal
     * [actualLength] on an IN success). Both are zeroed on error.
     */
    fun encodeSubmitReply(seqnum: Int, status: Int, actualLength: Int, inData: ByteArray): ByteArray = buildBytes {
        writeInt(RET_SUBMIT)
        writeInt(seqnum)
        writeInt(0) // devid (unused in replies)
        writeInt(0) // direction
        writeInt(0) // ep
        writeInt(status)
        writeInt(if (status == 0) actualLength else 0)
        writeInt(0) // start_frame
        writeInt(0) // number_of_packets
        writeInt(0) // error_count
        write(ByteArray(8)) // setup padding (unused in ret_submit)
        if (status == 0 && inData.isNotEmpty()) write(inData)
    }

    /** USBIP_RET_UNLINK. */
    fun encodeUnlinkReply(seqnum: Int, status: Int): ByteArray = buildBytes {
        writeInt(RET_UNLINK)
        writeInt(seqnum)
        writeInt(0) // devid
        writeInt(0) // direction
        writeInt(0) // ep
        writeInt(status)
        write(ByteArray(24)) // padding to the 28-byte ret area
    }

    // ---- helpers ---------------------------------------------------------

    private fun readFixedString(input: DataInputStream, len: Int): String {
        val buf = ByteArray(len)
        input.readFully(buf)
        val end = buf.indexOf(0.toByte()).let { if (it < 0) len else it }
        return String(buf, 0, end, Charsets.US_ASCII)
    }

    private fun DataOutputStream.writeFixedString(s: String, len: Int) {
        val b = s.toByteArray(Charsets.US_ASCII)
        val n = minOf(b.size, len)
        write(b, 0, n)
        repeat(len - n) { writeByte(0) }
    }

    private inline fun buildBytes(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }
}
