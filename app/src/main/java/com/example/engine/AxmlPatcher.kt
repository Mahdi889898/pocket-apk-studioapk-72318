package com.example.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary AndroidManifest.xml (AXML) and resources.arsc patcher.
 * Scans chunks dynamically and rebuilds the AXML ResStringPool cleanly so strings
 * of arbitrary length (including Unicode/Persian app names and custom package IDs) are valid.
 */
object AxmlPatcher {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 1 shl 8
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200

    fun patchManifest(
        axmlBytes: ByteArray,
        replacements: Map<String, String>,
        newVersionCode: Int? = null
    ): ByteArray {
        require(axmlBytes.size >= 16) {
            "Template AXML manifest is too small (${axmlBytes.size} bytes)"
        }
        val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)
        val xmlType = buf.getShort(0).toInt() and 0xFFFF
        val xmlHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        require(xmlType == RES_XML_TYPE && xmlHeaderSize >= 8) {
            val headHex = axmlBytes.take(16).joinToString(" ") { "%02x".format(it) }
            "Invalid AXML header: type=0x${xmlType.toString(16)} hdr=$xmlHeaderSize hex=[$headHex]"
        }

        // Scan chunks starting right after ResXMLTree_header to locate RES_STRING_POOL_TYPE
        var spOffset = -1
        var scan = xmlHeaderSize
        while (scan + 8 <= axmlBytes.size) {
            val chunkType = buf.getShort(scan).toInt() and 0xFFFF
            val chunkSize = buf.getInt(scan + 4)
            if (chunkType == RES_STRING_POOL_TYPE) {
                spOffset = scan
                break
            }
            if (chunkSize <= 0 || scan + chunkSize > axmlBytes.size) break
            scan += chunkSize
        }
        require(spOffset >= 0) {
            val headHex = axmlBytes.take(24).joinToString(" ") { "%02x".format(it) }
            "ResStringPool chunk (0x0001) not found in AXML ($headHex)"
        }

        val spHeaderSize = buf.getShort(spOffset + 2).toInt() and 0xFFFF
        val spChunkSize = buf.getInt(spOffset + 4)
        val stringCount = buf.getInt(spOffset + 8)
        val styleCount = buf.getInt(spOffset + 12)
        val flags = buf.getInt(spOffset + 16)
        val stringsStart = buf.getInt(spOffset + 20)
        val stylesStart = buf.getInt(spOffset + 24)
        val isUtf8 = (flags and UTF8_FLAG) != 0

        val strings = ArrayList<String>(stringCount)
        val strOffsetsBase = spOffset + spHeaderSize
        val strDataBase = spOffset + stringsStart

        for (i in 0 until stringCount) {
            val relOff = buf.getInt(strOffsetsBase + i * 4)
            val absOff = strDataBase + relOff
            val decoded = if (isUtf8) {
                decodeUtf8PoolString(axmlBytes, absOff)
            } else {
                decodeUtf16PoolString(axmlBytes, absOff)
            }
            strings.add(replacements[decoded] ?: decoded)
        }

        val styleOffsetsBytes = if (styleCount > 0) {
            axmlBytes.copyOfRange(strOffsetsBase + stringCount * 4, strOffsetsBase + stringCount * 4 + styleCount * 4)
        } else {
            ByteArray(0)
        }
        val styleDataBytes = if (styleCount > 0 && stylesStart > 0) {
            axmlBytes.copyOfRange(spOffset + stylesStart, spOffset + spChunkSize)
        } else {
            ByteArray(0)
        }

        val newOffsets = IntArray(stringCount)
        val encodedStream = ByteArrayOutputStream()
        for (i in 0 until stringCount) {
            newOffsets[i] = encodedStream.size()
            if (isUtf8) {
                writeUtf8PoolString(encodedStream, strings[i])
            } else {
                writeUtf16PoolString(encodedStream, strings[i])
            }
        }
        while (encodedStream.size() % 4 != 0) {
            encodedStream.write(0)
        }
        val encodedStringData = encodedStream.toByteArray()

        val newStringsStart = spHeaderSize + stringCount * 4 + styleCount * 4
        val newStylesStart = if (styleCount > 0) newStringsStart + encodedStringData.size else 0
        val newSpChunkSize = newStringsStart + encodedStringData.size + styleDataBytes.size

        val prefixBeforeSp = axmlBytes.copyOfRange(xmlHeaderSize, spOffset)
        val tailBytes = axmlBytes.copyOfRange(spOffset + spChunkSize, axmlBytes.size)
        if (newVersionCode != null) {
            patchVersionCodeInTail(tailBytes, strings, newVersionCode)
        }

        val totalFileSize = xmlHeaderSize + prefixBeforeSp.size + newSpChunkSize + tailBytes.size
        val out = ByteBuffer.allocate(totalFileSize).order(ByteOrder.LITTLE_ENDIAN)

        // ResXMLTree_header
        out.putShort(RES_XML_TYPE.toShort())
        out.putShort(xmlHeaderSize.toShort())
        out.putInt(totalFileSize)
        for (p in 8 until xmlHeaderSize) out.put(0)

        if (prefixBeforeSp.isNotEmpty()) {
            out.put(prefixBeforeSp)
        }

