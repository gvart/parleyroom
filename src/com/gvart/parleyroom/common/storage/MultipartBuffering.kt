package com.gvart.parleyroom.common.storage

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Browsers don't emit per-part Content-Length on multipart file uploads, so we can't
 * rely on the part header to size the upload. Read the stream into memory bounded by
 * [maxBytes]; anything larger is a client error.
 */
fun InputStream.readBoundedBytes(maxBytes: Long): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_CHUNK)
    var total = 0L
    use { input ->
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw BadRequestException("file exceeds max size of $maxBytes bytes")
            }
            buffer.write(chunk, 0, read)
        }
    }
    return buffer.toByteArray()
}

private const val DEFAULT_CHUNK = 8 * 1024
