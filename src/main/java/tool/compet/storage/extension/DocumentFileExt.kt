package tool.compet.storage.extension

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.annotation.RestrictTo
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.core.content.MimeTypeFilter
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Job
import tool.compet.storage.*
import tool.compet.storage.StorageId.DATA
import tool.compet.storage.StorageId.PRIMARY
import tool.compet.storage.callback.BaseFileCallback
import tool.compet.storage.callback.FileCallback
import tool.compet.storage.callback.DirCallback
import tool.compet.storage.callback.MultipleFileCallback
import tool.compet.storage.extension.*
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.*
import kotlin.collections.ArrayList

fun DocumentFile.isReadOnlyDk(context: Context) = canRead() && !isWritableDk(context)

val DocumentFile.idDk: String
	get() = DocumentsContract.getDocumentId(uri)

val DocumentFile.rootIdDk: String
	get() = DocumentsContract.getRootId(uri)

fun DocumentFile.isExternalStorageManagerDk(context: Context) =
	uri.isRawFileDk && File(uri.path!!).isExternalStorageManagerDk(context)

/**
 * Some media files do not return file extension from [DocumentFile.getName].
 * This function helps you to fix this kind of issue.
 */
val DocumentFile.fullNameDk: String
	get() = if (uri.isRawFileDk || uri.isExternalStorageDocumentDk || isDirectory) {
		name.orEmpty()
	}
	else {
		MimeType.getFullFileName(name.orEmpty(), type)
	}

fun DocumentFile.inSameMountPointWithDk(context: Context, file: DocumentFile): Boolean {
	val storageId1 = uri.getStorageIdDk(context)
	val storageId2 = file.uri.getStorageIdDk(context)
	return (storageId1 == storageId2)
		|| ((storageId1 == PRIMARY || storageId1 == DATA) && (storageId2 == PRIMARY || storageId2 == DATA))
}

fun DocumentFile.emptyDk(context: Context): Boolean {
	if (isFile && length() == 0L) {
		return true
	}
	return isDirectory && kotlin.run {
		if (uri.isRawFileDk) {
			toRawFileDk(context)?.list().isNullOrEmpty()
		}
		else try {
			val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, idDk)
			
			context.contentResolver
				.query(
					childrenUri,
					arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
					null,
					null,
					null
				)
				?.use {
					it.count == 0
				}
				?: true
		}
		catch (e: Exception) {
			true
		}
	}
}

/**
 * Similar to Get Info on MacOS or File Properties in Windows.
 * Use [Thread.interrupt] to cancel the proccess and it will trigger [FileProperties.CalculationCallback.onCanceled]
 */
@WorkerThread
fun DocumentFile.getPropertiesDk(context: Context, callback: FileProperties.CalculationCallback) {
	when {
		!canRead() -> {
			callback.uiScope.postToUiDk { callback.onError() }
		}
		isDirectory -> {
			val properties = FileProperties(
				name = name.orEmpty(),
				location = getAbsolutePathDk(context),
				isFolder = true,
				isVirtual = isVirtual,
				lastModified = lastModified().let { if (it > 0) Date(it) else null }
			)
			if (emptyDk(context)) {
				callback.uiScope.postToUiDk { callback.onComplete(properties) }
			}
			else {
				val timer = if (callback.updateInterval < 1) null
				else startCoroutineTimer(repeatMillis = callback.updateInterval) {
					callback.uiScope.postToUiDk { callback.onUpdate(properties) }
				}
				val thread = Thread.currentThread()
				walkFileTreeForInfoDk(properties, thread)
				timer?.cancel()
				// need to store isInterrupted in a variable, because calling it from UI thread always returns false
				val interrupted = thread.isInterrupted
				callback.uiScope.postToUiDk {
					if (interrupted) {
						callback.onCanceled(properties)
					}
					else {
						callback.onComplete(properties)
					}
				}
			}
		}
		isFile -> {
			val properties = FileProperties(
				name = fullNameDk,
				location = getAbsolutePathDk(context),
				size = length(),
				isVirtual = isVirtual,
				lastModified = lastModified().let { if (it > 0) Date(it) else null }
			)
			callback.uiScope.postToUiDk { callback.onComplete(properties) }
		}
	}
}

private fun DocumentFile.walkFileTreeForInfoDk(properties: FileProperties, thread: Thread) {
	val list = listFiles()
	if (list.isEmpty()) {
		properties.emptyFolders++
		return
	}
	list.forEach {
		if (thread.isInterrupted) {
			return
		}
		if (it.isFile) {
			properties.files++
			val size = it.length()
			properties.size += size
			if (size == 0L) properties.emptyFiles++
		}
		else {
			properties.folders++
			it.walkFileTreeForInfoDk(properties, thread)
		}
	}
}

/**
 * Returns NULL if this `DocumentFile` is picked from
 * [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT].
 */
fun DocumentFile.getStorageTypeDk(context: Context): StorageType {
	return if (uri.isTreeDocumentFileDk) {
		if (inPrimaryStorageDk(context)) StorageType.EXTERNAL else StorageType.SD_CARD
	}
	else when {
		inSdCardStorageDk(context) -> StorageType.SD_CARD
		inDataStorageDk(context) -> StorageType.DATA
		else -> StorageType.UNKNOWN
	}
}

fun DocumentFile.inInternalStorageDk(context: Context) = uri.getStorageIdDk(context).let { storageId ->
	storageId == PRIMARY || storageId == DATA
}

/**
 * Note that, all files created by [DocumentFile.fromFile] are always treated from external storage.
 * 
 * @return TRUE if this file is located in primary storage (for eg,. external storage).
 */
fun DocumentFile.inPrimaryStorageDk(context: Context) = (uri.isTreeDocumentFileDk && uri.getStorageIdDk(context) == PRIMARY)
	|| (uri.isRawFileDk && uri.path.orEmpty().startsWith(DkStorage.externalStoragePath))

/**
 * @return TRUE if this file located in SD Card.
 */
fun DocumentFile.inSdCardStorageDk(context: Context) = (uri.isTreeDocumentFileDk && uri.getStorageIdDk(context) != PRIMARY)
	|| (uri.isRawFileDk && uri.path.orEmpty().startsWith("/storage/${uri.getStorageIdDk(context)}"))

fun DocumentFile.inDataStorageDk(context: Context) = uri.isRawFileDk && File(uri.path!!).inDataStorageDk(context)

/**
 * Filename without extension.
 */
val DocumentFile.baseNameDk: String
	get() = fullNameDk.substringBeforeLast('.')

/**
 * File extension.
 */
val DocumentFile.extensionDk: String
	get() = fullNameDk.substringAfterLast('.', "")

/**
 * Advanced version of [DocumentFile.getType]. Returns:
 * - `null` if it is a directory or the file does not exist
 * - [MimeType.UNKNOWN] if the file exists but the mime type is not found
 */
val DocumentFile.mimeTypeDk: String?
	get() = if (isFile) (type ?: MimeType.getMimeTypeFromExtension(extensionDk)) else null

val DocumentFile.mimeTypeByFileNameDk: String?
	get() = if (isDirectory) null
	else {
		val extension = name.orEmpty().substringAfterLast('.', "")
		val mimeType = MimeType.getMimeTypeFromExtension(extension)
		if (mimeType == MimeType.UNKNOWN) type else mimeType
	}

/**
 * Please notice that accessing files with [File] only works on app private directory since Android 10.
 * You had better to stay using [DocumentFile].
 * 
 * See [File.toDocumentFileDk] for reversing.
 *
 * @return NULL if you try to read files from SD Card, or you want to convert a file picked
 * from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT].
 */
fun DocumentFile.toRawFileDk(context: Context): File? {
	return when {
		uri.isRawFileDk -> File(uri.path ?: return null)
		inPrimaryStorageDk(context) -> File("${DkStorage.externalStoragePath}/${getBasePathDk(context)}")
		uri.getStorageIdDk(context).isNotEmpty() -> File("/storage/${uri.getStorageIdDk(context)}/${getBasePathDk(context)}")
		else -> null
	}
}

fun DocumentFile.toRawDocumentFileDk(context: Context): DocumentFile? {
	return if (uri.isRawFileDk) this else DocumentFile.fromFile(toRawFileDk(context) ?: return null)
}

fun DocumentFile.toTreeDocumentFileDk(context: Context): DocumentFile? {
	return if (uri.isRawFileDk) {
		DkExternalStorage.findByFile(context, toRawFileDk(context) ?: return null, useRawFile = false)
	}
	else takeIf { it.uri.isTreeDocumentFileDk }
}

fun DocumentFile.toMediaFileDk(context: Context) = if (uri.isTreeDocumentFileDk) null else MediaFile(context, uri)

/**
 * It's faster than [DocumentFile.findFile].
 *
 * @param path Single fileName or filePath. Empty string will return itself.
 */
@JvmOverloads
fun DocumentFile.findChildDk(context: Context, path: String, requireWritable: Boolean = false): DocumentFile? {
	return when {
		path.isEmpty() -> {
			this
		}
		isDirectory -> {
			val file = if (uri.isRawFileDk) {
				quickFindRawFileDk(path)
			}
			else {
				var currentDirectory = this
				val resolver = context.contentResolver

				path.splitToDirsDk().forEach {
					val directory = currentDirectory.quickFindTreeFileDk(context, resolver, it) ?: return null
					if (directory.canRead()) {
						currentDirectory = directory
					}
					else {
						return null
					}
				}
				currentDirectory
			}
			file?.takeIfWritableDk(context, requireWritable)
		}
		else -> null
	}
}

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun DocumentFile.quickFindRawFileDk(name: String): DocumentFile? {
	return DocumentFile.fromFile(File(uri.path!!, name)).takeIf { it.canRead() }
}

/**
 * It's faster than [DocumentFile.findFile].
 *
 * Must set [ContentResolver] as additional parameter to improve performance.
 */
@SuppressLint("NewApi")
@RestrictTo(RestrictTo.Scope.LIBRARY)
fun DocumentFile.quickFindTreeFileDk(context: Context, resolver: ContentResolver, name: String): DocumentFile? {
	try {
		// Optimized algorithm. Do not change unless you really know algorithm complexity.
		val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, idDk)
		resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
			val columnName = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
			while (it.moveToNext()) {
				try {
					val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, it.getString(0))
					resolver.query(documentUri, columnName, null, null, null)?.use { childCursor ->
						if (childCursor.moveToFirst() && name == childCursor.getString(0))
							return DocumentFile.fromTreeUri(context, documentUri)
					}
				}
				catch (e: Exception) {
					// ignore
				}
			}
		}
	}
	catch (e: Exception) {
		// ignore
	}
	return null
}

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun DocumentFile.shouldWritableDk(context: Context, requireWritable: Boolean) =
	requireWritable && isWritableDk(context) || !requireWritable

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun DocumentFile.takeIfWritableDk(context: Context, requireWritable: Boolean) =
	takeIf { it.shouldWritableDk(context, requireWritable) }

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun DocumentFile.checkRequirementsDk(context: Context, requireWritable: Boolean, useRawFile: Boolean) =
	canRead() &&
		(useRawFile || isExternalStorageManagerDk(context)) && shouldWritableDk(context, requireWritable)

