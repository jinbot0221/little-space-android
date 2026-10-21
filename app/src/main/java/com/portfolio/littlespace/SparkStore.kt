package com.portfolio.littlespace

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/** Spark runtime: all authority is in firestore.rules, never in these client checks. */
class SparkException(val code: String) : IllegalStateException(code)

class SparkStore(private val db: FirebaseFirestore, private val auth: FirebaseAuth) {
    companion object {
        const val SLOT = 1800000L
        const val TTL = 300000L
    }

    private fun fail(code: String): Nothing = throw SparkException(code)

    private fun uid() = auth.currentUser?.uid ?: fail("UNAUTHENTICATED")

    private fun stamp(ms: Long) = Timestamp(Date(ms))

    private fun fieldTime(m: Map<String, Any>, key: String) = (m[key] as Timestamp).toDate().time

    private fun docData(d: DocumentSnapshot) = d.data ?: fail("NOT_FOUND")

    private fun clean(value: Any?): Any? =
        when (value) {
            is Timestamp -> value.toDate().time
            is Map<*, *> -> value.entries.associate { it.key.toString() to clean(it.value) }
            is List<*> -> value.map { clean(it) }
            else -> value
        }

    @Suppress("UNCHECKED_CAST") private fun view(m: Map<String, Any>) = clean(m) as Map<String, Any>

    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(KST).toLocalDate().toString()

    private fun inventoryId(spaceId: String, start: Long) = spaceId + "_" + date(start)

