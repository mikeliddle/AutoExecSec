package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test

class MmsPduTest {
    private val numbers = listOf("+12025550101", "+12025550102")

    @Test fun sendRequestHasBothToHeadersNoCcBccAndRelatedSmilUtf8Body() {
        val bytes = MmsPdu.encode(numbers, "Hello synthetic group", "test-transaction")
        val p = Reader(bytes)
        assertEquals(0x8c, p.octet()); assertEquals(0x80, p.octet())
        assertEquals(0x98, p.octet()); assertEquals("test-transaction", p.text())
        assertEquals(0x8d, p.octet()); assertEquals(0x92, p.octet())
        assertEquals(0x89, p.octet()); assertEquals(1, p.length()); assertEquals(0x81, p.octet())
        numbers.forEach { number ->
            assertEquals(0x97, p.octet())
            val address = Reader(p.value())
            assertEquals(0xea, address.octet())
            assertEquals("$number/TYPE=PLMN", address.text())
            assertTrue(address.finished())
        }
        assertArrayEquals(byteArrayOf(0x8a.toByte(), 0x80.toByte(), 0x8f.toByte(), 0x81.toByte(),
            0x86.toByte(), 0x81.toByte(), 0x90.toByte(), 0x81.toByte()), p.take(8))
        assertEquals(0x84, p.octet())
        val content = Reader(p.value())
        assertEquals(0xb3, content.octet())
        assertEquals(0x8a, content.octet()); assertEquals("<presentation>", content.text())
        assertEquals(0x89, content.octet()); assertEquals("application/smil", content.text())
        assertTrue(content.finished())
        assertEquals(2, p.uint())
        val parts = (1..2).map {
            val headerSize = p.uint()
            val dataSize = p.uint()
            p.take(headerSize) to p.take(dataSize)
        }
        assertTrue(p.finished())
        val presentation = parts[0].second.toString(Charsets.UTF_8)
        assertTrue(presentation.contains("""src="text.txt""""))
        assertTrue(parts[0].first.toString(Charsets.ISO_8859_1).contains("application/smil"))
        assertTrue(parts[1].first.toString(Charsets.ISO_8859_1).contains("text.txt"))
        assertEquals("Hello synthetic group", parts[1].second.toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0xea.toByte()), parts[1].first.copyOfRange(12, 14))
    }

    @Test fun utf8AndMultiByteLengthsRetainExactTextWithoutExtraRecipients() {
        val message = "\u4f60\u597d ".repeat(150)
        val bytes = MmsPdu.encode(numbers, message, "test")
        assertTrue(bytes.size < MmsPdu.MAX_BYTES)
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        assertArrayEquals(messageBytes, bytes.takeLast(messageBytes.size).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { MmsPdu.encode(numbers, "x".repeat(16385), "test") }
        assertThrows(IllegalArgumentException::class.java) { MmsPdu.encode(numbers, "null\u0000body", "test") }
        assertThrows(IllegalArgumentException::class.java) { MmsPdu.encode(numbers + numbers[0], "Test", "test") }
        assertThrows(IllegalArgumentException::class.java) { MmsPdu.encode(listOf("2025550101", numbers[1]), "Test", "test") }
        assertThrows(IllegalArgumentException::class.java) { MmsPdu.encode(numbers.take(1), "Test", "test") }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        fun octet(): Int = data[position++].toInt() and 0xff
        fun uint(): Int {
            var value = 0
            do { val next = octet(); value = (value shl 7) or (next and 0x7f) } while (next and 0x80 != 0)
            return value
        }
        fun length(): Int = octet().let { if (it == 31) uint() else it }
        fun take(size: Int): ByteArray = data.copyOfRange(position, position + size).also { position += size }
        fun value(): ByteArray = take(length())
        fun text(): String {
            val start = position
            while (octet() != 0) Unit
            return data.copyOfRange(start, position - 1).toString(Charsets.UTF_8)
        }
        fun finished() = position == data.size
    }
}