/**
 * @return File path without storage ID. Returns empty `String` if:
 * - It is the root path
 * - It is not a raw file and the authority is neither [DkExternalStorage.EXTERNAL_STORAGE_AUTHORITY]
 * 	nor [DkExternalStorage.DOWNLOADS_FOLDER_AUTHORITY]
 * - The authority is [DkExternalStorage.DOWNLOADS_FOLDER_AUTHORITY],
 * 	but [uri.isTreeDocumentFile] returns `false`
 */
@Suppress("DEPRECATION")
fun DocumentFile.getBasePathDk(context: Context): String {
	val path = uri.path.orEmpty()
	val storageID = uri.getStorageIdDk(context)
	return when {
		uri.isRawFileDk -> File(path).getBasePathDk(context)

		uri.isExternalStorageDocumentDk && path.contains("/document/$storageID:") -> {
			path.substringAfterLast("/document/$storageID:", "").trim('/')
		}

		uri.isDownloadsDocumentDk -> {
			// content://com.android.providers.downloads.documents/tree/raw:/storage/emulated/0/Download/Denai/document/raw:/storage/emulated/0/Download/Denai
			// content://com.android.providers.downloads.documents/tree/downloads/document/raw:/storage/emulated/0/Download/Denai
			when {
				// API 26 - 27 => content://com.android.providers.downloads.documents/document/22
				Build.VERSION.SDK_INT < Build.VERSION_CODES.P && path.matches(Regex("/document/\\d+")) -> {
					val fileName = MediaFile(context, uri).name ?: return ""
					"${Environment.DIRECTORY_DOWNLOADS}/$fileName"
				}

				Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.matches(Regex("(.*?)/ms[f,d]:\\d+(.*?)")) -> {
					if (uri.isTreeDocumentFileDk) {
						val parentTree = mutableListOf(name.orEmpty())
						var parent = this
						while (parent.parentFile?.also { parent = it } != null) {
							parentTree.add(parent.name.orEmpty())
						}
						parentTree.reversed().joinToString("/")
					}
					else {
						// we can't use msf/msd ID as MediaFile ID to fetch relative path, so just return empty String
						""
					}
				}

				else -> path.substringAfterLast(DkStorage.externalStoragePath, "").trim('/')
			}
		}
		else -> ""
	}
}

/**
 * -*Case 1**: Should return `Pop/Albums` from the following folders:
 * - `/storage/AAAA-BBBB/Music`
 * - `/storage/AAAA-BBBB/Music/Pop/Albums`
 *
 * -*Case 2**: Should return `Albums/A Day in the Hell` from the following folders:
 * - `/storage/AAAA-BBBB/Music/Pop/Albums/A Day in the Hell`
 * - `/storage/AAAA-BBBB/Other/Pop`
 *
 * -*Case 3**: Should return empty string from the following folders:
 * - `/storage/AAAA-BBBB/Music`
 * - `/storage/AAAA-BBBB/Music`
 *
 * -*Case 4**: Should return `null` from the following folders:
 * - `/storage/AAAA-BBBB/Music/Metal`
 * - `/storage/AAAA-BBBB/Music/Pop/Albums`
 */
private fun DocumentFile.getSubPath(context: Context, otherFolderAbsolutePath: String): String {
	val a = getAbsolutePathDk(context)
	return when {
		a.length > otherFolderAbsolutePath.length -> a
			.substringAfter(otherFolderAbsolutePath.substringAfterLast('/'), "")
			.trim('/')
		otherFolderAbsolutePath.length > a.length -> otherFolderAbsolutePath
			.substringAfter(a.substringAfterLast('/'), "")
			.trim('/')
		else -> ""
	}
}

/**
 * Root path of this file.
 * - For file picked from [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_CREATE_DOCUMENT], it will return empty `String`
 * - For file stored in external or primary storage, it will return [DkStorage.externalStoragePath].
 * - For file stored in SD Card, it will return something like `/storage/6881-2249`
 */
fun DocumentFile.getRootPathDk(context: Context) = when {
	uri.isRawFileDk -> uri.path?.let { File(it).getRootPathDk(context) }.orEmpty()
	!uri.isTreeDocumentFileDk -> ""
	inSdCardStorageDk(context) -> "/storage/${uri.getStorageIdDk(context)}"
	else -> DkStorage.externalStoragePath
}

fun DocumentFile.getRelativePathDk(context: Context) = getBasePathDk(context).substringBeforeLast('/', "")

/**
 * - For file in SD Card => `/storage/6881-2249/Music/song.mp3`
 * - For file in external storage => `/storage/emulated/0/Music/song.mp3`
 *
 * If you want to remember file locations in database or preference, please use this function.
 * When you reopen the file, just call [DkExternalStorage.findByFullPath]
 *
 * @return File's actual path. Returns empty `String` if:
 * - It is not a raw file and the authority is neither [DkExternalStorage.EXTERNAL_STORAGE_AUTHORITY] nor [DkExternalStorage.DOWNLOADS_FOLDER_AUTHORITY]
 * - The authority is [DkExternalStorage.DOWNLOADS_FOLDER_AUTHORITY], but [uri.isTreeDocumentFile] returns `false`
 *
 * @see File.getAbsolutePath
 * @see getSimplePathDk
 */
@Suppress("DEPRECATION")
fun DocumentFile.getAbsolutePathDk(context: Context): String {
	val path = uri.path.orEmpty()
	val storageID = uri.getStorageIdDk(context)

	return when {
		uri.isRawFileDk -> {
			path
		}
		uri.isExternalStorageDocumentDk && path.contains("/document/$storageID:") -> {
			val basePath = path.substringAfterLast("/document/$storageID:", "").trim('/')
			if (storageID == PRIMARY) {
				"${DkStorage.externalStoragePath}/$basePath".trimEnd('/')
			}
			else {
				"/storage/$storageID/$basePath".trimEnd('/')
			}
		}
		uri.toString()
			.let { it == DkStorage.DOWNLOADS_TREE_URI || it == "${DkStorage.DOWNLOADS_TREE_URI}/document/downloads" } -> {
			Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
		}
		uri.isDownloadsDocumentDk -> {
			when {
				// API 26 - 27 => content://com.android.providers.downloads.documents/document/22
				Build.VERSION.SDK_INT < Build.VERSION_CODES.P && path.matches(Regex("/document/\\d+")) -> {
					val fileName = MediaFile(context, uri).name ?: return ""
					File(
						Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
						fileName
					).absolutePath
				}
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.matches(Regex("(.*?)/ms[f,d]:\\d+(.*?)")) -> {
					if (uri.isTreeDocumentFileDk) {
						val parentTree = mutableListOf(name.orEmpty())
						var parent = this
						while (parent.parentFile?.also { parent = it } != null) {
							parentTree.add(parent.name.orEmpty())
						}
						"${DkStorage.externalStoragePath}/${parentTree.reversed().joinToString("/")}".trimEnd('/')
					}
					else {
						// we can't use msf/msd ID as MediaFile ID to fetch relative path, so just return empty String
						""
					}
				}
				else -> {
					path.substringAfterLast("/document/raw:", "").trimEnd('/')
				}
			}
		}

		!uri.isTreeDocumentFileDk -> {
			""
		}
		inPrimaryStorageDk(context) -> {
			"${DkStorage.externalStoragePath}/${getBasePathDk(context)}".trimEnd('/')
		}
		else -> {
			"/storage/$storageID/${getBasePathDk(context)}".trimEnd('/')
		}
	}
}

/**
 * @see getAbsolutePathDk
 */
fun DocumentFile.getSimplePathDk(context: Context) = "${uri.getStorageIdDk(context)}:${getBasePathDk(context)}".removePrefix(":")

/**
 * Delete this file and create new empty file using previous `filename` and `mimeType`.
 * It cannot be applied if current [DocumentFile] is a directory.
 */
fun DocumentFile.recreateFileDk(context: Context): DocumentFile? {
	return if (exists() && (uri.isRawFileDk || uri.isExternalStorageDocumentDk)) {
		val filename = name.orEmpty()
		val parentFile = parentFile

		if (parentFile?.isWritableDk(context) == true) {
			val mimeType = type

			deleteDk(context)
			parentFile.makeFileDk(context, filename, mimeType)
		}
		else null
	}
	else null
}

@JvmOverloads
fun DocumentFile.getRootFileDk(context: Context, requireWritable: Boolean = false) = when {
	uri.isTreeDocumentFileDk -> {
		DkStorage.getRootDir(context, uri.getStorageIdDk(context), requireWritable)
	}
	uri.isRawFileDk -> {
		uri.path?.run {
			File(this).getRootRawFileDk(context, requireWritable)?.let { DocumentFile.fromFile(it) }
		}
	}
	else -> null
}

/**
 * @return `true` if this file exists and writeable. [DocumentFile.canWrite] may return false if you have no URI permission for read & write access.
 */
fun DocumentFile.canModifyDk(context: Context) = canRead() && isWritableDk(context)

/**
 * Use it, because [DocumentFile.canWrite] is unreliable on Android 10.
 * Read [this issue](https://github.com/anggrayudi/SimpleStorage/issues/24#issuecomment-830000378)
 */
fun DocumentFile.isWritableDk(context: Context) = if (uri.isRawFileDk) File(uri.path!!).isWritableDk(context) else canWrite()

fun DocumentFile.isRootUriPermissionGrantedDk(context: Context): Boolean {
	return uri.isExternalStorageDocumentDk && DkStorage.isStorageUriPermissionGranted(context, uri.getStorageIdDk(context))
}

fun DocumentFile.getFormattedSizeDk(context: Context) = Formatter.formatFileSize(context, length())

/**
 * Avoid duplicate file name.
 */
@WorkerThread
fun DocumentFile.incrementFileNameDk(context: Context, filename: String): String {
	toRawFileDk(context)?.let {
		if (it.canRead())
			return it.incrementFileNameDk(filename)
	}
	val files = listFiles()
	return if (files.find { it.name == filename }?.exists() == true) {
		val baseName = filename.substringBeforeLast('.')
		val ext = filename.substringAfterLast('.', "")
		val prefix = "$baseName ("
		var lastFileCount = files.filter {
			val name = it.name.orEmpty()
			name.startsWith(prefix) && (DkStorage.FILE_NAME_DUPLICATION_REGEX_WITH_EXTENSION.matches(name)
				|| DkStorage.FILE_NAME_DUPLICATION_REGEX_WITHOUT_EXTENSION.matches(name))
		}.maxOfOrNull {
			it.name.orEmpty().substringAfterLast('(', "")
				.substringBefore(')', "")
				.toIntOrNull() ?: 0
		} ?: 0
		"$baseName (${++lastFileCount}).$ext".trimEnd('.')
	}
	else {
		filename
	}
}

