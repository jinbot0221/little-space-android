package com.portfolio.littlespace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import java.time.LocalDate
import kotlinx.coroutines.delay

private val Purple = Color(0xFF6A55C9)
private val Ink = Color(0xFF17202F)
private val Muted = Color(0xFF7D8795)
private val Paper = Color(0xFFF7F8FA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Purple,
                        background = Paper,
                        surface = Color.White,
                        onSurface = Ink,
                    ),
                typography = Typography(),
            ) {
                TeumgotApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeumgotApp(vm: AppViewModel = viewModel()) {
    val tabs = if (vm.provider) listOf("운영 홈", "내 공간", "운영 예약", "마이") else listOf("탐색", "찜", "예약내역", "마이")
    val icons =
        listOf(
            if (vm.provider) Icons.Outlined.Dashboard else Icons.Outlined.Explore,
            if (vm.provider) Icons.Outlined.Business else Icons.Outlined.FavoriteBorder,
            Icons.Outlined.CalendarMonth,
            Icons.Outlined.PersonOutline,
        )
    BackHandler(enabled = vm.route !in tabs) { vm.back() }
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (vm.route == "탐색") "틈곳" else vm.route,
                        fontWeight = FontWeight.Bold,
                        color = if (vm.route == "탐색") Purple else Ink,
                    )
                },
                navigationIcon = {
                    if (vm.route !in tabs)
                        IconButton(onClick = { vm.back() }, enabled = !vm.loading) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로")
                        }
                },
                actions = {
                    if (vm.email != null)
                        IconButton(onClick = { vm.loadNotifications() }, enabled = !vm.loading) {
                            Icon(Icons.Outlined.NotificationsNone, "알림")
                        }
                },
            )
        },
        bottomBar = {
            if (vm.route in tabs)
                NavigationBar(containerColor = Color.White) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = vm.route == t,
                            onClick = { if (!vm.loading) vm.tab(t) },
                            icon = { Icon(icons[i], t) },
                            label = { Text(t) },
                        )
                    }
                }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (BuildConfig.USE_EMULATORS)
                Text(
                    "DEMO  ·  가상 공간 / 테스트 결제",
                    color = Purple,
                    fontSize = 11.sp,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFEEEAFB))
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                )
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { Notice(it, true) { vm.error = null } }
            vm.message?.let { Notice(it, false) { vm.message = null } }
            when (vm.route) {
                "탐색",
                "검색 결과" -> Explore(vm)
                "공간 상세" -> SpaceDetail(vm)
                "시간 선택",
                "운영 시간 차단" -> TimeSelection(vm)
                "예약 확인" -> Confirmation(vm)
                "예약 상세",
                "예약 취소" -> BookingDetail(vm)
                "찜" ->
                    if (vm.email == null) Guest(vm, "마음에 드는 공간을 모아보세요")
                    else SpaceList(vm, vm.spaces.filter { it.id in vm.favorites }, "아직 찜한 공간이 없어요")
                "예약내역",
                "운영 예약" -> if (vm.email == null) Guest(vm, "나의 예약을 한눈에 확인해요")
                    else if (vm.route == "운영 예약" && !vm.host) ProviderHome(vm) else Bookings(vm)
                "운영 홈" -> ProviderHome(vm)
                "내 공간" -> ProviderSpaces(vm)
                "공간 등록" -> SpaceRegistrationForm(vm)
                "운영일 등록" -> OperatingDaysForm(vm)
                "마이" -> MyPage(vm)
                "로그인",
                "회원가입",
                "비밀번호 찾기",
                "가입 완료" -> AuthScreen(vm)
                "알림" -> Notifications(vm)
                "후기 작성" -> ReviewForm(vm)
            }
        }
    }
    if (vm.loginPrompt)
        ModalBottomSheet(onDismissRequest = { vm.loginPrompt = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "로그인하고 이어가세요",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("찜과 예약은 회원만 이용할 수 있어요. 선택한 조건은 유지됩니다.", color = Muted)
                Primary("로그인하기") {
                    vm.loginPrompt = false
                    vm.go("로그인")
                }
                TextButton(
                    onClick = { vm.loginPrompt = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("계속 둘러보기")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
}

@Composable
fun Notice(text: String, error: Boolean, dismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (error) Color(0xFFFFEEEE) else Color(0xFFEEEAFB))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp)
        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "안내 닫기") }
    }
}

