package com.portfolio.littlespace

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

data class Membership(val accountType: String, val approved: Boolean)

class FirebaseReadDenied(val target: String, cause: Exception) : IllegalStateException(target, cause)

class FirebaseRepository(context: Context) {
    companion object {
        private val emulatorApps = mutableSetOf<FirebaseApp>()
    }
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
    private val spark = SparkStore(db, auth)
    private val prefs =
        context.getSharedPreferences("transaction_recovery_spark_v2", Context.MODE_PRIVATE)

    init {
        synchronized(emulatorApps) {
            if (BuildConfig.USE_EMULATORS && app !in emulatorApps) {
                auth.useEmulator(BuildConfig.EMULATOR_HOST, 9099)
                db.useEmulator(BuildConfig.EMULATOR_HOST, 8080)
                emulatorApps.add(app)
            }
        }
    }

    suspend fun call(name: String, data: Map<String, Any> = emptyMap()): Map<String, Any> =
        withTimeout(20_000L) {
            when (name) {
                "getAvailability" -> spark.availability(data)
                "createHold" -> spark.createHold(data)
                "releaseHold" -> spark.releaseHold(data)
                "confirmBooking" -> spark.confirm(data)
                "cancelBooking" -> spark.cancel(data)
                "getBookings" -> spark.bookings()
                "getHostBookings" -> spark.bookings(host = true)
                "submitReview" -> spark.review(data)
                "setBlock" -> spark.block(data)
                else -> error("Unknown Spark operation: $name")
            }
        }

    suspend fun serverNow() = spark.serverNow()

    suspend fun spaces(owned: Boolean = false): List<Space> {
        var query: Query = db.collection("spaces")
            .whereEqualTo("active", true)
            .whereEqualTo("schemaVersion", 2)
        if (owned) query = query.whereEqualTo("hostId", checkNotNull(auth.currentUser).uid)
        return try {
            query.limit(30).get().await().documents.map { spaceOf(it.data!!, it.id) }
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw FirebaseReadDenied("spaces", e)
            throw e
        }
    }

    suspend fun login(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun signup(email: String, password: String, nickname: String, accountType: String = "customer") {
        auth.createUserWithEmailAndPassword(email, password).await()
        completeProfile(nickname, accountType)
    }

    suspend fun completeProfile(nickname: String, accountType: String = "customer") {
        require(accountType in listOf("customer", "host"))
        val user = checkNotNull(auth.currentUser)
        db.document("users/${user.uid}")
            .set(
                mapOf(
                    "nickname" to nickname,
                    "accountType" to accountType,
                    "approved" to false,
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

    private suspend fun profile() = auth.currentUser?.let {
        try {
            db.document("users/${it.uid}").get(com.google.firebase.firestore.Source.SERVER).await()
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw FirebaseReadDenied("users", e)
            throw e
        }
    }

    suspend fun membership(): Membership {
        val document = profile()
        val type = document?.getString("accountType") ?: "customer"
        return Membership(type, type == "host" && document?.get("approved") == true && document.get("termsAccepted") == true)
    }

    suspend fun accountType(): String = profile()?.getString("accountType") ?: "customer"

    suspend fun hasProfile() = profile()?.getBoolean("termsAccepted") == true

    suspend fun reset(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    suspend fun favorites(): Set<String> {
        val uid = auth.currentUser?.uid ?: return emptySet()
        return db.collection("users/$uid/favorites")
            .limit(50)
            .get()
            .await()
            .documents
            .map { it.id }
            .toSet()
    }

    suspend fun favorite(id: String, save: Boolean) {
        val ref = db.document("users/${checkNotNull(auth.currentUser).uid}/favorites/$id")
        if (save) ref.set(mapOf("spaceId" to id)).await() else ref.delete().await()
    }

    suspend fun reviews(spaceId: String) =
        db.collection("sparkReviews")
            .whereEqualTo("spaceId", spaceId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .get()
            .await()
            .documents
            .map { it.data!! }

    suspend fun notifications(): List<Map<String, Any>> {
        val now = spark.serverNow()
        return db.collection("users/${checkNotNull(auth.currentUser).uid}/notifications")
            .whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(now - 30 * 86400000L)))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .get()
            .await()
            .documents
            .map { doc ->
                doc.data!!
                    .mapValues { (_, v) -> if (v is Timestamp) v.toDate().time else v }
                    .plus("id" to doc.id)
            }
    }

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
