package com.hisabkhata.domain.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import java.time.LocalDate

/** Versioned portable backup. Monetary values and IDs remain exact JSON integers. */
data class DatabaseBackup(
    val createdAt: Long,
    val people: List<PersonEntity>,
    val loans: List<LoanEntryEntity>,
    val cash: List<CashEntryEntity>
)

class InvalidBackupException : IllegalArgumentException()

object BackupCodec {
    const val FORMAT = "hisab-khata-backup"
    const val VERSION = 1
    const val MAX_BYTES = 32 * 1024 * 1024
    private val minDate = LocalDate.of(1, 1, 1).toEpochDay()
    private val maxDate = LocalDate.of(9999, 12, 31).toEpochDay()
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    fun encode(backup: DatabaseBackup): String {
        validate(backup)
        val root = JsonObject().apply {
            addProperty("format", FORMAT); addProperty("version", VERSION); addProperty("createdAt", backup.createdAt)
            add("people", JsonArray().apply { backup.people.forEach { p -> add(JsonObject().apply {
                addProperty("id", p.id); addProperty("name", p.name); addProperty("phone", p.phone)
                addProperty("note", p.note); addProperty("type", p.type.name)
            }) } })
            add("loanEntries", JsonArray().apply { backup.loans.forEach { e -> add(JsonObject().apply {
                addProperty("id", e.id); addProperty("personId", e.personId); addProperty("date", e.date)
                addProperty("loan", e.loan); addProperty("paid", e.paid); addProperty("description", e.description)
                addProperty("createdAt", e.createdAt); addNullableLong("linkedCashEntryId", e.linkedCashEntryId)
            }) } })
            add("cashEntries", JsonArray().apply { backup.cash.forEach { e -> add(JsonObject().apply {
                addProperty("id", e.id); addProperty("date", e.date); addProperty("deposit", e.deposit)
                addProperty("cost", e.cost); addProperty("description", e.description); addProperty("createdAt", e.createdAt)
                addNullableLong("linkedLoanEntryId", e.linkedLoanEntryId)
            }) } })
        }
        val json = root.toString()
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw InvalidBackupException()
        return json
    }

    fun decode(bytes: ByteArray): DatabaseBackup {
        if (bytes.size > MAX_BYTES) throw InvalidBackupException()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw InvalidBackupException() }
        return decode(text)
    }

    fun decode(json: String): DatabaseBackup {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw InvalidBackupException()
        try {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.strictness = com.google.gson.Strictness.STRICT
            val root = JsonParser.parseReader(reader).objectValue()
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw InvalidBackupException()
            if (root.string("format") != FORMAT || root.long("version") != VERSION.toLong()) throw InvalidBackupException()
            val people = root.array("people").map { v -> v.objectValue().let { p ->
                PersonEntity(p.long("id"), p.string("name"), p.string("phone"), p.string("note"), PersonType.valueOf(p.string("type")))
            } }
            val loans = root.array("loanEntries").map { v -> v.objectValue().let { e ->
                LoanEntryEntity(e.long("id"), e.long("personId"), e.long("date"), e.long("loan"), e.long("paid"),
                    e.string("description"), e.long("createdAt"), e.nullableLong("linkedCashEntryId"))
            } }
            val cash = root.array("cashEntries").map { v -> v.objectValue().let { e ->
                CashEntryEntity(e.long("id"), e.long("date"), e.long("deposit"), e.long("cost"),
                    e.string("description"), e.long("createdAt"), e.nullableLong("linkedLoanEntryId"))
            } }
            return DatabaseBackup(root.long("createdAt"), people, loans, cash).also(::validate)
        } catch (e: InvalidBackupException) { throw e }
        catch (_: Exception) { throw InvalidBackupException() }
    }

    fun validate(b: DatabaseBackup) {
        fun validIds(ids: List<Long>) = ids.all { it > 0 } && ids.toSet().size == ids.size
        if (!validIds(b.people.map { it.id }) || !validIds(b.loans.map { it.id }) || !validIds(b.cash.map { it.id })) throw InvalidBackupException()
        if (b.people.any { it.name.isBlank() }) throw InvalidBackupException()
        val peopleIds = b.people.map { it.id }.toSet()
        val loans = b.loans.associateBy { it.id }
        val cash = b.cash.associateBy { it.id }
        if (b.loans.any { it.personId !in peopleIds || it.date !in minDate..maxDate || it.loan < 0 || it.paid < 0 || (it.loan == 0L && it.paid == 0L) }) throw InvalidBackupException()
        if (b.cash.any { it.date !in minDate..maxDate || it.deposit < 0 || it.cost < 0 || (it.deposit == 0L && it.cost == 0L) }) throw InvalidBackupException()
        b.loans.forEach { loan ->
            loan.linkedCashEntryId?.let { if (cash[it]?.linkedLoanEntryId != loan.id) throw InvalidBackupException() }
        }
        b.cash.forEach { entry ->
            entry.linkedLoanEntryId?.let { if (loans[it]?.linkedCashEntryId != entry.id) throw InvalidBackupException() }
        }
    }

    private fun JsonObject.addNullableLong(key: String, value: Long?) {
        if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
    }
    private fun JsonElement.objectValue(): JsonObject = if (isJsonObject) asJsonObject else throw InvalidBackupException()
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: throw InvalidBackupException()
    private fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: throw InvalidBackupException()
    private fun JsonObject.long(key: String): Long {
        val v = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString ?: throw InvalidBackupException()
        if (v.length > 20 || !integer.matches(v)) throw InvalidBackupException()
        return v.toLongOrNull() ?: throw InvalidBackupException()
    }
    private fun JsonObject.nullableLong(key: String): Long? {
        val value = get(key) ?: throw InvalidBackupException()
        return if (value.isJsonNull) null else long(key)
    }
}