/**
 * Useful for creating temporary files. The extension is `*.bin`
 */
@WorkerThread
@JvmOverloads
fun DocumentFile.createBinaryFileDk(context: Context, name: String, mode: FileCreationMode = FileCreationMode.CREATE_NEW) =
	makeFileDk(context, name, MimeType.BINARY_FILE, mode)

/**
 * Similar to [DocumentFile.createFile], but adds compatibility on API 28 and lower.
 * Creating files in API 28- with `createFile("my video.mp4", "video/mp4")` will create `my video.mp4`,
 * whereas API 29+ will create `my video.mp4.mp4`. This function helps you to fix this kind of bug.
 *
 * @param mimeType use [MimeType.UNKNOWN] if you're not sure about the file type
 * @param name you can input `My Video`, `My Video.mp4` or `My Folder/Sub Folder/My Video.mp4`
 */
@WorkerThread
@JvmOverloads
fun DocumentFile.makeFileDk(
	context: Context,
	name: String,
	mimeType: String? = MimeType.UNKNOWN,
	creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
): DocumentFile? {
	if (!isDirectory || !isWritableDk(context)) {
		return null
	}

	val cleanName = name.removeForbiddenCharsFromFilenameDk().trim('/')
	val subFolder = cleanName.substringBeforeLast('/', "")
	val parent = if (subFolder.isEmpty()) this
	else {
		makeDirDk(context, subFolder, creationMode) ?: return null
	}

	val filename = cleanName.substringAfterLast('/')
	val extensionByName = cleanName.substringAfterLast('.', "")
	val extension =
		if (extensionByName.isNotEmpty() && (mimeType == null || mimeType == MimeType.UNKNOWN || mimeType == MimeType.BINARY_FILE)) {
			extensionByName
		}
		else {
			MimeType.getExtensionFromMimeTypeOrFileName(mimeType, cleanName)
		}
	val baseFileName = filename.removeSuffix(".$extension")
	val fullFileName = "$baseFileName.$extension".trimEnd('.')

	if (creationMode != FileCreationMode.CREATE_NEW) {
		parent.findChildDk(context, fullFileName)?.let {
			when {
				creationMode == FileCreationMode.REPLACE -> it.recreateFileDk(context)
				it.isFile -> it
				else -> null
			}
		}
	}

	if (uri.isRawFileDk) {
		// RawDocumentFile does not avoid duplicate file name, but TreeDocumentFile does.
		return DocumentFile.fromFile(toRawFileDk(context)?.makeFileDk(context, cleanName, mimeType, creationMode) ?: return null)
	}

	val correctMimeType = MimeType.getMimeTypeFromExtension(extension).let { mimeType ->
		if (mimeType == MimeType.UNKNOWN) MimeType.BINARY_FILE else mimeType
	}

	return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
		parent.createFile(correctMimeType, baseFileName)?.also { documentFile ->
			if (correctMimeType == MimeType.BINARY_FILE && documentFile.name != fullFileName)
				documentFile.renameTo(fullFileName)
		}
	}
	else {
		parent.createFile(correctMimeType, fullFileName)
	}
}

/**
 * @param name can input `MyFolder` or `MyFolder/SubFolder`
 */
@WorkerThread
@JvmOverloads
fun DocumentFile.makeDirDk(context: Context, name: String, mode: FileCreationMode = FileCreationMode.CREATE_NEW): DocumentFile? {
	if (!isDirectory || !isWritableDk(context)) {
		return null
	}

	if (uri.isRawFileDk) {
		return DocumentFile.fromFile(toRawFileDk(context)?.makeDirDk(context, name, mode) ?: return null)
	}

	// if name is "Aduhhh/Now/Dee", system will convert it to Aduhhh_Now_Dee, so create a sequence
	val directorySequence = name.removeForbiddenCharsFromFilenameDk().splitToDirsDk().toMutableList()
	val folderNameLevel1 = directorySequence.removeFirstOrNull() ?: return null
	var currentDirectory =
		if (uri.isDownloadsDocumentDk && uri.isTreeDocumentFileDk) (toWritableDownloadsDocumentFileDk(context) ?: return null) else this
	val folderLevel1 = currentDirectory.findChildDk(context, folderNameLevel1)

	currentDirectory = if (folderLevel1 == null || mode == FileCreationMode.CREATE_NEW) {
		currentDirectory.createDirectory(folderNameLevel1) ?: return null
	}
	else if (mode == FileCreationMode.REPLACE) {
		folderLevel1.deleteDk(context, true)
		if (folderLevel1.isDirectory) folderLevel1 else currentDirectory.createDirectory(folderNameLevel1) ?: return null
	}
	else if (folderLevel1.isDirectory && folderLevel1.canRead()) {
		folderLevel1
	}
	else {
		return null
	}

	val resolver = context.contentResolver
	directorySequence.forEach { folder ->
		try {
			val directory = currentDirectory.quickFindTreeFileDk(context, resolver, folder)
			currentDirectory = if (directory == null) {
				currentDirectory.createDirectory(folder) ?: return null
			}
			else if (directory.isDirectory && directory.canRead()) {
				directory
			}
			else {
				return null
			}
		}
		catch (e: Exception) {
			return null
		}
	}
	return currentDirectory
}

/**
 * Use this function if you cannot create or read file/folder in downloads directory.
 */
@WorkerThread
fun DocumentFile.toWritableDownloadsDocumentFileDk(context: Context): DocumentFile? {
	return if (uri.isDownloadsDocumentDk) {
		val path = uri.path.orEmpty()
		when {
			uri.toString() == "${DkStorage.DOWNLOADS_TREE_URI}/document/downloads" -> takeIf {
				it.isWritableDk(
					context
				)
			}

			// content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fscreenshot.jpeg
			// content://com.android.providers.downloads.documents/tree/downloads/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FIKO5
			// raw:/storage/emulated/0/Download/IKO5
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (path.startsWith("/tree/downloads/document/raw:") || path.startsWith(
				"/document/raw:"
			)) -> {
				val downloads =
					DkExternalStorage.getFileByPath(context, PublicDirectory.DOWNLOADS, useRawFile = false)
						?: return null
				val fullPath = path.substringAfterLast("/document/raw:")
				val subFile = fullPath.substringAfter("/${Environment.DIRECTORY_DOWNLOADS}", "")
				downloads.findChildDk(context, subFile, true)
			}

			// msd for directories and msf for files
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (
				// // If comes from SAF file picker ACTION_OPEN_DOCUMENT on API 30+
				path.matches(Regex("/document/ms[f,d]:\\d+"))
					// If comes from SAF folder picker ACTION_OPEN_DOCUMENT_TREE,
					// e.g. content://com.android.providers.downloads.documents/tree/msd%3A535/document/msd%3A535
					|| path.matches(Regex("/tree/ms[f,d]:\\d+(.*?)"))
					// If comes from findFile() or fromPublicFolder(),
					// e.g. content://com.android.providers.downloads.documents/tree/downloads/document/msd%3A271
					|| path.matches(Regex("/tree/downloads/document/ms[f,d]:\\d+")))
				|| Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && (
				// If comes from SAF folder picker ACTION_OPEN_DOCUMENT_TREE,
				// e.g. content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FDenai/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FDenai
				path.startsWith("/tree/raw:")
					// If comes from findFile() or fromPublicFolder(),
					// e.g. content://com.android.providers.downloads.documents/tree/downloads/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FDenai
					|| path.startsWith("/tree/downloads/document/raw:")
					// API 26 - 27 => content://com.android.providers.downloads.documents/document/22
					|| path.matches(Regex("/document/\\d+"))
				)
			-> takeIf { it.isWritableDk(context) }

			else -> null
		}
	}
	else {
		null
	}
}

/**
 * @param fileNames full file names, with their extension
 */
fun DocumentFile.findFilesDk(
	fileNames: Array<String>,
	documentType: DocumentFileType = DocumentFileType.ANY
): List<DocumentFile> {
	val files = listFiles().filter { it.name in fileNames }
	return when (documentType) {
		DocumentFileType.FILE -> files.filter { it.isFile }
		DocumentFileType.DIRECTORY -> files.filter { it.isDirectory }
		else -> files
	}
}

fun DocumentFile.findDirDk(name: String): DocumentFile? = listFiles().find { it.name == name && it.isDirectory }

/**
 * Expect the file is a file literally, not a folder.
 */
fun DocumentFile.findFileLiterallyDk(name: String): DocumentFile? = listFiles().find { it.name == name && it.isFile }

/**
 * @param recursive walk into sub folders
 * @param fileName find file name exactly
 * @param regex you can use regex `^.*containsName.*\$` to search file name that contains specific words
 */
// @JvmOverloads
@WorkerThread
fun DocumentFile.searchDk(
	recursive: Boolean = false,
	documentType: DocumentFileType = DocumentFileType.ANY,
	mimeTypes: Array<String>? = null,
	fileName: String = "",
	regex: Regex? = null
): List<DocumentFile> {
	return when {
		!isDirectory || !canRead() -> {
			emptyList()
		}
		recursive -> {
			val thread = Thread.currentThread()
			if (mimeTypes.isNullOrEmpty() || mimeTypes.any { it == MimeType.UNKNOWN }) {
				walkFileTreeForSearchDk(documentType, emptyArray(), fileName, regex, thread)
			}
			else {
				walkFileTreeForSearchDk(DocumentFileType.FILE, mimeTypes, fileName, regex, thread)
			}
		}
		else -> {
			var sequence = listFiles().asSequence().filter { it.canRead() }
			if (regex != null) {
				sequence = sequence.filter { regex.matches(it.name.orEmpty()) }
			}
			val hasMimeTypeFilter = !mimeTypes.isNullOrEmpty() && !mimeTypes.any { it == MimeType.UNKNOWN }
			when {
				hasMimeTypeFilter || documentType == DocumentFileType.FILE -> sequence = sequence.filter { it.isFile }
				documentType == DocumentFileType.DIRECTORY -> sequence = sequence.filter { it.isDirectory }
			}
			if (hasMimeTypeFilter) {
				sequence = sequence.filter { it.matchesMimeTypesDk(mimeTypes!!) }
			}
			val result = sequence.toList()
			if (fileName.isEmpty()) result else result.firstOrNull { it.name == fileName }?.let { listOf(it) } ?: emptyList()
		}
	}
}