@Composable
fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Title(text: String, sub: String? = null) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
    if (sub != null) Text(sub, color = Muted, fontSize = 14.sp)
}

@Composable
fun Image(space: Space, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = space.imageUrl,
        contentDescription = "${space.name} 공간 사진",
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFE8E4F4)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Chair, "사진 불러오는 중", tint = Purple)
            }
        },
        error = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFE8E4F4)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MeetingRoom,
                    "공간 사진 없음",
                    tint = Purple,
                    modifier = Modifier.size(48.dp),
                )
            }
        },
    )
}

@Composable
fun SpaceCard(space: Space, vm: AppViewModel) {
    Card(
        onClick = { vm.openSpace(space) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Box {
            Image(space, Modifier.fillMaxWidth().height(180.dp))
            IconButton(
                onClick = { vm.toggleFavorite(space) },
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.White.copy(alpha = .95f), RoundedCornerShape(50)),
            ) {
                Icon(
                    if (space.id in vm.favorites) Icons.Outlined.Favorite
                    else Icons.Outlined.FavoriteBorder,
                    "${space.name} 찜",
                    tint = Purple,
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("진주 · ${space.type}", color = Muted, fontSize = 12.sp)
            Text(space.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "최대 ${space.capacity}명  ·  ${space.facilities.joinToString(" · ")}",
                color = Muted,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    won(space.slotPrice * 2),
                    color = Purple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                Text(" / 시간부터", color = Muted, fontSize = 12.sp)
            }
            Text("가상 공간 · 후기 평점은 아직 없어요", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
fun Explore(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Title(
                if (vm.route == "탐색") "오늘 필요한 공간,\n틈곳에서 찾아요." else "이런 공간은 어때요?",
                "필요한 시간만큼, 나에게 맞게",
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        vm.query,
                        { vm.query = it },
                        label = { Text("어떤 공간을 찾으세요?") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        vm.region,
                        { vm.region = it },
                        label = { Text("지역 직접 입력") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    DatePeople(vm)
                    Primary("공간 검색", !vm.loading) {
                        vm.go("검색 결과")
                        vm.refresh()
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("전체", "스터디룸", "회의실", "촬영 스튜디오").forEach {
                    FilterChip(
                        selected = vm.category == it,
                        onClick = { vm.category = it },
                        label = { Text(it, fontSize = 12.sp) },
                    )
                }
            }
        }
        if (vm.route == "탐색")
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEEEAFB))) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "당신의 다음 아이디어가\n시작될 곳",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Purple,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("집중하는 시간도, 함께하는 시간도.", fontSize = 13.sp, color = Muted)
                    }
                }
            }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (vm.route == "탐색") "오늘의 추천 공간" else "검색 결과 ${vm.filtered().size}곳",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                TextButton(onClick = { vm.refresh() }, enabled = !vm.loading) { Text("새로고침") }
            }
        }
        if (vm.spaces.isEmpty() && !vm.loading)
            item {
                Empty("공간을 불러오지 못했어요", "로컬 Firebase Emulator와 시드 데이터를 확인해 주세요.") { vm.refresh() }
            }
        else if (vm.filtered().isEmpty())
            item {
                Empty("조건에 맞는 공간이 없어요", "지역·인원·공간 유형을 바꿔보세요.") {
                    vm.category = "전체"
                    vm.query = ""
                    vm.region = "진주"
                    vm.people = 2
                }
            }
        items(vm.filtered(), key = { it.id }) { SpaceCard(it, vm) }
        item { Text("지도 키 없이도 목록과 지역 직접 입력으로 검색할 수 있어요.", color = Muted, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePeople(vm: AppViewModel) {
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { show = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp))
            Text(" ${vm.date}", fontSize = 13.sp)
        }
        IconButton(onClick = { if (vm.people > 1) vm.people-- }) {
            Icon(Icons.Outlined.Remove, "인원 줄이기")
        }
        Text("${vm.people}명")
        IconButton(onClick = { if (vm.people < 30) vm.people++ }) {
            Icon(Icons.Outlined.Add, "인원 늘리기")
        }
    }
    if (show) {
        val today = LocalDate.now(KST)
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = vm.date.toEpochDay() * 86400000,
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long) =
                            utcTimeMillis / 86400000 >= today.toEpochDay()
                    },
            )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            vm.date = LocalDate.ofEpochDay(it / 86400000)
                        }
                        show = false
                    }
                ) {
                    Text("선택")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("닫기") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
fun SpaceList(vm: AppViewModel, spaces: List<Space>, empty: String) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (spaces.isEmpty()) item { Empty(empty, "탐색에서 마음에 드는 공간을 찾아보세요.") { vm.tab("탐색") } }
        items(spaces, key = { it.id }) { SpaceCard(it, vm) }
    }
}

