package local.reminders.runtime

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import local.reminders.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

@Entity(tableName = "recipients")
internal data class RecipientEntity(@PrimaryKey val id: String, val name: String, val phone: String,
    val firstName: String? = null, val lastName: String? = null) {
    fun model() = Recipient(id, name, phone, firstName, lastName)
}

@Entity(tableName = "reminders", indices = [
    Index(value = ["state", "sendAt"]),
    Index(value = ["state", "updatedAt"])
])
internal data class ReminderEntity(
    @PrimaryKey val id: String,
    val recipientId: String,
    val name: String,
    val phone: String,
    val message: String,
    val appointmentAt: Long,
    val sendAt: Long,
    val zone: String,
    val state: String = ReminderState.PENDING.name,
    val subscriptionId: Int,
    val attemptId: String? = null,
    val owner: String? = null,
    val attemptAt: Long? = null,
    val deadline: Long? = null,
    val detail: String = "",
    val parts: Int,
    val sentResults: String = "[]",
    val deliveryResults: String = "[]",
    val delivered: Boolean = false,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'[]'") val members: String = "[]",
    val broadcastId: String? = null,
    val broadcastExpiresAt: Long? = null,
    val broadcastDelaySeconds: Long? = null
) {
    fun people(): List<Recipient> = PeopleCodec.decode(members).ifEmpty { listOf(Recipient(recipientId, name, phone)) }
    val group: Boolean get() = people().size > 1
    fun model(reviewed: Boolean = false) = ReminderRecord(id, recipientId, name, phone, message, appointmentAt, sendAt,
        zone, ReminderState.valueOf(state), subscriptionId, attemptId, detail, parts, delivered, reviewed, people(),
        broadcastId, broadcastExpiresAt, broadcastDelaySeconds)
    val expiresAt: Long get() = if (broadcastId != null) requireNotNull(broadcastExpiresAt)
        else minOf(appointmentAt, sendAt + Limits.GRACE_MILLIS + 1)
    fun expired(now: Long) = now >= expiresAt
    fun dispatchable(now: Long) = now >= sendAt && !expired(now)
}

// Tombstones contain only the opaque draft digest; deleting history never permits a second send.
@Entity(tableName = "tombstones")
internal data class Tombstone(@PrimaryKey val id: String)

@Entity(tableName = "values")
internal data class StoredValue(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "coverage")
internal data class Coverage(@PrimaryKey val date: String, val source: String)

@Dao
internal interface RuntimeDao {
    @Query("SELECT * FROM recipients ORDER BY name, id") fun recipients(): List<RecipientEntity>
    @Query("SELECT * FROM recipients WHERE id = :id") fun recipient(id: String): RecipientEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putRecipient(value: RecipientEntity)
    @Query("DELETE FROM recipients WHERE id = :id") fun deleteRecipient(id: String)
    @Query("DELETE FROM recipients") fun deleteRecipients()
    @Query("SELECT * FROM reminders ORDER BY sendAt, id") fun reminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE state IN ('PENDING', 'SUBMITTING') ORDER BY sendAt, id")
    fun activeReminders(): List<ReminderEntity>
    @Query("""SELECT EXISTS(SELECT 1 FROM reminders r WHERE state = 'UNCERTAIN'
        AND NOT EXISTS(SELECT 1 FROM `values` v WHERE v.`key` = 'reviewed:' || r.id AND v.value = 'true'))""")
    fun hasUnreviewedUnknown(): Boolean
    @Query("SELECT * FROM reminders WHERE state IN ('SENT','FAILED','EXPIRED','CANCELLED') AND updatedAt <= :before")
    fun finalizedBefore(before: Long): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id = :id") fun reminder(id: String): ReminderEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertReminder(value: ReminderEntity)
    @Update fun updateReminder(value: ReminderEntity)
    @Query("DELETE FROM reminders WHERE id = :id") fun deleteReminder(id: String)
    @Query("SELECT COUNT(*) FROM tombstones WHERE id = :id") fun tombstone(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putTombstone(value: Tombstone)
    @Query("SELECT value FROM `values` WHERE `key` = :key") fun value(key: String): String?
    @Query("SELECT * FROM `values` WHERE `key` LIKE 'rate:%'") fun rateAttempts(): List<StoredValue>
    @Query("SELECT * FROM `values` WHERE `key` LIKE 'reviewed:%' AND value = 'true'")
    fun reviewedReminders(): List<StoredValue>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putValue(value: StoredValue)
    @Query("DELETE FROM `values` WHERE `key` = :key") fun deleteValue(key: String)
    @Query("""DELETE FROM `values` WHERE `key` LIKE 'broadcast-next:%' AND `key` NOT IN
        (SELECT 'broadcast-next:' || broadcastId FROM reminders WHERE broadcastId IS NOT NULL)""")
    fun deleteUnusedBroadcastPacing()
    @Query("SELECT * FROM coverage") fun coverage(): List<Coverage>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putCoverage(value: Coverage)
    @Query("DELETE FROM coverage") fun deleteCoverage()
    @Query("DELETE FROM coverage WHERE date = :date AND source = 'approved'") fun removeApproval(date: String)
    @Query("""UPDATE reminders SET state = 'SUBMITTING', attemptId = :attempt,
        owner = :owner, attemptAt = :now, deadline = :deadline, updatedAt = :now,
        detail = 'Awaiting authoritative submission results.'
        WHERE id = :id AND state = 'PENDING'""")
    fun claim(id: String, attempt: String, owner: String, deadline: Long, now: Long): Int
}

@Database(
    entities = [RecipientEntity::class, ReminderEntity::class, StoredValue::class,
        Coverage::class, Tombstone::class],
    version = 4,
    exportSchema = true
)
internal abstract class RuntimeDatabase : RoomDatabase() {
    abstract fun dao(): RuntimeDao
    companion object {
        const val NAME = "reminders.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipients ADD COLUMN firstName TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipients ADD COLUMN lastName TEXT")
                db.execSQL("ALTER TABLE reminders ADD COLUMN members TEXT NOT NULL DEFAULT '[]'")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN broadcastId TEXT")
                db.execSQL("ALTER TABLE reminders ADD COLUMN broadcastExpiresAt INTEGER")
                db.execSQL("ALTER TABLE reminders ADD COLUMN broadcastDelaySeconds INTEGER")
            }
        }
        fun encrypted(context: Context): RuntimeDatabase {
            check(requireNotNull(context.getSystemService(android.os.UserManager::class.java)).isUserUnlocked) {
                "Unlock the device before opening reminders."
            }
            System.loadLibrary("sqlcipher")
            val key = DatabaseKey.load(context)
            return Room.databaseBuilder(context, RuntimeDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key))
                .build()
        }
    }
}