private fun DocumentFile.matchesMimeTypesDk(filterMimeTypes: Array<String>): Boolean {
	return filterMimeTypes.isEmpty() || !MimeTypeFilter.matches(mimeTypeByFileNameDk, filterMimeTypes).isNullOrEmpty()
}

private fun DocumentFile.walkFileTreeForSearchDk(
	documentType: DocumentFileType,
	mimeTypes: Array<String>,
	nameFilter: String,
	regex: Regex?,
	thread: Thread
): List<DocumentFile> {

	val fileTree = mutableListOf<DocumentFile>()

	for (file in listFiles()) {
		if (thread.isInterrupted) {
			break
		}
		if (!canRead()) {
			continue
		}

		if (file.isFile) {
			if (documentType == DocumentFileType.DIRECTORY) {
				continue
			}

			val filename = file.name.orEmpty()

			if ((nameFilter.isEmpty() || filename == nameFilter)
				&& (regex == null || regex.matches(filename))
				&& file.matchesMimeTypesDk(mimeTypes)
			) {
				fileTree.add(file)
			}
		}
		else {
			if (documentType != DocumentFileType.FILE) {
				val folderName = file.name.orEmpty()

				if ((nameFilter.isEmpty() || folderName == nameFilter) && (regex == null || regex.matches(folderName))) {
					fileTree.add(file)
				}
			}
			fileTree.addAll(file.walkFileTreeForSearchDk(documentType, mimeTypes, nameFilter, regex, thread))
		}
	}
	return fileTree
}

/**
 * Try delete a file or directory.
 *
 * @return TRUE if the file/folder does not exist, or was deleted.
 */
@WorkerThread
@JvmOverloads
fun DocumentFile.deleteDk(context: Context, childrenOnly: Boolean = false): Boolean {
	return if (isDirectory) {
		deleteDirRecursivelyDk(context, childrenOnly)
	}
	else {
		delete() || !exists()
	}
}


/**
 * Delete a directory.
 *
 * @return TRUE if the file/folder does not exist, or was deleted.
 */
@WorkerThread
@JvmOverloads
fun DocumentFile.deleteDirRecursivelyDk(context: Context, childrenOnly: Boolean = false): Boolean {
	if (isDirectory && canRead()) {
		val files = if (uri.isDownloadsDocumentDk) {
			toWritableDownloadsDocumentFileDk(context)?.walkFileTreeForDeletionDk() ?: return false
		}
		else {
			walkFileTreeForDeletionDk()
		}

		// MUST delete from last index since file-deletion requires direction: child -> parent
		var remainFileCount = files.size
		for (index in files.size - 1 downTo 0) {
			if (files[index].delete()) {
				--remainFileCount
			}
		}

		return remainFileCount == 0 && (childrenOnly || delete() || !exists())
	}

	return false
}

private fun DocumentFile.walkFileTreeForDeletionDk(): List<DocumentFile> {
	val fileTree = mutableListOf<DocumentFile>()
	listFiles().forEach {
		if (!it.delete()) {
			fileTree.add(it)
		}
		if (it.isDirectory) {
			fileTree.addAll(it.walkFileTreeForDeletionDk())
		}
	}
	return fileTree
}

fun DocumentFile.deleteEmptyDirsDk(context: Context): Boolean {
	return if (uri.isRawFileDk) {
		File(uri.path!!).deleteEmptyDirsDk(context)
		true
	}
	else if (isDirectory && isWritableDk(context)) {
		walkFileTreeAndDeleteEmptyDirsDk().reversed().forEach { it.delete() }
		true
	}
	else false
}

private fun DocumentFile.walkFileTreeAndDeleteEmptyDirsDk(): List<DocumentFile> {
	val fileTree = mutableListOf<DocumentFile>()
	listFiles().forEach {
		if (it.isDirectory && !it.delete()) { // Deletion is only success if the folder is empty
			fileTree.add(it)
			fileTree.addAll(it.walkFileTreeAndDeleteEmptyDirsDk())
		}
	}
	return fileTree
}

/**
 * @param append if `false` and the file already exists, it will recreate the file.
 */
@JvmOverloads
@WorkerThread
fun DocumentFile.openOutputStreamDk(context: Context, append: Boolean = true) = uri.openOutputStreamDk(context, append)

@WorkerThread
fun DocumentFile.openInputStreamDk(context: Context) = uri.openInputStreamDk(context)

@UiThread
fun DocumentFile.openFileIntentDk(context: Context, authority: String) = Intent(Intent.ACTION_VIEW)
	.setData(if (uri.isRawFileDk) FileProvider.getUriForFile(context, authority, File(uri.path!!)) else uri)
	.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun DocumentFile.hasParentDk(context: Context, parent: DocumentFile) =
	getAbsolutePathDk(context).hasParentDk(parent.getAbsolutePathDk(context))

private fun DocumentFile.walkFileTreeDk(context: Context): List<DocumentFile> {
	val fileTree = mutableListOf<DocumentFile>()
	listFiles().forEach {
		if (it.isDirectory) {
			if (it.emptyDk(context)) {
				fileTree.add(it)
			}
			else {
				fileTree.addAll(it.walkFileTreeDk(context))
			}
		}
		else {
			fileTree.add(it)
		}
	}
	return fileTree
}

private fun DocumentFile.walkFileTreeAndSkipEmptyFilesDk(): List<DocumentFile> {
	val fileTree = mutableListOf<DocumentFile>()
	listFiles().forEach {
		if (it.isDirectory) {
			fileTree.addAll(it.walkFileTreeAndSkipEmptyFilesDk())
		}
		else if (it.length() > 0) {
			fileTree.add(it)
		}
	}
	return fileTree
}

@WorkerThread
fun List<DocumentFile>.moveToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	callback: MultipleFileCallback
) {
	copyToDk(context, targetParentFolder, skipEmptyFiles, true, callback)
}

@WorkerThread
fun List<DocumentFile>.copyToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	callback: MultipleFileCallback
) {
	copyToDk(context, targetParentFolder, skipEmptyFiles, false, callback)
}

