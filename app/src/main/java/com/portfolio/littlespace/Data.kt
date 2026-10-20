package com.portfolio.littlespace

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

val KST: ZoneId = ZoneId.of("Asia/Seoul")

fun won(n: Long) = "%,d원".format(n)

fun timeLabel(t: Long): String =
    Instant.ofEpochMilli(t).atZone(KST).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))

data class Space(
    val id: String,
    val hostId: String,
    val name: String,
    val description: String,
    val address: String,
    val type: String,
    val capacity: Int,
    val slotPrice: Long,
    val imageUrl: String,
    val facilities: List<String>,
    val rules: String,
    val openMinute: Int,
    val closeMinute: Int,
)

fun spaceOf(m: Map<String, Any>, id: String) =
    Space(
        id,
        m["hostId"] as? String ?: "",
        m["name"] as? String ?: "",
        m["description"] as? String ?: "",
        m["address"] as? String ?: "",
        m["type"] as? String ?: "",
        (m["capacity"] as? Number)?.toInt() ?: 0,
        (m["slotPrice"] as? Number)?.toLong() ?: 0,
        m["imageUrl"] as? String ?: "",
        (m["facilities"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        m["rules"] as? String ?: "",
        (m["openMinute"] as? Number)?.toInt() ?: 540,
        (m["closeMinute"] as? Number)?.toInt() ?: 1320,
    )

fun Map<String, Any>.long(k: String) = (get(k) as? Number)?.toLong() ?: 0L

fun Map<String, Any>.str(k: String) = get(k) as? String ?: ""

class FirebaseRepository(context: Context) {
    private val app =
        FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .build(),
            )
    val auth = FirebaseAuth.getInstance(app)
    val db = FirebaseFirestore.getInstance(app)
    private val functions = FirebaseFunctions.getInstance(app, "asia-northeast3")
    private val prefs = context.getSharedPreferences("transaction_recovery", Context.MODE_PRIVATE)

    init {
        if (BuildConfig.USE_EMULATORS) {
            auth.useEmulator(BuildConfig.EMULATOR_HOST, 9099)
            db.useEmulator(BuildConfig.EMULATOR_HOST, 8080)
            functions.useEmulator(BuildConfig.EMULATOR_HOST, 5001)
        }
    }

    suspend fun call(name: String, data: Map<String, Any> = emptyMap()): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return functions.getHttpsCallable(name).call(data).await().data as Map<String, Any>
    }

    suspend fun spaces() =
        db.collection("spaces").whereEqualTo("active", true).get().await().documents.map {
            spaceOf(it.data!!, it.id)
        }

    suspend fun login(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun signup(email: String, password: String, nickname: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
        completeProfile(nickname)
    }

    suspend fun completeProfile(nickname: String) {
        val user = checkNotNull(auth.currentUser)
        db.document("users/${user.uid}")
            .set(
                mapOf(
                    "nickname" to nickname,
                    "termsAccepted" to true,
                    "termsVersion" to "1.0",
                    "privacyVersion" to "1.0",
                )
            )
            .await()
        user
            .updateProfile(UserProfileChangeRequest.Builder().setDisplayName(nickname).build())
            .await()
    }

    suspend fun hasProfile() =
        auth.currentUser?.let {
            db.document("users/${it.uid}").get().await().getBoolean("termsAccepted") == true
        } ?: false

    suspend fun reset(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    suspend fun favorites(): Set<String> {
        val uid = auth.currentUser?.uid ?: return emptySet()
        return db.collection("users/$uid/favorites").get().await().documents.map { it.id }.toSet()
    }

    suspend fun favorite(id: String, save: Boolean) {
        val ref = db.document("users/${checkNotNull(auth.currentUser).uid}/favorites/$id")
        if (save) ref.set(mapOf("spaceId" to id)).await() else ref.delete().await()
    }

    suspend fun reviews(spaceId: String) =
        db.collection("reviews")
            .whereEqualTo("spaceId", spaceId)
            .limit(30)
            .get()
            .await()
            .documents
            .map { it.data!! }

    suspend fun notifications() =
        db.collection("users/${checkNotNull(auth.currentUser).uid}/notifications")
            .get()
            .await()
            .documents
            .map { it.data!!.plus("id" to it.id) }
            .filter { it.long("createdAt") >= System.currentTimeMillis() - 30 * 86400000L }
            .sortedByDescending { it.long("createdAt") }

    suspend fun markRead(id: String) {
        db.document("users/${checkNotNull(auth.currentUser).uid}/notifications/$id")
            .update("read", true)
            .await()
    }

    suspend fun rememberConfirm(holdId: String, key: String, mode: String) =
        withContext(Dispatchers.IO) {
            val saved =
                prefs
                    .edit()
                    .putString("uid", auth.currentUser?.uid)
                    .putString("holdId", holdId)
                    .putString("key", key)
                    .putString("mode", mode)
                    .commit()
            check(saved) { "요청 복구 정보를 저장하지 못했어요. 예약을 전송하지 않았습니다." }
        }

    fun pending(): Map<String, Any>? =
        if (prefs.getString("uid", null) == auth.currentUser?.uid && prefs.contains("key"))
            mapOf(
                "holdId" to prefs.getString("holdId", "")!!,
                "requestKey" to prefs.getString("key", "")!!,
                "paymentMode" to prefs.getString("mode", "success")!!,
                "policyAgreed" to true,
            )
        else null

    suspend fun clearPending() =
        withContext(Dispatchers.IO) {
            check(prefs.edit().clear().commit()) { "확정 결과를 정리하지 못했어요. 같은 요청으로 다시 확인해 주세요." }
        }

    fun logout() {
        auth.signOut()
    }
}

fun requestKey() = UUID.randomUUID().toString()