internal object SettingsCodec {
    fun encode(s: AppSettings): String = JSONObject().apply {
        put("country", s.country); put("template", s.template); put("lead", s.leadMinutes)
        put("sim", s.subscriptionId); put("enabled", s.promptEnabled)
        put("weekdays", JSONArray(s.promptWeekdays.map { it.value }))
        put("time", s.promptTime.toString()); put("dates", JSONArray(s.promptDates.map { it.toString() }))
        put("lock", s.screenLock); put("retention", s.retentionDays)
        put("simAutomatic", s.subscriptionAutomatic)
        put("groupTemplates", JSONObject().apply { s.groupTemplates.forEach { (count, template) -> put(count.toString(), template) } })
        put("broadcastTemplate", s.broadcastTemplate); put("broadcastDelaySeconds", s.broadcastDelaySeconds)
    }.toString()
    fun decode(raw: String?): AppSettings {
        if (raw == null) return AppSettings()
        val j = JSONObject(raw)
        val days = j.getJSONArray("weekdays")
        val dates = j.getJSONArray("dates")
        return AppSettings(j.getString("country"), j.getString("template"), j.getLong("lead"),
            j.getInt("sim"), j.getBoolean("enabled"),
            (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
            LocalTime.parse(j.getString("time")),
            (0 until dates.length()).map { LocalDate.parse(dates.getString(it)) }.toSet(),
            j.getBoolean("lock"), j.getInt("retention"), j.optBoolean("simAutomatic", false),
            j.optJSONObject("groupTemplates")?.let { groups ->
                groups.keys().asSequence().associate { it.toInt() to groups.getString(it) }
            }.orEmpty(), j.optString("broadcastTemplate", "Hello {FirstName}."),
            j.optLong("broadcastDelaySeconds", Broadcasts.DEFAULT_DELAY_SECONDS))
    }
}

internal object PeopleCodec {
    fun encode(people: List<Recipient>): String = JSONArray().apply {
        people.forEach { person -> put(JSONObject().apply {
            put("id", person.id); put("name", person.name); put("phone", person.phone)
            put("firstName", person.firstName ?: JSONObject.NULL); put("lastName", person.lastName ?: JSONObject.NULL)
        }) }
    }.toString()

    fun decode(raw: String): List<Recipient> {
        val array = JSONArray(raw)
        return (0 until array.length()).map {
            val person = array.getJSONObject(it)
            Recipient(person.getString("id"), person.getString("name"), person.getString("phone"),
                if (person.isNull("firstName")) null else person.getString("firstName"),
                if (person.isNull("lastName")) null else person.getString("lastName"))
        }
    }
}
internal fun results(raw: String, parts: Int): MutableList<Int?> {
    val array = JSONArray(raw)
    return MutableList(parts) { if (it >= array.length() || array.isNull(it)) null else array.getInt(it) }
}

internal fun encodeResults(values: List<Int?>): String =
    JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }.toString()