private fun List<DocumentFile>.copyToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	deleteSourceWhenComplete: Boolean,
	callback: MultipleFileCallback
) {
	val pair = doesMeetCopyRequirementsDk(context, targetParentFolder, callback) ?: return

	callback.uiScope.postToUiDk { callback.onPrepare() }

	val validSources = pair.second
	val writableTargetParentFolder = pair.first
	val conflictResolutions = validSources.handleParentDirConflictDk(context, writableTargetParentFolder, callback)
		?: return
	validSources
		.removeAll(conflictResolutions.filter { it.solution == DirCallback.ConflictResolution.SKIP }
		.map { it.source })
	if (validSources.isEmpty()) {
		return
	}

	callback.uiScope.postToUiDk { callback.onCountingFiles() }

	class SourceInfo(
		val children: List<DocumentFile>,
		val size: Long,
		val totalFiles: Int,
		val conflictResolution: DirCallback.ConflictResolution
	)

	val sourceInfos = validSources.map { src ->
		val children = if (skipEmptyFiles) src.walkFileTreeAndSkipEmptyFilesDk() else src.walkFileTreeDk(context)
		var totalFilesToCopy = 0
		var totalSizeToCopy = 0L
		children.forEach {
			if (it.isFile) {
				totalFilesToCopy++
				totalSizeToCopy += it.length()
			}
		}
		val resolution =
			conflictResolutions.find { it.source == src }?.solution ?: DirCallback.ConflictResolution.CREATE_NEW
		Pair(src, SourceInfo(children, totalSizeToCopy, totalFilesToCopy, resolution))
	}.toMap().toMutableMap()

	// key=src, value=result
	val results = mutableMapOf<DocumentFile, DocumentFile>()

	if (deleteSourceWhenComplete) {
		sourceInfos.forEach { (src, info) ->
			when (val result = src.tryMoveFolderByRenamingPathDk(
				context,
				writableTargetParentFolder,
				src.fullNameDk,
				skipEmptyFiles,
				null,
				info.conflictResolution
			)) {
				is DocumentFile -> {
					results[src] = result
				}

				is DirCallback.ErrorCode -> {
					val errorCode = when (result) {
						DirCallback.ErrorCode.INVALID_TARGET_FOLDER -> MultipleFileCallback.ErrorCode.INVALID_TARGET_FOLDER
						DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED -> MultipleFileCallback.ErrorCode.STORAGE_PERMISSION_DENIED
						else -> return
					}
					callback.uiScope.postToUiDk { callback.onFailed(errorCode) }
					return
				}
			}
		}

		var copiedFiles = 0
		results.forEach {
			sourceInfos.remove(it.key)?.run {
				copiedFiles += totalFiles
			}
		}

		if (sourceInfos.isEmpty()) {
			val result = MultipleFileCallback.Result(results.map { it.value }, copiedFiles, copiedFiles, true)
			callback.uiScope.postToUiDk { callback.onCompleted(result) }
			return
		}
	}

	val totalSizeToCopy = sourceInfos.values.sumOf { it.size }

	try {
		if (!callback.onCheckFreeSpace(
				SpaceManager.getFreeSpace(
					context,
					writableTargetParentFolder.uri.getStorageIdDk(context)
				), totalSizeToCopy
			)
		) {
			callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
			return
		}
	}
	catch (e: Throwable) {
		callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return
	}

	val thread = Thread.currentThread()
	val totalFilesToCopy = validSources.count { it.isFile } + sourceInfos.values.sumOf { it.totalFiles }
	val reportInterval = awaitUiResult(callback.uiScope) {
		callback.onStart(sourceInfos.map { it.key }, totalFilesToCopy, thread)
	}
	if (reportInterval < 0) return

	var totalCopiedFiles = 0
	var timer: Job? = null
	var bytesMoved = 0L
	var writeSpeed = 0
	val startTimer: (Boolean) -> Unit = { start ->
		if (start && reportInterval > 0) {
			timer = startCoroutineTimer(repeatMillis = reportInterval) {
				val report = MultipleFileCallback.Report(
					bytesMoved * 100f / totalSizeToCopy,
					bytesMoved,
					writeSpeed,
					totalCopiedFiles
				)
				callback.uiScope.postToUiDk { callback.onReport(report) }
				writeSpeed = 0
			}
		}
	}
	startTimer(totalSizeToCopy > 10 * 1024 * 1024)

	var targetFile: DocumentFile? = null
	// This is required to prevent the callback from called again on next FOR iteration
	// after the thread was interrupted
	var canceled = false
	val notifyCanceled: (MultipleFileCallback.ErrorCode) -> Unit = { errorCode ->
		if (!canceled) {
			canceled = true
			timer?.cancel()
			targetFile?.delete()

			val result = MultipleFileCallback.Result(
				results.map { it.value },
				totalFilesToCopy,
				totalCopiedFiles,
				false
			)

			callback.uiScope.postToUiDk {
				callback.onFailed(errorCode)
				callback.onCompleted(result)
			}
		}
	}

	val buffer = ByteArray(1024)
	var success = true

	val copy: (DocumentFile, DocumentFile) -> Unit = { sourceFile, destFile ->
		createFileStreamsDk(context, sourceFile, destFile, callback) { inputStream, outputStream ->
			try {
				var read = inputStream.read(buffer)
				while (read != -1) {
					outputStream.write(buffer, 0, read)
					bytesMoved += read
					writeSpeed += read
					read = inputStream.read(buffer)
				}
			}
			finally {
				inputStream.tryCloseDk()
				outputStream.tryCloseDk()
			}
		}
		totalCopiedFiles++
		if (deleteSourceWhenComplete) sourceFile.delete()

	}

	val handleError: (Exception) -> Boolean = {
		val errorCode = it.toMultipleFileCallbackErrorCodeDk()
		if (errorCode == MultipleFileCallback.ErrorCode.CANCELED || errorCode == MultipleFileCallback.ErrorCode.UNKNOWN_IO_ERROR) {
			notifyCanceled(errorCode)
			true
		}
		else {
			timer?.cancel()
			callback.uiScope.postToUiDk { callback.onFailed(errorCode) }
			false
		}
	}

	val conflictedFiles = mutableListOf<DirCallback.FileConflict>()

	for ((src, info) in sourceInfos) {
		if (thread.isInterrupted) {
			notifyCanceled(MultipleFileCallback.ErrorCode.CANCELED)
			return
		}
		val mode = info.conflictResolution.toCreateMode()
		val targetRootFile = writableTargetParentFolder.let {
			if (src.isDirectory) it.makeDirDk(context, src.fullNameDk, mode)
			else it.makeFileDk(
				context,
				src.fullNameDk,
				src.mimeTypeDk,
				mode
			)
		}
		if (targetRootFile == null) {
			timer?.cancel()
			callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
			return
		}

		try {
			if (targetRootFile.isFile) {
				copy(src, targetRootFile)
				results[src] = targetRootFile
				continue
			}

			val targetFolderParentPath = "${writableTargetParentFolder.getAbsolutePathDk(context)}/${src.fullNameDk}"

			for (sourceFile in info.children) {
				if (thread.isInterrupted) {
					notifyCanceled(MultipleFileCallback.ErrorCode.CANCELED)
					return
				}
				if (!sourceFile.exists()) {
					continue
				}

				val subPath = sourceFile.getSubPath(context, targetFolderParentPath).substringBeforeLast('/', "")
				val filename = ("$subPath/" + sourceFile.fullNameDk).trim('/')
				if (sourceFile.isDirectory) {
					val newFolder = targetRootFile.makeDirDk(context, filename, FileCreationMode.REUSE)
					if (newFolder == null) {
						success = false
						break
					}
					continue
				}

				targetFile = targetRootFile.makeFileDk(context, filename, sourceFile.type, FileCreationMode.REUSE)
				if (targetFile != null && targetFile.length() > 0) {
					conflictedFiles.add(DirCallback.FileConflict(sourceFile, targetFile))
					continue
				}

				if (targetFile == null) {
					notifyCanceled(MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
					return
				}

				copy(sourceFile, targetFile)
			}
			results[src] = targetRootFile
		}
		catch (e: Exception) {
			if (handleError(e)) return
			success = false
			break
		}
	}

	val finalize: () -> Boolean = {
		timer?.cancel()
		if (!success || conflictedFiles.isEmpty()) {
			if (deleteSourceWhenComplete && success) {
				sourceInfos.forEach { (src, _) -> src.deleteDirRecursivelyDk(context) }
			}
			val result = MultipleFileCallback.Result(results.map { it.value }, totalFilesToCopy, totalCopiedFiles, success)
			callback.uiScope.postToUiDk { callback.onCompleted(result) }
			true
		}
		else false
	}
	if (finalize()) return

	val solutions = awaitUiResultWithPending<List<DirCallback.FileConflict>>(callback.uiScope) {
		callback.onContentConflict(
			writableTargetParentFolder,
			conflictedFiles,
			DirCallback.FolderContentConflictAction(it)
		)
	}.filter {
		// free up space first, by deleting some files
		if (it.solution == FileCallback.ConflictResolution.SKIP) {
			if (deleteSourceWhenComplete) it.source.delete()
			totalCopiedFiles++
		}
		it.solution != FileCallback.ConflictResolution.SKIP
	}

	val leftoverSize = totalSizeToCopy - bytesMoved
	startTimer(solutions.isNotEmpty() && leftoverSize > 10 * 1024 * 1024)

	for (conflict in solutions) {
		if (thread.isInterrupted) {
			notifyCanceled(MultipleFileCallback.ErrorCode.CANCELED)
			return
		}
		if (!conflict.source.isFile) {
			continue
		}
		val filename = conflict.target.fullNameDk
		if (conflict.solution == FileCallback.ConflictResolution.REPLACE && conflict.target.let { !it.delete() || it.exists() }) {
			continue
		}

		targetFile = conflict.target.parentFile?.makeFileDk(context, filename)
		if (targetFile == null) {
			notifyCanceled(MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
			return
		}

		try {
			copy(conflict.source, targetFile)
		}
		catch (e: Exception) {
			if (handleError(e)) return
			success = false
			break
		}
	}

	finalize()
}

private fun List<DocumentFile>.doesMeetCopyRequirementsDk(
	context: Context,
	targetParentFolder: DocumentFile,
	callback: MultipleFileCallback
): Pair<DocumentFile, MutableList<DocumentFile>>? {
	callback.uiScope.postToUiDk { callback.onValidate() }

	if (!targetParentFolder.isDirectory) {
		callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.INVALID_TARGET_FOLDER) }
		return null
	}
	if (!targetParentFolder.isWritableDk(context)) {
		callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return null
	}

	val targetParentFolderPath = targetParentFolder.getAbsolutePathDk(context)
	val sourceFiles = distinctBy { it.name }
	val invalidSourceFiles = sourceFiles.mapNotNull {
		when {
			!it.exists() -> Pair(it, DirCallback.ErrorCode.SOURCE_FILE_NOT_FOUND)
			!it.canRead() -> Pair(it, DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED)
			targetParentFolderPath == it.parentFile?.getAbsolutePathDk(context) ->
				Pair(it, DirCallback.ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER)
			else -> null
		}
	}.toMap()

	if (invalidSourceFiles.isNotEmpty()) {
		val abort = awaitUiResultWithPending<Boolean>(callback.uiScope) {
			callback.onInvalidSourceFilesFound(invalidSourceFiles, MultipleFileCallback.InvalidSourceFilesAction(it))
		}
		if (abort) {
			callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.CANCELED) }
			return null
		}
		if (invalidSourceFiles.size == size) {
			callback.uiScope.postToUiDk { callback.onCompleted(MultipleFileCallback.Result(emptyList(), 0, 0, true)) }
			return null
		}
	}

	val writableFolder =
		targetParentFolder.let { if (it.uri.isDownloadsDocumentDk) it.toWritableDownloadsDocumentFileDk(context) else it }
	if (writableFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return null
	}

	return Pair(writableFolder, sourceFiles.toMutableList().apply { removeAll(invalidSourceFiles.map { it.key }) })
}

private fun DocumentFile.tryMoveFolderByRenamingPathDk(
	context: Context,
	writableTargetParentFolder: DocumentFile,
	targetFolderParentName: String,
	skipEmptyFiles: Boolean,
	newFolderNameInTargetPath: String?,
	conflictResolution: DirCallback.ConflictResolution
): Any? {
	if (inSameMountPointWithDk(context, writableTargetParentFolder)) {
		if (inInternalStorageDk(context)) {
			toRawFileDk(context)?.moveToDk(
				context,
				writableTargetParentFolder.getAbsolutePathDk(context),
				targetFolderParentName,
				conflictResolution.toFileConflictResolution()
			)?.let {
				if (skipEmptyFiles) it.deleteEmptyDirsDk(context)
				return DocumentFile.fromFile(it)
			}
		}

		if (isExternalStorageManagerDk(context)) {
			val sourceFile = toRawFileDk(context) ?: return DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED
			writableTargetParentFolder.toRawFileDk(context)?.let { destinationFolder ->
				sourceFile.moveToDk(
					context,
					destinationFolder,
					targetFolderParentName,
					conflictResolution.toFileConflictResolution()
				)?.let {
					if (skipEmptyFiles) it.deleteEmptyDirsDk(context)
					return DocumentFile.fromFile(it)
				}
			}
		}

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !uri.isRawFileDk && writableTargetParentFolder.uri.isTreeDocumentFileDk) {
				val movedFileUri = parentFile?.uri?.let {
					DocumentsContract.moveDocument(
						context.contentResolver,
						uri,
						it,
						writableTargetParentFolder.uri
					)
				}
				if (movedFileUri != null) {
					val newFile = DocumentFile.fromTreeUri(context, movedFileUri)
					return if (newFile != null && newFile.isDirectory) {
						if (newFolderNameInTargetPath != null) newFile.renameTo(targetFolderParentName)
						if (skipEmptyFiles) newFile.deleteEmptyDirsDk(context)
						newFile
					}
					else {
						DirCallback.ErrorCode.INVALID_TARGET_FOLDER
					}
				}
			}
		}
		catch (e: Throwable) {
			return DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED
		}
	}
	return null
}

@WorkerThread
fun DocumentFile.moveDirToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	newFolderNameInTargetPath: String? = null,
	callback: DirCallback
) {
	copyDirToDk(context, targetParentFolder, skipEmptyFiles, newFolderNameInTargetPath, true, callback)
}

@WorkerThread
fun DocumentFile.copyDirToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	newFolderNameInTargetPath: String? = null,
	callback: DirCallback
) {
	copyDirToDk(context, targetParentFolder, skipEmptyFiles, newFolderNameInTargetPath, false, callback)
}

/**
 * @param skipEmptyFiles skip copying empty files & folders
 */
