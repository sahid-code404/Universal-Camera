package com.omnicam.camera.camerax

import android.graphics.Bitmap
import android.hardware.camera2.DngCreator
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Android's DngCreator requires the initial RAW payload to match the sensor metadata dimensions.
 * We first let DngCreator generate all of the camera-specific DNG metadata, then replace the CFA RAW
 * strip and its dimension/crop tags when OmniCam has produced a zoomed/aspect-cropped/upscaled Bayer
 * mosaic. This keeps vendor color calibration metadata while allowing a processed RAW payload.
 */
internal object AdaptiveDngWriter {
    fun write(
        file: File,
        merged: ComputationalRawEngine.MergeResult,
        transformed: RawDngTransform.Result,
        thumbnail: Bitmap?,
        description: String,
    ) {
        file.parentFile?.mkdirs()
        val dngThumbnail = thumbnail?.let(::fitDngThumbnail)
        try {
            FileOutputStream(file).use { output ->
                DngCreator(merged.referenceCharacteristics, merged.referenceResult).use { creator ->
                    creator.setDescription(description)
                    dngThumbnail?.let(creator::setThumbnail)
                    creator.writeByteBuffer(
                        output,
                        Size(merged.width, merged.height),
                        merged.pixels16.duplicate(),
                        0,
                    )
                }
            }

            val transformedPayload =
                transformed.width != merged.width ||
                    transformed.height != merged.height ||
                    transformed.cropLeft != 0 ||
                    transformed.cropTop != 0 ||
                    transformed.scale > 1.001f
            if (transformedPayload) {
                replaceRawStrip(
                    file = file,
                    width = transformed.width,
                    height = transformed.height,
                    pixels = transformed.pixels16,
                )
            }
        } finally {
            if (dngThumbnail != null && dngThumbnail !== thumbnail) dngThumbnail.recycle()
        }
    }

    private fun fitDngThumbnail(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= DngCreator.MAX_THUMBNAIL_DIMENSION) return source
        val scale = DngCreator.MAX_THUMBNAIL_DIMENSION.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun replaceRawStrip(file: File, width: Int, height: Int, pixels: ByteBuffer) {
        require(width > 0 && height > 0)
        val rawBytesLong = width.toLong() * height.toLong() * 2L
        require(rawBytesLong in 1..Int.MAX_VALUE.toLong()) { "Processed DNG payload is too large" }
        val rawBytes = rawBytesLong.toInt()
        require(pixels.capacity() >= rawBytes) { "Processed RAW buffer is smaller than its dimensions" }

        RandomAccessFile(file, "rw").use { raf ->
            val header = readHeader(raf)
            val rawIfd = findRawIfd(raf, header)
                ?: error("DNG RAW IFD was not found")
            val stripOffsetEntry = rawIfd.entries[TAG_STRIP_OFFSETS]
                ?: error("DNG RAW strip offset tag was not found")
            val stripByteCountEntry = rawIfd.entries[TAG_STRIP_BYTE_COUNTS]
                ?: error("DNG RAW strip byte-count tag was not found")

            var appendOffset = raf.length()
            val padding = ((4L - (appendOffset and 3L)) and 3L).toInt()
            if (padding > 0) {
                raf.seek(appendOffset)
                repeat(padding) { raf.write(0) }
                appendOffset += padding
            }
            require(appendOffset <= 0xffff_ffffL) { "DNG offset exceeds classic TIFF range" }

            raf.seek(appendOffset)
            val source = pixels.duplicate()
            source.position(0)
            source.limit(rawBytes)
            val block = ByteArray(1024 * 1024)
            while (source.hasRemaining()) {
                val count = minOf(source.remaining(), block.size)
                source.get(block, 0, count)
                raf.write(block, 0, count)
            }

            rawIfd.entries[TAG_IMAGE_WIDTH]?.let { forceLong(raf, header, it, width.toLong()) }
            rawIfd.entries[TAG_IMAGE_LENGTH]?.let { forceLong(raf, header, it, height.toLong()) }
            rawIfd.entries[TAG_COMPRESSION]?.let { forceShort(raf, header, it, 1) }
            forceLong(raf, header, stripOffsetEntry, appendOffset)
            rawIfd.entries[TAG_ROWS_PER_STRIP]?.let { forceLong(raf, header, it, height.toLong()) }
            forceLong(raf, header, stripByteCountEntry, rawBytesLong)

            rawIfd.entries[TAG_DEFAULT_CROP_ORIGIN]?.let {
                patchVector(raf, header, it, longArrayOf(0, 0))
            }
            rawIfd.entries[TAG_DEFAULT_CROP_SIZE]?.let {
                patchVector(raf, header, it, longArrayOf(width.toLong(), height.toLong()))
            }
            rawIfd.entries[TAG_ACTIVE_AREA]?.let {
                patchVector(raf, header, it, longArrayOf(0, 0, height.toLong(), width.toLong()))
            }
            rawIfd.entries[TAG_MASKED_AREAS]?.let {
                // Old masked sensor borders no longer describe a resampled payload.
                clearOptionalEntry(raf, header, it)
            }
            rawIfd.entries[TAG_OPCODE_LIST_1]?.let { clearOptionalEntry(raf, header, it) }
            rawIfd.entries[TAG_OPCODE_LIST_2]?.let { clearOptionalEntry(raf, header, it) }
            rawIfd.entries[TAG_OPCODE_LIST_3]?.let { clearOptionalEntry(raf, header, it) }
        }
    }

