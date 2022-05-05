package tool.compet.storage.extension

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import tool.compet.core.DkLogcats
import tool.compet.storage.DkStorage
import tool.compet.storage.MediaFile
import tool.compet.storage.StorageId
import java.io.*

@WorkerThread
fun Uri.openInputStreamDk(context: Context): InputStream? {
	return try {
		if (isRawFileDk) {
			// Handle file from external storage
			FileInputStream(File(path ?: return null))
		}
		else {
			context.contentResolver.openInputStream(this)
		}
	}
	catch (e: IOException) {
		DkLogcats.error(this, "Could not open input stream", e)
		null
	}
}

@WorkerThread
fun Uri.openOutputStreamDk(context: Context, append: Boolean = true): OutputStream? {
	return try {
		if (isRawFileDk) {
			FileOutputStream(File(path ?: return null), append)
		}
		else {
			context.contentResolver.openOutputStream(this, if (append && isTreeDocumentFileDk) "wa" else "w")
		}
	}
	catch (e: IOException) {
		DkLogcats.error(this, "Could not open output stream", e)
		null
	}
}

/**
 * Get storageId from this file.
 * - If the file is in external storage, it will return [PRIMARY],
 * - Otherwise it is a SD Card and will return integers like `6881-2249`.
 *
 * However, it will return empty `String` if this [DocumentFile] is
 * picked from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT]
 *
 * For eg,. if path of `Uri` is `/tree/primary:Downloads/MyVideo.mp4`, then return `primary`.
 */
fun Uri.getStorageIdDk(context: Context): String {
	val path = path.orEmpty()

	return when {
		isRawFileDk -> File(path).getStorageIdDk(context)
		isExternalStorageDocumentDk -> path.substringBefore(':', "").substringAfterLast('/')
		isDownloadsDocumentDk -> StorageId.PRIMARY
		else -> ""
	}
}

val Uri.isTreeDocumentFileDk: Boolean
	get() = path?.startsWith("/tree/") == true

val Uri.isExternalStorageDocumentDk: Boolean
	get() = authority == DkStorage.EXTERNAL_STORAGE_AUTHORITY

val Uri.isDownloadsDocumentDk: Boolean
	get() = authority == DkStorage.DOWNLOADS_FOLDER_AUTHORITY

val Uri.isMediaDocumentDk: Boolean
	get() = authority == DkStorage.MEDIA_FOLDER_AUTHORITY

/**
 * @return TRUE if the file was created with [java.io.File]. Only works on API 28 and lower.
 */
val Uri.isRawFileDk: Boolean
	get() = scheme == ContentResolver.SCHEME_FILE

val Uri.isMediaFileDk: Boolean
	get() = authority == MediaStore.AUTHORITY

fun Uri.toMediaFileDk(context: Context) = if (isMediaFileDk) MediaFile(context, this) else null

fun Uri.toDocumentFileDk(context: Context) = this.getFileDk(context)

/**
 * @return DocumentFile corresponding with this uri.
 */
fun Uri.getFileDk(context: Context): DocumentFile? {
	val fileUri = this

	return when {
		fileUri.isRawFileDk -> File(fileUri.path ?: return null).run {
			if (canRead())
				DocumentFile.fromFile(this)
			else
				null
		}
		fileUri.isTreeDocumentFileDk -> DocumentFile.fromTreeUri(context, fileUri)?.let { docFile ->
			if (docFile.uri.isDownloadsDocumentDk)
				docFile.toWritableDownloadsDocumentFileDk(context)
			else
				docFile
		}
		else -> {
			DocumentFile.fromSingleUri(context, fileUri) // context.getFileFromSingleUri(fileUri)
		}
	}
}