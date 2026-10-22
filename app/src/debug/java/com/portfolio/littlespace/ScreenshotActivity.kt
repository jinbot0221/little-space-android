package com.portfolio.littlespace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Offline screenshot fixtures; this activity does not exist in release builds. */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixtures =
            listOf(
                Space(
                    "focus-a",
                    "demo-host",
                    "포커스 룸 A",
                    "몰입이 필요한 순간, 오롯이 나만의 공간",
                    "경상남도 진주시 진주대로 1035",
                    "스터디룸",
                    6,
                    5000,
                    "",
                    listOf("Wi-Fi", "화이트보드", "콘센트"),
                    "실내 금연 · 간단한 음료만 반입 가능해요. 이용 후 정리해 주세요.",
                    540,
                    1320,
                ),
                Space(
                    "round-table",
                    "demo-host",
                    "라운드 테이블",
                    "좋은 아이디어가 시작되는 곳",
                    "경상남도 진주시 진주대로 1035",
                    "회의실",
                    10,
                    7500,
                    "",
                    listOf("Wi-Fi", "프로젝터", "화이트보드"),
                    "실내 금연 · 정시 입퇴실을 지켜주세요.",
                    540,
                    1320,
                ),
                Space(
                    "slow-studio",
                    "demo-host",
                    "슬로우 스튜디오",
                    "햇살과 함께 만드는 새로운 장면",
                    "경상남도 진주시 진주대로 1035",
                    "촬영 스튜디오",
                    4,
                    10000,
                    "",
                    listOf("Wi-Fi", "자연광", "촬영 장비"),
                    "실내 금연 · 장비를 소중히 다뤄주세요.",
                    540,
                    1320,
                ),
            )
        val model = AppViewModel(application, fixtures)
        model.selected = fixtures.first()
        model.people = 4
        val now = System.currentTimeMillis()
        val start = model.date.atStartOfDay(KST).plusHours(14).toInstant().toEpochMilli()
        val reservation =
            mapOf<String, Any>(
                "id" to "screenshot-booking-001",
                "spaceName" to fixtures.first().name,
                "address" to fixtures.first().address,
                "startAt" to start,
                "endAt" to start + 7200000L,
                "people" to 4,
                "amount" to 20000,
                "status" to "confirmed",
                "version" to 1,
                "canCancel" to true,
                "expiresAt" to now + 300000L,
            )
        model.hold = reservation
        model.booking = reservation
        val routes =
            mapOf(
                "home" to "탐색",
                "search" to "검색 결과",
                "detail" to "공간 상세",
                "time" to "시간 선택",
                "favorites" to "찜",
                "bookings" to "예약내역",
                "my" to "마이",
                "login" to "로그인",
                "signup" to "회원가입",
                "space-register" to "공간 등록",
                "reset" to "비밀번호 찾기",
                "confirm" to "예약 확인",
                "booking" to "예약 상세",
                "cancel" to "예약 취소",
                "review" to "후기 작성",
            )
        if (intent.getStringExtra("screen") == "space-register") {
            model.host = true // Offline visual fixture only; server permission is never changed.
            model.name = "테스트 제공자"
        }
        model.go(routes[intent.getStringExtra("screen")] ?: "탐색")
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF6A55C9),
                        background = Color(0xFFF7F8FA),
                        surface = Color.White,
                        onSurface = Color(0xFF17202F),
                    )
            ) {
                TeumgotApp(model)
            }
        }
    }
}
