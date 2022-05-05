package tool.compet.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import tool.compet.core.createFileDk
import tool.compet.storage.extension.*
import java.io.File

/**
 * This is a part of external storage, is sharable between users and apps.
 * It is considered as one from: `Environment.getExternalStoragePublicDirectory()`.
 *
 * Normally, we don't need any permission for read/write to this part, but some version of Android is exception,
 * so we should provide in manifest file below permissions for better back-compatibility:
 * - READ_EXTERNAL_STORAGE
 * - WRITE_EXTERNAL_STORAGE
 *
 * Refer:
 * - https://developer.android.com/training/data-storage
 * - https://developer.android.com/training/data-storage/shared/media
 * - https://github.com/android/storage-samples/tree/main/MediaStore
 */
object DkMediaStore { // singleton class
	@SuppressLint("InlinedApi")
	val MEDIA_VOLUMN_NAME = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
		MediaStore.VOLUME_EXTERNAL
	else
		MediaStore.VOLUME_EXTERNAL_PRIMARY

	/**
	 * Create file at `Downloads` directory.
	 */
	fun createDownloadFile(
		context: Context,
		fileDescription: FileDescription,
		creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
	): MediaFile? {
		return createFile(context, MediaType.DOWNLOADS, PublicDirectory.DOWNLOADS.dirName, fileDescription, creationMode)
	}

	/**
	 * Create file at given image-directory (for eg,. `Images`, `DCIM`,...).
	 */
	fun createImageFile(
		context: Context,
		fileDescription: FileDescription,
		atDirectory: ImageMediaDirectory = ImageMediaDirectory.PICTURES,
		creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
	): MediaFile? {
		return createFile(context, MediaType.IMAGE, atDirectory.dirName, fileDescription, creationMode)
	}

	/**
	 * Create file at given audio-directory (for eg,. `Music`, `Ringtones`, `Alarms`, `Notifications`, `Podcasts`,...).
	 */
	fun createAudioFile(
		context: Context,
		fileDescription: FileDescription,
		atDirectory: AudioMediaDirectory = AudioMediaDirectory.MUSIC,
		creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
	): MediaFile? {
		return createFile(context, MediaType.AUDIO, atDirectory.dirName, fileDescription, creationMode)
	}

	/**
	 * Create file at given video-directory (for eg,. `Movies`, `DCIM`,...).
	 */
	fun createVideoFile(
		context: Context,
		fileDescription: FileDescription,
		atDirectory: VideoMediaDirectory = VideoMediaDirectory.MOVIES,
		creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
	): MediaFile? {
		return createFile(context, MediaType.VIDEO, atDirectory.dirName, fileDescription, creationMode)
	}

	/**
	 * From given a full path, this will create a file at target public directory.
	 *
	 * @param fullPath For eg,. `storage/6881-2249/Downloads/FileMan/Backup/database.json`.
	 */
	@RequiresApi(Build.VERSION_CODES.KITKAT)
	fun createFile(
		context: Context,
		fullPath: String,
		fileDescription: FileDescription,
		creationMode: FileCreationMode = FileCreationMode.CREATE_NEW
	): MediaFile? {
		// For eg,. basePath = `Downloads/FileMan/Backup/database.json`
		val basePath = DkStorage.getBasePath(context, fullPath).trim('/')
		if (basePath.isEmpty()) {
			return null
		}
		// For eg,. basePath = `Downloads/FileMan/Backup/database.json` -> directoryName = `Downloads`
		val directoryName = basePath.substringBefore('/')
		val mediaType = when (directoryName) {
			Environment.DIRECTORY_DOWNLOADS -> MediaType.DOWNLOADS
			in ImageMediaDirectory.values().map { it.dirName } -> MediaType.IMAGE
			in AudioMediaDirectory.values().map { it.dirName } -> MediaType.AUDIO
			in VideoMediaDirectory.values().map { it.dirName } -> MediaType.VIDEO
			else -> return null
		}
		// For eg,. `FileMan/Backup/database.json` ???? pls correct this
		val subDir = basePath.substringAfter('/', "")

		fileDescription.subDir = "$subDir/${fileDescription.subDir}".trim('/')

		return createFile(context, mediaType, directoryName, fileDescription, creationMode)
	}

