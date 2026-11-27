package com.hisabkhata.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake store/gateway pair: small enough to read, strict enough to prove the
 * ordering, account isolation and retry rules the real Room + PostgREST code obeys.
 */
private class FakeStore : SyncStore {
    val queue = mutableListOf<SyncEntry>()
    val cloudIds = mutableMapOf<Pair<SyncEntity, Long>, String>()
    val versions = mutableMapOf<Pair<SyncEntity, Long>, String>()
    val failures = mutableMapOf<Long, String>()
    val conflicts = mutableSetOf<Long>()
    private var nextOutbox = 1L

    fun enqueue(accountId: String, entity: SyncEntity, localId: Long, op: SyncOperation = SyncOperation.UPSERT) =
        enqueueWithClient(accountId, entity, localId, "client-$localId", op)

    fun enqueueWithClient(
        accountId: String,
        entity: SyncEntity,
        localId: Long,
        clientId: String,
        op: SyncOperation = SyncOperation.UPSERT
    ) {
        queue += SyncEntry(nextOutbox++, accountId, entity, op, localId, clientId)
    }

    override suspend fun pending(accountId: String, limit: Int) =
        queue.filter { it.accountId == accountId }.sortedBy { it.outboxId }.take(limit)

    override suspend fun cloudId(entity: SyncEntity, localId: Long) = cloudIds[entity to localId]

    override suspend fun clientId(entity: SyncEntity, localId: Long) = "client-$localId"
    override suspend fun knownUpdatedAt(entity: SyncEntity, localId: Long) = versions[entity to localId]

    override suspend fun recordUploaded(entity: SyncEntity, localId: Long, cloudId: String, updatedAt: String?) {
        cloudIds[entity to localId] = cloudId
        updatedAt?.let { versions[entity to localId] = it }
    }

    override suspend fun remove(outboxId: Long) { queue.removeAll { it.outboxId == outboxId } }
    override suspend fun recordFailure(outboxId: Long, reason: String) {
        failures[outboxId] = reason
        queue.replaceAll { if (it.outboxId == outboxId) it.copy(attempts = it.attempts + 1) else it }
    }
    override suspend fun markConflict(outboxId: Long, reason: String) {
        conflicts += outboxId
        queue.removeAll { it.outboxId == outboxId }
    }
    override suspend fun countPending(accountId: String) = queue.count { it.accountId == accountId }
}

private class FakeRecords(private val store: FakeStore) : SyncRecordSource {
    /** local ids that no longer exist locally */
    val deletedLocally = mutableSetOf<Pair<SyncEntity, Long>>()
    /** loan local id -> linked cash local id, for the pairing tests. */
    val linkedCash = mutableMapOf<Long, Long>()
    val sent = mutableListOf<CloudPayload>()

    override suspend fun prepare(entry: SyncEntry): PreparedRecord {
        if (entry.entity to entry.localId in deletedLocally) return PreparedRecord.RowGone
        return when (entry.entity) {
            SyncEntity.PERSON -> PreparedRecord.Ready(
                CloudPayload.Person(entry.clientId, "name-${entry.localId}", "", "", "lent")
            )
            SyncEntity.LOAN -> {
                // parentId is encoded as localId * 10 for this fake
                val personLocal = entry.localId / 10
                if (store.cloudId(SyncEntity.PERSON, personLocal) == null) {
                    PreparedRecord.Deferred(SyncEntity.PERSON)
                } else {
                    // A linked cash row is expressed by its local id; the cloud id is
                    // resolved the same way the production adapter does it.
                    val linkedCashLocal = linkedCash[entry.localId]
                    PreparedRecord.Ready(
                        CloudPayload.Loan(
                            clientId = entry.clientId,
                            personId = store.cloudId(SyncEntity.PERSON, personLocal)!!,
                            entryDate = 0, loan = 100, paid = 0, description = "",
                            createdAtMs = 1,
                            linkedCashEntryId = linkedCashLocal?.let { store.cloudId(SyncEntity.CASH, it) },
                            linkedCashLocalId = linkedCashLocal
                        )
                    )
                }
            }
            SyncEntity.CASH -> PreparedRecord.Ready(
                CloudPayload.Cash(entry.clientId, 0, 100, 0, "", 1, null)
            )
        }
    }
}