    private data class TiffHeader(val littleEndian: Boolean, val firstIfdOffset: Long)
    private data class Entry(
        val position: Long,
        val tag: Int,
        val type: Int,
        val count: Long,
        val valueOrOffset: Long,
    )
    private data class Ifd(val offset: Long, val entries: Map<Int, Entry>)

    private fun readHeader(raf: RandomAccessFile): TiffHeader {
        require(raf.length() >= 8) { "DNG is too small" }
        raf.seek(0)
        val a = raf.readUnsignedByte()
        val b = raf.readUnsignedByte()
        val little = when {
            a == 'I'.code && b == 'I'.code -> true
            a == 'M'.code && b == 'M'.code -> false
            else -> error("Invalid TIFF byte order")
        }
        require(readU16(raf, little) == 42) { "Invalid TIFF marker" }
        val first = readU32(raf, little)
        require(first in 8 until raf.length()) { "Invalid first IFD offset" }
        return TiffHeader(little, first)
    }

    private fun findRawIfd(raf: RandomAccessFile, header: TiffHeader): Ifd? {
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue += header.firstIfdOffset
        while (queue.isNotEmpty()) {
            val offset = queue.removeFirst()
            if (!visited.add(offset) || offset <= 0 || offset >= raf.length() - 2) continue
            val ifd = readIfd(raf, header, offset) ?: continue
            val photometric = ifd.entries[TAG_PHOTOMETRIC]?.let {
                readEntryValues(raf, header, it, 1).firstOrNull()
            }
            if (photometric == PHOTOMETRIC_CFA.toLong() || ifd.entries.containsKey(TAG_CFA_REPEAT_PATTERN_DIM)) {
                return ifd
            }

            ifd.entries[TAG_SUB_IFDS]?.let { entry ->
                readEntryValues(raf, header, entry, 32).forEach { child ->
                    if (child > 0) queue += child
                }
            }
            readNextIfdOffset(raf, header, ifd)?.takeIf { it > 0 }?.let(queue::add)
        }
        return null
    }

    private fun readIfd(raf: RandomAccessFile, header: TiffHeader, offset: Long): Ifd? {
        if (offset + 2 > raf.length()) return null
        raf.seek(offset)
        val count = readU16(raf, header.littleEndian)
        if (count > 4096 || offset + 2L + count * 12L + 4L > raf.length()) return null
        val entries = LinkedHashMap<Int, Entry>(count)
        repeat(count) {
            val position = raf.filePointer
            val tag = readU16(raf, header.littleEndian)
            val type = readU16(raf, header.littleEndian)
            val itemCount = readU32(raf, header.littleEndian)
            val value = readU32(raf, header.littleEndian)
            entries[tag] = Entry(position, tag, type, itemCount, value)
        }
        return Ifd(offset, entries)
    }

    private fun readNextIfdOffset(raf: RandomAccessFile, header: TiffHeader, ifd: Ifd): Long {
        val position = ifd.offset + 2L + ifd.entries.size * 12L
        if (position + 4 > raf.length()) return 0
        raf.seek(position)
        return readU32(raf, header.littleEndian)
    }