	/**
	 * Create a file with given `fileDescription`.
	 *
	 * Note: before api Q (29), we create a file via `java.io.file`, but after Q, we must
	 * create via database.
	 *
	 * For eg,. create a file with below info will make a file: `Downloads/FileMan/MyLove/hotgirl.png`
	 * - mediaType = `MediaType.IMAGES`,
	 * - atDirectory = `PublicDirectory.DOWNLOADS`,
	 * - fileDescription = {name: hotgirl.png, subDir: FileMan/MyLove, mime: Unknown}
	 *
	 * @param directoryName This is public directory name, see values in [PublicDirectory].
	 */
	private fun createFile(
		context: Context,
		mediaType: MediaType,
		directoryName: String,
		fileDescription: FileDescription,
		creationMode: FileCreationMode
	): MediaFile? {
		// Before Q, we just handle with raw file `java.io.file`.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			val publicDirectory = Environment.getExternalStoragePublicDirectory(directoryName)
			if (publicDirectory.canModifyDk(context)) {
				// Create parent directory if not exist
				val filename = fileDescription.fullName
				var newFile = File("$publicDirectory/${fileDescription.subDir}", filename)
				val parentFile = newFile.parentFile ?: return null

				parentFile.mkdirs()

				// For mode `CREATE_NEW`, prepare new file if the file exists
				if (creationMode == FileCreationMode.CREATE_NEW && newFile.exists()) {
					newFile = parentFile.childDk(parentFile.incrementFileNameDk(filename))
				}
				// For mode `REPLACE`, try delete current file
				if (creationMode == FileCreationMode.REPLACE) {
					newFile.deleteDk()
				}
				// Finally, try create new file if not exist
				if (newFile.createFileDk()) {
					return if (newFile.canRead()) MediaFile(context, newFile) else null
				}
			}

			return null
		}

		// From Q, we create file via database.
		val dateCreated = System.currentTimeMillis()
		val relativePath = "${directoryName}/${fileDescription.subDir}".trim('/')

