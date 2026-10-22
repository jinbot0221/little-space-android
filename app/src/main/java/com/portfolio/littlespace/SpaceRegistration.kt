package com.portfolio.littlespace

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import kotlinx.coroutines.tasks.await

val spaceTypes = listOf("스터디룸", "회의실", "촬영 스튜디오")
val spaceFacilities = listOf("Wi-Fi", "화이트보드", "콘센트", "프로젝터", "자연광", "촬영 장비", "주차", "냉난방")

data class SpaceDraft(
    val id: String,
    val name: String,
    val description: String,
    val address: String,
    val type: String,
    val capacity: Int,
    val slotPrice: Long,
    val openMinute: Int,
    val closeMinute: Int,
    val facilities: List<String>,
    val rules: String,
    val imageUrl: String,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "공간 등록 요청을 다시 시작해 주세요." }
        require(name.length in 2..60 && description.length in 10..1000) { "공간명은 2~60자, 소개는 10~1000자로 입력해 주세요." }
        require(address.length in 5..200 && address.contains("진주")) { "진주 지역의 상세 주소를 입력해 주세요." }
        require(type in spaceTypes && capacity in 1..100 && slotPrice in 1..1_000_000) { "공간 유형, 정원(1~100명), 30분 요금(1~1,000,000원)을 확인해 주세요." }
        require(openMinute in 0..1410 && closeMinute in 30..1440 && openMinute % 30 == 0 && closeMinute % 30 == 0 && closeMinute - openMinute >= 60) { "운영 시간은 30분 단위로, 하루에 최소 1시간 이상 설정해 주세요." }
        require(facilities.size <= 10 && facilities.all { it in spaceFacilities } && rules.length <= 1000) { "편의시설과 이용 수칙을 확인해 주세요." }
        require(imageUrl.length <= 2048 && (imageUrl.isEmpty() || imageUrl.matches(Regex("https://\\S+")))) { "사진 주소는 https://로 시작하는 URL을 입력해 주세요." }
    }

    fun document(uid: String): Map<String, Any> = mapOf(
        "id" to id, "hostId" to uid, "name" to name, "description" to description,
        "address" to address, "region" to "진주", "type" to type,
        "capacity" to capacity.toLong(), "slotPrice" to slotPrice,
        "openMinute" to openMinute.toLong(), "closeMinute" to closeMinute.toLong(),
        "facilities" to facilities, "rules" to rules, "imageUrl" to imageUrl,
        "active" to true, "schemaVersion" to 2L, "pricingMode" to "flat",
    )
}

private fun inventoryDocument(spaceId: String, date: LocalDate): Map<String, Any> = mapOf(
    "id" to "${spaceId}_$date", "spaceId" to spaceId, "date" to date.toString(),
    "dayStart" to Timestamp(Date(date.atStartOfDay(KST).toInstant().toEpochMilli())),
    "slots" to (0..47).associate { it.toString() to mapOf("kind" to "free") },
    "schemaVersion" to 2L, "version" to 0L, "updatedAt" to FieldValue.serverTimestamp(),
    "lastAction" to "seed", "lastId" to "seed", "lastStartSlot" to 0L, "lastCount" to 0L,
)

private suspend fun FirebaseRepository.validateOperatingDates(firstDate: LocalDate, days: Int) {
    val today = Instant.ofEpochMilli(serverNow()).atZone(KST).toLocalDate()
    require(days in 1..30 && firstDate >= today && firstDate.plusDays(days.toLong() - 1) <= today.plusDays(29)) {
        "운영일은 오늘부터 30일 이내에서 1~30일을 선택해 주세요."
    }
}

suspend fun FirebaseRepository.registerSpace(draft: SpaceDraft, firstDate: LocalDate, days: Int): String {
    draft.validate()
    check(membership().approved) { "승인된 장소 제공자만 공간을 등록할 수 있어요." }
    validateOperatingDates(firstDate, days)
    val document = draft.document(checkNotNull(auth.currentUser).uid)
    val reference = db.document("spaces/${draft.id}")
    db.runTransaction { transaction ->
        val existing = transaction.get(reference)
        if (existing.exists()) {
            if (existing.data != document) throw SparkException("REQUEST_KEY_REUSED")
        } else {
            transaction.set(reference, document)
            repeat(days) { offset ->
                val date = firstDate.plusDays(offset.toLong())
                transaction.set(db.document("sparkInventory/${draft.id}_$date"), inventoryDocument(draft.id, date))
            }
        }
    }.await()
    return draft.id
}

suspend fun FirebaseRepository.addOperatingDays(spaceId: String, firstDate: LocalDate, days: Int): Int {
    check(membership().approved) { "승인된 장소 제공자만 운영일을 추가할 수 있어요." }
    validateOperatingDates(firstDate, days)
    val uid = checkNotNull(auth.currentUser).uid
    return db.runTransaction { transaction ->
        val space = transaction.get(db.document("spaces/$spaceId"))
        check(space.getString("hostId") == uid) { "본인 공간만 관리할 수 있어요." }
        val dates = (0 until days).map { firstDate.plusDays(it.toLong()) }
        val missing = dates.filter { !transaction.get(db.document("sparkInventory/${spaceId}_$it")).exists() }
        missing.forEach { date ->
            transaction.set(db.document("sparkInventory/${spaceId}_$date"), inventoryDocument(spaceId, date))
        }
        missing.size
    }.await()
}
