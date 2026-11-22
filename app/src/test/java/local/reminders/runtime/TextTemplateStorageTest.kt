package local.reminders.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import local.reminders.domain.TextTemplate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TextTemplateStorageTest {
    @Test fun definitionsRoundTripInExistingStoreAndFullDeletionRemovesThem() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RuntimeDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val definitions = listOf(TextTemplate("meeting", "Meeting", "Hello {name}\nAt {time}."))
            db.dao().putValue(StoredValue(TextTemplateStorage.KEY, TextTemplateStorage.encode(definitions)))
            assertEquals(definitions, TextTemplateStorage.decode(db.dao().value(TextTemplateStorage.KEY)))
            Transactions(db).deleteAll()
            assertTrue(TextTemplateStorage.decode(db.dao().value(TextTemplateStorage.KEY)).isEmpty())
        } finally { db.close() }
    }
    @Test fun invalidStoredDefinitionsFailInsteadOfDisappearing() {
        assertTrue(TextTemplateStorage.decode(null).isEmpty())
        assertThrows(Exception::class.java) { TextTemplateStorage.decode("not-json") }
        assertThrows(IllegalArgumentException::class.java) {
            TextTemplateStorage.encode(listOf(TextTemplate("x", "Name", "{broken")))
        }
    }
}