private class FakeGateway(private val store: FakeStore) : CloudGateway {
    val upserts = mutableListOf<SyncRecord>()
    var result: (SyncRecord) -> UploadResult = { UploadResult.Uploaded("cloud-${it.entry.localId}", "t1") }
    var deleteResult: (SyncEntity, String) -> UploadResult = { _, id -> UploadResult.Uploaded(id, null) }

    override suspend fun upsert(record: SyncRecord): UploadResult {
        upserts += record
        return result(record)
    }

    override suspend fun markDeleted(entity: SyncEntity, cloudId: String) = deleteResult(entity, cloudId)

    val links = mutableListOf<Triple<String, String, Boolean>>()
    var linkResult: (String, String) -> UploadResult = { loanId, cashId -> UploadResult.Uploaded(loanId, null) }

    override suspend fun link(loanClientId: String, cashClientId: String, linked: Boolean): UploadResult {
        links += Triple(loanClientId, cashClientId, linked)
        return linkResult(loanClientId, cashClientId)
    }
}

class SyncEngineTest {
    @Test
    fun `uploads oldest first and records the cloud id`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.enqueue("acct-a", SyncEntity.PERSON, 1)
        store.enqueue("acct-a", SyncEntity.PERSON, 2)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(2, pass.uploaded)
        assertEquals(0, pass.remaining)
        assertEquals(listOf(1L, 2L), gateway.upserts.map { it.entry.localId })
        assertEquals("cloud-1", store.cloudId(SyncEntity.PERSON, 1))
        assertEquals("t1", store.knownUpdatedAt(SyncEntity.PERSON, 1))
    }

    @Test
    fun `never sends another account's queued row`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.enqueue("acct-a", SyncEntity.PERSON, 1)
        store.enqueue("acct-b", SyncEntity.PERSON, 2)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.uploaded)
        assertEquals(listOf(1L), gateway.upserts.map { it.entry.localId })
        // The other account's work is untouched and still pending.
        assertEquals(1, store.countPending("acct-b"))
    }

    @Test
    fun `a loan waits for its person instead of sending a local id`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        // loan local id 10 belongs to person local id 1 (see FakeRecords)
        store.enqueue("acct-a", SyncEntity.LOAN, 10)

        val first = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(0, first.uploaded)
        assertEquals(1, first.waiting)
        assertEquals(1, first.remaining)
        assertTrue(gateway.upserts.isEmpty())
        assertEquals("waiting for person", store.failures.values.first())

        // Once the person has a cloud id the same loan uploads with it, no re-queue needed.
        store.recordUploaded(SyncEntity.PERSON, 1, "person-uuid", "t0")
        val second = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, second.uploaded)
        val payload = gateway.upserts.single().payload as CloudPayload.Loan
        assertEquals("person-uuid", payload.personId)
    }

    @Test
    fun `conflict keeps the local change and stops retrying`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        gateway.result = { UploadResult.Conflict }
        store.enqueue("acct-a", SyncEntity.PERSON, 1)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.conflicts)
        assertTrue(store.conflicts.isNotEmpty())
        // Nothing was recorded as uploaded, so a later resolve re-sends the row.
        assertNull(store.cloudId(SyncEntity.PERSON, 1))
        assertEquals(1, gateway.upserts.size)
    }

    @Test
    fun `retry keeps the row queued and counts the attempt`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        gateway.result = { UploadResult.Retry("offline") }
        store.enqueue("acct-a", SyncEntity.PERSON, 1)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(0, pass.uploaded)
        assertEquals(1, pass.remaining)
        assertEquals(1, store.pending("acct-a").single().attempts)
        assertEquals("offline", store.failures.values.first())
    }

    @Test
    fun `rejected row is kept but not retried into a hot loop`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        gateway.result = { UploadResult.Rejected("access denied") }
        store.enqueue("acct-a", SyncEntity.PERSON, 1)

        repeat(10) { SyncEngine(store, records, gateway).push("acct-a") }

        // The row still exists locally and in the queue; only 8 attempts were made.
        assertEquals(1, store.countPending("acct-a"))
        assertEquals(8, gateway.upserts.size)
        assertEquals(8, store.pending("acct-a").single().attempts)
    }

    @Test
    fun `row deleted after queuing is dropped without an upload`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.enqueue("acct-a", SyncEntity.PERSON, 1)
        records.deletedLocally += SyncEntity.PERSON to 1L

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(0, pass.uploaded)
        assertEquals(0, pass.remaining)
        assertTrue(gateway.upserts.isEmpty())
    }

    @Test
    fun `delete of a never-uploaded row is dropped locally only`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.enqueue("acct-a", SyncEntity.CASH, 5, SyncOperation.DELETE)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.deleted)
        assertEquals(0, pass.remaining)
        assertTrue(gateway.upserts.isEmpty())
    }

    @Test
    fun `delete of an uploaded row is soft-deleted in the cloud`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        var deletedId: String? = null
        gateway.deleteResult = { _, id -> deletedId = id; UploadResult.Uploaded(id, "t2") }
        store.recordUploaded(SyncEntity.CASH, 5, "cash-uuid", "t1")
        store.enqueue("acct-a", SyncEntity.CASH, 5, SyncOperation.DELETE)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.deleted)
        assertEquals("cash-uuid", deletedId)
        assertEquals(0, pass.remaining)
    }

    @Test
    fun `replaying the same pass cannot duplicate rows because the client id is stable`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        gateway.result = { UploadResult.Retry("timeout after commit") }
        store.enqueueWithClient("acct-a", SyncEntity.PERSON, 1, "stable-client-id")

        SyncEngine(store, records, gateway).push("acct-a")
        SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(2, gateway.upserts.size)
        assertEquals(setOf("stable-client-id"), gateway.upserts.map { it.entry.clientId }.toSet())
        assertEquals("stable-client-id", (gateway.upserts.last().payload as CloudPayload.Person).clientId)
    }

    @Test
    fun `a saved loan with a linked cash row is joined by one atomic link call`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.recordUploaded(SyncEntity.PERSON, 1, "person-uuid", "t0")
        store.recordUploaded(SyncEntity.CASH, 1001, "cash-uuid", "t0")
        records.linkedCash[10] = 1001L
        store.enqueue("acct-a", SyncEntity.LOAN, 10)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.uploaded)
        assertEquals(0, pass.remaining)
        // Keyed by the stable client ids, not by cloud uuids.
        assertEquals(Triple("client-10", "client-1001", true), gateway.links.single())
    }

    @Test
    fun `a failed link keeps the queue row so the pair is retried`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        gateway.linkResult = { _, _ -> UploadResult.Retry("offline") }
        store.recordUploaded(SyncEntity.PERSON, 1, "person-uuid", "t0")
        store.recordUploaded(SyncEntity.CASH, 1001, "cash-uuid", "t0")
        records.linkedCash[10] = 1001L
        store.enqueue("acct-a", SyncEntity.LOAN, 10)

        val pass = SyncEngine(store, records, gateway).push("acct-a")

        assertEquals(1, pass.uploaded)
        assertEquals(1, pass.remaining)   // still queued for the link retry
        assertTrue(pass.lastError!!.startsWith("link retry"))

        // Next pass succeeds: the row is re-upserted (idempotent) and linked.
        gateway.linkResult = { id, _ -> UploadResult.Uploaded(id, null) }
        val second = SyncEngine(store, records, gateway).push("acct-a")
        assertEquals(0, second.remaining)
        assertEquals(2, gateway.links.size)
    }

    @Test
    fun `an unlinked loan never calls the link function`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.recordUploaded(SyncEntity.PERSON, 1, "person-uuid", "t0")
        store.enqueue("acct-a", SyncEntity.LOAN, 10)

        SyncEngine(store, records, gateway).push("acct-a")

        assertTrue(gateway.links.isEmpty())
    }

    @Test
    fun `conditional update carries the last seen server version`() = runTest {
        val store = FakeStore()
        val records = FakeRecords(store)
        val gateway = FakeGateway(store)
        store.recordUploaded(SyncEntity.PERSON, 1, "person-uuid", "2026-10-30T00:00:00Z")
        store.enqueue("acct-a", SyncEntity.PERSON, 1)

        SyncEngine(store, records, gateway).push("acct-a")

        val sent = gateway.upserts.single()
        assertEquals("person-uuid", sent.cloudId)
        assertEquals("2026-10-30T00:00:00Z", sent.knownUpdatedAt)
    }
}
