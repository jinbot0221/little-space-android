package com.portfolio.littlespace

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemberTypeTest {
    @Test
    fun signupPersistsTypeAndRestoresCorrectHomeWithoutSelfGrantingHost() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = FirebaseRepository(app)
        for (type in listOf("customer", "host")) {
            val email = "$type-${System.nanoTime()}@teumgot.test"
            repo.signup(email, "Teumgot123!", "신규회원", type)
            assertEquals(type, repo.accountType())
            assertFalse(repo.membership().approved)
            assertNotEquals(true, repo.auth.currentUser!!.getIdToken(true).await().claims["host"])
            repo.logout()
            repo.login(email, "Teumgot123!")
            lateinit var vm: AppViewModel
            instrumentation.runOnMainSync { vm = AppViewModel(app) }
            withTimeout(20_000) {
                while (true) {
                    var loaded = false
                    instrumentation.runOnMainSync { loaded = !vm.loading }
                    if (loaded) break
                    delay(50)
                }
            }
            instrumentation.runOnMainSync {
                assertNull(vm.error)
                assertEquals(type, vm.accountType)
                assertEquals(type == "host", vm.provider)
                assertFalse(vm.host)
                assertEquals(if (type == "host") "운영 홈" else "탐색", vm.route)
                vm.logout()
                assertFalse(vm.provider)
                assertEquals("탐색", vm.route)
            }
            withTimeout(20_000) {
                while (true) {
                    var loaded = false
                    instrumentation.runOnMainSync { loaded = !vm.loading }
                    if (loaded) break
                    delay(50)
                }
            }
        }
    }

    @Test
    fun approvedHostRestoresOperationsAndQueriesOnlyOwnedSpaces() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = FirebaseRepository(app)
        repo.login("host@teumgot.test", "Teumgot123!")
        assertNotEquals(true, repo.auth.currentUser!!.getIdToken(true).await().claims["host"])
        lateinit var vm: AppViewModel
        instrumentation.runOnMainSync { vm = AppViewModel(app) }
        withTimeout(20_000) {
            while (true) {
                var loaded = false
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (loaded) break
                delay(50)
            }
        }
        instrumentation.runOnMainSync {
            assertNull(vm.error)
            assertTrue(vm.host)
            assertTrue(vm.provider)
            assertEquals("운영 홈", vm.route)
            assertTrue(vm.ownedSpaces().map { it.id }.containsAll(listOf("focus-a", "round-table", "slow-studio")))
            assertTrue(vm.spaces.all { it.hostId == repo.auth.currentUser!!.uid })
            vm.logout()
        }
        withTimeout(20_000) {
            while (true) {
                var loaded = false
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (loaded) break
                delay(50)
            }
        }
    }
    @Test
    fun signedInAccountWithoutProfileResumesSignupOnStartup() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = FirebaseRepository(app)
        repo.auth.createUserWithEmailAndPassword("incomplete-${System.nanoTime()}@teumgot.test", "Teumgot123!").await()
        lateinit var vm: AppViewModel
        instrumentation.runOnMainSync { vm = AppViewModel(app) }
        withTimeout(20_000) {
            while (true) {
                var loaded = false
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (loaded) break
                delay(50)
            }
        }
        instrumentation.runOnMainSync {
            assertNull(vm.error)
            assertEquals("가입 완료", vm.route)
            assertNotNull(vm.email)
            vm.logout()
        }
        withTimeout(20_000) {
            while (true) {
                var loaded = false
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (loaded) break
                delay(50)
            }
        }
    }

    @Test
    fun consoleApprovalAndRevocationRefreshWithoutRelogin() = runBlocking {
        check(BuildConfig.USE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-teumgot")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = FirebaseRepository(app)
        repo.signup("approval-${System.nanoTime()}@teumgot.test", "Teumgot123!", "제공신청", "host")
        lateinit var vm: AppViewModel
        instrumentation.runOnMainSync { vm = AppViewModel(app) }
        suspend fun waitForLoad() = withTimeout(20_000) {
            while (true) {
                var loaded = false
                instrumentation.runOnMainSync { loaded = !vm.loading }
                if (loaded) break
                delay(50)
            }
        }
        waitForLoad()
        instrumentation.runOnMainSync {
            assertNull(vm.error)
            assertTrue(vm.provider)
            assertFalse(vm.host)
        }
        for (approved in listOf(true, false)) {
            // Emulator-only admin REST token simulates the console; never used by production code.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val uid = repo.auth.currentUser!!.uid
                val connection = java.net.URL("http://${BuildConfig.EMULATOR_HOST}:8080/v1/projects/demo-teumgot/databases/(default)/documents/users/$uid?updateMask.fieldPaths=approved").openConnection() as java.net.HttpURLConnection
                try {
                    connection.requestMethod = "PATCH"
                    connection.setRequestProperty("Authorization", "Bearer owner")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.outputStream.use { it.write("{\"fields\":{\"approved\":{\"booleanValue\":$approved}}}".toByteArray()) }
                    assertEquals(200, connection.responseCode)
                } finally { connection.disconnect() }
            }
            instrumentation.runOnMainSync { vm.refreshMemberType() }
            waitForLoad()
            instrumentation.runOnMainSync {
                assertNull(vm.error)
                assertEquals(approved, vm.host)
                assertTrue(vm.provider)
                assertEquals("운영 홈", vm.route)
            }
            assertNotEquals(true, repo.auth.currentUser!!.getIdToken(true).await().claims["host"])
        }
        instrumentation.runOnMainSync { vm.logout() }
        waitForLoad()
    }

}
