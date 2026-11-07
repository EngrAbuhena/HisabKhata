package com.hisabkhata.data.drive

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hisabkhata.domain.backup.BackupCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
data class DriveBackupFile(val id: String, val name: String, val createdAt: Long, val size: Long)
class DriveRequestException(val statusCode: Int? = null) : IllegalStateException()
class DriveUnauthorizedException : IllegalStateException()

/** Uses only the private appDataFolder scope, never the user's regular Drive files. */
@Singleton
class DriveBackupService @Inject constructor(@ApplicationContext private val context: Context) {
    private val date = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC)
    private val api = "https://www.googleapis.com/drive/v3/files"
    private val uploadApi = "https://www.googleapis.com/upload/drive/v3/files"

    fun list(token: String): List<DriveBackupFile> {
        val files = mutableListOf<DriveBackupFile>(); var page: String? = null; var pages = 0
        do {
            if (++pages > 200) throw DriveRequestException()
            val params = "spaces=appDataFolder&q=${encode("trashed = false")}&orderBy=${encode("createdTime desc")}" +
                "&pageSize=100&fields=${encode("nextPageToken,files(id,name,createdTime,size)")}" +
                (page?.let { "&pageToken=${encode(it)}" } ?: "")
            val root = JsonParser.parseString(request(token, "GET", "$api?$params").toString(Charsets.UTF_8)).asJsonObject
            root.getAsJsonArray("files")?.forEach { item ->
                val file = item.asJsonObject
                val id = file.get("id")?.asString ?: throw DriveRequestException()
                if (!validId(id)) throw DriveRequestException()
                val name = file.get("name")?.asString ?: throw DriveRequestException()
                // App-data folder can contain only this app's files; show only our JSON backups.
                if (name.startsWith("HisabKhata-backup-") && name.endsWith(".json")) {
                    val time = runCatching { Instant.parse(file.get("createdTime").asString).toEpochMilli() }.getOrDefault(0L)
                    val size = file.get("size")?.asString?.toLongOrNull() ?: 0L
                    files += DriveBackupFile(id, name, time, size)
                }
            }
            page = root.get("nextPageToken")?.takeUnless { it.isJsonNull }?.asString
            if (files.size > 10_000) throw DriveRequestException()
        } while (!page.isNullOrBlank())
        return files.sortedByDescending { it.createdAt }
    }

    fun upload(token: String, json: ByteArray, createdAt: Long) {
        require(json.size <= BackupCodec.MAX_BYTES)
        val name = "HisabKhata-backup-${date.format(Instant.ofEpochMilli(createdAt))}.json"
        val meta = JsonObject().apply {
            addProperty("name", name); addProperty("mimeType", "application/json")
            add("parents", com.google.gson.JsonArray().apply { add("appDataFolder") })
        }.toString()
        val boundary = "hisabkhata_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val first = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray(Charsets.UTF_8)
        val last = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = first + json + last
        request(token, "POST", "$uploadApi?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
    }

    fun download(token: String, id: String): ByteArray {
        if (!validId(id)) throw DriveRequestException()
        return request(token, "GET", "$api/$id?alt=media", limit = BackupCodec.MAX_BYTES)
    }

    fun delete(token: String, id: String) {
        if (!validId(id)) throw DriveRequestException()
        request(token, "DELETE", "$api/$id")
    }

    private fun request(token: String, method: String, url: String, body: ByteArray? = null,
        contentType: String? = null, limit: Int = 2 * 1024 * 1024): ByteArray {
        require(token.isNotBlank())
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.requestMethod = method; conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true; conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            if (status == 401) throw DriveUnauthorizedException()
            if (status !in 200..299) throw DriveRequestException(status)
            val stream = conn.inputStream ?: ByteArrayInputStream(ByteArray(0))
            return stream.use { com.hisabkhata.data.transfer.FileTransfer.readBounded(it, limit) }
        } finally { conn.disconnect() }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun validId(id: String) = id.isNotBlank() && id.length < 300 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
