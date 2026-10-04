package com.wzm.launcher.cdn

import java.io.ByteArrayOutputStream

/**
 * Pergunta DNS (apenas 1 pergunta por pacote — suficiente para o caso real).
 * [endOffset] é o offset do primeiro byte depois da seção de pergunta (permite descartar
 * seções adicionais como OPT/EDNS0 ao montar a resposta).
 */
data class DnsQuestion(val name: String, val qType: Int, val qClass: Int, val endOffset: Int = 0)

/**
 * Parser/gerador mínimo de mensagens DNS (RFC 1035) — somente o necessário para
 * responder consultas A/AAAA do WZM. Sem dependências (testável em JVM).
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65
    const val TYPE_ANY = 255
    const val CLASS_IN = 1

    private const val HEADER_SIZE = 12
    private const val POINTER_TO_QUESTION = 0xC00C

    fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun parseQuery(packet: ByteArray, length: Int): DnsQuestion? {
        if (length < HEADER_SIZE) return null
        if (u16(packet, 4) < 1) return null
        val sb = StringBuilder()
        var pos = HEADER_SIZE
        var guard = 0
        while (pos < length && guard++ < 128) {
            val labelLength = packet[pos].toInt() and 0xFF
            pos++
            if (labelLength == 0) break
            if (labelLength and 0xC0 != 0) return null // ponteiro comprimido na pergunta: não suportado
            if (pos + labelLength > length) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(packet, pos, labelLength, Charsets.US_ASCII))
            pos += labelLength
        }
        if (sb.isEmpty() || pos + 4 > length) return null
        return DnsQuestion(
            name = sb.toString().lowercase(),
            qType = u16(packet, pos),
            qClass = u16(packet, pos + 2),
            endOffset = pos + 4
        )
    }

    /** Resposta com a pergunta ecoada + os registros informados (usa compressão p/ o nome). */
    fun buildResponse(
        query: ByteArray,
        queryLength: Int,
        question: DnsQuestion,
        answers: List<DnsRecord>
    ): ByteArray {
        val out = ByteArrayOutputStream(64 + answers.sumOf { it.encodedSize(question.name) })
        val header = ByteArray(HEADER_SIZE)
        header[0] = query[0]
        header[1] = query[1]
        header[2] = 0x81.toByte() // QR=1, Opcode=0, AA=0, TC=0, RD=1
        header[3] = 0x80.toByte() // RA=1, RCODE=0 (NOERROR)
        header[5] = 1 // QDCOUNT
        writeU16(header, 6, answers.size) // ANCOUNT
        out.write(header)
        // copia SOMENTE a seção de pergunta (descarta OPT/EDNS0 e outras seções adicionais)
        val questionEnd = if (question.endOffset in (HEADER_SIZE + 1)..queryLength) {
            question.endOffset
        } else {
            queryLength
        }
        out.write(query, HEADER_SIZE, questionEnd - HEADER_SIZE)
        for (answer in answers) answer.write(out, question.name)
        return out.toByteArray()
    }

    fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    /** Escreve o nome: ponteiro para a pergunta se coincidir, senão labels normais. */
    private fun writeName(out: ByteArrayOutputStream, name: String, questionName: String) {
        if (name.equals(questionName, ignoreCase = true)) {
            out.write((POINTER_TO_QUESTION shr 8) and 0xFF)
            out.write(POINTER_TO_QUESTION and 0xFF)
            return
        }
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
    }

    private fun nameEncodedSize(name: String, questionName: String): Int {
        if (name.equals(questionName, ignoreCase = true)) return 2
        return name.split('.').filter { it.isNotEmpty() }.sumOf { it.length + 1 } + 1
    }

    /**
     * Consulta DNS "de verdade" montada em memória (M3.5) — usada pelo teste sintético do caminho
     * `DNS CDNI -> 10.111.222.1 -> listener :443`. Não é usada no caminho do túnel (lá a consulta vem
     * do cliente); existe para que o launcher possa **produzir e verificar** a própria consulta.
     */
    fun buildQuery(name: String, qType: Int = TYPE_A, id: Int = 0x4D35): ByteArray {
        val out = ByteArrayOutputStream(32)
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // RD=1
        out.write(0x00)
        out.write(0x00) // QDCOUNT = 1
        out.write(0x01)
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0
        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
        out.write((qType shr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00) // CLASS = IN
        out.write(0x01)
        return out.toByteArray()
    }

    /**
     * Lê os registros A de uma resposta DNS (só o necessário para conferir a resposta virtual do
     * caminho CDNI). Devolve lista vazia quando não há resposta A ou o pacote está malformado —
     * nunca lança. Nenhum conteúdo além dos endereços é lido.
     */
    fun extractARecords(response: ByteArray, length: Int): List<String> {
        if (length < HEADER_SIZE) return emptyList()
        val questions = u16(response, 4)
        val answers = u16(response, 6)
        var pos = HEADER_SIZE
        for (index in 0 until questions) {
            pos = skipName(response, pos, length)
            if (pos < 0) return emptyList()
            pos += 4
            if (pos > length) return emptyList()
        }
        val records = mutableListOf<String>()
        for (index in 0 until answers) {
            pos = skipName(response, pos, length)
            if (pos < 0 || pos + 10 > length) return records
            val type = u16(response, pos)
            val rdLength = u16(response, pos + 8)
            val rdataStart = pos + 10
            if (rdataStart + rdLength > length) return records
            if (type == TYPE_A && rdLength == 4) {
                records += "${response[rdataStart].toInt() and 0xFF}." +
                    "${response[rdataStart + 1].toInt() and 0xFF}." +
                    "${response[rdataStart + 2].toInt() and 0xFF}." +
                    "${response[rdataStart + 3].toInt() and 0xFF}"
            }
            pos = rdataStart + rdLength
        }
        return records
    }

    /** Avança além de um nome (labels ou ponteiro de compressão). -1 quando o pacote acaba antes. */
    private fun skipName(data: ByteArray, start: Int, length: Int): Int {
        var pos = start
        var guard = 0
        while (pos < length && guard++ < 128) {
            val labelLength = data[pos].toInt() and 0xFF
            if (labelLength and 0xC0 == 0xC0) return pos + 2
            pos++
            if (labelLength == 0) return pos
            pos += labelLength
        }
        return -1
    }

    fun ipv4Bytes(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[index] = value.toByte()
        }
        return out
    }
}