@Composable
fun SpaceDetail(vm: AppViewModel) {
    val s = vm.selected ?: return
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Image(s, Modifier.fillMaxWidth().height(260.dp))
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("진주 · ${s.type}", color = Purple)
            Title(s.name, s.description)
            Text("최대 ${s.capacity}명 · ${s.facilities.joinToString(" · ")}", color = Muted)
            HorizontalDivider()
            Text("공간 소개", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(s.description + ". 필요한 시간만큼 편하게 머물러 보세요.")
            Text("이용 안내", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(s.rules)
            Text("이용 후기 ${vm.reviews.size}개", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (vm.reviews.isEmpty()) Text("아직 작성된 후기가 없어요.", color = Muted)
            vm.reviews.forEach { r -> Text("★ ${r.long("rating")}  ${r.str("content")}") }
            Text(s.address, color = Muted)
            Text("운영 ${clock(s.openMinute)} ~ ${clock(s.closeMinute)}")
            Text(
                "30분 ${won(s.slotPrice)} · 시간당 ${won(s.slotPrice*2)}부터",
                fontWeight = FontWeight.Bold,
                color = Purple,
            )
            Text("기준가 안내입니다. 최종 금액은 서버 선점 결과로 확인해요.", fontSize = 12.sp, color = Muted)
            OutlinedButton(onClick = { vm.toggleFavorite(s) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.id in vm.favorites) "찜 해제" else "찜하기")
            }
            Primary("날짜와 시간 선택", !vm.loading) { vm.selectTimes() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
fun TimeSelection(vm: AppViewModel) {
    val s = vm.selected ?: return
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Title(s.name, "30분 단위 · 최소 1시간, 최대 8시간")
        DatePeople(vm)
        Primary("이 날짜의 가능 시간 조회", !vm.loading) { vm.availability() }
        Text("시작 시간", fontWeight = FontWeight.Bold)
        val times = (s.openMinute until s.closeMinute step 30).toList()
        times.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    val epoch =
                        vm.date.atStartOfDay(KST).plusMinutes(m.toLong()).toInstant().toEpochMilli()
                    val unavailable =
                        epoch in vm.busySlots ||
                            epoch <= System.currentTimeMillis() + vm.serverOffset
                    FilterChip(
                        selected = vm.startMinute == m,
                        onClick = { vm.startMinute = m },
                        enabled = !unavailable,
                        label = { Text(clock(m)) },
                    )
                }
            }
        }
        Text("회색: 예약·선점·운영 차단 또는 지난 시간", fontSize = 12.sp, color = Muted)
        Text("이용 시간", fontWeight = FontWeight.Bold)
        (60..480 step 30).toList().chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    FilterChip(
                        selected = vm.duration == m,
                        onClick = { vm.duration = m },
                        label = { Text("${m/60}${if(m%60!=0)".5" else ""}시간", fontSize = 12.sp) },
                    )
                }
            }
        }
        Text(
            "${clock(vm.startMinute)} ~ ${clock(vm.startMinute+vm.duration)} · ${vm.people}명",
            fontWeight = FontWeight.Bold,
        )
        Text(
            "예상 ${won(s.slotPrice*(vm.duration/30))}",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = Purple,
        )
        Text("최종 금액과 가능 여부는 서버에서 다시 확인해요.", fontSize = 12.sp, color = Muted)
        if (vm.route == "운영 시간 차단") {
            Primary("선택 시간 차단", !vm.loading) { vm.block(true) }
            OutlinedButton(
                onClick = { vm.block(false) },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("내가 만든 차단 해제")
            }
        } else {
            if (!vm.validSelection())
                Text("가능 시간을 조회하고, 정원과 운영 시간 안에서 연속된 시간을 선택해 주세요.", fontSize = 12.sp, color = Muted)
            Primary("5분간 시간 선점", !vm.loading && vm.validSelection()) { vm.createHold() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Confirmation(vm: AppViewModel) {
    val h = vm.hold ?: return
    var agreed by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("success") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() + vm.serverOffset) }
    LaunchedEffect(h.str("id")) {
        while (true) {
            now = System.currentTimeMillis() + vm.serverOffset
            delay(1000)
        }
    }
    val left = ((h.long("expiresAt") - now) / 1000).coerceAtLeast(0)
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Title("예약을 확인해 주세요", "실제 과금 없는 테스트 예약입니다.")
        Text(
            "선점 남은 시간 ${left/60}:${"%02d".format(left%60)}",
            color = Purple,
            fontWeight = FontWeight.Bold,
        )
        Summary(h)
        Text("취소 정책", fontWeight = FontWeight.Bold)
        Text("이용 시작 24시간 전까지 전액 취소할 수 있어요. 이후 고객 직접 취소는 불가합니다.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, { agreed = it })
            Text("이용 및 취소 정책에 동의합니다.")
        }
        Text("테스트 결제 결과", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("success" to "성공", "failure" to "실패", "delay" to "응답 지연").forEach { (k, label) ->
                FilterChip(
                    selected = mode == k,
                    onClick = { mode = k },
                    enabled = !vm.hasRecovery,
                    label = { Text(label) },
                )
            }
        }
        Primary(
            if (vm.hasRecovery) "동일 요청으로 결과 확인" else "테스트 예약 확정",
            !vm.loading && agreed && (left > 0 || vm.hasRecovery),
        ) {
            if (vm.hasRecovery) vm.recover() else vm.confirm(mode)
        }
        OutlinedButton(
            onClick = { vm.release() },
            enabled = !vm.loading && !vm.hasRecovery,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("선점 해제하고 돌아가기")
        }
    }
}

