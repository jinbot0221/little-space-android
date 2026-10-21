package com.portfolio.littlespace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SparkBookingTest {
    @Test
    fun actualAndroidSdkReservesRecoversAndCancels() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot") {
            "This test is emulator-only"
        }
        val repo = FirebaseRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.login("customer@teumgot.test", "Teumgot123!")
        val s = repo.spaces().first { it.id == "focus-a" }
        val date = LocalDate.now(KST).plusDays(4)
        val start = date.atStartOfDay(KST).plusHours(14).toInstant().toEpochMilli()
        val available =
            repo.call("getAvailability", mapOf("spaceId" to s.id, "date" to date.toString()))
        assertTrue(available.long("serverNow") > 0)
        val request =
            mapOf(
                "spaceId" to s.id,
                "startAt" to start,
                "endAt" to start + 7200000L,
                "people" to 4,
                "requestKey" to requestKey(),
            )
        val hold = repo.call("createHold", request)
        assertEquals(20000L, hold.long("amount"))
        assertEquals(hold.str("id"), repo.call("createHold", request).str("id"))
        val confirmation =
            mapOf(
                "holdId" to hold.str("id"),
                "requestKey" to requestKey(),
                "paymentMode" to "success",
                "policyAgreed" to true,
            )
        val booking = repo.call("confirmBooking", confirmation)
        assertEquals("confirmed", booking.str("status"))
        assertEquals(
            booking.str("bookingId"),
            repo.call("confirmBooking", confirmation).str("bookingId"),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = repo.call("getBookings")["bookings"] as List<Map<String, Any>>
        val saved = rows.single { it.str("id") == booking.str("bookingId") }
        assertTrue(saved["canCancel"] == true)
        assertEquals(
            "cancelled",
            repo
                .call(
                    "cancelBooking",
                    mapOf(
                        "bookingId" to saved.str("id"),
                        "expectedVersion" to saved.long("version"),
                        "requestKey" to requestKey(),
                    ),
                )
                .str("status"),
        )
        assertEquals(2, repo.notifications().count { it.str("bookingId") == saved.str("id") })
        val after =
            repo.call("getAvailability", mapOf("spaceId" to s.id, "date" to date.toString()))
        assertFalse((after["busy"] as List<*>).contains(start))
        repo.logout()
    }
}
