package sh.haven.core.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Wire coverage for the server-side USB/IP protocol (Slice 0). */
class UsbIpProtocolTest {

    private fun buildSubmit(
        seqnum: Int,
        direction: Int,
        ep: Int,
        transferBufferLength: Int,
        setup: ByteArray,
        out: ByteArray,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(UsbIpProtocol.CMD_SUBMIT)
            o.writeInt(seqnum)
            o.writeInt(0) // devid
            o.writeInt(direction)
            o.writeInt(ep)
            o.writeInt(0) // transfer_flags
            o.writeInt(transferBufferLength)
            o.writeInt(0) // start_frame
            o.writeInt(0) // number_of_packets
            o.writeInt(0) // interval
            o.write(setup)
            if (direction == UsbIpProtocol.DIR_OUT) o.write(out)
        }
        return bos.toByteArray()
    }

    @Test
    fun `import request round-trips busid`() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeShort(UsbIpProtocol.VERSION)
            o.writeShort(UsbIpProtocol.OP_REQ_IMPORT)
            o.writeInt(0)
            val busid = "1-1".toByteArray(Charsets.US_ASCII)
            o.write(busid); repeat(32 - busid.size) { o.writeByte(0) }
        }
        val req = UsbIpProtocol.readOpRequest(DataInputStream(bos.toByteArray().inputStream()))
        assertEquals(UsbIpProtocol.OpRequest.Import("1-1"), req)
    }

    @Test
    fun `import reply carries 312-byte device, null is bare failure`() {
        val d = UsbIpProtocol.Device(
            busid = "1-1", busnum = 1, devnum = 2, speed = 3,
            idVendor = 0x1050, idProduct = 0x0406, bcdDevice = 0x0543,
            deviceClass = 0, deviceSubClass = 0, deviceProtocol = 0,
            configurationValue = 1, numConfigurations = 1, numInterfaces = 2,
        )
        assertEquals(8 + 312, UsbIpProtocol.encodeImportReply(d).size)
        assertEquals(8, UsbIpProtocol.encodeImportReply(null).size)
    }

    @Test
    fun `submit control IN round-trips header and setup`() {
        // GET_DESCRIPTOR(device), wLength 0x12 — native little-endian setup.
        val setup = byteArrayOf(0x80.toByte(), 6, 0x00, 0x01, 0, 0, 0x12, 0)
        val frame = buildSubmit(7, UsbIpProtocol.DIR_IN, ep = 0, transferBufferLength = 0x12, setup = setup, out = ByteArray(0))
        val urb = UsbIpProtocol.readUrb(DataInputStream(frame.inputStream())) as UsbIpProtocol.Urb.Submit
        assertEquals(7, urb.seqnum)
        assertEquals(UsbIpProtocol.DIR_IN, urb.direction)
        assertEquals(0, urb.ep)
        assertEquals(0x12, urb.transferBufferLength)
        assertArrayEquals(setup, urb.setup)
        assertEquals(0, urb.out.size)
    }

    @Test
    fun `submit bulk OUT carries payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = buildSubmit(9, UsbIpProtocol.DIR_OUT, ep = 4, transferBufferLength = payload.size, setup = ByteArray(8), out = payload)
        val urb = UsbIpProtocol.readUrb(DataInputStream(frame.inputStream())) as UsbIpProtocol.Urb.Submit
        assertEquals(4, urb.ep)
        assertArrayEquals(payload, urb.out)
    }

    @Test
    fun `unlink parses target seqnum`() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(UsbIpProtocol.CMD_UNLINK)
            o.writeInt(11) // this command's seqnum
            o.writeInt(0); o.writeInt(0); o.writeInt(0) // devid, direction, ep
            o.writeInt(7) // unlink seqnum
            repeat(6) { o.writeInt(0) } // padding
        }
        assertEquals(
            UsbIpProtocol.Urb.Unlink(seqnum = 11, unlinkSeqnum = 7),
            UsbIpProtocol.readUrb(DataInputStream(bos.toByteArray().inputStream())),
        )
    }

    @Test
    fun `submit reply appends IN data and sets actual_length`() {
        val data = ByteArray(18) { it.toByte() }
        val reply = UsbIpProtocol.encodeSubmitReply(seqnum = 7, status = 0, actualLength = data.size, inData = data)
        assertEquals(48 + 18, reply.size)
        DataInputStream(reply.inputStream()).use { din ->
            assertEquals(UsbIpProtocol.RET_SUBMIT, din.readInt())
            assertEquals(7, din.readInt())
            din.readInt(); din.readInt(); din.readInt() // devid, direction, ep
            assertEquals(0, din.readInt()) // status
            assertEquals(18, din.readInt()) // actual_length
        }
    }

    @Test
    fun `OUT submit reply reports bytes sent with no payload`() {
        val reply = UsbIpProtocol.encodeSubmitReply(seqnum = 5, status = 0, actualLength = 64, inData = ByteArray(0))
        assertEquals(48, reply.size)
        DataInputStream(reply.inputStream()).use { din ->
            repeat(5) { din.readInt() } // command, seqnum, devid, direction, ep
            assertEquals(0, din.readInt()) // status
            assertEquals(64, din.readInt()) // actual_length = bytes sent
        }
    }

    @Test
    fun `error submit reply carries no data and zero length`() {
        val reply = UsbIpProtocol.encodeSubmitReply(seqnum = 3, status = -32, actualLength = 64, inData = ByteArray(64))
        assertEquals(48, reply.size)
        DataInputStream(reply.inputStream()).use { din ->
            repeat(5) { din.readInt() }
            assertEquals(-32, din.readInt()) // status
            assertEquals(0, din.readInt()) // actual_length zeroed on error
        }
    }

    @Test
    fun `clean end of stream yields null`() {
        assertNull(UsbIpProtocol.readUrb(DataInputStream(ByteArray(0).inputStream())))
        assertNull(UsbIpProtocol.readOpRequest(DataInputStream(ByteArray(0).inputStream())))
    }
}
