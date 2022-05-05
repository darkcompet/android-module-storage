package tool.compet.storage

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Job
import tool.compet.core.parseIntDk
import tool.compet.storage.callback.FileCallback
import tool.compet.storage.extension.awaitUiResult
import tool.compet.storage.extension.awaitUiResultWithPending
import tool.compet.storage.extension.copyFileToDk
import tool.compet.storage.extension.deleteDk
import tool.compet.storage.extension.findChildDk
import tool.compet.storage.extension.getBasePathDk
import tool.compet.storage.extension.getStorageIdDk
import tool.compet.storage.extension.isRawFileDk
import tool.compet.storage.extension.makeDirDk
import tool.compet.storage.extension.makeFileDk
import tool.compet.storage.extension.mimeTypeDk
import tool.compet.storage.extension.moveFileToDk
import tool.compet.storage.extension.openInputStreamDk
import tool.compet.storage.extension.openOutputStreamDk
import tool.compet.storage.extension.postToUiDk
import tool.compet.storage.extension.removeForbiddenCharsFromFilenameDk
import tool.compet.storage.extension.replaceCompletelyDk
import tool.compet.storage.extension.startCoroutineTimer
import tool.compet.storage.extension.toFileCallbackErrorCodeDk
import tool.compet.storage.extension.tryCloseDk
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@SuppressLint("InlinedApi", "Range")
class MediaFile(context: Context, val uri: Uri) {
	constructor(context: Context, rawFile: File) : this(context, Uri.fromFile(rawFile))

	private val context = context.applicationContext

	interface AccessCallback {
		/**
		 * When this function called, you can ask user's concent to modify other app's files.
		 * @see RecoverableSecurityException
		 * @see [android.app.Activity.startIntentSenderForResult]
		 */
		fun onWriteAccessDenied(mediaFile: MediaFile, sender: IntentSender)
	}

	/**
	 * Only useful for Android 10 and higher.
	 * @see RecoverableSecurityException
	 */
	var accessCallback: AccessCallback? = null

	/**
	 * Same with `fullName` but sometime media files do not return file extension.
	 */
	val name: String?
		get() = toRawFile()?.name ?: getColumnInfoString(MediaStore.MediaColumns.DISPLAY_NAME)

	/**
	 * Some media files do not return file extension. This function helps you to fix this kind of issue.
	 */
	val fullName: String
		get() = if (uri.isRawFileDk) {
			toRawFile()?.name.orEmpty()
		}
		else {
			val mimeType = getColumnInfoString(MediaStore.MediaColumns.MIME_TYPE)
			val displayName = getColumnInfoString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()

			MimeType.getFullFileName(displayName, mimeType)
		}

	val baseName: String
		get() = fullName.substringBeforeLast('.')

	val extension: String
		get() = fullName.substringAfterLast('.', "")

	/**
	 * @see [mimeTypeDk]
	 */
	val type: String?
		get() = toRawFile()?.name?.let { MimeType.getMimeTypeFromExtension(it.substringAfterLast('.', "")) }
			?: getColumnInfoString(MediaStore.MediaColumns.MIME_TYPE)

	/**
	 * Advanced version of [type]. Returns:
	 * * `null` if the file does not exist
	 * * [MimeType.UNKNOWN] if the file exists but the mime type is not found
	 */
	val mimeType: String?
		get() = if (exists) {
			getColumnInfoString(MediaStore.MediaColumns.MIME_TYPE)
				?: MimeType.getMimeTypeFromExtension(extension)
		}
		else null

