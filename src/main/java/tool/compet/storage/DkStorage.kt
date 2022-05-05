package tool.compet.storage

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import tool.compet.core.DkLogcats
import tool.compet.storage.extension.*
import java.io.File
import java.io.IOException

/**
 * This is combination of InternalStorage and ExternalStorage.
 * We can consider it as overral one which can handle features for storages.
 *
 * Terminology:
 * - FullPath := [AbsolutePath | SimplePath] = [RootPath + BasePath]
 * - BasePath := [RelativePath + FileName]
 *
 * Eg1,. if we have a AbsolutePath `/storage/emulated/0/Downloads/FileMan/database.json`, then:
 * - StorageId is `emulated/0`,
 * - RootPath is `storage/emulated/0`,
 * - BasePath is `Downloads/FileMan/database.json`,
 * - RelativePath is `Downloads/FileMan`,
 * - FileName is `database.json`.
 *
 * Eg2,. if we have a SimplePath `9016-4EF6/Downloads/FileMan/database.json``, then:
 * - StorageId is `9016-4EF6`,
 * - RootPath is `9016-4EF6`,
 * - BasePath is `Downloads/FileMan/database.json`,
 * - RelativePath is `Downloads/FileMan`,
 * - FileName is `database.json`.
 *
 * About external storage:
 * - [Context.getFilesDir]: Locate in internal storage, private for the app.
 * - [Context.getExternalFilesDir]: Locate in external storage, private for the app, but other apps can access if
 * they provide read/write permission on external storage.
 * For device which has multiple users, space returned from this function is of current user. That is, the app
 * cannot access to storage of other users unless they login to device.
 *
 * Handle with files under [Environment.getExternalStorageDirectory] but should have permissions to access.
 * Before access, we need ask user below permissions:
 * - [Manifest.permission.READ_EXTERNAL_STORAGE] for read.
 * - [Manifest.permission.WRITE_EXTERNAL_STORAGE] for write.
 * - [Manifest.permission.MANAGE_EXTERNAL_STORAGE] for full access.
 */
object DkStorage {
	const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

	// File picker for each API version gives the following URIs:
	// - API 26 - 27 => content://com.android.providers.downloads.documents/document/22
	// - API 28 - 29 => content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fscreenshot.jpeg
	// - API 30+     => content://com.android.providers.downloads.documents/document/msf%3A42
	const val DOWNLOADS_FOLDER_AUTHORITY = "com.android.providers.downloads.documents"

	const val MEDIA_FOLDER_AUTHORITY = "com.android.providers.media.documents"

	// Only available on API 26 to 29.
	const val DOWNLOADS_TREE_URI = "content://$DOWNLOADS_FOLDER_AUTHORITY/tree/downloads"

	val FILE_NAME_DUPLICATION_REGEX_WITH_EXTENSION = Regex("(.*?) \\(\\d+\\)\\.[a-zA-Z0-9]+")

	val FILE_NAME_DUPLICATION_REGEX_WITHOUT_EXTENSION = Regex("(.*?) \\(\\d+\\)")

	/**
	 * Get absolute path for external storage.
	 *
	 * Note: use path from here to get children file directly is NOT recommend from api Q.
	 *
	 * For root path of internal storage, see [Context.dataRootDirDk].
	 */
	val externalStoragePath: String
		@Suppress("DEPRECATION")
		get() = Environment.getExternalStorageDirectory().absolutePath

	/**
	 * List all app-specific external storage IDs on this device.
	 * The first index is one of primary external storage.
	 * The next index is non-primary, for eg,. secondary external storage (SD card), cloud storage,...
	 *
	 * Prior to API 28, retrieving storage ID for SD card only applicable if
	 * URI permission is granted for read & write access.
	 *
	 * @return List of [StorageId].
	 */
	fun listExternalStorageIds(context: Context): List<String> {
		// First, find external-storages which the app can associate with it
		val storageIds = ContextCompat.getExternalFilesDirs(context, null).filterNotNull().map {
			val externalStoragePath = it.path

			@Suppress("DEPRECATION")
			if (externalStoragePath.startsWith(Environment.getExternalStorageDirectory().absolutePath)) {
				// Path -> /storage/emulated/0/Android/data/tool.compet.storage.sample/files
				StorageId.PRIMARY
			}
			else {
				// Path -> /storage/131D-261A/Android/data/tool.compet.storage.sample/files
				externalStoragePath.substringAfter("/storage/").substringBefore('/')
			}
		}

		// Before 19 we don't have permission to do next, so stop here.
		// From 28, we cannot retrieve SD card storage ids more.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			return storageIds
		}