private fun DocumentFile.copyDirToDk(
	context: Context,
	targetParentFolder: DocumentFile,
	skipEmptyFiles: Boolean = true,
	newFolderNameInTargetPath: String? = null,
	deleteSourceWhenComplete: Boolean,
	callback: DirCallback
) {
	val writableTargetParentFolder =
		doesMeetCopyRequirementsDk(context, targetParentFolder, newFolderNameInTargetPath, callback) ?: return

	callback.uiScope.postToUiDk { callback.onPrepare() }

	val targetFolderParentName =
		(newFolderNameInTargetPath ?: name.orEmpty()).removeForbiddenCharsFromFilenameDk().trim('/')
	val conflictResolution = handleParentDirConflictDk(context, targetParentFolder, targetFolderParentName, callback)
	if (conflictResolution == DirCallback.ConflictResolution.SKIP) {
		return
	}

	callback.uiScope.postToUiDk { callback.onCountingFiles() }

	val filesToCopy = if (skipEmptyFiles) walkFileTreeAndSkipEmptyFilesDk() else walkFileTreeDk(context)
	if (filesToCopy.isEmpty()) {
		val targetFolder =
			writableTargetParentFolder.makeDirDk(context, targetFolderParentName, conflictResolution.toCreateMode())
		if (targetFolder == null) {
			callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
		}
		else {
			if (deleteSourceWhenComplete) delete()
			callback.uiScope.postToUiDk { callback.onCompleted(DirCallback.Result(targetFolder, 0, 0, true)) }
		}
		return
	}

	var totalFilesToCopy = 0
	var totalSizeToCopy = 0L
	filesToCopy.forEach {
		if (it.isFile) {
			totalFilesToCopy++
			totalSizeToCopy += it.length()
		}
	}

	val thread = Thread.currentThread()
	if (thread.isInterrupted) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANCELED) }
		return
	}

	if (deleteSourceWhenComplete) {
		when (val result = tryMoveFolderByRenamingPathDk(
			context,
			writableTargetParentFolder,
			targetFolderParentName,
			skipEmptyFiles,
			newFolderNameInTargetPath,
			conflictResolution
		)) {
			is DocumentFile -> {
				callback.uiScope.postToUiDk {
					callback.onCompleted(
						DirCallback.Result(
							result,
							totalFilesToCopy,
							totalFilesToCopy,
							true
						)
					)
				}
				return
			}

			is DirCallback.ErrorCode -> {
				callback.uiScope.postToUiDk { callback.onFailed(result) }
				return
			}
		}
	}

	try {
		if (!callback.onCheckFreeSpace(
				SpaceManager.getFreeSpace(
					context,
					writableTargetParentFolder.uri.getStorageIdDk(context)
				), totalSizeToCopy
			)
		) {
			callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
			return
		}
	}
	catch (e: Throwable) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return
	}

	val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, totalFilesToCopy, thread) }
	if (reportInterval < 0) return

	val targetFolder =
		writableTargetParentFolder.makeDirDk(context, targetFolderParentName, conflictResolution.toCreateMode())
	if (targetFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
		return
	}

	var totalCopiedFiles = 0
	var timer: Job? = null
	var bytesMoved = 0L
	var writeSpeed = 0
	val startTimer: (Boolean) -> Unit = { start ->
		if (start && reportInterval > 0) {
			timer = startCoroutineTimer(repeatMillis = reportInterval) {
				val report =
					DirCallback.Report(bytesMoved * 100f / totalSizeToCopy, bytesMoved, writeSpeed, totalCopiedFiles)
				callback.uiScope.postToUiDk { callback.onReport(report) }
				writeSpeed = 0
			}
		}
	}
	startTimer(totalSizeToCopy > 10 * 1024 * 1024)

	var targetFile: DocumentFile? = null
	var canceled =
		false // is required to prevent the callback from called again on next FOR iteration after the thread was interrupted
	val notifyCanceled: (DirCallback.ErrorCode) -> Unit = { errorCode ->
		if (!canceled) {
			canceled = true
			timer?.cancel()
			targetFile?.delete()
			callback.uiScope.postToUiDk {
				callback.onFailed(errorCode)
				callback.onCompleted(DirCallback.Result(targetFolder, totalFilesToCopy, totalCopiedFiles, false))
			}
		}
	}

	val targetFolderParentPath = "${writableTargetParentFolder.getAbsolutePathDk(context)}/$targetFolderParentName"
	val conflictedFiles = ArrayList<DirCallback.FileConflict>(totalFilesToCopy)
	val buffer = ByteArray(1024)
	var success = true

	val copy: (DocumentFile, DocumentFile) -> Unit = { sourceFile, destFile ->
		createFileStreamsDk(context, sourceFile, destFile, callback) { inputStream, outputStream ->
			try {
				var read = inputStream.read(buffer)
				while (read != -1) {
					outputStream.write(buffer, 0, read)
					bytesMoved += read
					writeSpeed += read
					read = inputStream.read(buffer)
				}
			}
			finally {
				inputStream.tryCloseDk()
				outputStream.tryCloseDk()
			}
		}
		totalCopiedFiles++
		if (deleteSourceWhenComplete) sourceFile.delete()
	}

	val handleError: (Exception) -> Boolean = {
		val errorCode = it.toDirCallbackErrorCodeDk()
		if (errorCode == DirCallback.ErrorCode.CANCELED || errorCode == DirCallback.ErrorCode.UNKNOWN_IO_ERROR) {
			notifyCanceled(errorCode)
			true
		}
		else {
			timer?.cancel()
			callback.uiScope.postToUiDk { callback.onFailed(errorCode) }
			false
		}
	}

	for (sourceFile in filesToCopy) {
		try {
			if (Thread.currentThread().isInterrupted) {
				notifyCanceled(DirCallback.ErrorCode.CANCELED)
				return
			}
			if (!sourceFile.exists()) {
				continue
			}

			val subPath = sourceFile.getSubPath(context, targetFolderParentPath).substringBeforeLast('/', "")
			val filename = ("$subPath/" + sourceFile.name.orEmpty()).trim('/')
			if (sourceFile.isDirectory) {
				val newFolder = targetFolder.makeDirDk(context, filename, FileCreationMode.REUSE)
				if (newFolder == null) {
					success = false
					break
				}
				continue
			}

			targetFile = targetFolder.makeFileDk(context, filename, sourceFile.type, FileCreationMode.REUSE)
			if (targetFile != null && targetFile.length() > 0) {
				conflictedFiles.add(DirCallback.FileConflict(sourceFile, targetFile))
				continue
			}

			if (targetFile == null) {
				notifyCanceled(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
				return
			}

			copy(sourceFile, targetFile)
		}
		catch (e: Exception) {
			if (handleError(e)) return
			success = false
			break
		}
	}

	val finalize: () -> Boolean = {
		timer?.cancel()
		if (!success || conflictedFiles.isEmpty()) {
			if (deleteSourceWhenComplete && success) deleteDk(context)
			callback.uiScope.postToUiDk {
				callback.onCompleted(
					DirCallback.Result(
						targetFolder,
						totalFilesToCopy,
						totalCopiedFiles,
						success
					)
				)
			}
			true
		}
		else false
	}
	if (finalize()) return

	val solutions = awaitUiResultWithPending<List<DirCallback.FileConflict>>(callback.uiScope) {
		callback.onContentConflict(targetFolder, conflictedFiles, DirCallback.FolderContentConflictAction(it))
	}.filter {
		// free up space first, by deleting some files
		if (it.solution == FileCallback.ConflictResolution.SKIP) {
			if (deleteSourceWhenComplete) it.source.delete()
			totalCopiedFiles++
		}
		it.solution != FileCallback.ConflictResolution.SKIP
	}

	val leftoverSize = totalSizeToCopy - bytesMoved
	startTimer(solutions.isNotEmpty() && leftoverSize > 10 * 1024 * 1024)

	for (conflict in solutions) {
		if (Thread.currentThread().isInterrupted) {
			notifyCanceled(DirCallback.ErrorCode.CANCELED)
			return
		}
		if (!conflict.source.isFile) {
			continue
		}
		val filename = conflict.target.name.orEmpty()
		if (conflict.solution == FileCallback.ConflictResolution.REPLACE && conflict.target.let { !it.delete() || it.exists() }) {
			continue
		}

		targetFile = conflict.target.parentFile?.makeFileDk(context, filename)
		if (targetFile == null) {
			notifyCanceled(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET)
			return
		}

		try {
			copy(conflict.source, targetFile)
		}
		catch (e: Exception) {
			if (handleError(e)) return
			success = false
			break
		}
	}

	finalize()
}

private fun Exception.toDirCallbackErrorCodeDk(): DirCallback.ErrorCode {
	return when (this) {
		is SecurityException -> DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED
		is InterruptedIOException, is InterruptedException -> DirCallback.ErrorCode.CANCELED
		else -> DirCallback.ErrorCode.UNKNOWN_IO_ERROR
	}
}

private fun Exception.toMultipleFileCallbackErrorCodeDk(): MultipleFileCallback.ErrorCode {
	return when (this) {
		is SecurityException -> MultipleFileCallback.ErrorCode.STORAGE_PERMISSION_DENIED
		is InterruptedIOException, is InterruptedException -> MultipleFileCallback.ErrorCode.CANCELED
		else -> MultipleFileCallback.ErrorCode.UNKNOWN_IO_ERROR
	}
}

private fun DocumentFile.doesMeetCopyRequirementsDk(
	context: Context,
	targetParentFolder: DocumentFile,
	newFolderNameInTargetPath: String?,
	callback: DirCallback
): DocumentFile? {
	callback.uiScope.postToUiDk { callback.onValidate() }

	if (!isDirectory) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.SOURCE_FOLDER_NOT_FOUND) }
		return null
	}

	if (!targetParentFolder.isDirectory) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.INVALID_TARGET_FOLDER) }
		return null
	}

	if (!canRead() || !targetParentFolder.isWritableDk(context)) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return null
	}

	if (targetParentFolder.getAbsolutePathDk(context) == parentFile?.getAbsolutePathDk(context) && (newFolderNameInTargetPath.isNullOrEmpty() || name == newFolderNameInTargetPath)) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER) }
		return null
	}

	val writableFolder =
		targetParentFolder.let { if (it.uri.isDownloadsDocumentDk) it.toWritableDownloadsDocumentFileDk(context) else it }
	if (writableFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
	}
	return writableFolder
}