@Composable
fun Summary(b: Map<String, Any>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(b.str("spaceName"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(timeLabel(b.long("startAt")) + " ~ " + timeLabel(b.long("endAt")))
            Text("${b.long("people")}명 · ${b.str("address")}", color = Muted)
            HorizontalDivider()
            Text(
                won(b.long("amount")),
                color = Purple,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun status(s: String) =
    when (s) {
        "confirmed" -> "예약 확정"
        "cancelled" -> "취소 완료"
        "completed" -> "이용 완료"
        else -> s
    }

@Composable
fun BookingDetail(vm: AppViewModel) {
    val b = vm.booking ?: return
    val cancel = vm.route == "예약 취소"
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Title(if (cancel) "예약을 취소할까요?" else status(b.str("status")), "예약번호 ${b.str("id").take(10)}")
        Summary(b)
        if (cancel) {
            Text("취소 수수료 0원 · 테스트 환불 ${won(b.long("amount"))}")
            Text("정확히 24시간 전까지 취소할 수 있으며 서버 시각으로 최종 판정합니다.")
            Primary("취소 확정", !vm.loading) { vm.cancel() }
        } else {
            if (!vm.provider && b.str("status") == "confirmed") {
                Text("이용 안내", fontWeight = FontWeight.Bold)
                Text("가상 공간의 테스트 예약입니다. 실제 출입이나 이용은 제공되지 않습니다.")
                if (b["canCancel"] == true)
                    OutlinedButton(
                        onClick = { vm.go("예약 취소") },
                        enabled = !vm.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("예약 취소")
                    }
                else Text("이용 24시간 이내에는 직접 취소할 수 없어요.", color = Muted)
            }
            if (!vm.provider && b.str("status") == "completed") Primary("후기 작성") { vm.go("후기 작성") }
            Primary("예약내역 보기", !vm.loading) { vm.tab(if (vm.provider) "운영 예약" else "예약내역") }
        }
    }
}

@Composable
fun Bookings(vm: AppViewModel) {
    var filter by rememberSaveable { mutableStateOf("confirmed") }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("confirmed" to "예정", "completed" to "완료", "cancelled" to "취소").forEach {
                    (k, label) ->
                    FilterChip(
                        selected = filter == k,
                        onClick = { filter = k },
                        label = { Text(label) },
                    )
                }
            }
        }
        item {
            TextButton(
                onClick = { if (vm.route == "운영 예약") vm.hostBookings() else vm.loadBookings() },
                enabled = !vm.loading,
            ) {
                Text("최신 내역 조회")
            }
        }
        val list = vm.bookings.filter { it.str("status") == filter }
        if (list.isEmpty()) item {
            Empty("예약내역이 없어요", if (vm.provider) "등록한 공간의 예약이 여기에 표시돼요." else "원하는 공간과 시간을 찾아보세요.") {
                vm.tab(if (vm.provider) "내 공간" else "탐색")
            }
        }
        items(list, key = { it.str("id") }) { b ->
            Card(
                onClick = {
                    vm.booking = b
                    vm.go("예약 상세")
                },
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status(b.str("status")), color = Purple)
                    Text(b.str("spaceName"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(timeLabel(b.long("startAt")), color = Muted)
                    Text(won(b.long("amount")), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Guest(vm: AppViewModel, title: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(64.dp), tint = Purple)
        Title(title, "로그인하면 나만의 기록을 볼 수 있어요.")
        Primary("로그인 / 회원가입") { vm.go("로그인") }
        TextButton(onClick = { vm.tab("탐색") }) { Text("계속 둘러보기") }
    }
}

@Composable
fun ProviderHome(vm: AppViewModel) {
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Title("${vm.name ?: "제공자"}님의 운영 홈", "내 공간과 고객 예약을 관리해요")
        if (!vm.host) {
            Text("장소 제공자 승인 대기", fontWeight = FontWeight.Bold, color = Purple)
            Text("장소 제공자로 가입했어요. 관리자가 계정과 공간을 확인한 뒤 운영 권한을 승인해 드려요.")
            Text("등록 이메일: ${vm.email ?: ""}", color = Muted)
            Primary("승인 상태 새로고침", !vm.loading) { vm.refreshMemberType() }
        } else {
            Text("관리 중인 공간 ${vm.ownedSpaces().size}곳", style = MaterialTheme.typography.headlineSmall)
            Primary("새 공간 등록", !vm.loading) { vm.go("공간 등록") }
            Primary("고객 예약 관리", !vm.loading) { vm.hostBookings() }
            OutlinedButton(onClick = { vm.tab("내 공간") }, modifier = Modifier.fillMaxWidth()) { Text("내 공간 · 운영 시간 관리") }
            Text("예약 취소와 후기는 예약한 이용자가 진행해요.", color = Muted)
            TextButton(onClick = { vm.refreshMemberType() }, enabled = !vm.loading) { Text("승인 상태 새로고침") }
        }
    }
}

@Composable
fun ProviderSpaces(vm: AppViewModel) {
    if (!vm.host) {
        ProviderHome(vm)
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Title("내 공간", "공간별 운영 시간과 예약 가능한 날짜를 관리해요")
            Primary("새 공간 등록", !vm.loading) { vm.go("공간 등록") }
        }
        if (vm.ownedSpaces().isEmpty()) item {
            Text("아직 등록한 공간이 없어요. 위의 새 공간 등록으로 첫 공간을 추가해 보세요.")
        }
        items(vm.ownedSpaces(), key = { it.id }) { space ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(space.name, style = MaterialTheme.typography.titleLarge)
                    Text(space.address, color = Muted)
                    OutlinedButton(onClick = { vm.selected = space; vm.go("운영일 등록") }, enabled = !vm.loading, modifier = Modifier.fillMaxWidth()) { Text("예약 가능한 날짜 추가") }
                    Primary("운영 시간 설정", !vm.loading) {
                        vm.selected = space
                        vm.go("운영 시간 차단")
                    }
                }
            }
        }
    }
}

