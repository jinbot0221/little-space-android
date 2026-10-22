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
            assertEquals(3, vm.ownedSpaces().size)
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
}