    private fun slots(m: Map<String, Any>): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return (m["slots"] as Map<String, Any>).toMutableMap()
    }

    private fun occupied(s: Map<*, *>, now: Long) =
        when (s["kind"]) {
            "booking",
            "block" -> true
            "hold" -> (s["stamp"] as Timestamp).toDate().time + TTL > now
            else -> false
        }

    private fun range(m: Map<String, Any>) =
        (m.long("startSlot").toInt() until (m.long("startSlot") + m.long("slotCount")).toInt())
            .map { it.toString() }

    private fun updateInventory(
        tx: Transaction,
        ref: DocumentReference,
        old: Map<String, Any>,
        values: Map<String, Any>,
        action: String,
        id: String,
        start: Long,
        count: Long,
    ) {
        tx.update(
            ref,
            mapOf(
                "slots" to values,
                "version" to old.long("version") + 1,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastAction" to action,
                "lastId" to id,
                "lastStartSlot" to start,
                "lastCount" to count,
            ),
        )
    }

    suspend fun serverNow(): Long {
        if (auth.currentUser == null)
            return System.currentTimeMillis() // Guest preview only; rules always decide writes.
        val ref = db.document("users/${uid()}/clock/current")
        ref.set(mapOf("at" to FieldValue.serverTimestamp())).await()
        return ref.get(Source.SERVER).await().getTimestamp("at")!!.toDate().time
    }

    suspend fun availability(d: Map<String, Any>): Map<String, Any> {
        val now = serverNow()
        val inv =
            db.document("sparkInventory/${d.str("spaceId")}_${d.str("date")}")
                .get(Source.SERVER)
                .await()
        if (!inv.exists()) fail("DAY_UNAVAILABLE")
        val data = docData(inv)
        val day = fieldTime(data, "dayStart")
        val busy =
            slots(data)
                .filter { (_, v) -> occupied(v as Map<*, *>, now) }
                .keys
                .map { day + it.toLong() * SLOT }
        return mapOf("busy" to busy, "serverNow" to now)
    }

    suspend fun createHold(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val now = serverNow()
        val id = d.str("requestKey")
        val hRef = db.document("sparkHolds/$id")
        val spaceId = d.str("spaceId")
        val start = d.long("startAt")
        val end = d.long("endAt")
        val iRef = db.document("sparkInventory/${inventoryId(spaceId,start)}")
        db.runTransaction { tx ->
                val previous = tx.get(hRef)
                if (previous.exists()) {
                    val h = docData(previous)
                    if (
                        h["userId"] != user ||
                            h["spaceId"] != spaceId ||
                            fieldTime(h, "startAt") != start ||
                            fieldTime(h, "endAt") != end ||
                            h.long("people") != d.long("people")
                    )
                        fail("REQUEST_KEY_REUSED")
                    return@runTransaction null
                }
                val s = docData(tx.get(db.document("spaces/$spaceId")))
                val invDoc = tx.get(iRef)
                if (!invDoc.exists()) fail("DAY_UNAVAILABLE")
                val inv = docData(invDoc)
                val day = fieldTime(inv, "dayStart")
                val slot = (start - day) / SLOT
                val count = (end - start) / SLOT
                if (
                    start <= now ||
                        start % SLOT != 0L ||
                        end % SLOT != 0L ||
                        count !in 2..16 ||
                        slot < 0 ||
                        slot + count > 48 ||
                        d.long("people") !in 1..s.long("capacity") ||
                        s["active"] != true ||
                        s["pricingMode"] != "flat" ||
                        slot * 30 < s.long("openMinute") ||
                        (slot + count) * 30 > s.long("closeMinute")
                )
                    fail("INVALID_SELECTION")
                val values = slots(inv)
                val keys = (slot until slot + count).map { it.toString() }
                if (keys.any { occupied(values[it] as Map<*, *>, now) }) fail("SLOT_TAKEN")
                val h =
                    mapOf(
                        "id" to id,
                        "userId" to user,
                        "spaceId" to spaceId,
                        "inventoryId" to iRef.id,
                        "date" to date(start),
                        "startSlot" to slot,
                        "slotCount" to count,
                        "startAt" to stamp(start),
                        "endAt" to stamp(end),
                        "people" to d.long("people"),
                        "amount" to s.long("slotPrice") * count,
                        "slotPrice" to s.long("slotPrice"),
                        "spaceName" to s.str("name"),
                        "hostId" to s.str("hostId"),
                        "address" to s.str("address"),
                        "requestKey" to id,
                        "status" to "active",
                        "version" to 1,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                keys.forEach {
                    values[it] =
                        mapOf("kind" to "hold", "id" to id, "stamp" to FieldValue.serverTimestamp())
                }
                tx.set(hRef, h)
                updateInventory(tx, iRef, inv, values, "hold", id, slot, count)
                null
            }
            .await()
        val h = docData(hRef.get(Source.SERVER).await())
        if (h.str("status") != "active" || fieldTime(h, "createdAt") + TTL <= now)
            fail("HOLD_EXPIRED")
        return view(h) + mapOf("expiresAt" to fieldTime(h, "createdAt") + TTL, "serverNow" to now)
    }

    private fun release(
        tx: Transaction,
        hRef: DocumentReference,
        h: Map<String, Any>,
        iRef: DocumentReference,
        inv: Map<String, Any>,
        key: String,
        reason: String,
    ) {
        val values = slots(inv)
        range(h).forEach { k ->
            val slot = values[k] as Map<*, *>
            if (slot["kind"] == "hold" && slot["id"] == h.str("id"))
                values[k] = mapOf("kind" to "free")
        }
        tx.update(
            hRef,
            mapOf(
                "status" to "released",
                "version" to h.long("version") + 1,
                "updatedAt" to FieldValue.serverTimestamp(),
                "releaseRequestKey" to key,
                "releaseReason" to reason,
            ),
        )
        updateInventory(
            tx,
            iRef,
            inv,
            values,
            "release",
            h.str("id"),
            h.long("startSlot"),
            h.long("slotCount"),
        )
    }

    suspend fun releaseHold(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val ref = db.document("sparkHolds/${d.str("holdId")}")
        db.runTransaction { tx ->
                val h = docData(tx.get(ref))
                if (h.str("userId") != user) fail("FORBIDDEN")
                if (h.str("status") == "active") {
                    val ir = db.document("sparkInventory/${h.str("inventoryId")}")
                    val inv = docData(tx.get(ir))
                    release(tx, ref, h, ir, inv, d.str("requestKey"), "user")
                }
                null
            }
            .await()
        return mapOf("released" to true)
    }

    private fun inbox(tx: Transaction, b: Map<String, Any>) {
        val status = b.str("status")
        val title = if (status == "confirmed") "공간 예약이 확정됐어요" else "공간 예약이 취소됐어요"
        setOf(b.str("userId"), b.str("hostId")).forEach { user ->
            tx.set(
                db.document("users/$user/notifications/${b.str("id")}_${b.long("version")}"),
                mapOf(
                    "bookingId" to b.str("id"),
                    "version" to b.long("version"),
                    "status" to status,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "read" to false,
                    "title" to title,
                    "body" to b.str("spaceName") + " · 예약내역에서 확인해 주세요.",
                ),
            )
        }
    }

    private suspend fun committed(
        ref: DocumentReference,
        original: Exception,
        matches: (Map<String, Any>) -> Boolean,
    ): Map<String, Any> {
        val saved =
            try {
                ref.get(Source.SERVER).await().data
            } catch (_: Exception) {
                null
            }
        if (saved != null && matches(saved)) return saved
        throw original
    }

    suspend fun confirm(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val now = serverNow()
        val id = d.str("holdId")
        val key = d.str("requestKey")
        val mode = d.str("paymentMode")
        if (d["policyAgreed"] != true || mode !in listOf("success", "failure", "delay"))
            fail("CONSENT_REQUIRED")
        val hRef = db.document("sparkHolds/$id")
        val bRef = db.document("sparkBookings/$id")
        val result =
            try {
                db.runTransaction { tx ->
                        val bDoc = tx.get(bRef)
                        if (bDoc.exists()) {
                            val b = docData(bDoc)
                            if (
                                b.str("userId") != user ||
                                    b.str("requestKey") != key ||
                                    b.str("testMode") != mode
                            )
                                fail("REQUEST_KEY_REUSED")
                            return@runTransaction mapOf<String, Any>(
                                "status" to "confirmed",
                                "bookingId" to id,
                            )
                        }
                        val h = docData(tx.get(hRef))
                        if (h.str("userId") != user) fail("FORBIDDEN")
                        if (
                            mode == "failure" &&
                                h.str("status") == "released" &&
                                h.str("releaseRequestKey") == key &&
                                h.str("releaseReason") == "payment_failed"
                        )
                            return@runTransaction mapOf<String, Any>("status" to "payment_failed")
                        if (h.str("status") != "active" || fieldTime(h, "createdAt") + TTL <= now)
                            fail("HOLD_EXPIRED")
                        val ir = db.document("sparkInventory/${h.str("inventoryId")}")
                        val inv = docData(tx.get(ir))
                        val values = slots(inv)
                        if (
                            range(h).any { k ->
                                val s = values[k] as Map<*, *>
                                s["kind"] != "hold" || s["id"] != id
                            }
                        )
                            fail("SLOT_TAKEN")
                        if (mode == "failure") {
                            release(tx, hRef, h, ir, inv, key, "payment_failed")
                            return@runTransaction mapOf<String, Any>("status" to "payment_failed")
                        }
                        val b =
                            h.filterKeys {
                                    it !in
                                        setOf(
                                            "createdAt",
                                            "updatedAt",
                                            "requestKey",
                                            "status",
                                            "version",
                                        )
                                }
                                .toMutableMap()
                        b.putAll(
                            mapOf(
                                "holdId" to id,
                                "status" to "confirmed",
                                "version" to 1,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "requestKey" to key,
                                "paymentMode" to "test",
                                "testMode" to mode,
                            )
                        )
                        range(h).forEach { values[it] = mapOf("kind" to "booking", "id" to id) }
                        tx.set(bRef, b)
                        tx.update(
                            hRef,
                            mapOf(
                                "status" to "confirmed",
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "version" to h.long("version") + 1,
                            ),
                        )
                        updateInventory(
                            tx,
                            ir,
                            inv,
                            values,
                            "confirm",
                            id,
                            h.long("startSlot"),
                            h.long("slotCount"),
                        )
                        inbox(tx, b)
                        mapOf<String, Any>("status" to "confirmed", "bookingId" to id)
                    }
                    .await()
            } catch (e: FirebaseFirestoreException) {
                committed(bRef, e) { b ->
                    b.str("userId") == user &&
                        b.str("requestKey") == key &&
                        b.str("testMode") == mode
                }
                mapOf<String, Any>("status" to "confirmed", "bookingId" to id)
            }
        if (mode == "delay") delay(3000)
        return result
    }

    suspend fun cancel(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val now = serverNow()
        val id = d.str("bookingId")
        val ref = db.document("sparkBookings/$id")
        db.runTransaction { tx ->
                val b = docData(tx.get(ref))
                if (b.str("userId") != user) fail("FORBIDDEN")
                if (b.str("status") == "cancelled") return@runTransaction null
                if (
                    b.long("version") != d.long("expectedVersion") || b.str("status") != "confirmed"
                )
                    fail("STATE_CONFLICT")
                if (fieldTime(b, "startAt") - now < 86400000L) fail("CANCEL_DEADLINE")
                val ir = db.document("sparkInventory/${b.str("inventoryId")}")
                val inv = docData(tx.get(ir))
                val values = slots(inv)
                range(b).forEach { k ->
                    val s = values[k] as Map<*, *>
                    if (s["kind"] != "booking" || s["id"] != id) fail("STATE_CONFLICT")
                    values[k] = mapOf("kind" to "free")
                }
                val next =
                    b +
                        mapOf(
                            "status" to "cancelled",
                            "version" to b.long("version") + 1,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "refundAmount" to b.long("amount"),
                            "cancelRequestKey" to d.str("requestKey"),
                        )
                tx.update(ref, next)
                updateInventory(
                    tx,
                    ir,
                    inv,
                    values,
                    "cancel",
                    id,
                    b.long("startSlot"),
                    b.long("slotCount"),
                )
                inbox(tx, next)
                null
            }
            .await()
        return mapOf("status" to "cancelled", "bookingId" to id)
    }

    suspend fun bookings(host: Boolean = false): Map<String, Any> {
        val now = serverNow()
        val field = if (host) "hostId" else "userId"
        val docs =
            db.collection("sparkBookings")
                .whereEqualTo(field, uid())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .get(Source.SERVER)
                .await()
        val values =
            docs.documents.map { doc ->
                val b = view(docData(doc))
                b +
                    mapOf(
                        "status" to
                            if (b.str("status") == "confirmed" && b.long("endAt") <= now)
                                "completed"
                            else b.str("status"),
                        "canCancel" to
                            (!host &&
                                b.str("status") == "confirmed" &&
                                b.long("startAt") - now >= 86400000L),
                    )
            }
        return mapOf("serverNow" to now, "bookings" to values)
    }

    suspend fun review(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val id = d.str("bookingId")
        val ref = db.document("sparkReviews/$id")
        val content = d.str("content").trim()
        if (content.length !in 10..500 || d.long("rating") !in 1..5) fail("INVALID_REVIEW")
        db.runTransaction { tx ->
                val old = tx.get(ref)
                if (old.exists()) {
                    val r = docData(old)
                    if (
                        r.str("requestKey") != d.str("requestKey") ||
                            r.str("content") != content ||
                            r.long("rating") != d.long("rating")
                    )
                        fail("STATE_CONFLICT")
                    return@runTransaction null
                }
                val b = docData(tx.get(db.document("sparkBookings/$id")))
                tx.set(
                    ref,
                    mapOf(
                        "bookingId" to id,
                        "userId" to user,
                        "spaceId" to b.str("spaceId"),
                        "rating" to d.long("rating"),
                        "content" to content,
                        "requestKey" to d.str("requestKey"),
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
                null
            }
            .await()
        return mapOf("saved" to true)
    }

    suspend fun block(d: Map<String, Any>): Map<String, Any> {
        val user = uid()
        val now = serverNow()
        val id = d.str("requestKey")
        val op = db.document("sparkOperations/$id")
        val start = d.long("startAt")
        val end = d.long("endAt")
        val sid = d.str("spaceId")
        val ir = db.document("sparkInventory/${inventoryId(sid,start)}")
        db.runTransaction { tx ->
                val old = tx.get(op)
                if (old.exists()) {
                    val o = docData(old)
                    if (
                        o.str("userId") != user ||
                            o.str("spaceId") != sid ||
                            fieldTime(o, "startAt") != start ||
                            fieldTime(o, "endAt") != end ||
                            o["blocked"] != d["blocked"]
                    )
                        fail("REQUEST_KEY_REUSED")
                    return@runTransaction null
                }
                val inv = docData(tx.get(ir))
                val day = fieldTime(inv, "dayStart")
                val slot = (start - day) / SLOT
                val count = (end - start) / SLOT
                if (
                    start <= now ||
                        start % SLOT != 0L ||
                        end % SLOT != 0L ||
                        count !in 2..16 ||
                        slot < 0 ||
                        slot + count > 48
                )
                    fail("INVALID_SELECTION")
                val values = slots(inv)
                val blocked = d["blocked"] == true
                (slot until slot + count).forEach { n ->
                    val k = n.toString()
                    val s = values[k] as Map<*, *>
                    if (blocked) {
                        if (occupied(s, now)) fail("SLOT_TAKEN")
                        values[k] = mapOf("kind" to "block", "id" to user)
                    } else if (s["kind"] == "block" && s["id"] == user)
                        values[k] = mapOf("kind" to "free")
                }
                tx.set(
                    op,
                    mapOf(
                        "id" to id,
                        "requestKey" to id,
                        "userId" to user,
                        "spaceId" to sid,
                        "inventoryId" to ir.id,
                        "date" to date(start),
                        "startSlot" to slot,
                        "slotCount" to count,
                        "startAt" to stamp(start),
                        "endAt" to stamp(end),
                        "blocked" to blocked,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
                updateInventory(
                    tx,
                    ir,
                    inv,
                    values,
                    if (blocked) "block" else "unblock",
                    id,
                    slot,
                    count,
                )
                null
            }
            .await()
        return mapOf("saved" to true)
    }
}