		// Next, we find from UriPermissions
		val persistedStorageIds = context.contentResolver.persistedUriPermissions
			.filter { it.isReadPermission && it.isWritePermission && it.uri.isExternalStorageDocumentDk }
			.mapNotNull {
				it.uri.path?.run { this.substringBefore(':').substringAfterLast('/') }
			}

		// Distinct merge with persisted storages
		return storageIds.toMutableList().let { list ->
			list.addAll(persistedStorageIds)
			list.distinct()
		}
	}

	/**
	 * In API 29+, `/storage/emulated/0` may not be granted for URI permission,
	 * but all directories under `/storage/emulated/0/Music` are granted and accessible.
	 *
	 * For example, given `/storage/emulated/0/Music/Metal`, then return `/storage/emulated/0/Music`
	 *
	 * @param fullPath construct it using [getAbsolutePathDk] or [getSimplePathDk]
	 * @return NULL if accessible root path is not found in [ContentResolver.getPersistedUriPermissions],
	 * 	or the folder does not exist.
	 */
	@JvmOverloads
	fun getAccessibleRootDocumentFile(
		context: Context,
		fullPath: String,
		requireWritable: Boolean = false,
		useRawFile: Boolean = true
	): DocumentFile? {
		if (useRawFile && fullPath.startsWith('/')) {
			val rootFile = File(fullPath).getRootRawFileDk(context, requireWritable)
			if (rootFile != null) {
				return DocumentFile.fromFile(rootFile)
			}
		}
		val storageId = getStorageId(context, fullPath)
		if (storageId == StorageId.DATA) {
			return DocumentFile.fromFile(context.dataRootDirDk)
		}
		if (storageId.isNotEmpty()) {
			val cleanBasePath = getBasePath(context, fullPath)

			@Suppress("DEPRECATION")
			val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				context.contentResolver.persistedUriPermissions
					// For instance, content://com.android.externalstorage.documents/tree/primary%3AMusic
					.filter { it.isReadPermission && it.isWritePermission && it.uri.isTreeDocumentFileDk }
					.forEach {
						if (fullPath.startsWith(downloadPath) && it.uri.isDownloadsDocumentDk) {
							return DocumentFile.fromTreeUri(context, Uri.parse(DOWNLOADS_TREE_URI))
						}

						// For eg,. /tree/primary:Music
						val uriPath = it.uri.path

						if (uriPath != null && it.uri.isExternalStorageDocumentDk) {
							val currentStorageId = uriPath.substringBefore(':').substringAfterLast('/')
							val currentRootFolder = uriPath.substringAfter(':', "")
							val found = currentStorageId == storageId
								&& (currentRootFolder.isEmpty() || cleanBasePath.hasParentDk(currentRootFolder))

							if (found) {
								return DocumentFile.fromTreeUri(context, it.uri)
							}
						}
					}
			}
		}
		return null
	}

	/**
	 * @param simpleOrFullPath Can omit filename, just provide path from root to a folder inside the storage.
	 * 		For SD card can be full path `storage/6881-2249/Music` or simple path `6881-2249:Music`.
	 * 		For primary storage can be `/storage/emulated/0/Music` or simple path `primary:Music`.
	 *
	 * @return Given `storage/6881-2249/Music/MyLove.mp3`, then return `Music/MyLove.mp3`.
	 * May return empty string if it is a root path of the storage.
	 */
	fun getBasePath(context: Context, simpleOrFullPath: String): String {
		val basePath = if (simpleOrFullPath.startsWith('/')) {
			val dataDir = context.dataRootDirDk.path
			when {
				simpleOrFullPath.startsWith(externalStoragePath) -> {
					simpleOrFullPath.substringAfter(externalStoragePath)
				}
				simpleOrFullPath.startsWith(dataDir) -> {
					simpleOrFullPath.substringAfter(dataDir)
				}
				else -> {
					simpleOrFullPath.substringAfter("/storage/", "").substringAfter('/', "")
				}
			}
		}
		else {
			simpleOrFullPath.substringAfter(':', "")
		}

		return basePath.trim('/').removeForbiddenCharsFromFilenameDk()
	}

	/**
	 * Give a path from root of a file, this will calculate [StorageId] from given fullPath.
	 *
	 * @param fullPath Can omit filename, just provide path from root to a folder inside the storage.
	 * 		For SD card, can be `storage/6881-2249/Music` or simple path `6881-2249:Music`.
	 * 		For primary storage, can be `/storage/emulated/0/Music` or simple path `primary:Music`.
	 *
	 * @return Normally value in [StorageId], but some cases maybe unknown storage id.
	 */
	fun getStorageId(context: Context, fullPath: String): String {
		return if (fullPath.startsWith('/')) {
			when {
				fullPath.startsWith(externalStoragePath) -> StorageId.PRIMARY
				fullPath.startsWith(context.dataRootDirDk.path) -> StorageId.DATA
				else -> fullPath.substringAfter("/storage/", "").substringBefore('/')
			}
		}
		else {
			fullPath.substringBefore(':', "").substringAfterLast('/')
		}
	}

	/**
	 * Check whether we have right permission for access on given storage.
	 *
	 * @param storageId Value in [StorageId].
	 */
	fun isAccessGranted(context: Context, storageId: String): Boolean {
		return (storageId == StorageId.DATA)
			|| (storageId == StorageId.PRIMARY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
			|| (getRootDir(context, storageId, true) != null)
	}

	/**
	 * - For internal storage, it is free to handle since the app does not need any permission on it.
	 * This function will get root dir which is parent file of children as: files, cache,....
	 *
	 * - For external storage, if you target to api < 30, just get root file as raw file.
	 * For api >= 30, since raw file `java.io.File` was deprecated, BUT if you have full access
	 * [Manifest.permission.MANAGE_EXTERNAL_STORAGE] on given storage, lets use raw file when gain root file.
	 *
	 * @param storageId Value in [StorageId].
	 * @param requireWritable If TRUE then Only take if the root file is writable (NULL if not writable).
	 * @param useRawFile For api < 30, should use this.
	 * 	For api >= 30, and you have full-access permission to given storage,
	 * 	pass flag as `true` for better perfomance.
	 */
	@JvmOverloads
	fun getRootDir(
		context: Context,
		storageId: String, // For eg,. StorageId.DATA
		requireWritable: Boolean = false,
		useRawFile: Boolean = true
	): DocumentFile? {
		// InternalStorage
		if (storageId == StorageId.DATA) {
			return DocumentFile.fromFile(context.dataRootDirDk)
		}
		// ExternalStorage
		val documentFile = if (useRawFile) {
			getRootRawDir(context, storageId, requireWritable)?.let { DocumentFile.fromFile(it) }
				?: DocumentFile.fromTreeUri(context, createDocumentUri(storageId))
		}
		else {
			DocumentFile.fromTreeUri(context, createDocumentUri(storageId))
		}

		return documentFile?.takeIf {
			it.canRead() && (requireWritable && it.isWritableDk(context) || !requireWritable)
		}
	}

	/**
	 * Gain root file as raw file `java.io.File`.
	 *
	 * Note that, from api 30+, raw file was deprecated, but if you have full runtime-access
	 * granted [Manifest.permission.MANAGE_EXTERNAL_STORAGE], you can use this function.
	 *
	 * @param storageId Value in [StorageId].
	 *
	 * @return NULL if you have no full storage access.
	 */
	@JvmOverloads
	fun getRootRawDir(
		context: Context,
		storageId: String, // For eg,. StorageId.DATA
		requireWritable: Boolean = false
	) : File? {
		@Suppress("DEPRECATION")
		val rootDir = when (storageId) {
			StorageId.PRIMARY -> Environment.getExternalStorageDirectory()
			StorageId.DATA -> context.dataRootDirDk
			else -> File("/storage/$storageId")
		}

		return rootDir.takeIf {
			rootDir.canRead() && (!requireWritable || (requireWritable && rootDir.isWritableDk(context)))
		}
	}

	/**
	 * When you have right permission on given storage, just go ahead.
	 *
	 * @param storageId Value in [StorageId].
	 * @param basePath File path without root path, for eg,. `/storage/emulated/0/Music/Pop`
	 * 		should be written as `Music/Pop`
	 *
	 * @return NULL if you don't have storage permission.
	 */
	@JvmOverloads
	fun createFile(
		context: Context,
		storageId: String, // For eg,. StorageId.PRIMARY,
		basePath: String, // Path from rootPath, for eg,. `Downloads/FileMan/collection/hotgirl.png`
		mimeType: String = MimeType.UNKNOWN, // For eg,. `text/*`
		useRawFile: Boolean = true
	): DocumentFile? {
		// Use raw file for better perfomance
		val goWithRawFile = (storageId == StorageId.DATA)
			|| (useRawFile && storageId == StorageId.PRIMARY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)

		if (goWithRawFile) {
			val file = File(makeAbsolutePath(context, storageId, basePath))

			file.parentFile?.mkdirs()

			return if (doCreateNewFile(file)) DocumentFile.fromFile(file) else null
		}

		return try {
			// Go with DocumentFile instead of raw file
			val directory = mkdirsParentDirectory(context, storageId, basePath, useRawFile)
			val filename = basePath.trimEnd('/').substringAfterLast('/').removeForbiddenCharsFromFilenameDk()

			if (filename.isEmpty()) null else directory?.makeFileDk(context, filename, mimeType)
		}
		catch (e: Exception) {
			DkLogcats.error(this, "Could not create file `$basePath` at storage `$storageId`", e)
			null
		}
	}

	private fun doCreateNewFile(file: File): Boolean {
		return try {
			file.isFile && file.length() == 0L || file.createNewFile()
		}
		catch (e: IOException) {
			DkLogcats.error(this, "Could not create file `${file.name}`", e)
			false
		}
	}

	/**
	 * Create folders. You should do this process in background.
	 *
	 * @param fullPath Construct it via [getAbsolutePathDk] or [getSimplePathDk]
	 * @param requireWritable The folder should have write access, otherwise return NULL.
	 * @param useRawFile Indicate this function should try with raw file instead of query database...
	 *
	 * @return NULL if you have no storage permission.
	 */
	@JvmOverloads
	fun mkdirs(
		context: Context,
		fullPath: String,
		requireWritable: Boolean = true,
		useRawFile: Boolean = true
	): DocumentFile? {
		// Prepare lambda for make directory
		val tryCreateWithRawFile: () -> DocumentFile? = {
			val directory = File(fullPath.removeForbiddenCharsFromFilenameDk()).apply {
				this.mkdirs()
			}
			val okDir = directory.isDirectory
				&& directory.canRead()
				&& (!requireWritable || (requireWritable && directory.isWritableDk(context)))

			if (okDir) DocumentFile.fromFile(directory) else null
		}

		// Consider `java.io.File` for better performance
		if (useRawFile && (fullPath.startsWith('/') || fullPath.startsWith(context.dataRootDirDk.path))) {
			tryCreateWithRawFile()?.let {
				return it
			}
		}

		var currentDirectory = getAccessibleRootDocumentFile(context, fullPath, requireWritable, useRawFile)
			?: return null

		if (currentDirectory.uri.isRawFileDk) {
			return tryCreateWithRawFile()
		}

		val resolver = context.contentResolver
		getBasePath(context, fullPath).splitToDirsDk().forEach {
			try {
				val directory = currentDirectory.quickFindTreeFileDk(context, resolver, it)
				currentDirectory = when {
					directory == null -> {
						currentDirectory.createDirectory(it) ?: return null
					}
					directory.isDirectory && directory.canRead() -> {
						directory
					}
					else -> {
						return null
					}
				}
			}
			catch (e: Exception) {
				return null
			}
		}

		return currentDirectory.takeIfWritableDk(context, requireWritable)
	}

	/**
	 * Optimized performance for creating multiple folders.
	 * The result may contains `null` elements for unsuccessful creation.
	 * For instance, if parameter `fullPaths` contains 5 elements and successful `mkdirs()` is 3,
	 * then return 3 non-null elements + 2 null elements.
	 *
	 * @param fullPaths either simple path or absolute path.
	 * 		Tips: use [getAbsolutePathDk] or [getSimplePathDk] to construct full path.
	 * @param requireWritable the folder should have write access, otherwise return `null`
	 */
	@JvmOverloads
	fun mkdirs(
		context: Context,
		fullPaths: List<String>,
		requireWritable: Boolean = true,
		useRawFile: Boolean = true
	): Array<DocumentFile?> {

		val dataDir = context.dataRootDirDk.path
		val results = arrayOfNulls<DocumentFile>(fullPaths.size)
		val cleanedFullPaths = fullPaths.map {
			makeAbsolutePath(context, it)
		}

		for (path in findUniqueDeepestSubFolders(context, cleanedFullPaths)) {
			// use java.io.File for faster performance
			val folder = File(path).apply { mkdirs() }
			if (useRawFile && folder.isDirectory && folder.canRead() || path.startsWith(dataDir)) {
				cleanedFullPaths.forEachIndexed { index, s ->
					if (path.hasParentDk(s)) {
						results[index] =
							DocumentFile.fromFile(File(s.splitToDirsDk().joinToString(prefix = "/", separator = "/")))
					}
				}
			}
			else {
				var currentDirectory = getAccessibleRootDocumentFile(context, path, requireWritable, useRawFile)
					?: continue
				val isRawFile = currentDirectory.uri.isRawFileDk
				val resolver = context.contentResolver

				getBasePath(context, path).splitToDirsDk().forEach {
					try {
						val directory = if (isRawFile) currentDirectory.quickFindRawFileDk(it)
						else currentDirectory.quickFindTreeFileDk(
							context,
							resolver,
							it
						)
						if (directory == null) {
							currentDirectory = currentDirectory.createDirectory(it) ?: return@forEach
							val fullPath = currentDirectory.getAbsolutePathDk(context)

							cleanedFullPaths.forEachIndexed { index, s ->
								if (fullPath == s) {
									results[index] = currentDirectory
								}
							}
						}
						else if (directory.isDirectory && directory.canRead()) {
							currentDirectory = directory
							val fullPath = directory.getAbsolutePathDk(context)
							cleanedFullPaths.forEachIndexed { index, s ->
								if (fullPath == s) {
									results[index] = directory
								}
							}
						}
					}
					catch (e: Throwable) {
						return@forEach
					}
				}
			}
		}

		results.indices.forEach { index ->
			results[index] = results[index]?.takeIfWritableDk(context, requireWritable)
		}

		return results
	}

	fun makeSimplePath(storageId: String, basePath: String): String {
		val cleanBasePath = basePath.removeForbiddenCharsFromFilenameDk().trim('/')
		return "$storageId:$cleanBasePath"
	}

	fun makeSimplePath(context: Context, absolutePath: String): String {
		return makeSimplePath(getStorageId(context, absolutePath), getBasePath(context, absolutePath))
	}

	/**
	 * @param storageId Value in [StorageId].
	 * @param basePath File path from rootPath, for eg,. `Downloads/FileMan/collection/hotgirl.png`.
	 */
	fun makeAbsolutePath(context: Context, storageId: String, basePath: String): String {
		val cleanBasePath = basePath.removeForbiddenCharsFromFilenameDk()

		@Suppress("DEPRECATION")
		val rootPath = when (storageId) {
			StorageId.PRIMARY -> Environment.getExternalStorageDirectory().absolutePath
			StorageId.DATA -> context.dataRootDirDk.path
			else -> "/storage/$storageId"
		}

		return "$rootPath/$cleanBasePath".trimEnd('/')
	}

	/**
	 * @param simplePath Build from [getSimplePathDk] for eg,. `Downloads/FileMan/collection/hotgirl.png`.
	 */
	fun makeAbsolutePath(context: Context, simplePath: String): String {
		val path = simplePath.trimEnd('/')
		return if (path.startsWith('/')) {
			path.removeForbiddenCharsFromFilenameDk()
		}
		else {
			makeAbsolutePath(context, getStorageId(context, path), getBasePath(context, path))
		}
	}

	private fun mkdirsParentDirectory(
		context: Context,
		storageId: String,
		basePath: String,
		useRawFile: Boolean
	): DocumentFile? {
		val parentPath = basePath.splitToDirsDk().let { it.getOrNull(it.size - 2) }

		return if (parentPath != null) {
			mkdirs(context, makeAbsolutePath(context, storageId, parentPath), useRawFile)
		}
		else {
			getRootDir(context, storageId, true, useRawFile)
		}
	}

	@JvmOverloads
	fun reCreateFile(
		context: Context,
		storageId: String, // For eg,. StorageId.PRIMARY
		basePath: String,
		mimeType: String = MimeType.UNKNOWN,
		useRawFile: Boolean = true
	): DocumentFile? {
		// Delete target file
		val file = File(makeAbsolutePath(context, storageId, basePath))
		file.delete()
		file.parentFile?.mkdirs()

		if ((useRawFile || storageId == StorageId.DATA) && doCreateNewFile(file)) {
			return DocumentFile.fromFile(file)
		}

		val directory = mkdirsParentDirectory(context, storageId, basePath, useRawFile)
		val filename = file.name

		if (filename.isNullOrEmpty()) {
			return null
		}

		return directory?.run {
			findChildDk(context, filename)?.delete()
			makeFileDk(context, filename, mimeType)
		}
	}

	internal fun exploreFile(
		context: Context,
		storageId: String,
		basePath: String,
		documentType: DocumentFileType,
		requireWritable: Boolean,
		useRawFile: Boolean
	): DocumentFile? {

		val rawFile = File(makeAbsolutePath(context, storageId, basePath))
		val goWithRawFile = (useRawFile || storageId == StorageId.DATA)
			&& rawFile.canRead()
			&& (!requireWritable || rawFile.isWritableDk(context))

		if (goWithRawFile) {
			return if (documentType == DocumentFileType.ANY
				|| (documentType == DocumentFileType.FILE && rawFile.isFile)
				|| (documentType == DocumentFileType.DIRECTORY && rawFile.isDirectory)
			) {
				// Find with DocumentFile
				DocumentFile.fromFile(rawFile)
			}
			else {
				null
			}
		}

		val file = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			getRootDir(context, storageId, requireWritable, useRawFile)?.findChildDk(context, basePath)
				?: return null
		}
		else {
			val directorySequence = basePath.splitToDirsDk().toMutableList()
			val parentTree = ArrayList<String>(directorySequence.size)
			var grantedFile: DocumentFile? = null
			// Find granted file tree.
			// For example, /storage/emulated/0/Music may not granted, but /storage/emulated/0/Music/Pop is granted by user.
			while (directorySequence.isNotEmpty()) {
				parentTree.add(directorySequence.removeFirst())
				val folderTree = parentTree.joinToString(separator = "/")
				try {
					grantedFile = DocumentFile.fromTreeUri(context, createDocumentUri(storageId, folderTree))
					if (grantedFile?.canRead() == true) break
				}
				catch (e: SecurityException) {
					// ignore
				}
			}
			if (grantedFile == null || directorySequence.isEmpty()) {
				grantedFile
			}
			else {
				val fileTree = directorySequence.joinToString(prefix = "/", separator = "/")
				DocumentFile.fromTreeUri(context, Uri.parse(grantedFile.uri.toString() + Uri.encode(fileTree)))
			}
		}

		return file?.takeIf {
			it.canRead() && (documentType == DocumentFileType.ANY
				|| (documentType == DocumentFileType.FILE && it.isFile)
				|| (documentType == DocumentFileType.DIRECTORY && it.isDirectory))
		}
	}

	/**
	 * Given the following `folderFullPaths`:
	 * - `/storage/9016-4EF8/Downloads`
	 * - `/storage/9016-4EF8/Downloads/Archive`
	 * - `/storage/9016-4EF8/Video`
	 * - `/storage/9016-4EF8/Music`
	 * - `/storage/9016-4EF8/Music/Favorites/Pop`
	 * - `/storage/emulated/0/Music`
	 * - `primary:Alarm/Morning`
	 * - `primary:Alarm`
	 *
	 * Then return:
	 * - `/storage/9016-4EF8/Downloads/Archive`
	 * - `/storage/9016-4EF8/Music/Favorites/Pop`
	 * - `/storage/9016-4EF8/Video`
	 * - `/storage/emulated/0/Music`
	 * - `/storage/emulated/0/Alarm/Morning`
	 */
	private fun findUniqueDeepestSubFolders(context: Context, folderFullPaths: Collection<String>): List<String> {
		val paths = folderFullPaths.map { makeAbsolutePath(context, it) }.distinct()
		val results = ArrayList(paths)
		paths.forEach { path ->
			paths.find { it != path && path.hasParentDk(it) }?.let {
				results.remove(it)
			}
		}
		return results
	}

	/**
	 * Given the following `folderFullPaths`:
	 * - `/storage/9016-4EF8/Downloads`
	 * - `/storage/9016-4EF8/Downloads/Archive`
	 * - `/storage/9016-4EF8/Video`
	 * - `/storage/9016-4EF8/Music`
	 * - `/storage/9016-4EF8/Music/Favorites/Pop`
	 * - `/storage/emulated/0/Music`
	 * - `primary:Alarm/Morning`
	 * - `primary:Alarm`
	 *
	 * Then return:
	 * - `/storage/9016-4EF8/Downloads`
	 * - `/storage/9016-4EF8/Music`
	 * - `/storage/9016-4EF8/Video`
	 * - `/storage/emulated/0/Music`
	 * - `/storage/emulated/0/Alarm`
	 */
	fun findUniqueParents(context: Context, folderFullPaths: Collection<String>): List<String> {
		val paths = folderFullPaths.map { makeAbsolutePath(context, it) }.distinct()
		val results = ArrayList<String>(paths.size)
		paths.forEach { path ->
			if (!paths.any { it != path && path.hasParentDk(it) }) {
				results.add(path)
			}
		}
		return results
	}

	@WorkerThread
	fun findInaccessibleStorageLocations(context: Context, fullPaths: List<String>): List<String> {
		return if (SimpleStorage.hasStoragePermission(context)) {
			val uniqueParents = findUniqueParents(context, fullPaths)
			val inaccessibleStorageLocations = ArrayList<String>(uniqueParents.size)
			// if folder not found, try create it and check whether is successful
			mkdirs(context, uniqueParents).forEachIndexed { index, folder ->
				if (folder == null) {
					inaccessibleStorageLocations.add(uniqueParents[index])
				}
			}
			inaccessibleStorageLocations
		}
		else {
			fullPaths.map { makeAbsolutePath(context, it) }
		}
	}

	/**
	 * @param storageId If in SD card, it should be integers like `6881-2249`.
	 * 		Otherwise, if in external storage it will be [PRIMARY].
	 * @param basePath If in Downloads folder of SD card, it will be `Downloads/MyMovie.mp4`.
	 * 		If in external storage it will be `Downloads/MyMovie.mp4` as well.
	 */
	@JvmOverloads
	fun findBySimplePath(
		context: Context,
		storageId: String = StorageId.PRIMARY,
		basePath: String = "",
		documentType: DocumentFileType = DocumentFileType.ANY,
		requireWritable: Boolean = false,
		useRawFile: Boolean = true
	): DocumentFile? {

		if (storageId == StorageId.DATA) {
			return DocumentFile.fromFile(context.dataRootDirDk.childDk(basePath))
		}
		if (basePath.isEmpty()) {
			return getRootDir(context, storageId, requireWritable, useRawFile)
		}

		val file = exploreFile(context, storageId, basePath, documentType, requireWritable, useRawFile)

		if (file == null && storageId == StorageId.PRIMARY && basePath.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
			val downloads = DocumentFile.fromTreeUri(context, Uri.parse(DOWNLOADS_TREE_URI))?.takeIf { it.canRead() }
				?: return null

			return downloads.findChildDk(context, basePath.substringAfter('/', ""))?.takeIf {
				documentType == DocumentFileType.ANY
					|| documentType == DocumentFileType.FILE && it.isFile
					|| documentType == DocumentFileType.DIRECTORY && it.isDirectory
			}
		}

		return file
	}

	@JvmOverloads
	fun createDocumentUri(storageId: String, basePath: String = ""): Uri {
		return Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/tree/" + Uri.encode("$storageId:$basePath"))
	}

	/**
	 * Check if storage has URI permission for read and write access.
	 *
	 * Persisted URIs revoked whenever the related folders deleted. Hence, you need to request URI permission again even though the folder
	 * recreated by user. However, you should not worry about this on API 28 and lower, because URI permission always granted for root path
	 * and rooth path itself can't be deleted.
	 */
	@JvmOverloads
	fun isStorageUriPermissionGranted(context: Context, storageId: String, basePath: String = ""): Boolean {
		val rootUri = createDocumentUri(storageId, basePath)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			return context.contentResolver.persistedUriPermissions.any {
				it.isReadPermission && it.isWritePermission && it.uri == rootUri
			}
		}
		return true
	}

	fun isDownloadsUriPermissionGranted(context: Context): Boolean {
		val uri = Uri.parse(DOWNLOADS_TREE_URI)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			return context.contentResolver.persistedUriPermissions.any {
				it.isReadPermission && it.isWritePermission && it.uri == uri
			}
		}
		return true
	}

	/**
	 * Check file/dir exists or not via given `fullPath`.
	 */
	fun exists(context: Context, fullPath: String) : Boolean {
		return DkExternalStorage.findByFullPath(context, fullPath)?.exists() == true
	}

	/**
	 * Delete a file/dir via given `fullPath`.
	 */
	fun delete(context: Context, fullPath: String) : Boolean {
		return DkExternalStorage.findByFullPath(context, fullPath)?.deleteDk(context) == true
	}
}