@Composable
fun MyPage(vm: AppViewModel) {
    if (vm.email == null) {
        Guest(vm, "반가워요, 틈곳입니다")
        return
    }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("${vm.name?:"회원"}님, 반가워요", vm.email)
        Text(if (vm.provider) "장소 제공자 · ${if (vm.host) "승인 완료" else "승인 대기"}" else "일반 이용자", color = Purple)
        OutlinedButton(onClick = { vm.tab(if (vm.provider) "운영 예약" else "예약내역") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.provider) "공간 운영 예약" else "내 예약내역")
        }
        OutlinedButton(onClick = { vm.loadNotifications() }, modifier = Modifier.fillMaxWidth()) {
            Text("알림함")
        }
        if (vm.hasRecovery) Primary("미확정 요청 결과 확인", !vm.loading) { vm.recover() }
        if (vm.host) {
            Primary("운영 예약 조회", !vm.loading) { vm.hostBookings() }
            Text("시간 차단은 소유 공간 상세에서만 서버 승인을 받아 저장됩니다.", color = Muted)
            vm.ownedSpaces().forEach { s ->
                TextButton(
                    onClick = {
                        vm.selected = s
                        vm.go("운영 시간 차단")
                    }
                ) {
                    Text("${s.name} 운영 시간 설정")
                }
            }
        }
        TextButton(onClick = { vm.logout() }, enabled = !vm.loading) { Text("로그아웃") }
        Text(
            "현재 이메일 로그인과 앱 알림함을 제공합니다. 자동 푸시와 사진 업로드는 제공하지 않습니다.",
            fontSize = 12.sp,
            color = Muted,
        )
    }
}