        // ResStringPool_header
        out.putShort(RES_STRING_POOL_TYPE.toShort())
        out.putShort(spHeaderSize.toShort())
        out.putInt(newSpChunkSize)
        out.putInt(stringCount)
        out.putInt(styleCount)
        out.putInt(flags)
        out.putInt(newStringsStart)
        out.putInt(newStylesStart)

        for (p in 28 until spHeaderSize) {
            out.put(0)
        }

        for (off in newOffsets) {
            out.putInt(off)
        }
        if (styleOffsetsBytes.isNotEmpty()) {
            out.put(styleOffsetsBytes)
        }
        out.put(encodedStringData)
        if (styleDataBytes.isNotEmpty()) {
            out.put(styleDataBytes)
        }
        out.put(tailBytes)

        return out.array()
    }

    fun patchResourcesArscPackageName(arscBytes: ByteArray, newPackageName: String): ByteArray {
        val copy = arscBytes.copyOf()
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
        val tableType = buf.getShort(0).toInt() and 0xFFFF
        val tableHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        if (tableType != RES_TABLE_TYPE) return copy

        var offset = tableHeaderSize
        while (offset + 8 <= copy.size) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > copy.size) break
            if (chunkType == RES_TABLE_PACKAGE_TYPE && offset + 268 <= copy.size) {
                val nameOffset = offset + 12
                for (i in 0 until 256) {
                    copy[nameOffset + i] = 0
                }
                val encoded = newPackageName.take(127).toByteArray(StandardCharsets.UTF_16LE)
                System.arraycopy(encoded, 0, copy, nameOffset, minOf(encoded.size, 254))
                break
            }
            offset += chunkSize
        }
        return copy
    }

    private fun patchVersionCodeInTail(tailBytes: ByteArray, strings: List<String>, newVersionCode: Int) {
        val versionCodeIdx = strings.indexOf("versionCode")
        if (versionCodeIdx < 0) return

        val buf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset + 8 <= tailBytes.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > tailBytes.size) break

            if (type == RES_XML_START_ELEMENT_TYPE && offset + headerSize + 20 <= tailBytes.size) {
                val attrStart = buf.getShort(offset + headerSize + 8).toInt() and 0xFFFF
                val attrSize = buf.getShort(offset + headerSize + 10).toInt() and 0xFFFF
                val attrCount = buf.getShort(offset + headerSize + 12).toInt() and 0xFFFF
                val attrsBase = offset + headerSize + attrStart
                for (a in 0 until attrCount) {
                    val attrOff = attrsBase + a * attrSize
                    if (attrOff + 20 > tailBytes.size) break
                    val nameIdx = buf.getInt(attrOff + 4)
                    val dataType = buf.get(attrOff + 15).toInt() and 0xFF
                    if (nameIdx == versionCodeIdx && (dataType == 0x10 || dataType == 0x11)) {
                        buf.putInt(attrOff + 16, newVersionCode)
                    }
                }
            }
            offset += chunkSize
        }
    }

    private fun decodeUtf16PoolString(bytes: ByteArray, offset: Int): String {
        var pos = offset
        val first = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos].toInt() and 0xFF)
        pos += 2
        val length = if ((first and 0x8000) != 0) {
            val second = ((bytes[pos + 1].toInt() and 0xFF) shl 8) or (bytes[pos].toInt() and 0xFF)
            pos += 2
            ((first and 0x7FFF) shl 16) or second
        } else {
            first
        }
        return String(bytes, pos, length * 2, StandardCharsets.UTF_16LE)
    }

    private fun decodeUtf8PoolString(bytes: ByteArray, offset: Int): String {
        var pos = offset
        val b0 = bytes[pos++].toInt() and 0xFF
        if ((b0 and 0x80) != 0) pos++
        val l0 = bytes[pos++].toInt() and 0xFF
        val byteLen = if ((l0 and 0x80) != 0) {
            val l1 = bytes[pos++].toInt() and 0xFF
            ((l0 and 0x7F) shl 8) or l1
        } else {
            l0
        }
        return String(bytes, pos, byteLen, StandardCharsets.UTF_8)
    }

    private fun writeUtf16PoolString(out: ByteArrayOutputStream, value: String) {
        val utf16 = value.toByteArray(StandardCharsets.UTF_16LE)
        val charLen = utf16.size / 2
        if (charLen >= 0x8000) {
            val high = ((charLen ushr 16) and 0x7FFF) or 0x8000
            val low = charLen and 0xFFFF
            out.write(high and 0xFF)
            out.write((high ushr 8) and 0xFF)
            out.write(low and 0xFF)
            out.write((low ushr 8) and 0xFF)
        } else {
            out.write(charLen and 0xFF)
            out.write((charLen ushr 8) and 0xFF)
        }
        out.write(utf16)
        out.write(0)
        out.write(0)
    }

    private fun writeUtf8PoolString(out: ByteArrayOutputStream, value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        val charLen = value.length
        if (charLen >= 0x80) {
            out.write(((charLen ushr 8) and 0x7F) or 0x80)
            out.write(charLen and 0xFF)
        } else {
            out.write(charLen)
        }
        val byteLen = utf8.size
        if (byteLen >= 0x80) {
            out.write(((byteLen ushr 8) and 0x7F) or 0x80)
            out.write(byteLen and 0xFF)
        } else {
            out.write(byteLen)
        }
        out.write(utf8)
        out.write(0)
    }
}
