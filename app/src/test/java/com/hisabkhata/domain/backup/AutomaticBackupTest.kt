package com.hisabkhata.domain.backup

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomaticBackupTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val clock = Instant.parse("2026-11-07T17:45:00Z") // 23:45 in Dhaka

    @Test fun scheduleTargetsLocal2345BeforeAndAfterCutoff() {
        assertEquals("2026-11-07T23:45+06:00[Asia/Dhaka]",
            DailyBackupSchedule.next(Instant.parse("2026-11-07T17:44:59Z"), dhaka).toString())
        assertEquals("2026-11-08T23:45+06:00[Asia/Dhaka]", DailyBackupSchedule.next(clock, dhaka).toString())
        assertEquals("2026-11-08T23:45+06:00[Asia/Dhaka]",
            DailyBackupSchedule.next(Instant.parse("2026-11-07T20:30:00Z"), dhaka).toString())
    }

    @Test fun scheduleUsesTheDeviceZoneAndHandlesDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        // DST begins on this day; the target still uses 23:45 local, not a fixed UTC hour.
        assertEquals("2025-03-09T23:45-04:00[America/New_York]",
            DailyBackupSchedule.next(Instant.parse("2025-03-09T05:00:00Z"), ny).toString())
        assertEquals("2025-11-02T23:45-05:00[America/New_York]",
            DailyBackupSchedule.next(Instant.parse("2025-11-02T05:00:00Z"), ny).toString())
    }

    private class State(var account: String? = "example@example.invalid") : AutomaticBackupState {
        var day: String? = null
        var status = AutomaticBackupStatus.NONE
        var savedAt: Long? = null
        var savedAccount: String? = null
        override fun activeAccount() = account
        override fun lastSuccessfulDay() = day
        override fun recordStatus(status: AutomaticBackupStatus) { this.status = status }
        override fun recordSuccess(account: String, time: Long, localDay: String) {
            savedAccount = account; savedAt = time; day = localDay; status = AutomaticBackupStatus.SUCCESS
        }
    }

    private fun executor(
        state: State,
        authorize: suspend (String) -> AutomaticAuthorization = { AutomaticAuthorization.Authorized("short-lived") },
        upload: suspend (String, String) -> Long = { _, _ -> 123L }
    ) = AutomaticBackupExecutor(state, authorize, upload, now = { clock }, zone = { dhaka })

    @Test fun disabledNeverAuthorizesOrUploads() = runBlocking {
        val state = State(null); var calls = 0
        val worker = executor(state, { calls++; AutomaticAuthorization.NeedsUser }, { _, _ -> calls++; 0L })
        assertEquals(AutomaticBackupResult.SKIPPED, worker.run())
        assertEquals(0, calls)
        assertEquals(AutomaticBackupStatus.NONE, state.status)
    }

    @Test fun successRecordsCorrectAccountTimeAndDayAndDeduplicates() = runBlocking {
        val state = State(); var uploads = 0
        val worker = executor(state, upload = { account, token ->
            assertEquals(state.account, account); assertEquals("short-lived", token); uploads++; 456L
        })
        assertEquals(AutomaticBackupResult.SUCCESS, worker.run())
        assertEquals(456L, state.savedAt); assertEquals(state.account, state.savedAccount)
        assertEquals("2026-11-07", state.day); assertEquals(AutomaticBackupStatus.SUCCESS, state.status)
        assertEquals(AutomaticBackupResult.SKIPPED, worker.run())
        assertEquals(1, uploads)
    }

    @Test fun lateRetryDoesNotSuppressTheFollowingEvening() = runBlocking {
        val state = State(); var uploads = 0
        val afterMidnight = Instant.parse("2026-11-07T18:15:00Z") // Nov 8, 00:15 in Dhaka
        val worker = AutomaticBackupExecutor(state, { AutomaticAuthorization.Authorized("token") },
            { _, _ -> uploads++; 123L }, now = { afterMidnight }, zone = { dhaka })
        assertEquals(AutomaticBackupResult.SUCCESS, worker.run("2026-11-07"))
        assertEquals("2026-11-07", state.day)
        assertEquals(AutomaticBackupResult.SKIPPED, worker.run("2026-11-07"))
        assertEquals(AutomaticBackupResult.SUCCESS, worker.run("2026-11-08"))
        assertEquals(2, uploads)
    }

    @Test fun concurrentWorkersAreSerialized() = runBlocking {
        val state = State(); var uploads = 0
        val worker = executor(state, upload = { _, _ -> uploads++; delay(40); 123L })
        val first = async { worker.run() }; val second = async { worker.run() }
        assertEquals(setOf(AutomaticBackupResult.SUCCESS, AutomaticBackupResult.SKIPPED), setOf(first.await(), second.await()))
        assertEquals(1, uploads)
    }

    @Test fun consentOrBlankTokenRequestsUserActionWithoutUpload() = runBlocking {
        for (grant in listOf(AutomaticAuthorization.NeedsUser, AutomaticAuthorization.Authorized(""))) {
            val state = State(); var uploaded = false
            assertEquals(AutomaticBackupResult.NEEDS_AUTHORIZATION,
                executor(state, { grant }, { _, _ -> uploaded = true; 0L }).run())
            assertEquals(AutomaticBackupStatus.NEEDS_AUTHORIZATION, state.status)
            assertFalse(uploaded); assertNull(state.day)
        }
    }

    @Test fun transientErrorsRetryWhileRevokedGrantAndBadDataDoNot() = runBlocking {
        val state = State()
        assertEquals(AutomaticBackupResult.RETRY, executor(state, { AutomaticAuthorization.Retry }).run())
        assertEquals(AutomaticBackupStatus.RETRYING, state.status)
        assertEquals(AutomaticBackupResult.RETRY, executor(state, upload = { _, _ -> throw IOException() }).run())
        assertEquals(AutomaticBackupStatus.RETRYING, state.status)
        assertEquals(AutomaticBackupResult.NEEDS_AUTHORIZATION,
            executor(state, upload = { _, _ -> throw AutomaticBackupAuthorizationRequired() }).run())
        assertEquals(AutomaticBackupStatus.NEEDS_AUTHORIZATION, state.status)
        assertEquals(AutomaticBackupResult.FAILED,
            executor(state, upload = { _, _ -> throw AutomaticBackupPermanentFailure() }).run())
        assertEquals(AutomaticBackupStatus.FAILED, state.status)
        assertNull(state.day)
    }

    @Test fun disablingOrSwitchingAccountsDuringAuthorizationPreventsUpload() = runBlocking {
        for (replacement in listOf(null, "other@example.invalid")) {
            val state = State(); var uploaded = false
            val worker = executor(state, { state.account = replacement; AutomaticAuthorization.Authorized("token") },
                { _, _ -> uploaded = true; 0L })
            assertEquals(AutomaticBackupResult.SKIPPED, worker.run())
            assertFalse(uploaded); assertNull(state.savedAt); assertEquals(AutomaticBackupStatus.NONE, state.status)
        }
    }

    @Test fun switchingDuringUploadDoesNotMarkTheNewAccountSuccessful() = runBlocking {
        val state = State()
        val worker = executor(state, upload = { _, _ -> state.account = "other@example.invalid"; 0L })
        assertEquals(AutomaticBackupResult.SKIPPED, worker.run())
        assertNull(state.savedAt); assertNull(state.day)
    }

    @Test fun cancellationPropagatesInsteadOfBeingRecordedAsFailure() = runBlocking {
        val state = State()
        try { executor(state, { throw CancellationException("stopped") }).run(); fail("expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(AutomaticBackupStatus.NONE, state.status)
    }
}