/**
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.copyFileToDk(
	context: Context,
	targetFolder: File,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	copyFileToDk(context, targetFolder.absolutePath, fileDescription, callback)
}

/**
 * @param targetFolderAbsolutePath use [DkExternalStorage.getAbsolutePath] to construct the path
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.copyFileToDk(
	context: Context,
	targetFolderAbsolutePath: String,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	val targetFolder = DkStorage.mkdirs(context, targetFolderAbsolutePath, true)
	if (targetFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
	}
	else {
		copyFileToDk(context, targetFolder, fileDescription, callback)
	}
}

/**
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.copyFileToDk(
	context: Context,
	targetFolder: DocumentFile,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	if (fileDescription?.subDir.isNullOrEmpty()) {
		copyFileToDk(context, targetFolder, fileDescription?.name, fileDescription?.mimeType, callback)
	}
	else {
		val targetDirectory = targetFolder.makeDirDk(context, fileDescription?.subDir.orEmpty(), FileCreationMode.REUSE)
		if (targetDirectory == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
		}
		else {
			copyFileToDk(context, targetDirectory, fileDescription?.name, fileDescription?.mimeType, callback)
		}
	}
}

private fun DocumentFile.copyFileToDk(
	context: Context,
	targetFolder: DocumentFile,
	newFilenameInTargetPath: String?,
	newMimeTypeInTargetPath: String?,
	callback: FileCallback
) {
	val writableTargetFolder =
		doesMeetCopyRequirementsDk(context, targetFolder, newFilenameInTargetPath, callback) ?: return

	callback.uiScope.postToUiDk { callback.onPrepare() }

	try {
		if (!callback.onCheckFreeSpace(
				SpaceManager.getFreeSpace(
					context,
					writableTargetFolder.uri.getStorageIdDk(context)
				), length()
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

	val cleanFileName = MimeType
		.getFullFileName(newFilenameInTargetPath ?: name.orEmpty(), newMimeTypeInTargetPath ?: mimeTypeByFileNameDk)
		.removeForbiddenCharsFromFilenameDk().trim('/')
	
	val fileConflictResolution = handleFileConflictDk(context, writableTargetFolder, cleanFileName, callback)
	
	if (fileConflictResolution == FileCallback.ConflictResolution.SKIP) {
		return
	}

	val thread = Thread.currentThread()
	val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, thread) }
	if (reportInterval < 0) return
	val watchProgress = reportInterval > 0

	try {
		val targetFile = createTargetFileDk(
			context, writableTargetFolder, cleanFileName, newMimeTypeInTargetPath ?: mimeTypeByFileNameDk,
			fileConflictResolution.toCreateMode(), callback
		) ?: return
		createFileStreamsDk(context, this, targetFile, callback) { inputStream, outputStream ->
			copyFileStreamDk(inputStream, outputStream, targetFile, watchProgress, reportInterval, false, callback)
		}
	}
	catch (e: Exception) {
		callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
	}
}

/**
 * @return writable [DocumentFile] for `targetFolder`
 */
private fun DocumentFile.doesMeetCopyRequirementsDk(
	context: Context,
	targetFolder: DocumentFile,
	newFilenameInTargetPath: String?,
	callback: FileCallback
): DocumentFile? {
	callback.uiScope.postToUiDk { callback.onValidate() }

	if (!isFile) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND) }
		return null
	}

	if (!targetFolder.isDirectory) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.TARGET_FOLDER_NOT_FOUND) }
		return null
	}

	if (!canRead() || !targetFolder.isWritableDk(context)) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return null
	}

	if (parentFile?.getAbsolutePathDk(context) == targetFolder.getAbsolutePathDk(context) && (newFilenameInTargetPath.isNullOrEmpty() || name == newFilenameInTargetPath)) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER) }
		return null
	}

	val writableFolder =
		targetFolder.let { if (it.uri.isDownloadsDocumentDk) it.toWritableDownloadsDocumentFileDk(context) else it }
	if (writableFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
	}
	return writableFolder
}

@Suppress("UNCHECKED_CAST")
private fun <Enum> createFileStreamsDk(
	context: Context,
	sourceFile: DocumentFile,
	targetFile: DocumentFile,
	callback: BaseFileCallback<Enum, *, *>,
	onStreamsReady: (InputStream, OutputStream) -> Unit
) {
	val outputStream = targetFile.openOutputStreamDk(context)
	if (outputStream == null) {
		val errorCode = when (callback) {
			is MultipleFileCallback -> MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET
			is DirCallback -> DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET
			else -> FileCallback.ErrorCode.TARGET_FILE_NOT_FOUND
		}
		callback.uiScope.postToUiDk { callback.onFailed(errorCode as Enum) }
		return
	}

	val inputStream = sourceFile.openInputStreamDk(context)
	if (inputStream == null) {
		outputStream.tryCloseDk()
		val errorCode = when (callback) {
			is MultipleFileCallback -> MultipleFileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND
			is DirCallback -> DirCallback.ErrorCode.SOURCE_FILE_NOT_FOUND
			else -> FileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND
		}
		callback.uiScope.postToUiDk { callback.onFailed(errorCode as Enum) }
		return
	}

	onStreamsReady(inputStream, outputStream)
}

private inline fun createFileStreamsDk(
	context: Context,
	sourceFile: DocumentFile,
	targetFile: MediaFile,
	callback: FileCallback,
	onStreamsReady: (InputStream, OutputStream) -> Unit
) {
	val outputStream = targetFile.openOutputStream()
	if (outputStream == null) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.TARGET_FILE_NOT_FOUND) }
		return
	}

	val inputStream = sourceFile.openInputStreamDk(context)
	if (inputStream == null) {
		outputStream.tryCloseDk()
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND) }
		return
	}

	onStreamsReady(inputStream, outputStream)
}

private fun createTargetFileDk(
	context: Context,
	targetFolder: DocumentFile,
	newFilenameInTargetPath: String,
	mimeType: String?,
	mode: FileCreationMode,
	callback: FileCallback
): DocumentFile? {
	val targetFile = targetFolder.makeFileDk(context, newFilenameInTargetPath, mimeType, mode)
	if (targetFile == null) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
	}
	return targetFile
}

/**
 * @param targetFile can be [MediaFile] or [DocumentFile]
 */
private fun DocumentFile.copyFileStreamDk(
	inputStream: InputStream,
	outputStream: OutputStream,
	targetFile: Any,
	watchProgress: Boolean,
	reportInterval: Long,
	deleteSourceFileWhenComplete: Boolean,
	callback: FileCallback
) {
	var timer: Job? = null
	try {
		var bytesMoved = 0L
		var writeSpeed = 0
		val srcSize = length()
		// using timer on small file is useless. We set minimum 10MB.
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
		if (targetFile is MediaFile) {
			targetFile.length = srcSize
		}
		callback.uiScope.postToUiDk { callback.onCompleted(targetFile) }
	}
	finally {
		timer?.cancel()
		inputStream.tryCloseDk()
		outputStream.tryCloseDk()
	}
}

/**
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.moveFileToDk(
	context: Context,
	targetFolder: File,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	moveFileToDk(context, targetFolder.absolutePath, fileDescription, callback)
}

/**
 * @param targetFolderAbsolutePath use [DkExternalStorage.getAbsolutePath] to construct the path
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.moveFileToDk(
	context: Context,
	targetFolderAbsolutePath: String,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	val targetFolder = DkStorage.mkdirs(context, targetFolderAbsolutePath, true)
	if (targetFolder == null) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
	}
	else {
		moveFileToDk(context, targetFolder, fileDescription, callback)
	}
}

/**
 * @param fileDescription Use it if you want to change file name and type in the destination.
 */
@WorkerThread
fun DocumentFile.moveFileToDk(
	context: Context,
	targetFolder: DocumentFile,
	fileDescription: FileDescription? = null,
	callback: FileCallback
) {
	if (fileDescription?.subDir.isNullOrEmpty()) {
		moveFileToDk(context, targetFolder, fileDescription?.name, fileDescription?.mimeType, callback)
	}
	else {
		val targetDirectory = targetFolder.makeDirDk(context, fileDescription?.subDir.orEmpty(), FileCreationMode.REUSE)
		if (targetDirectory == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
		}
		else {
			moveFileToDk(context, targetDirectory, fileDescription?.name, fileDescription?.mimeType, callback)
		}
	}
}

private fun DocumentFile.moveFileToDk(
	context: Context,
	targetFolder: DocumentFile,
	newFilenameInTargetPath: String?,
	newMimeTypeInTargetPath: String?,
	callback: FileCallback
) {
	val writableTargetFolder =
		doesMeetCopyRequirementsDk(context, targetFolder, newFilenameInTargetPath, callback) ?: return

	callback.uiScope.postToUiDk { callback.onPrepare() }

	val cleanFileName = MimeType
		.getFullFileName(newFilenameInTargetPath ?: name.orEmpty(), newMimeTypeInTargetPath ?: mimeTypeByFileNameDk)
		.removeForbiddenCharsFromFilenameDk()
		.trim('/')
	val fileConflictResolution = handleFileConflictDk(context, writableTargetFolder, cleanFileName, callback)
	if (fileConflictResolution == FileCallback.ConflictResolution.SKIP) {
		return
	}

	if (inInternalStorageDk(context)) {
		toRawFileDk(context)?.moveToDk(
			context,
			writableTargetFolder.getAbsolutePathDk(context),
			cleanFileName,
			fileConflictResolution
		)?.let {
			callback.uiScope.postToUiDk { callback.onCompleted(DocumentFile.fromFile(it)) }
			return
		}
	}

	val targetStorageId = writableTargetFolder.uri.getStorageIdDk(context)
	if (isExternalStorageManagerDk(context) && uri.getStorageIdDk(context) == targetStorageId) {
		val sourceFile = toRawFileDk(context)
		if (sourceFile == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
			return
		}
		writableTargetFolder.toRawFileDk(context)?.let { destinationFolder ->
			sourceFile.moveToDk(context, destinationFolder, cleanFileName, fileConflictResolution)?.let {
				callback.uiScope.postToUiDk { callback.onCompleted(DocumentFile.fromFile(it)) }
				return
			}
		}
	}

	try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !uri.isRawFileDk && writableTargetFolder.uri.isTreeDocumentFileDk && uri.getStorageIdDk(
				context
			) == targetStorageId
		) {
			val movedFileUri = parentFile?.uri?.let {
				DocumentsContract.moveDocument(
					context.contentResolver,
					uri,
					it,
					writableTargetFolder.uri
				)
			}
			if (movedFileUri != null) {
				val newFile = DocumentFile.fromTreeUri(context, movedFileUri)
				if (newFile != null && newFile.isFile) {
					if (newFilenameInTargetPath != null) newFile.renameTo(cleanFileName)
					callback.uiScope.postToUiDk { callback.onCompleted(newFile) }
				}
				else {
					callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.TARGET_FILE_NOT_FOUND) }
				}
				return
			}
		}

		if (!callback.onCheckFreeSpace(SpaceManager.getFreeSpace(context, targetStorageId), length())) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
			return
		}
	}
	catch (e: Throwable) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return
	}

	val thread = Thread.currentThread()
	val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, thread) }
	if (reportInterval < 0) return
	val watchProgress = reportInterval > 0

	try {
		val targetFile = createTargetFileDk(
			context, writableTargetFolder, cleanFileName, newMimeTypeInTargetPath ?: mimeTypeByFileNameDk,
			fileConflictResolution.toCreateMode(), callback
		) ?: return
		createFileStreamsDk(context, this, targetFile, callback) { inputStream, outputStream ->
			copyFileStreamDk(inputStream, outputStream, targetFile, watchProgress, reportInterval, true, callback)
		}
	}
	catch (e: Exception) {
		callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
	}
}

/**
 * @return `true` if error
 */
