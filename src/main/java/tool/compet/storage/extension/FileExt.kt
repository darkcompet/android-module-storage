package tool.compet.storage.extension

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.annotation.RestrictTo
import androidx.annotation.WorkerThread
import tool.compet.core.DkLogcats
import tool.compet.core.createFileDk
import tool.compet.core.emptyDk
import tool.compet.storage.*
import tool.compet.storage.StorageId.DATA
import tool.compet.storage.StorageId.PRIMARY
import tool.compet.storage.callback.FileCallback
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * ID of this storage. For external storage, it will return [PRIMARY],
 * otherwise it is a SD Card and will return integers like `6881-2249`.
 */
fun File.getStorageIdDk(context: Context) = when {
	path.startsWith(DkStorage.externalStoragePath) -> PRIMARY
	path.startsWith(context.dataRootDirDk.path) -> DATA
	else -> path.substringAfter("/storage/", "").substringBefore('/')
}

val File.inPrimaryExternalStorageDk: Boolean
	get() = path.startsWith(DkStorage.externalStoragePath)

fun File.inDataStorageDk(context: Context) = path.startsWith(context.dataRootDirDk.path)

fun File.inSdCardStorageDk(context: Context) =
	getStorageIdDk(context).let { it != PRIMARY && it != DATA && path.startsWith("/storage/$it") }

fun File.inSameMountPointWithDk(context: Context, file: File): Boolean {
	val storageId1 = getStorageIdDk(context)
	val storageId2 = file.getStorageIdDk(context)
	return storageId1 == storageId2 || (storageId1 == PRIMARY || storageId1 == DATA) && (storageId2 == PRIMARY || storageId2 == DATA)
}

fun File.getStorageTypeDk(context: Context) = when {
	inPrimaryExternalStorageDk -> StorageType.EXTERNAL
	inDataStorageDk(context) -> StorageType.DATA
	inSdCardStorageDk(context) -> StorageType.SD_CARD
	else -> StorageType.UNKNOWN
}

/**
 * Get raw file of given child at this directory.
 *
 * @param pathname Single fileName (for eg,. `database.json`), or filePath (for eg,. `FileMan/Backup/database.json`).
 */
fun File.childDk(pathname: String) = File(this, pathname)

fun File.getBasePathDk(context: Context): String {
	val externalStoragePath = DkStorage.externalStoragePath
	if (path.startsWith(externalStoragePath)) {
		return path.substringAfter(externalStoragePath, "").trim('/')
	}
	val dataDir = context.dataRootDirDk.path
	if (path.startsWith(dataDir)) {
		return path.substringAfter(dataDir, "").trim('/')
	}
	val storageId = getStorageIdDk(context)
	return path.substringAfter("/storage/$storageId", "").trim('/')
}

fun File.getRootPathDk(context: Context): String {
	val storageId = getStorageIdDk(context)
	return when {
		storageId == PRIMARY -> DkStorage.externalStoragePath
		storageId == DATA -> context.dataRootDirDk.path
		storageId.isNotEmpty() -> "/storage/$storageId"
		else -> ""
	}
}

fun File.getSimplePathDk(context: Context) = "${getStorageIdDk(context)}:${getBasePathDk(context)}".removePrefix(":")

/**
 *  Returns:
 * * `null` if it is a directory or the file does not exist
 * * [MimeType.UNKNOWN] if the file exists but the mime type is not found
 */
val File.mimeTypeDk: String?
	get() = if (isFile) MimeType.getMimeTypeFromExtension(extension) else null

@JvmOverloads
fun File.getRootRawFileDk(context: Context, requireWritable: Boolean = false) = getRootPathDk(context).let { rootPath ->
	if (rootPath.isEmpty()) {
		null
	}
	else {
		File(rootPath).run {
			if (canRead()) takeIfWritableDk(context, requireWritable) else null
		}
	}
}

fun File.canModifyDk(context: Context) = canRead() && isWritableDk(context)

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun File.takeIfWritableDk(context: Context, requireWritable: Boolean) = takeIf {
	!requireWritable || isWritableDk(context)
}

@RestrictTo(RestrictTo.Scope.LIBRARY)
fun File.checkRequirementsDk(context: Context, requireWritable: Boolean, considerRawFile: Boolean) : Boolean {
	return canRead()
		&& (considerRawFile || isExternalStorageManagerDk(context))
		&& (! requireWritable || isWritableDk(context))
}

/**
 * Should use it since [File.canWrite] is unreliable on Android 10.
 * Read [this issue](https://github.com/anggrayudi/SimpleStorage/issues/24#issuecomment-830000378)
 */
