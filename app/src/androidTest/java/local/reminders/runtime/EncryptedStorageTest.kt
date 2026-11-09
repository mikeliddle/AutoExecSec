package local.reminders.runtime

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.UUID

/**
 * Opt-in device test. Creates only an isolated synthetic database, never the live reminder database.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    @Test fun encryptedDatabaseReopensAndDoesNotContainPlaintextRecipient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-check-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sentinel = "SYNTHETIC_ENCRYPTION_RECIPIENT"
        System.loadLibrary("sqlcipher")
        fun open() = Room.databaseBuilder(context, RuntimeDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(secret.copyOf()))
            .build()
        var database: RuntimeDatabase? = null
        try {
            database = open()
            database.dao().putRecipient(RecipientEntity("synthetic_check", sentinel, "+12025550101"))
            database.close()
            database = null
            assertTrue(file.exists())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(sentinel))
            database = open()
            assertEquals(sentinel, database.dao().recipient("synthetic_check")!!.name)
        } finally {
            database?.close()
            context.deleteDatabase(name)
            secret.fill(0)
        }
    }
}