    private fun readEntryValues(
        raf: RandomAccessFile,
        header: TiffHeader,
        entry: Entry,
        maxValues: Int,
    ): List<Long> {
        if (entry.count <= 0) return emptyList()
        val itemSize = typeSize(entry.type) ?: return emptyList()
        val totalBytes = itemSize.toLong() * entry.count
        val dataOffset = if (totalBytes <= 4L) entry.position + 8L else entry.valueOrOffset
        if (dataOffset < 0 || dataOffset + totalBytes > raf.length()) return emptyList()
        raf.seek(dataOffset)
        val output = ArrayList<Long>(minOf(entry.count.toInt(), maxValues))
        repeat(minOf(entry.count.toInt(), maxValues)) {
            output += when (entry.type) {
                TYPE_BYTE, TYPE_UNDEFINED -> raf.readUnsignedByte().toLong()
                TYPE_SHORT -> readU16(raf, header.littleEndian).toLong()
                TYPE_LONG -> readU32(raf, header.littleEndian)
                else -> return output
            }
        }
        return output
    }

    private fun forceLong(raf: RandomAccessFile, header: TiffHeader, entry: Entry, value: Long) {
        require(value in 0..0xffff_ffffL)
        raf.seek(entry.position + 2)
        writeU16(raf, header.littleEndian, TYPE_LONG)
        writeU32(raf, header.littleEndian, 1)
        writeU32(raf, header.littleEndian, value)
    }

    private fun forceShort(raf: RandomAccessFile, header: TiffHeader, entry: Entry, value: Int) {
        require(value in 0..0xffff)
        raf.seek(entry.position + 2)
        writeU16(raf, header.littleEndian, TYPE_SHORT)
        writeU32(raf, header.littleEndian, 1)
        writeU16(raf, header.littleEndian, value)
        writeU16(raf, header.littleEndian, 0)
    }

    private fun patchVector(
        raf: RandomAccessFile,
        header: TiffHeader,
        entry: Entry,
        values: LongArray,
    ) {
        if (entry.count < values.size) return
        val itemSize = typeSize(entry.type) ?: return
        val totalBytes = itemSize.toLong() * entry.count
        val dataOffset = if (totalBytes <= 4L) entry.position + 8L else entry.valueOrOffset
        if (dataOffset < 0 || dataOffset + itemSize.toLong() * values.size > raf.length()) return
        raf.seek(dataOffset)
        when (entry.type) {
            TYPE_SHORT -> values.forEach { writeU16(raf, header.littleEndian, it.toInt().coerceIn(0, 0xffff)) }
            TYPE_LONG -> values.forEach { writeU32(raf, header.littleEndian, it.coerceIn(0, 0xffff_ffffL)) }
            TYPE_RATIONAL -> values.forEach {
                writeU32(raf, header.littleEndian, it.coerceIn(0, 0xffff_ffffL))
                writeU32(raf, header.littleEndian, 1)
            }
        }
    }

    private fun clearOptionalEntry(raf: RandomAccessFile, header: TiffHeader, entry: Entry) {
        raf.seek(entry.position + 4)
        writeU32(raf, header.littleEndian, 0)
        writeU32(raf, header.littleEndian, 0)
    }

    private fun typeSize(type: Int): Int? = when (type) {
        TYPE_BYTE, TYPE_ASCII, TYPE_UNDEFINED -> 1
        TYPE_SHORT -> 2
        TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
        else -> null
    }

    private fun readU16(raf: RandomAccessFile, little: Boolean): Int {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun readU32(raf: RandomAccessFile, little: Boolean): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return if (little) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    private fun writeU16(raf: RandomAccessFile, little: Boolean, value: Int) {
        if (little) {
            raf.write(value and 0xff)
            raf.write((value ushr 8) and 0xff)
        } else {
            raf.write((value ushr 8) and 0xff)
            raf.write(value and 0xff)
        }
    }

    private fun writeU32(raf: RandomAccessFile, little: Boolean, value: Long) {
        if (little) {
            raf.write((value and 0xff).toInt())
            raf.write(((value ushr 8) and 0xff).toInt())
            raf.write(((value ushr 16) and 0xff).toInt())
            raf.write(((value ushr 24) and 0xff).toInt())
        } else {
            raf.write(((value ushr 24) and 0xff).toInt())
            raf.write(((value ushr 16) and 0xff).toInt())
            raf.write(((value ushr 8) and 0xff).toInt())
            raf.write((value and 0xff).toInt())
        }
    }

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SLONG = 9
    private const val TYPE_SRATIONAL = 10
    private const val TYPE_FLOAT = 11
    private const val TYPE_DOUBLE = 12

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_SUB_IFDS = 330
    private const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_ACTIVE_AREA = 50829
    private const val TAG_MASKED_AREAS = 50830
    private const val TAG_OPCODE_LIST_1 = 51008
    private const val TAG_OPCODE_LIST_2 = 51009
    private const val TAG_OPCODE_LIST_3 = 51022
    private const val PHOTOMETRIC_CFA = 32803
}