fun File.isWritableDk(context: Context) = canWrite() && (isFile || isExternalStorageManagerDk(context))

/**
 * For Q+, this will check with `Environment.isExternalStorageManager`.
 * To gain full access, the app should request permission `Manifest.permission.MANAGE_EXTERNAL_STORAGE` via
 * setting of `android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
 *
 * @return TRUE if we have full disk access.
 */
fun File.isExternalStorageManagerDk(context: Context) : Boolean {
	return (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && Environment.isExternalStorageManager(this))
		|| (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && path.startsWith(DkStorage.externalStoragePath) && SimpleStorage.hasStoragePermission(context))
		|| (context.writableDirsDk.any { path.startsWith(it.path) })
}

/**
 * Create file and if exists, increment file name.
 */
@WorkerThread
@JvmOverloads
fun File.makeFileDk(
	context: Context,
	name: String,
	mimeType: String? = MimeType.UNKNOWN,
	mode: FileCreationMode = FileCreationMode.CREATE_NEW
): File? {

	if (!isDirectory || !isWritableDk(context)) {
		return null
	}

	val cleanName = name.removeForbiddenCharsFromFilenameDk().trim('/')
	val subFolder = cleanName.substringBeforeLast('/', "")
	val parent = if (subFolder.isEmpty()) {
		this
	}
	else {
		File(this, subFolder).apply { mkdirs() }
	}

	val filename = cleanName.substringAfterLast('/')
	val extensionByName = cleanName.substringAfterLast('.', "")
	val extension = if (extensionByName.isNotEmpty() && (mimeType == null || mimeType == MimeType.UNKNOWN || mimeType == MimeType.BINARY_FILE)) {
		extensionByName
	}
	else {
		MimeType.getExtensionFromMimeTypeOrFileName(mimeType, cleanName)
	}
	val baseFileName = filename.removeSuffix(".$extension")
	val fullFileName = "$baseFileName.$extension".trimEnd('.')

	if (mode != FileCreationMode.CREATE_NEW) {
		val existingFile = File(parent, fullFileName)
		if (existingFile.exists()) {
			return existingFile.let { eFile ->
				when {
					mode == FileCreationMode.REPLACE -> eFile.takeIf {
						it.deleteDk()
						it.createFileDk()
					}
					eFile.isFile -> eFile
					else -> null
				}
			}
		}
	}

	return try {
		File(parent, incrementFileNameDk(fullFileName)).let { if (it.createNewFile()) it else null }
	}
	catch (e: IOException) {
		null
	}
}

/**
 * @param name can input `MyFolder` or `MyFolder/SubFolder`
 */
@WorkerThread
@JvmOverloads
fun File.makeDirDk(context: Context, name: String, mode: FileCreationMode = FileCreationMode.CREATE_NEW): File? {
	if (!isDirectory || !isWritableDk(context)) {
		return null
	}

	val directorySequence = name.removeForbiddenCharsFromFilenameDk().splitToDirsDk().toMutableList()
	val folderNameLevel1 = directorySequence.removeFirstOrNull() ?: return null
	val incrementedFolderNameLevel1 = if (mode == FileCreationMode.CREATE_NEW) {
		incrementFileNameDk(folderNameLevel1)
	}
	else {
		folderNameLevel1
	}

	val folderLevel1 = childDk(incrementedFolderNameLevel1)
	if (mode == FileCreationMode.REPLACE) {
		folderLevel1.deleteDk(true)
	}
	folderLevel1.mkdir()

	val folder = folderLevel1.let {
		if (directorySequence.isEmpty()) {
			it
		}
		else {
			it.childDk(directorySequence.joinToString("/")).apply {
				mkdirs()
			}
		}
	}

	return if (folder.isDirectory) folder else null
}

fun File.toDocumentFileDk(context: Context) = if (canRead()) DkExternalStorage.findByFile(context, this) else null

fun File.deleteEmptyDirsDk(context: Context): Boolean {
	return if (isDirectory && isWritableDk(context)) {
		walkFileTreeAndDeleteEmptyDirsDk().reversed().forEach { it.delete() }
		true
	}
	else false
}

private fun File.walkFileTreeAndDeleteEmptyDirsDk(): List<File> {
	val fileTree = mutableListOf<File>()
	listFiles()?.forEach {
		// Deletion is only success if the folder is empty
		if (it.isDirectory && !it.delete()) {
			fileTree.add(it)
			fileTree.addAll(it.walkFileTreeAndDeleteEmptyDirsDk())
		}
	}
	return fileTree
}