@Composable
fun AuthScreen(vm: AppViewModel) {
    val signup = vm.route == "회원가입"
    val complete = vm.route == "가입 완료"
    val reset = vm.route == "비밀번호 찾기"
    var mail by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var terms by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var memberType by rememberSaveable { mutableStateOf("customer") }
    val emailValid = android.util.Patterns.EMAIL_ADDRESS.matcher(mail.trim()).matches()
    val valid =
        if (complete) nickname.trim().length in 2..20 && terms && privacy
        else if (reset) emailValid
        else
            emailValid &&
                password.length in 8..64 &&
                (!signup ||
                    (nickname.trim().length in 2..20 && confirm == password && terms && privacy))
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Title(
            if (signup || complete) "틈곳에 오신 걸 환영해요"
            else if (reset) "비밀번호를 잊으셨나요?" else "나만의 공간을 만나세요",
            "이메일 계정으로 간편하게 시작해요.",
        )
        if (signup || complete) {
            Text("어떤 회원으로 이용하시나요?", fontWeight = FontWeight.Bold)
            listOf("customer" to "일반 이용자", "host" to "장소 제공자").forEach { (value, label) ->
                OutlinedButton(onClick = { memberType = value }, enabled = !vm.loading, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = memberType == value, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(if (memberType == "host") "내 공간과 예약을 관리해요. 가입 후 관리자 승인이 완료되면 운영을 시작할 수 있어요." else "공간을 탐색하고 찜하거나 예약할 수 있어요.", color = Muted)
        }
        if (signup || complete)
            OutlinedTextField(
                nickname,
                { nickname = it },
                label = { Text("닉네임 (2~20자)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        if (!complete)
            OutlinedTextField(
                mail,
                { mail = it },
                label = { Text("이메일") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        if (!reset && !complete) {
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("비밀번호 (8~64자)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (signup)
                OutlinedTextField(
                    confirm,
                    { confirm = it },
                    label = { Text("비밀번호 확인") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
        }
        if (signup || complete) {
            Text(
                "포트폴리오 테스트 서비스입니다. 실제 공간 대여와 과금은 제공하지 않습니다. 가입 시 이메일·닉네임·예약 기록을 Firebase에 저장합니다.",
                color = Muted,
                fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(terms, { terms = it })
                Text("[필수] 테스트 서비스 이용약관 동의")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(privacy, { privacy = it })
                Text("[필수] 개인정보 수집·이용 동의")
            }
        }
        Primary(
            if (signup || complete) "가입 완료" else if (reset) "재설정 메일 보내기" else "로그인",
            valid && !vm.loading,
        ) {
            when {
                complete -> vm.complete(nickname.trim(), memberType)
                signup -> vm.signup(mail, password, nickname, memberType)
                reset -> vm.reset(mail)
                else -> vm.login(mail, password)
            }
        }
        if (!complete) {
            if (!signup && !reset) {
                TextButton(onClick = { vm.go("회원가입") }) { Text("이메일 회원가입") }
                TextButton(onClick = { vm.go("비밀번호 찾기") }) { Text("비밀번호 찾기") }
            }
            Text("Google · 카카오 · Apple 로그인은 공급자 설정 후 제공 예정입니다.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Notifications(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.notices.isEmpty()) item { Text("최근 알림이 없어요.", color = Muted) }
        items(vm.notices, key = { it.str("id") }) { n ->
            Card(
                onClick = { vm.openNotice(n) },
                colors =
                    CardDefaults.cardColors(
                        containerColor = if (n["read"] == true) Color.White else Color(0xFFEEEAFB)
                    ),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n.str("title"), fontWeight = FontWeight.Bold)
                    Text(n.str("body"), color = Muted)
                    Text(timeLabel(n.long("createdAt")), fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
fun ReviewForm(vm: AppViewModel) {
    var rating by rememberSaveable { mutableIntStateOf(5) }
    var content by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Title("공간은 어떠셨나요?", "완료 예약당 한 번 작성할 수 있어요.")
        Row {
            (1..5).forEach { i ->
                IconButton(onClick = { rating = i }) {
                    Icon(Icons.Outlined.Star, "${i}점", tint = if (i <= rating) Purple else Muted)
                }
            }
        }
        OutlinedTextField(
            content,
            { content = it.take(500) },
            label = { Text("후기 10~500자") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Text("${content.trim().length}/500", color = Muted)
        Primary("후기 저장", !vm.loading && content.trim().length in 10..500) {
            vm.review(rating, content)
        }
    }
}

@Composable
fun Empty(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.SearchOff, null, Modifier.size(48.dp), tint = Muted)
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 13.sp)
        TextButton(onClick = onClick) { Text("다시 찾아보기") }
    }
}
