package local.reminders.domain

import java.io.ByteArrayOutputStream

/** Text-only MMS 1.2 SEND_REQ, using WSP multipart/related with SMIL and UTF-8 text. */
object MmsPdu {
    const val MAX_BYTES = 64 * 1024

    fun encode(numbers: List<String>, message: String, transaction: String): ByteArray {
        require(numbers.size in 2..Limits.MAX_GROUP && numbers.distinct().size == numbers.size) {
            "MMS requires distinct group numbers."
        }
        require(numbers.all { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }) { "MMS requires international phone numbers." }
        require(transaction.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid MMS transaction identifier." }
        require(message.isNotBlank() && '\u0000' !in message && message.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
            "MMS text must contain 1-16384 UTF-8 bytes and no null characters."
        }
        val out = Wire()
        out.bytes(0x8c, 0x80) // Message-Type: m-send-req
        out.byte(0x98); out.text(transaction)
        out.bytes(0x8d, 0x92) // MMS-Version: 1.2
        out.bytes(0x89, 0x01, 0x81) // From: insert-address-token; system/carrier supplies sender
        numbers.forEach { number ->
            out.byte(0x97)
            out.value(Wire().apply { byte(0xea); text("$number/TYPE=PLMN") }.result())
        }
        out.bytes(0x8a, 0x80, 0x8f, 0x81, 0x86, 0x81, 0x90, 0x81)
        out.byte(0x84)
        out.value(Wire().apply {
            byte(0xb3)
            byte(0x8a); text("<presentation>")
            byte(0x89); text("application/smil")
        }.result())
        val smil = """<smil><head><layout><root-layout/><region id="Text" top="0" left="0" height="100%" width="100%"/></layout></head><body><par dur="5000ms"><text src="text.txt" region="Text"/></par></body></smil>"""
        out.uint(2)
        out.part("application/smil", "presentation", "smil.xml", smil.toByteArray(Charsets.UTF_8), false)
        out.part("text/plain", "text", "text.txt", message.toByteArray(Charsets.UTF_8), true)
        return out.result().also { require(it.size <= MAX_BYTES) { "MMS payload exceeds the local safety limit." } }
    }

    private class Wire {
        private val out = ByteArrayOutputStream()
        fun byte(value: Int) { out.write(value) }
        fun bytes(vararg values: Int) { values.forEach(::byte) }
        fun raw(value: ByteArray) { out.write(value) }
        fun text(value: String) {
            val data = value.toByteArray(Charsets.UTF_8)
            if (data.isNotEmpty() && data[0].toInt() and 0xff >= 0x80) byte(0x7f)
            raw(data); byte(0)
        }
        fun uint(value: Int) {
            require(value >= 0)
            var shift = 28
            while (shift > 0 && value ushr shift == 0) shift -= 7
            while (shift > 0) { byte((value ushr shift and 0x7f) or 0x80); shift -= 7 }
            byte(value and 0x7f)
        }
        fun value(data: ByteArray) {
            if (data.size <= 30) byte(data.size) else { byte(31); uint(data.size) }
            raw(data)
        }
        fun part(type: String, id: String, location: String, data: ByteArray, utf8: Boolean) {
            val headers = Wire().apply {
                value(Wire().apply {
                    if (type == "text/plain") byte(0x83) else text(type)
                    byte(0x85); text(location)
                    if (utf8) bytes(0x81, 0xea)
                }.result())
                byte(0xc0); byte(0x22); text("<$id>")
                byte(0x8e); text(location)
            }.result()
            uint(headers.size); uint(data.size); raw(headers); raw(data)
        }
        fun result(): ByteArray = out.toByteArray()
    }
}