private fun DocumentFile.simpleCheckSourceFileDk(callback: FileCallback): Boolean {
	if (!isFile) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.SOURCE_FILE_NOT_FOUND) }
		return true
	}
	if (!canRead()) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return true
	}
	return false
}

private fun DocumentFile.copyFileToMediaDk(
	context: Context,
	fileDescription: FileDescription,
	callback: FileCallback,
	publicDirectory: PublicDirectory,
	deleteSourceFileWhenComplete: Boolean,
	mode: FileCreationMode
) {
	if (simpleCheckSourceFileDk(callback)) return

	val publicFolder = DkExternalStorage.getFileByPath(context, publicDirectory, fileDescription.subDir, true)
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || deleteSourceFileWhenComplete && !uri.isRawFileDk && publicFolder?.uri!!.isTreeDocumentFileDk) {
		if (publicFolder == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
			return
		}
		publicFolder.findChildDk(context, fileDescription.fullName)?.let {
			if (mode == FileCreationMode.REPLACE) {
				if (!it.deleteDk(context)) {
					callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
					return
				}
			}
			else {
				fileDescription.name = publicFolder.incrementFileNameDk(context, it.name.orEmpty())
			}
		}
		fileDescription.subDir = ""
		if (deleteSourceFileWhenComplete) {
			moveFileToDk(context, publicFolder, fileDescription, callback)
		}
		else {
			copyFileToDk(context, publicFolder, fileDescription, callback)
		}
	}
	else {
		val validMode = if (mode == FileCreationMode.REUSE) FileCreationMode.CREATE_NEW else mode
		val mediaFile = if (publicDirectory == PublicDirectory.DOWNLOADS) {
			DkMediaStore.createDownloadFile(context, fileDescription, validMode)
		}
		else {
			DkMediaStore.createImageFile(context, fileDescription, creationMode = validMode)
		}
		if (mediaFile == null) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
		}
		else {
			copyFileToDk(context, mediaFile, deleteSourceFileWhenComplete, callback)
		}
	}
}

@WorkerThread
@JvmOverloads
fun DocumentFile.copyFileToDownloadMediaDk(
	context: Context,
	fileDescription: FileDescription,
	callback: FileCallback,
	mode: FileCreationMode = FileCreationMode.CREATE_NEW
) {
	copyFileToMediaDk(context, fileDescription, callback, PublicDirectory.DOWNLOADS, false, mode)
}

@WorkerThread
@JvmOverloads
fun DocumentFile.copyFileToPictureMediaDk(
	context: Context,
	fileDescription: FileDescription,
	callback: FileCallback,
	mode: FileCreationMode = FileCreationMode.CREATE_NEW
) {
	copyFileToMediaDk(context, fileDescription, callback, PublicDirectory.PICTURES, false, mode)
}

@WorkerThread
@JvmOverloads
fun DocumentFile.moveFileToDownloadMediaDk(
	context: Context,
	fileDescription: FileDescription,
	callback: FileCallback,
	mode: FileCreationMode = FileCreationMode.CREATE_NEW
) {
	copyFileToMediaDk(context, fileDescription, callback, PublicDirectory.DOWNLOADS, true, mode)
}

@WorkerThread
@JvmOverloads
fun DocumentFile.moveFileToPictureMediaDk(
	context: Context,
	fileDescription: FileDescription,
	callback: FileCallback,
	mode: FileCreationMode = FileCreationMode.CREATE_NEW
) {
	copyFileToMediaDk(context, fileDescription, callback, PublicDirectory.PICTURES, true, mode)
}

/**
 * @param targetFile create it with [DkMediaStore], e.g. [DkMediaStore.createDownloadFile]
 */
@WorkerThread
fun DocumentFile.moveFileToDk(context: Context, targetFile: MediaFile, callback: FileCallback) {
	copyFileToDk(context, targetFile, true, callback)
}

/**
 * @param targetFile create it with [DkMediaStore], e.g. [DkMediaStore.createDownloadFile]
 */
@WorkerThread
fun DocumentFile.copyFileToDk(context: Context, targetFile: MediaFile, callback: FileCallback) {
	copyFileToDk(context, targetFile, false, callback)
}

private fun DocumentFile.copyFileToDk(
	context: Context,
	targetFile: MediaFile,
	deleteSourceFileWhenComplete: Boolean,
	callback: FileCallback
) {
	if (simpleCheckSourceFileDk(callback)) return

	try {
		if (!callback.onCheckFreeSpace(SpaceManager.getFreeSpace(context, PRIMARY), length())) {
			callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH) }
			return
		}
	}
	catch (e: Throwable) {
		callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED) }
		return
	}

	val thread = Thread.currentThread()
	val reportInterval = awaitUiResult(callback.uiScope) { callback.onStart(this, thread) }
	if (reportInterval < 0) return
	val watchProgress = reportInterval > 0

	try {
		createFileStreamsDk(context, this, targetFile, callback) { inputStream, outputStream ->
			copyFileStreamDk(
				inputStream,
				outputStream,
				targetFile,
				watchProgress,
				reportInterval,
				deleteSourceFileWhenComplete,
				callback
			)
		}
	}
	catch (e: Exception) {
		callback.uiScope.postToUiDk { callback.onFailed(e.toFileCallbackErrorCodeDk()) }
	}
}

internal fun Exception.toFileCallbackErrorCodeDk(): FileCallback.ErrorCode {
	return when (this) {
		is SecurityException -> FileCallback.ErrorCode.STORAGE_PERMISSION_DENIED
		is InterruptedIOException, is InterruptedException -> FileCallback.ErrorCode.CANCELED
		else -> FileCallback.ErrorCode.UNKNOWN_IO_ERROR
	}
}

private fun handleFileConflictDk(
	context: Context,
	targetFolder: DocumentFile,
	targetFileName: String,
	callback: FileCallback
): FileCallback.ConflictResolution {
	targetFolder.findChildDk(context, targetFileName)?.let { targetFile ->
		val resolution = awaitUiResultWithPending<FileCallback.ConflictResolution>(callback.uiScope) {
			callback.onConflict(targetFile, FileCallback.FileConflictAction(it))
		}
		if (resolution == FileCallback.ConflictResolution.REPLACE) {
			callback.uiScope.postToUiDk { callback.onDeleteConflictedFiles() }
			if (!targetFile.deleteDk(context)) {
				callback.uiScope.postToUiDk { callback.onFailed(FileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
				return FileCallback.ConflictResolution.SKIP
			}
		}
		return resolution
	}
	return FileCallback.ConflictResolution.CREATE_NEW
}

private fun handleParentDirConflictDk(
	context: Context,
	targetParentFolder: DocumentFile,
	targetFolderParentName: String,
	callback: DirCallback
): DirCallback.ConflictResolution {
	targetParentFolder.findChildDk(context, targetFolderParentName)?.let { targetFolder ->
		val canMerge = targetFolder.isDirectory
		if (canMerge && targetFolder.emptyDk(context)) {
			return DirCallback.ConflictResolution.MERGE
		}

		val resolution = awaitUiResultWithPending<DirCallback.ConflictResolution>(callback.uiScope) {
			callback.onParentConflict(targetFolder, DirCallback.ParentFolderConflictAction(it), canMerge)
		}

		@Suppress("NON_EXHAUSTIVE_WHEN")
		when (resolution) {
			DirCallback.ConflictResolution.REPLACE -> {
				callback.uiScope.postToUiDk { callback.onDeleteConflictedFiles() }
				val isFolder = targetFolder.isDirectory
				if (targetFolder.deleteDk(context, true)) {
					if (!isFolder) {
						val newFolder = targetFolder.parentFile?.createDirectory(targetFolderParentName)
						if (newFolder == null) {
							callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
							return DirCallback.ConflictResolution.SKIP
						}
					}
				}
				else {
					callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
					return DirCallback.ConflictResolution.SKIP
				}
			}

			DirCallback.ConflictResolution.MERGE -> {
				if (targetFolder.isFile) {
					if (targetFolder.delete()) {
						val newFolder = targetFolder.parentFile?.createDirectory(targetFolderParentName)
						if (newFolder == null) {
							callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
							return DirCallback.ConflictResolution.SKIP
						}
					}
					else {
						callback.uiScope.postToUiDk { callback.onFailed(DirCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
						return DirCallback.ConflictResolution.SKIP
					}
				}
			}
		}
		return resolution
	}
	return DirCallback.ConflictResolution.CREATE_NEW
}

private fun List<DocumentFile>.handleParentDirConflictDk(
	context: Context,
	targetParentFolder: DocumentFile,
	callback: MultipleFileCallback
): List<MultipleFileCallback.ParentConflict>? {
	val sourceFileNames = map { it.name }
	val conflictedFiles = targetParentFolder.listFiles().filter { it.name in sourceFileNames }
	val conflicts = conflictedFiles.map {
		val sourceFile = first { src -> src.name == it.name }
		val canMerge = sourceFile.isDirectory && it.isDirectory
		val solution =
			if (canMerge && it.emptyDk(context)) DirCallback.ConflictResolution.MERGE else DirCallback.ConflictResolution.CREATE_NEW
		MultipleFileCallback.ParentConflict(sourceFile, it, canMerge, solution)
	}
	val unresolvedConflicts = conflicts.filter { it.solution != DirCallback.ConflictResolution.MERGE }.toMutableList()
	if (unresolvedConflicts.isNotEmpty()) {
		val unresolvedFiles = unresolvedConflicts.filter { it.source.isFile }.toMutableList()
		val unresolvedFolders = unresolvedConflicts.filter { it.source.isDirectory }.toMutableList()
		val resolution = awaitUiResultWithPending<List<MultipleFileCallback.ParentConflict>>(callback.uiScope) {
			callback.onParentConflict(
				targetParentFolder,
				unresolvedFolders,
				unresolvedFiles,
				MultipleFileCallback.ParentFolderConflictAction(it)
			)
		}
		if (resolution.any { it.solution == DirCallback.ConflictResolution.REPLACE }) {
			callback.uiScope.postToUiDk { callback.onDeleteConflictedFiles() }
		}
		resolution.forEach { conflict ->
			@Suppress("NON_EXHAUSTIVE_WHEN")
			when (conflict.solution) {
				DirCallback.ConflictResolution.REPLACE -> {
					if (!conflict.target.let { it.deleteDirRecursivelyDk(context, true) || !it.exists() }) {
						callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
						return null
					}
				}

				DirCallback.ConflictResolution.MERGE -> {
					if (conflict.target.isFile && !conflict.target.delete()) {
						callback.uiScope.postToUiDk { callback.onFailed(MultipleFileCallback.ErrorCode.CANNOT_CREATE_FILE_IN_TARGET) }
						return null
					}
				}
			}
		}
		return resolution.toMutableList()
			.apply { addAll(conflicts.filter { it.solution == DirCallback.ConflictResolution.MERGE }) }
	}
	return emptyList()
}