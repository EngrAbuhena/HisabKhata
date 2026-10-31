package com.hisabkhata.data.transfer

import android.content.Context
import android.net.Uri
import com.hisabkhata.domain.backup.BackupCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** File access is limited to user-selected SAF URIs and a private share cache. */
object FileTransfer {
    fun read(context: Context, uri: Uri, limit: Int = BackupCodec.MAX_BYTES): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { readBounded(it, limit) } ?: error("Could not open selected document")

    fun readBounded(input: InputStream, limit: Int = BackupCodec.MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            total += count; require(total <= limit); output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not write selected document")
        output.use { it.write(bytes); it.flush() }
    }
}