	var length: Long
		get() = toRawFile()?.length() ?: getColumnInfoLong(MediaStore.MediaColumns.SIZE)
		set(value) {
			try {
				val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.SIZE, value) }
				context.contentResolver.update(uri, contentValues, null, null)
			}
			catch (e: SecurityException) {
				handleSecurityException(e)
			}
		}

	val formattedSize: String
		get() = Formatter.formatFileSize(context, length)

	/**
	 * Check if file exists
	 */
	val exists: Boolean
		get() = toRawFile()?.exists()
			?: uri.openInputStreamDk(context)?.use { true }
			?: false

	/**
	 * The URI presents in SAF database, but the file is not found.
	 */
	val isEmpty: Boolean
		get() = context.contentResolver.query(uri, null, null, null, null)?.use {
			it.count > 0 && !exists
		} ?: false

	val lastModified: Long
		get() = toRawFile()?.lastModified()
			?: getColumnInfoLong(MediaStore.MediaColumns.DATE_MODIFIED)

	val owner: String?
		get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			getColumnInfoString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
		else null

	/**
	 * Check if this media is owned by your app.
	 */
	val isMine: Boolean
		get() = owner == context.packageName

	/**
	 * @return NULL from api 29, or if you try to read files from SD Card, or you want to convert a file picked
	 * 	from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT].
	 */
	@Deprecated("From api 29, accessing files with raw-file only works on app's private directory.", ReplaceWith(""))
	fun toRawFile() : File? {
		return if (uri.isRawFileDk)
			uri.path?.let { File(it) }
		else null
	}

	@RequiresApi(Build.VERSION_CODES.KITKAT)
	fun toDocumentFile() : DocumentFile? {
		return absolutePath.let {
			if (it.isEmpty()) null
			else DkExternalStorage.findByFullPath(context, it)
		}
	}

	val absolutePath: String
		@SuppressLint("Range")
		get() {
			val rawFile = toRawFile()

			return when {
				rawFile != null -> {
					rawFile.path
				}
				// Note that, api < Q is not yet deprecated
				Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
					try {
						context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
							?.use { cursor ->
								if (cursor.moveToFirst()) {
									cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA))
								}
								else ""
							}.orEmpty()
					}
					catch (e: Exception) {
						""
					}
				}
				else -> {
					@SuppressLint("InlinedApi")
					val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
					context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
						if (cursor.moveToFirst()) {
							val relativePath = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH))
								?: return ""
							val name = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME))

							"${DkStorage.externalStoragePath}/$relativePath/$name".trimEnd('/').replaceCompletelyDk("//", "/")
						}
						else
							""
					}.orEmpty()
				}
			}
		}

	val basePath: String
		get() = absolutePath.substringAfter(DkStorage.externalStoragePath).trim('/')

	/**
	 * @see MediaStore.MediaColumns.RELATIVE_PATH
	 */
	val relativePath: String
		get() {
			@Suppress("DEPRECATION")
			val rawFile = toRawFile()

			return when {
				rawFile != null -> {
					rawFile.path.substringBeforeLast('/').replaceFirst(DkStorage.externalStoragePath, "").trim('/') + "/"
				}
				Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
					try {
						context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
							?.use { cursor ->
								if (cursor.moveToFirst()) {
									val realFolderAbsolutePath = cursor
										.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA))
										.substringBeforeLast('/')

									realFolderAbsolutePath.replaceFirst(DkStorage.externalStoragePath, "").trim('/') + "/"
								}
								else ""
							}.orEmpty()
					}
					catch (e: Exception) {
						""
					}
				}
				else -> {
					val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
					context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
						if (cursor.moveToFirst()) {
							cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH))
						}
						else ""
					}.orEmpty()
				}
			}
		}

	fun delete(): Boolean {
		val rawFile = toRawFile()

		return if (rawFile != null) {
			rawFile.delete() || !rawFile.exists()
		}
		else try {
			context.contentResolver.delete(uri, null, null) > 0
		}
		catch (e: SecurityException) {
			handleSecurityException(e)
			false
		}
	}

	/**
	 * Please note that this function does not move file if you input `newName` as `Download/filename.mp4`.
	 * If you want to move media files, please use [moveFileToDk] instead.
	 */
	fun renameTo(newName: String): Boolean {
		val rawFile = toRawFile()
		val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }

		return if (rawFile != null) {
			rawFile.renameTo(File(rawFile.parent, newName)) && context.contentResolver.update(uri, contentValues, null, null) > 0
		}
		else try {
			context.contentResolver.update(uri, contentValues, null, null) > 0
		}
		catch (e: SecurityException) {
			handleSecurityException(e)
			false
		}
	}

	/**
	 * Set to `true` if the file is being written to prevent users from accessing it.
	 *
	 * See [MediaStore.MediaColumns.IS_PENDING].
	 */
	var isPending: Boolean
		@RequiresApi(Build.VERSION_CODES.Q)
		get() = getColumnInfoInt(MediaStore.MediaColumns.IS_PENDING) == 1
		@RequiresApi(Build.VERSION_CODES.Q)
		set(value) {
			val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.IS_PENDING, value.parseIntDk()) }
			try {
				context.contentResolver.update(uri, contentValues, null, null)
			}
			catch (e: SecurityException) {
				handleSecurityException(e)
			}
		}

	private fun handleSecurityException(e: SecurityException, callback: FileCallback? = null) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
			accessCallback?.onWriteAccessDenied(this, e.userAction.actionIntent.intentSender)
		}
		else {
			callback?.uiScope?.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		}
	}

	@UiThread
	fun openFileIntent(authority: String) = Intent(Intent.ACTION_VIEW)
		.setData(if (uri.isRawFileDk) FileProvider.getUriForFile(context, authority, File(uri.path!!)) else uri)
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

	@WorkerThread
	fun openInputStream(): InputStream? {
		return try {
			val rawFile = toRawFile()

			if (rawFile != null) {
				FileInputStream(rawFile)
			}
			else {
				context.contentResolver.openInputStream(uri)
			}
		}
		catch (e: IOException) {
			null
		}
	}

	/**
	 * @param append if `false` and the file already exists, it will recreate the file.
	 */
	@WorkerThread
	@JvmOverloads
	fun openOutputStream(append: Boolean = true): OutputStream? {
		return try {
			val rawFile = toRawFile()

			if (rawFile != null) {
				FileOutputStream(rawFile, append)
			}
			else {
				context.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
			}
		}
		catch (e: IOException) {
			null
		}
	}

	@TargetApi(Build.VERSION_CODES.Q)
	fun moveTo(relativePath: String): Boolean {
		val contentValues = ContentValues(1).apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }

		return try {
			context.contentResolver.update(uri, contentValues, null, null) > 0
		}
		catch (e: SecurityException) {
			handleSecurityException(e)
			false
		}
	}

	@RequiresApi(Build.VERSION_CODES.KITKAT)
	@WorkerThread
	fun moveTo(targetFolder: DocumentFile, fileDescription: FileDescription? = null, callback: FileCallback) {
		val sourceFile = toDocumentFile()

		if (sourceFile != null) {
			sourceFile.moveFileToDk(context, targetFolder, fileDescription, callback)
			return
		}

		try {
			if (!callback.onCheckFreeSpace(
					SpaceManager.getFreeSpace(context, targetFolder.uri.getStorageIdDk(context)),
					length
				)
			) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
				return
			}
		}
		catch (e: Throwable) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
			return
		}

		val targetDirectory = if (fileDescription?.subDir.isNullOrEmpty()) {
			targetFolder
		}
		else {
			val directory = targetFolder.makeDirDk(context, fileDescription?.subDir.orEmpty(), FileCreationMode.REUSE)
			if (directory == null) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
				return
			}
			else {
				directory
			}
		}

		val cleanFileName = MimeType
			.getFullFileName(fileDescription?.name ?: name.orEmpty(), fileDescription?.mimeType ?: type)
			.removeForbiddenCharsFromFilenameDk()
			.trim('/')
		val conflictResolution = handleFileConflict(targetDirectory, cleanFileName, callback)
		if (conflictResolution == FileCallback.ConflictResolution.SKIP) {
			return
		}

		val thread = Thread.currentThread()
		val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, thread) }
		val watchProgress = reportInterval > 0

		try {
			val targetFile = createTargetFile(
				targetDirectory, cleanFileName, fileDescription?.mimeType ?: type,
				conflictResolution.toCreateMode(), callback
			) ?: return
			createFileStreams(targetFile, callback) { inputStream, outputStream ->
				copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, true, callback)
			}
		}
		catch (e: SecurityException) {
			handleSecurityException(e, callback)
		}
		catch (e: Exception) {
			callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
		}
	}

	@RequiresApi(Build.VERSION_CODES.KITKAT)
	@WorkerThread
	fun copyTo(targetFolder: DocumentFile, fileDescription: FileDescription? = null, callback: FileCallback) {
		val sourceFile = toDocumentFile()

		if (sourceFile != null) {
			sourceFile.copyFileToDk(context, targetFolder, fileDescription, callback)
			return
		}

		try {
			if (!callback.onCheckFreeSpace(
					SpaceManager.getFreeSpace(context, targetFolder.uri.getStorageIdDk(context)),
					length
				)
			) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
				return
			}
		}
		catch (e: Throwable) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
			return
		}

		val targetDirectory = if (fileDescription?.subDir.isNullOrEmpty()) {
			targetFolder
		}
		else {
			val directory = targetFolder.makeDirDk(context, fileDescription?.subDir.orEmpty(), FileCreationMode.REUSE)
			if (directory == null) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
				return
			}
			else {
				directory
			}
		}

		val cleanFileName = MimeType
			.getFullFileName(
				fileDescription?.name ?: name.orEmpty(),
				fileDescription?.mimeType ?: type
			)
			.removeForbiddenCharsFromFilenameDk()
			.trim('/')

		val conflictResolution = handleFileConflict(targetDirectory, cleanFileName, callback)
		if (conflictResolution == FileCallback.ConflictResolution.SKIP) {
			return
		}

		val thread = Thread.currentThread()
		val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, thread) }
		val watchProgress = reportInterval > 0
		try {
			val targetFile = createTargetFile(
				targetDirectory, cleanFileName, fileDescription?.mimeType ?: type,
				conflictResolution.toCreateMode(), callback
			) ?: return
			createFileStreams(targetFile, callback) { inputStream, outputStream ->
				copyFileStream(inputStream, outputStream, targetFile, watchProgress, reportInterval, false, callback)
			}
		}
		catch (e: SecurityException) {
			handleSecurityException(e, callback)
		}
		catch (e: Exception) {
			callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
		}
	}

	@RequiresApi(Build.VERSION_CODES.KITKAT)
	private fun createTargetFile(
		targetDirectory: DocumentFile,
		fileName: String,
		mimeType: String?,
		mode: FileCreationMode,
		callback: FileCallback
	): DocumentFile? {
		try {
			val absolutePath = DkStorage.makeAbsolutePath(
				context,
				targetDirectory.uri.getStorageIdDk(context),
				targetDirectory.getBasePathDk(context)
			)
			val targetFolder = DkStorage.mkdirs(context, absolutePath)
			if (targetFolder == null) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
				return null
			}

			val targetFile = targetFolder.makeFileDk(context, fileName, mimeType, mode)
			if (targetFile == null) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
			}
			else {
				return targetFile
			}
		}
		catch (e: SecurityException) {
			handleSecurityException(e, callback)
		}
		catch (e: Exception) {
			callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
		}
		return null
	}

	private inline fun createFileStreams(
		targetFile: DocumentFile,
		callback: FileCallback,
		onStreamsReady: (InputStream, OutputStream) -> Unit
	) {
		val outputStream = targetFile.openOutputStreamDk(context)
		if (outputStream == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.TARGET_FILE_NOT_FOUND) }
			return
		}

		val inputStream = openInputStream()
		if (inputStream == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND) }
			outputStream.tryCloseDk()
			return
		}

		onStreamsReady(inputStream, outputStream)
	}

	private fun copyFileStream(
		inputStream: InputStream,
		outputStream: OutputStream,
		targetFile: DocumentFile,
		watchProgress: Boolean,
		reportInterval: Long,
		deleteSourceFileWhenComplete: Boolean,
		callback: FileCallback
	) {
		var timer: Job? = null
		try {
			var bytesMoved = 0L
			var writeSpeed = 0
			val srcSize = length
			// Using timer on small file is useless. We set minimum 10 MB.
			if (watchProgress && srcSize > 10 * 1024 * 1024) {
				timer = startCoroutineTimer(repeatMillis = reportInterval) {
					val report = FileCallback.Report(bytesMoved * 100f / srcSize, bytesMoved, writeSpeed)
					callback.uiScope.postToUiDk { callback.onReport(report) }
					writeSpeed = 0
				}
			}
			val buffer = ByteArray(1024)
			var read = inputStream.read(buffer)
			while (read != -1) {
				outputStream.write(buffer, 0, read)
				bytesMoved += read
				writeSpeed += read
				read = inputStream.read(buffer)
			}
			timer?.cancel()
			if (deleteSourceFileWhenComplete) {
				delete()
			}
			callback.uiScope.postToUiDk { callback.onCompleted(targetFile) }
		}
		finally {
			timer?.cancel()
			inputStream.tryCloseDk()
			outputStream.tryCloseDk()
		}
	}

	private fun handleFileConflict(
		targetFolder: DocumentFile,
		fileName: String,
		callback: FileCallback
	): FileCallback.ConflictResolution {
		targetFolder.findChildDk(context, fileName)?.let { targetFile ->
			val resolution = awaitUiResultWithPending<FileCallback.ConflictResolution>(callback.uiScope) {
				callback.onConflict(targetFile, FileCallback.FileConflictAction(it))
			}
			if (resolution == FileCallback.ConflictResolution.REPLACE) {
				if (!targetFile.deleteDk(context)) {
					callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
					return FileCallback.ConflictResolution.SKIP
				}
			}
			return resolution
		}
		return FileCallback.ConflictResolution.CREATE_NEW
	}

	private fun getColumnInfoString(column: String): String? {
		context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				val columnIndex = cursor.getColumnIndex(column)
				if (columnIndex != -1) {
					return cursor.getString(columnIndex)
				}
			}
		}
		return null
	}

	private fun getColumnInfoLong(column: String): Long {
		context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				val columnIndex = cursor.getColumnIndex(column)
				if (columnIndex != -1) {
					return cursor.getLong(columnIndex)
				}
			}
		}
		return 0
	}

	private fun getColumnInfoInt(column: String): Int {
		context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				val columnIndex = cursor.getColumnIndex(column)
				if (columnIndex != -1) {
					return cursor.getInt(columnIndex)
				}
			}
		}
		return 0
	}

	override fun equals(other: Any?) = other === this || other is MediaFile && other.uri == uri

	override fun hashCode() = uri.hashCode()

	override fun toString() = uri.toString()
}
