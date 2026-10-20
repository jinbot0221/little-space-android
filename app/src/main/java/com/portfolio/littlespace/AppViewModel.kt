package com.portfolio.littlespace

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.functions.FirebaseFunctionsException
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FirebaseRepository(app)
    var route by mutableStateOf("탐색")
        private set

    private val backStack = mutableListOf<String>()
    var spaces by mutableStateOf<List<Space>>(emptyList())
        private set

    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set

    var bookings by mutableStateOf<List<Map<String, Any>>>(emptyList())
        private set

    var reviews by mutableStateOf<List<Map<String, Any>>>(emptyList())
        private set

    var notices by mutableStateOf<List<Map<String, Any>>>(emptyList())
        private set

    var selected by mutableStateOf<Space?>(null)
    var booking by mutableStateOf<Map<String, Any>?>(null)
    var hold by mutableStateOf<Map<String, Any>?>(null)
    var busySlots by mutableStateOf<Set<Long>>(emptySet())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var email by mutableStateOf(repo.auth.currentUser?.email)
    var name by mutableStateOf(repo.auth.currentUser?.displayName)
    var host by mutableStateOf(false)
    var loginPrompt by mutableStateOf(false)
    var region by mutableStateOf("진주")
    var query by mutableStateOf("")
    var category by mutableStateOf("전체")
    var date by mutableStateOf(LocalDate.now(KST).plusDays(1))
    var people by mutableIntStateOf(2)
    var startMinute by mutableIntStateOf(840)
    var duration by mutableIntStateOf(120)
    var availabilityReady by mutableStateOf(false)
        private set

    var hasRecovery by mutableStateOf(repo.pending() != null)
        private set

    var serverOffset by mutableLongStateOf(0L)
        private set

    private var availabilityFor = ""
    private val keys = mutableMapOf<String, String>()

    private fun key(op: String, body: Map<String, Any>): String =
        keys.getOrPut(op + body.toSortedMap().toString()) { requestKey() }

    private suspend fun mutation(
        op: String,
        name: String,
        body: Map<String, Any>,
    ): Map<String, Any> {
        val result = repo.call(name, body + ("requestKey" to key(op, body)))
        keys.remove(op + body.toSortedMap().toString())
        return result
    }

    private fun run(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = errorText(e)
                val code = ((e as? FirebaseFunctionsException)?.details as? Map<*, *>)?.get("code")
                if (code == "HOLD_EXPIRED" && repo.pending() != null) {
                    repo.clearPending()
                    hasRecovery = false
                    hold = null
                    route = if (selected != null) "시간 선택" else "예약내역"
                    availabilityReady = false
                }
            } finally {
                loading = false
            }
        }
    }

    private fun errorText(e: Exception): String {
        val code =
            (e as? FirebaseFunctionsException)?.details.let {
                (it as? Map<*, *>)?.get("code") as? String
            }
        return when (code) {
            "SLOT_TAKEN" -> "방금 다른 사용자가 선택한 시간이에요. 가능 시간을 새로 조회해 주세요."
            "HOLD_EXPIRED" -> "5분 선점이 만료됐어요. 시간을 다시 선택해 주세요."
            "CANCEL_DEADLINE" -> "이용 24시간 전까지만 취소할 수 있어요."
            "REQUEST_KEY_REUSED" -> "기존 요청과 내용이 달라요. 예약내역에서 결과를 확인해 주세요."
            "TERMS_REQUIRED" -> "가입을 완료해 주세요."
            "STATE_CONFLICT" -> "상태가 변경됐어요. 예약내역을 다시 조회해 주세요."
            else -> e.localizedMessage ?: "연결을 확인하고 다시 시도해 주세요."
        }
    }

    init {
        refresh()
    }

    fun go(next: String) {
        if (next == route) return
        backStack.add(route)
        route = next
    }

    fun back() {
        if (loading) return
        if (route == "예약 확인" && hasRecovery) {
            message = "미확정 요청의 결과를 먼저 확인해 주세요."
            return
        }
        if (route == "예약 확인" && hold != null) {
            release()
            return
        }
        route = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else "탐색"
    }

    fun tab(next: String) {
        backStack.clear()
        route = next
        if (email != null) {
            if (next == "예약내역") loadBookings()
            if (next == "찜") refreshFavorites()
        }
    }

    fun protect(action: () -> Unit) {
        if (email == null) loginPrompt = true else action()
    }

    fun refresh() = run {
        spaces = repo.spaces()
        if (email != null) {
            favorites = repo.favorites()
            host = repo.auth.currentUser?.getIdToken(false)?.awaitTokenHost() ?: false
        }
    }

    fun openSpace(space: Space) {
        selected = space
        reviews = emptyList()
        go("공간 상세")
        run { reviews = repo.reviews(space.id) }
    }

    fun filtered() =
        spaces.filter {
            it.capacity >= people &&
                it.address.contains(region) &&
                (category == "전체" || it.type == category) &&
                (query.isBlank() || it.name.contains(query) || it.description.contains(query))
        }

    fun refreshFavorites() = run { favorites = repo.favorites() }

    fun toggleFavorite(space: Space) = protect {
        run {
            val save = space.id !in favorites
            repo.favorite(space.id, save)
            favorites = repo.favorites()
        }
    }

    private fun finishAuth() {
        while (backStack.lastOrNull() in listOf("로그인", "회원가입", "비밀번호 찾기", "가입 완료")) backStack
            .removeAt(backStack.lastIndex)
        route = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else "탐색"
    }

    fun login(mail: String, password: String) = run {
        repo.login(mail.trim(), password)
        email = repo.auth.currentUser?.email
        name = repo.auth.currentUser?.displayName
        if (repo.hasProfile()) finishAuth() else route = "가입 완료"
        favorites = repo.favorites()
        host = repo.auth.currentUser?.getIdToken(false)?.awaitTokenHost() ?: false
        hasRecovery = repo.pending() != null
    }

    fun signup(mail: String, password: String, nickname: String) = run {
        repo.signup(mail.trim(), password, nickname.trim())
        email = repo.auth.currentUser?.email
        name = nickname
        finishAuth()
    }

    fun complete(nickname: String) = run {
        repo.completeProfile(nickname)
        name = nickname
        finishAuth()
    }

    fun reset(mail: String) = run {
        try {
            repo.reset(mail.trim())
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            /* Same public response. */
        }
        message = "가입된 이메일이라면 재설정 링크가 발송됩니다."
    }

    fun logout() {
        repo.logout()
        email = null
        name = null
        host = false
        favorites = emptySet()
        bookings = emptyList()
        notices = emptyList()
        hold = null
        booking = null
        hasRecovery = false
        tab("탐색")
    }

    fun selectTimes() {
        go("시간 선택")
        availability()
    }

    fun availability() {
        availabilityReady = false
        run {
            val s = selected ?: return@run
            val result =
                repo.call("getAvailability", mapOf("spaceId" to s.id, "date" to date.toString()))
            busySlots =
                (result["busy"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toSet()
                    ?: emptySet()
            serverOffset = result.long("serverNow") - System.currentTimeMillis()
            availabilityFor = "${s.id}:${date}"
            availabilityReady = true
        }
    }

    fun startAt() =
        date.atStartOfDay(KST).plusMinutes(startMinute.toLong()).toInstant().toEpochMilli()

    fun validSelection(): Boolean {
        val s = selected ?: return false
        val start = startAt()
        return availabilityReady &&
            availabilityFor == "${s.id}:${date}" &&
            people <= s.capacity &&
            startMinute >= s.openMinute &&
            startMinute + duration <= s.closeMinute &&
            start > System.currentTimeMillis() + serverOffset &&
            (0 until duration / 30).none { start + it * 1800000L in busySlots }
    }

    fun createHold() = protect {
        run {
            val s = selected ?: return@run
            val body =
                mapOf(
                    "spaceId" to s.id,
                    "startAt" to startAt(),
                    "endAt" to startAt() + duration * 60000L,
                    "people" to people,
                )
            hold = mutation("hold", "createHold", body)
            serverOffset = hold!!.long("serverNow") - System.currentTimeMillis()
            go("예약 확인")
        }
    }

    fun confirm(mode: String) = run {
        val h = hold ?: return@run
        val body =
            repo.pending()
                ?: mapOf(
                        "holdId" to h.str("id"),
                        "paymentMode" to mode,
                        "policyAgreed" to true,
                        "requestKey" to requestKey(),
                    )
                    .also { repo.rememberConfirm(h.str("id"), it.str("requestKey"), mode) }
        hasRecovery = true
        finishConfirm(repo.call("confirmBooking", body))
    }

    private suspend fun finishConfirm(result: Map<String, Any>) {
        when (result.str("status")) {
            "confirmed" -> {
                repo.clearPending()
                hasRecovery = false
                hold = null
                route = "예약내역"
                val r = repo.call("getBookings")
                bookings = maps(r["bookings"])
                booking = bookings.first { it.str("id") == result.str("bookingId") }
                hold = null
                backStack.clear()
                route = "예약 상세"
            }
            "payment_failed" -> {
                repo.clearPending()
                hasRecovery = false
                hold = null
                route = "시간 선택"
                message = "테스트 결제에 실패해 선점을 해제했어요. 시간을 새로 조회해 주세요."
                availabilityReady = false
            }
            "pending" -> {
                message = result.str("message")
            }
        }
    }

    fun recover() = run {
        val p = repo.pending() ?: return@run
        finishConfirm(repo.call("confirmBooking", p))
    }

    fun release() = run {
        val h = hold ?: return@run
        repo.call(
            "releaseHold",
            mapOf(
                "holdId" to h.str("id"),
                "requestKey" to key("release", mapOf("holdId" to h.str("id"))),
            ),
        )
        hold = null
        route = "시간 선택"
        availabilityReady = false
    }

    fun loadBookings() = run {
        val r = repo.call("getBookings")
        bookings = maps(r["bookings"])
        booking = booking?.let { old -> bookings.find { it.str("id") == old.str("id") } }
    }

    fun cancel() = run {
        val b = booking ?: return@run
        val body = mapOf("bookingId" to b.str("id"), "expectedVersion" to b.long("version"))
        mutation("cancel", "cancelBooking", body)
        val r = repo.call("getBookings")
        bookings = maps(r["bookings"])
        booking = bookings.first { it.str("id") == b.str("id") }
        route = "예약 상세"
    }

    fun review(rating: Int, content: String) = run {
        val b = booking ?: return@run
        val body =
            mapOf("bookingId" to b.str("id"), "rating" to rating, "content" to content.trim())
        mutation("review", "submitReview", body)
        message = "후기를 저장했어요."
        route = "예약 상세"
    }

    fun loadNotifications() {
        go("알림")
        run { notices = repo.notifications() }
    }

    fun openNotice(n: Map<String, Any>) = run {
        repo.markRead(n.str("id"))
        val r = repo.call("getBookings")
        bookings = maps(r["bookings"])
        booking = bookings.find { it.str("id") == n.str("bookingId") }
        if (booking != null) go("예약 상세") else message = "예약내역에서 최신 상태를 확인해 주세요."
    }

    fun hostBookings() {
        go("운영 예약")
        run { bookings = maps(repo.call("getHostBookings")["bookings"]) }
    }

    fun ownedSpaces() = spaces.filter { it.hostId == repo.auth.currentUser?.uid }

    fun block(blocked: Boolean) = run {
        val s = selected ?: return@run
        val body =
            mapOf(
                "spaceId" to s.id,
                "startAt" to startAt(),
                "endAt" to startAt() + duration * 60000L,
                "blocked" to blocked,
            )
        mutation("block", "setBlock", body)
        message = "운영 시간을 변경했어요."
        availabilityReady = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?) =
        (v as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
}

private suspend fun com.google.android.gms.tasks.Task<com.google.firebase.auth.GetTokenResult>
    .awaitTokenHost() = this.await().claims["host"] == true