		val contentValues = ContentValues().apply {
			this.put(MediaStore.MediaColumns.DISPLAY_NAME, fileDescription.name)
			this.put(MediaStore.MediaColumns.MIME_TYPE, fileDescription.mimeType)
			this.put(MediaStore.MediaColumns.DATE_ADDED, dateCreated)
			this.put(MediaStore.MediaColumns.DATE_MODIFIED, dateCreated)
			this.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, context.packageName)
			if (relativePath.isNotBlank()) {
				this.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
			}
		}

		var existingMediaFile = findFileByBasePath(context, mediaType, "$relativePath/${fileDescription.name}")
		when {
			existingMediaFile?.isEmpty == true -> {
				return existingMediaFile
			}
			existingMediaFile?.exists == true -> {
				if (creationMode == FileCreationMode.REUSE) {
					return existingMediaFile
				}
				if (creationMode == FileCreationMode.REPLACE) {
					existingMediaFile.delete()

					return MediaFile(
						context,
						context.contentResolver.insert(mediaType.writeUri!!, contentValues) ?: return null
					)
				}

				// We use this file duplicate handler because it is better than the system's.
				// This handler also fixes Android 10's media file duplicate handler. Here's how to reproduce:
				// 1) Use Android 10. Let's say there's a file named Pictures/profile.png with media ID 25.
				// 2) Create an image file with ContentValues using the same name (profile) & mime type (image/png), under Pictures directory too.
				// 3) A new media file is created into the file database with ID 26, but it uses the old file,
				// instead of creating a new file named profile (1).png. On Android 11, it will be profile (1).png.
				val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(fileDescription.mimeType)
					?: fileDescription.name.substringAfterLast('.', "")
				val baseName = fileDescription.name.substringBeforeLast('.')
				val prefix = "$baseName ("
				val lastFile = findFilesWhichContainsName(context, mediaType, baseName)
					.filter { relativePath.isBlank() || relativePath == it.relativePath.removeSuffix("/") }
					.mapNotNull { it.name }
					.filter {
						it.startsWith(prefix) && (DkStorage.FILE_NAME_DUPLICATION_REGEX_WITH_EXTENSION.matches(it)
							|| DkStorage.FILE_NAME_DUPLICATION_REGEX_WITHOUT_EXTENSION.matches(it))
					}
					.maxOfOrNull { it }
					.orEmpty()

				var count = lastFile.substringAfterLast('(', "")
					.substringBefore(')', "")
					.toIntOrNull() ?: 0

				// Check if file exists, but has zero length
				existingMediaFile = findFileByFileName(context, mediaType, "$baseName ($count).$ext".trimEnd('.'))
				if (existingMediaFile?.openInputStream()?.use { it.available() == 0 } == true) {
					return existingMediaFile
				}

				contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName (${++count}).$ext".trimEnd('.'))

				return MediaFile(
					context,
					context.contentResolver.insert(mediaType.writeUri!!, contentValues) ?: return null
				)
			}
			else -> {
				return MediaFile(
					context,
					context.contentResolver.insert(mediaType.writeUri!!, contentValues) ?: return null
				)
			}
		}
	}

	/**
	 * Should run at worker thread.
	 *
	 * - For api < Q (29), this ignores given `mediaType` and returns file under `Downloads` public directory.
	 * - From api Q, we use `MediaStore` query from database to get MediaFile under given directory (`mediaType`).
	 *
	 * @param fileName Name of file directly under directory, for eg,. `database.json`.
	 */
	fun findFileByFileName(context: Context, mediaType: MediaType, fileName: String): MediaFile? {
		// Before Q, we just handle with raw file
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION") //todo how to continue?
			val list = mediaType.directories.map { directory ->
				DocumentFile.fromFile(directory)
					.searchDk(true, fileName = fileName)
					.map { MediaFile(context, File(it.uri.path!!)) }
			}.flatten()

			return if (list.isNotEmpty()) list[0] else null

//			// For now, we consider Downloads folder only...
//			val dirName = Environment.DIRECTORY_DOWNLOADS
//			@Suppress("DEPRECATION")
//			return File(Environment.getExternalStoragePublicDirectory(dirName), fileName).let { raw ->
//				if (raw.isFile && raw.canRead())
//					MediaFile(context, raw)
//				else
//					null
//			}
		}

		// From Q, we query to get MediaFile under target directory (given `mediaType`).
		return context.contentResolver.query(
			mediaType.readUri ?: return null,
			arrayOf(BaseColumns._ID),
			"${MediaStore.MediaColumns.DISPLAY_NAME} = ?", // where's clause
			arrayOf(fileName), // where's param bindings
			null
		)?.use {
			getFileByCursor(context, mediaType, it)
		}
	}

	/**
	 * Should run at worker thread.
	 *
	 * For api < Q (29), this just `get` target file from the directory. But for api Q+, this
	 * will `query` database to get info of the target file.
	 *
	 * @param basePath It is `RelativePath + FileName`, for eg,. `Downloads/FileMan/Backup/database.json`.
	 *
	 * @return NULL if basePath does not contain relativePath or the media is not found.
	 */
	@RequiresApi(Build.VERSION_CODES.KITKAT)
	fun findFileByBasePath(context: Context, mediaType: MediaType, basePath: String): MediaFile? {
		val cleanBasePath = basePath.removeForbiddenCharsFromFilenameDk().trim('/')

		// Before Q
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return File(Environment.getExternalStorageDirectory(), cleanBasePath).let { file ->
				if (file.isFile && file.canRead())
					MediaFile(context, file)
				else
					null
			}
		}

		// From Q, we use contentResolver to query the file
		val relativePath = cleanBasePath.substringBeforeLast('/', "")
		if (relativePath.isEmpty()) {
			return null
		}

		val fileName = cleanBasePath.substringAfterLast('/')
		val whereClause = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
		val whereParamBindings = arrayOf(fileName, "$relativePath/")

		return context.contentResolver
			.query(
				mediaType.readUri ?: return null,
				arrayOf(BaseColumns._ID),
				whereClause,
				whereParamBindings,
				null
			)
			?.use { getFileByCursor(context, mediaType, it) }
	}

	/**
	 * Should run at worker thread.
	 *
	 * Before Q (29), this just `get` target file from the directory. But from Q, this
	 * will `query` database to get info of the target file.
	 *
	 * @param publicDirectory This is relative path for target files. That is, NOT only child files
	 * 		at directly publicDirectory, but also sub directories under publicDirectory are targeted for search.
	 *
	 * @see MediaStore.MediaColumns.RELATIVE_PATH
	 */
	fun findFilesByRelativePath(context: Context, publicDirectory: PublicDirectory) =
		findFilesByRelativePath(context, publicDirectory.dirName)

	/**
	 * Should run at worker thread.
	 *
	 * For api < Q (29), this just `get` target file from the directory. But for api Q+, this
	 * will `query` database to get info of the target file.
	 *
	 * @param relativePath Is basePath without fileName, for detail see terminology at [DkExternalStorage].
	 * 		For query, it is `MediaStore.MediaColumns.RELATIVE_PATH`.
	 */
	fun findFilesByRelativePath(context: Context, relativePath: String): List<MediaFile> {
		val cleanRelativePath = relativePath.trim('/')

		// Before Q
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return DocumentFile.fromFile(File(Environment.getExternalStorageDirectory(), cleanRelativePath))
				.searchDk(true, DocumentFileType.FILE)
				.map { MediaFile(context, File(it.uri.path!!)) }
		}

		// From Q
		val mediaType = relativePath2mediaType(cleanRelativePath) ?: return emptyList()
		val relativePathWithSlashSuffix = relativePath.trimEnd('/') + '/'
		val whereClause = "${MediaStore.MediaColumns.RELATIVE_PATH} IN(?, ?)"
		val whereBindingParams = arrayOf(cleanRelativePath, relativePathWithSlashSuffix)

		return context.contentResolver.query(
			mediaType.readUri ?: return emptyList(), // uri
			arrayOf(BaseColumns._ID), // selections
			whereClause,
			whereBindingParams,
			null // sort order
		)?.use {
			getFilesByCursor(context, mediaType, it)
		}.orEmpty()
	}

	/**
	 * Should run at worker thread.
	 *
	 * @param relativePath Value from `MediaStore.MediaColumns.RELATIVE_PATH`.
	 */
	fun findFileByRelativePath(context: Context, relativePath: String, fileName: String): MediaFile? {
		val cleanRelativePath = relativePath.trim('/')

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return DocumentFile.fromFile(File(Environment.getExternalStorageDirectory(), cleanRelativePath))
				.searchDk(true, DocumentFileType.FILE, fileName = fileName)
				.map { MediaFile(context, File(it.uri.path!!)) }
				.firstOrNull()
		}

		// Query for Q+
		val mediaType = relativePath2mediaType(cleanRelativePath) ?: return null
		val relativePathWithSlashSuffix = relativePath.trimEnd('/') + '/'
		val whereClause = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" +
			" AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN(?, ?)"
		val whereParamBindings = arrayOf(fileName, relativePathWithSlashSuffix, cleanRelativePath)

		return context.contentResolver.query(
			mediaType.readUri ?: return null, // uri
			arrayOf(BaseColumns._ID), // selections
			whereClause,
			whereParamBindings,
			null // sort order
		)?.use {
			getFileByCursor(context, mediaType, it)
		}
	}

	/**
	 * Should run at worker thread.
	 *
	 * Search list of file which has fileName contains given substring `containsName`.
	 */
	fun findFilesWhichContainsName(context: Context, mediaType: MediaType, containsName: String): List<MediaFile> {
		// Before Q
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return mediaType.directories.map { directory ->
				DocumentFile.fromFile(directory)
					.searchDk(true, regex = Regex("^.*$containsName.*\$"), mimeTypes = arrayOf(mediaType.mimeType))
					.map { MediaFile(context, File(it.uri.path!!)) }
			}.flatten()
		}

		// From Q
		return context.contentResolver.query(
			mediaType.readUri ?: return emptyList(), // uri
			arrayOf(BaseColumns._ID), // selections
			"${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%?%'", // where's clause
			arrayOf(containsName), // where's paramBindings
			null
		)?.use {
			getFilesByCursor(context, mediaType, it)
		}.orEmpty()
	}

	/**
	 * Should run at worker thread.
	 *
	 * @param mimeType Value in [MimeType].
	 */
	fun fileFilesByMimeType(context: Context, mediaType: MediaType, mimeType: String): List<MediaFile> {
		// Before Q
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return mediaType.directories.map { directory ->
				DocumentFile.fromFile(directory)
					.searchDk(true, DocumentFileType.FILE, arrayOf(mimeType))
					.map { MediaFile(context, File(it.uri.path!!)) }
			}.flatten()
		}

		// From Q
		return context.contentResolver.query(
			mediaType.readUri ?: return emptyList(), // uri
			arrayOf(BaseColumns._ID), // selections
			"${MediaStore.MediaColumns.MIME_TYPE} = ?", // where's clause
			arrayOf(mimeType), // where's paramBindings
			null // sort order
		)?.use {
			getFilesByCursor(context, mediaType, it)
		}.orEmpty()
	}

	/**
	 * Should run at worker thread.
	 */
	fun findFilesByMediaType(context: Context, mediaType: MediaType): List<MediaFile> {
		// Before Q
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			@Suppress("DEPRECATION")
			return mediaType.directories.map { directory ->
				DocumentFile.fromFile(directory)
					.searchDk(true, mimeTypes = arrayOf(mediaType.mimeType))
					.map { MediaFile(context, File(it.uri.path!!)) }
			}.flatten()
		}

		// From Q
		return context.contentResolver.query(
			mediaType.readUri ?: return emptyList(),
			arrayOf(BaseColumns._ID),
			null,
			null,
			null
		)?.use {
			getFilesByCursor(context, mediaType, it)
		}.orEmpty()
	}

	private fun getFilesByCursor(context: Context, mediaType: MediaType, cursor: Cursor): List<MediaFile> {
		if (cursor.moveToFirst()) {
			val columnId = cursor.getColumnIndex(BaseColumns._ID)
			val mediaFiles = ArrayList<MediaFile>(cursor.count)

			do {
				val mediaId = cursor.getString(columnId)

				getFileByMediaId(context, mediaType, mediaId)?.let { mediaFile ->
					mediaFiles.add(mediaFile)
				}
			}
			while (cursor.moveToNext())

			return mediaFiles
		}
		return emptyList()
	}

	private fun getFileByCursor(context: Context, mediaType: MediaType, cursor: Cursor): MediaFile? {
		return if (cursor.moveToFirst()) {
			val mediaId = cursor.getString(cursor.getColumnIndex(BaseColumns._ID))

			getFileByMediaId(context, mediaType, mediaId)
		}
		else null
	}

	private fun getFileByMediaId(context: Context, mediaType: MediaType, id: String): MediaFile? {
		return mediaType.writeUri?.let { writeUri ->
			MediaFile(
				context,
				writeUri.buildUpon().appendPath(id).build()
			)
		}
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun relativePath2mediaType(cleanRelativePath: String) = when (cleanRelativePath) {
		Environment.DIRECTORY_DCIM,
		Environment.DIRECTORY_PICTURES -> {
			MediaType.IMAGE
		}
		Environment.DIRECTORY_MOVIES,
		Environment.DIRECTORY_DCIM -> {
			MediaType.VIDEO
		}
		Environment.DIRECTORY_MUSIC,
		Environment.DIRECTORY_PODCASTS,
		Environment.DIRECTORY_RINGTONES,
		Environment.DIRECTORY_ALARMS,
		Environment.DIRECTORY_NOTIFICATIONS -> {
			MediaType.AUDIO
		}
		Environment.DIRECTORY_DOWNLOADS -> {
			MediaType.DOWNLOADS
		}
		else -> null
	}
}