/**
 * Delete file or directory.
 * For case of directory, different with [File.delete], this also delete child files too.
 *
 * This uses [File.deleteRecursively] internally for case of directory deletion.
 *
 * @param childrenOnly TRUE if only delete children. Otherwise delete this directory too.
 *
 * @return TRUE if `delete succeed` OR `target is not exist`.
 */
@JvmOverloads
fun File.deleteDk(childrenOnly: Boolean = false) = try {
	if (isDirectory) {
		val success = deleteRecursively()
		if (childrenOnly) {
			mkdir()
			isDirectory && list().isNullOrEmpty()
		}
		else {
			success || !exists()
		}
	}
	else {
		delete() || !exists()
	}
}
catch (e: Exception) {
	DkLogcats.error(this, "Could not tryDelete", e)
	false
}

/**
 * Increment (Generate) given file name to avoid duplicate with current files under this directory.
 *
 * NOTE: It doesn't work if below both conditions occur:
 * - We are outside of [Context.getExternalFilesDir] and
 * - Don't have full disk access for Android 10+.
 */
fun File.incrementFileNameDk(filename: String): String {
	if (childDk(filename).exists()) {
		val baseName = filename.substringBeforeLast('.')
		val ext = filename.substringAfterLast('.', "")
		val prefix = "$baseName ("
		var lastFileCount = list().orEmpty()
			.filter {
				it.startsWith(prefix) &&
					(DkStorage.FILE_NAME_DUPLICATION_REGEX_WITH_EXTENSION.matches(it)
					|| DkStorage.FILE_NAME_DUPLICATION_REGEX_WITHOUT_EXTENSION.matches(it))
			}
			.maxOfOrNull {
				it.substringAfterLast('(', "")
					.substringBefore(')', "")
					.toIntOrNull() ?: 0
			} ?: 0

		return "$baseName (${++lastFileCount}).$ext".trimEnd('.')
	}

	return filename
}

@JvmOverloads
fun File.moveToDk(
	context: Context,
	targetFolder: String,
	newFileNameInTarget: String? = null,
	conflictResolution: FileCallback.ConflictResolution = FileCallback.ConflictResolution.CREATE_NEW
): File? {
	return moveToDk(context, File(targetFolder), newFileNameInTarget, conflictResolution)
}

/**
 * @param conflictResolution using [FileCallback.ConflictResolution.SKIP] will return `null`
 */
@JvmOverloads
fun File.moveToDk(
	context: Context,
	targetFolder: File,
	newFileNameInTarget: String? = null,
	conflictResolution: FileCallback.ConflictResolution = FileCallback.ConflictResolution.CREATE_NEW
): File? {
	// Check src file
	if (!exists() || !isWritableDk(context)) {
		return null
	}
	// Make dst file
	targetFolder.mkdirs()
	if (!targetFolder.isDirectory || !targetFolder.isWritableDk(context)) {
		return null
	}
	//
	val filename = newFileNameInTarget ?: name
	var dst = targetFolder.childDk(filename)
	if (parent == targetFolder.path) {
		return if (renameTo(dst)) dst else null
	}
	if (!inSameMountPointWithDk(context, targetFolder)) {
		return null
	}
	if (dst.exists()) {
		when (conflictResolution) {
			FileCallback.ConflictResolution.SKIP -> return null
			FileCallback.ConflictResolution.REPLACE -> if (!dst.deleteDk()) return null
			FileCallback.ConflictResolution.CREATE_NEW -> {
				dst = targetFolder.childDk(targetFolder.incrementFileNameDk(filename))
			}
		}
	}
	// Return true for files and empty folders
	if (renameTo(dst)) {
		return dst
	}
	if (isDirectory) {
		dst.mkdirs()

		walkFileTreeForMoveDk(path, dst.path)
		deleteRecursively()

		return dst.takeIf { !it.emptyDk() }
	}

	return null
}

private fun File.walkFileTreeForMoveDk(srcPath: String, destFolderPath: String) {
	listFiles()?.forEach {
		val targetFile = File(destFolderPath, it.path.substringAfter(srcPath).trim('/'))
		if (it.isFile) {
			it.renameTo(targetFile)
		}
		else {
			targetFile.mkdirs()
			it.walkFileTreeForMoveDk(srcPath, destFolderPath)
		}
	}
}

fun String.url2filenameDk(url: String) : String {
	return try {
		URLDecoder.decode(url, "UTF-8").substringAfterLast('/')
	}
	catch (e: Exception) {
		url
	}
}
