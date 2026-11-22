package local.reminders.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import local.reminders.ReminderApplication
import java.io.FileNotFoundException
import java.util.concurrent.Semaphore

/** The platform receives one exact URI grant, never a directory, file, or database grant. */
class MmsPayloadProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.wap.mms-message"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = requireNotNull(context)
        val path = uri.pathSegments
        if (mode != "r" || uri.authority != "${ctx.packageName}.mms" || path.size != 3 || path[0] != "mms" ||
            !path[1].matches(Regex("[a-f0-9]{64}")) || !path[2].matches(Regex("[a-f0-9-]{36}")))
            throw FileNotFoundException("MMS payload is unavailable.")
        try {
            val bytes = (ctx.applicationContext as ReminderApplication).runtime.mmsPayload(path[1], path[2])
            return openMemory(ctx, bytes)
        } catch (_: Exception) {
            throw FileNotFoundException("MMS payload is unavailable.")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        throw UnsupportedOperationException("MMS payloads are read-only streams.")
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("MMS payloads are read-only.")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("MMS payloads are read-only.")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("MMS payloads are read-only.")

    companion object {
        private val slots = Semaphore(8)
        private val handler by lazy { Handler(HandlerThread("mms-payload").apply { start() }.looper) }

        internal fun openMemory(ctx: android.content.Context, bytes: ByteArray): ParcelFileDescriptor {
            if (!slots.tryAcquire()) {
                bytes.fill(0)
                throw FileNotFoundException("MMS stream limit reached.")
            }
            try {
                return requireNotNull(ctx.getSystemService(StorageManager::class.java))
                    .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = bytes.size.toLong()
                    override fun onRead(offset: Long, size: Int, buffer: ByteArray): Int {
                        if (offset < 0 || offset >= bytes.size || size <= 0) return 0
                        val count = minOf(size, buffer.size, bytes.size - offset.toInt())
                        bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
                        return count
                    }
                    override fun onRelease() { bytes.fill(0); slots.release() }
                }, handler)
            } catch (_: Exception) {
                bytes.fill(0)
                slots.release()
                throw FileNotFoundException("MMS stream unavailable.")
            }
        }

        fun uri(context: android.content.Context, id: String, attempt: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.mms")
                .appendPath("mms").appendPath(id).appendPath(attempt).build()
    }
}
