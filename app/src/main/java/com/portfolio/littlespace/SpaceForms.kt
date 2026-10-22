package com.portfolio.littlespace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private fun parseOpeningTime(value: String): Int? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..24 || minute !in listOf(0, 30) || (hour == 24 && minute != 0)) return null
    return hour * 60 + minute
}

@Composable
private fun FormField(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        singleLine = true)
}

@Composable
fun SpaceRegistrationForm(vm: AppViewModel) {
    if (!vm.host) { ProviderHome(vm); return }
    val id = rememberSaveable { requestKey() }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("스터디룸") }
    var capacity by rememberSaveable { mutableStateOf("4") }
    var price by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf("09:00") }
    var close by rememberSaveable { mutableStateOf("22:00") }
    var facilities by rememberSaveable { mutableStateOf(listOf<String>()) }
    var rules by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf(LocalDate.now(KST).plusDays(1).toString()) }
    var days by rememberSaveable { mutableStateOf("7") }
    val date = runCatching { LocalDate.parse(first) }.getOrNull()
    val count = days.toIntOrNull()
    val draft = runCatching {
        SpaceDraft(id, name.trim(), description.trim(), address.trim(), type,
            capacity.toInt(), price.toLong(), checkNotNull(parseOpeningTime(open)), checkNotNull(parseOpeningTime(close)),
            facilities, rules.trim(), image.trim()).also { it.validate() }
    }.getOrNull()
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title("새 공간 등록", "등록한 공간은 일반회원의 탐색 화면에 공개돼요.")
        FormField("공간명 (2~60자)", name) { name = it }
        OutlinedTextField(description, { description = it }, label = { Text("공간 소개 (10~1000자)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        FormField("진주 지역 상세 주소", address) { address = it }
        Text("공간 유형")
        spaceTypes.forEach { option -> FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) }) }
        FormField("최대 인원 (1~100명)", capacity, true) { capacity = it }
        FormField("30분 요금 (원, 1~1,000,000)", price, true) { price = it }
        FormField("운영 시작 (예: 09:00)", open) { open = it }
        FormField("운영 종료 (예: 22:00, 최대 24:00)", close) { close = it }
        Text("운영 시간은 30분 단위로 최소 1시간 이상 설정해 주세요.")
        Text("편의시설")
        spaceFacilities.forEach { facility ->
            FilterChip(selected = facility in facilities, onClick = {
                facilities = if (facility in facilities) facilities - facility else facilities + facility
            }, label = { Text(facility) })
        }
        OutlinedTextField(rules, { rules = it }, label = { Text("이용 수칙 (선택, 최대 1000자)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        FormField("사진 https:// 주소 (선택)", image) { image = it }
        Text("사진 주소를 비워두면 기본 이미지가 표시돼요.")
        FormField("첫 운영일 (YYYY-MM-DD)", first) { first = it }
        FormField("연속 운영일 수 (1~30일)", days, true) { days = it }
        Text("오늘부터 30일 이내의 날짜만 등록할 수 있어요. 이후 운영일은 내 공간에서 추가할 수 있어요.")
        Primary("공간과 운영일 등록", !vm.loading && draft != null && date != null && count != null && count in 1..30) {
            vm.registerSpace(checkNotNull(draft), checkNotNull(date), checkNotNull(count))
        }
    }
}

@Composable
fun OperatingDaysForm(vm: AppViewModel) {
    if (!vm.host) { ProviderHome(vm); return }
    val space = vm.selected ?: return
    var first by rememberSaveable { mutableStateOf(LocalDate.now(KST).plusDays(1).toString()) }
    var days by rememberSaveable { mutableStateOf("7") }
    val date = runCatching { LocalDate.parse(first) }.getOrNull()
    val count = days.toIntOrNull()
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("${space.name} 운영일 추가", "기존 운영일과 예약을 유지하고 누락된 날짜만 추가해요.")
        FormField("첫 운영일 (YYYY-MM-DD)", first) { first = it }
        FormField("연속 운영일 수 (1~30일)", days, true) { days = it }
        Text("오늘부터 30일 이내의 날짜를 선택해 주세요.")
        Primary("예약 가능한 운영일 추가", !vm.loading && date != null && count != null && count in 1..30) {
            vm.addOperatingDays(checkNotNull(date), checkNotNull(count))
        }
    }
}
