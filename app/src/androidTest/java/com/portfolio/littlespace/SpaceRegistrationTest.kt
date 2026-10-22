package com.portfolio.littlespace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceRegistrationTest {
    @Test
    fun providerRegistersAndCustomerBooksWhileOperatingDayExtensionPreservesBooking() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot")
        val repo = FirebaseRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.login("host@teumgot.test", "Teumgot123!")
        val id = requestKey()
        val draft = SpaceDraft(id, "신규 등록 공간", "고객이 예약할 수 있는 조용한 테스트 공간입니다.",
            "경상남도 진주시 진주대로 100", "스터디룸", 4, 3500L, 540, 1320, listOf("Wi-Fi"), "금연", "")
        val date = LocalDate.now(KST).plusDays(4)
        assertEquals(id, repo.registerSpace(draft, date, 2))
        assertEquals(id, repo.registerSpace(draft, date, 2))
        assertTrue(repo.spaces(owned = true).any { it.id == id && it.hostId == repo.auth.currentUser!!.uid })
        repo.logout()
        repo.login("customer@teumgot.test", "Teumgot123!")
        assertTrue(repo.spaces().any { it.id == id })
        val start = date.atStartOfDay(KST).plusHours(14).toInstant().toEpochMilli()
        val hold = repo.call("createHold", mapOf("spaceId" to id, "startAt" to start,
            "endAt" to start + 7200000L, "people" to 2, "requestKey" to requestKey()))
        assertEquals(14000L, hold.long("amount"))
        val booking = repo.call("confirmBooking", mapOf("holdId" to hold.str("id"), "requestKey" to requestKey(), "paymentMode" to "success", "policyAgreed" to true))
        repo.logout()
        repo.login("host@teumgot.test", "Teumgot123!")
        assertEquals(1, repo.addOperatingDays(id, date, 3))
        assertEquals(0, repo.addOperatingDays(id, date, 3))
        val availability = repo.call("getAvailability", mapOf("spaceId" to id, "date" to date.toString()))
        assertTrue((availability["busy"] as List<*>).contains(start))
        repo.logout()
        repo.login("customer@teumgot.test", "Teumgot123!")
        assertEquals("cancelled", repo.call("cancelBooking", mapOf("bookingId" to booking.str("bookingId"), "expectedVersion" to 1, "requestKey" to requestKey())).str("status"))
        repo.logout()
    }
}
