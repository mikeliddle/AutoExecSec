package local.reminders.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MigrationTest {
    @Test fun versionOneMigratesWithoutChangingFrozenRemindersOrLegacyPeople() = migrateFrom(1)
    @Test fun versionTwoMigratesWithoutChangingFrozenRemindersOrExplicitFirstNames() = migrateFrom(2)
    @Test fun versionThreeMigratesWithoutChangingExistingMmsOrSmsRows() = migrateFrom(3)

    private fun migrateFrom(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        try {
            val schema = requireNotNull(javaClass.classLoader?.getResourceAsStream(
                "local.reminders.runtime.RuntimeDatabase/$version.json")).bufferedReader().use { JSONObject(it.readText()) }
                .getJSONObject("database")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length())
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO recipients VALUES ('legacy','Mary Ann Example','+12025550101'" +
                    (when (version) { 3 -> ",'Mary Ann','Example')"; 2 -> ",'Mary Ann')"; else -> ")" }))
                old.execSQL("""INSERT INTO reminders
                    (id,recipientId,name,phone,message,appointmentAt,sendAt,zone,state,subscriptionId,
                    detail,parts,sentResults,deliveryResults,delivered,updatedAt)
                    VALUES ('frozen','legacy','Mary Ann Example','+12025550101','Hey Mary',
                    1800007200000,1800000000000,'America/New_York','PENDING',17,'',1,'[]','[]',0,1800000000000)""")
                if (version == 3) old.execSQL("""INSERT INTO reminders
                    (id,recipientId,name,phone,message,appointmentAt,sendAt,zone,state,subscriptionId,
                    detail,parts,sentResults,deliveryResults,delivered,updatedAt,members)
                    VALUES ('group_frozen','legacy','Mary Ann Example','+12025550101','Frozen group message',
                    1800007200000,1800000000000,'America/New_York','PENDING',17,'',1,'[]','[]',0,1800000000000,?)""",
                    arrayOf(PeopleCodec.encode(listOf(
                        local.reminders.domain.Recipient("legacy", "Mary Ann Example", "+12025550101", "Mary Ann", "Example"),
                        local.reminders.domain.Recipient("sam", "Sam Example", "+12025550102", "Sam", "Example")))))
                old.execSQL("INSERT INTO tombstones VALUES ('processed')")
                old.execSQL("INSERT INTO coverage VALUES ('2027-01-12','approved')")
                old.execSQL("INSERT INTO `values` VALUES ('settings','unchanged encrypted content')")
                old.version = version
            }
            val migrated = Room.databaseBuilder(context, RuntimeDatabase::class.java, name)
                .addMigrations(RuntimeDatabase.MIGRATION_1_2, RuntimeDatabase.MIGRATION_2_3,
                    RuntimeDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
            try {
                    val dao = migrated.dao()
                    assertEquals("legacy", dao.recipients().single().id)
                    assertEquals(if (version >= 2) "Mary Ann" else null, dao.recipients().single().firstName)
                    assertEquals(if (version == 3) "Example" else null, dao.recipients().single().lastName)
                    val reminder = requireNotNull(dao.reminder("frozen"))
                    assertEquals("PENDING", reminder.state)
                    assertEquals("Hey Mary", reminder.message)
                    assertEquals("America/New_York", reminder.zone)
                    assertEquals(1800007200000L, reminder.appointmentAt)
                    assertEquals(17, reminder.subscriptionId)
                    assertFalse(reminder.group)
                    assertNull(reminder.broadcastId)
                    assertNull(reminder.broadcastExpiresAt)
                    assertNull(reminder.broadcastDelaySeconds)
                    assertEquals("legacy", reminder.people().single().id)
                    if (version == 3) {
                        val group = requireNotNull(dao.reminder("group_frozen"))
                        assertTrue(group.group)
                        assertEquals(listOf("legacy", "sam"), group.people().map { it.id })
                        assertEquals("Frozen group message", group.message)
                        assertEquals("PENDING", group.state)
                        assertNull(group.broadcastId)
                    }
                    assertEquals(1, dao.tombstone("processed"))
                    assertEquals("approved", dao.coverage().single().source)
                    assertEquals("unchanged encrypted content", dao.value("settings"))
                    dao.putRecipient(dao.recipients().single().copy(firstName = "Mary Ann"))
                } finally { migrated.close() }
                val reopened = Room.databaseBuilder(context, RuntimeDatabase::class.java, name)
                    .addMigrations(RuntimeDatabase.MIGRATION_1_2, RuntimeDatabase.MIGRATION_2_3,
                        RuntimeDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
                try {
                    assertEquals("Mary Ann", reopened.dao().recipients().single().model().firstName)
                    assertEquals("Hey Mary", reopened.dao().reminder("frozen")!!.message)
                } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