/** Registro de resposta DNS. */
sealed class DnsRecord {
    abstract fun encodedSize(questionName: String): Int
    abstract fun write(out: ByteArrayOutputStream, questionName: String)

    private fun writeCommon(
        out: ByteArrayOutputStream,
        questionName: String,
        name: String,
        type: Int,
        rdata: ByteArray
    ) {
        val nameBytes = nameFor(name, questionName)
        out.write(nameBytes, 0, nameBytes.size)
        writeU16Raw(out, type)
        writeU16Raw(out, DnsMessage.CLASS_IN)
        writeTtl(out, CdnRouterConfig.DNS_TTL_SECONDS.toLong())
        writeU16Raw(out, rdata.size)
        out.write(rdata, 0, rdata.size)
    }

    protected fun finishWrite(
        out: ByteArrayOutputStream,
        questionName: String,
        name: String,
        type: Int,
        rdata: ByteArray
    ) = writeCommon(out, questionName, name, type, rdata)

    protected fun sizeOf(questionName: String, name: String, rdataSize: Int): Int =
        nameFor(name, questionName).size + 10 + rdataSize

    private companion object {
        fun nameFor(name: String, questionName: String): ByteArray {
            if (name.equals(questionName, ignoreCase = true)) return byteArrayOf(0xC0.toByte(), 0x0C)
            val out = ByteArrayOutputStream()
            for (label in name.split('.')) {
                if (label.isEmpty()) continue
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes, 0, bytes.size)
            }
            out.write(0)
            return out.toByteArray()
        }

        fun writeU16Raw(out: ByteArrayOutputStream, value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeTtl(out: ByteArrayOutputStream, value: Long) {
            out.write(((value shr 24) and 0xFF).toInt())
            out.write(((value shr 16) and 0xFF).toInt())
            out.write(((value shr 8) and 0xFF).toInt())
            out.write((value and 0xFF).toInt())
        }
    }
}

/** Registro A: `name` → IPv4. */
class AddressRecord(private val name: String, private val address: String) : DnsRecord() {
    private val rdata: ByteArray = DnsMessage.ipv4Bytes(address)
        ?: throw IllegalArgumentException("IPv4 inválido: $address")

    override fun encodedSize(questionName: String) = sizeOf(questionName, name, rdata.size)
    override fun write(out: ByteArrayOutputStream, questionName: String) =
        finishWrite(out, questionName, name, DnsMessage.TYPE_A, rdata)
}

/** Registro CNAME: `owner` → `target` (usado para mapear `cdn.cdni...` → host raiz). */
class CnameRecord(private val owner: String, private val target: String) : DnsRecord() {
    private val rdata: ByteArray = encodeName(target)

    override fun encodedSize(questionName: String) = sizeOf(questionName, owner, rdata.size)
    override fun write(out: ByteArrayOutputStream, questionName: String) =
        finishWrite(out, questionName, owner, DnsMessage.TYPE_CNAME, rdata)

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
        return out.toByteArray()
    }
}
