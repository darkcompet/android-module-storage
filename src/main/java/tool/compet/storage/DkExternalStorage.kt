/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import tool.compet.storage.extension.*
import java.io.File

/**
 * External Storage has 2 types: built-in storage, and pluggable storage.
 * Before do some action, should check `isReadable` or `isWritable` first.
 *
 * More terminology, pls see [DkStorage].
 */
object DkExternalStorage {
	/**
	 * Check external storage is available to write.
	 */
	val isWritable: Boolean
		get() {
			val state = Environment.getExternalStorageState()
			return state == Environment.MEDIA_MOUNTED
		}

	/**
	 * Check external storage is available for read.
	 */
	val isReadable: Boolean
		get() {
			val state = Environment.getExternalStorageState()
			return state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
		}

	/**
	 * Get `DocumentFile` under given `PublicDirectory` from given filePath.
	 *
	 * @param filePathFromDirectory Path from given `PublicDirectory`, for eg,. `FileMan/Backup` inside `Downloads/`.
	 * 	If empty value was given, `PublicDirectory` will be returned.
	 *
	 * @return NULL if directory does not exist, or you have no permission on this directory.
	 */
	@JvmOverloads
	fun getFileByPath(
		context: Context, // We need context to handle with User's storage
		publicDirectory: PublicDirectory, // Target file is located under `PublicDirectory`
		filePathFromDirectory: String = "", // Default "" will return the `PublicDirectory`
		requiresWritable: Boolean = false, // Option to get writable file
		useRawFile: Boolean = true // Allow get raw file
	): DocumentFile? {
		// [Get target directory]
		// From Q+, the path returned from this method is no longer directly accessible to apps.
		// But we can still get the path of public directory, so just go ahead.
		@Suppress("DEPRECATION")
		var atDirectory = Environment.getExternalStoragePublicDirectory(publicDirectory.dirName)
		if (filePathFromDirectory.isNotEmpty()) {
			atDirectory = File("$atDirectory/$filePathFromDirectory".trimEnd('/'))
		}
		if (atDirectory.checkRequirementsDk(context, requiresWritable, useRawFile)) {
			return DocumentFile.fromFile(atDirectory)
		}

		// When requirements was not passed, we
		val folder = if (publicDirectory == PublicDirectory.DOWNLOADS) {
			// Root path will be                   => content://com.android.providers.downloads.documents/tree/downloads/document/downloads
			// Get file/listFiles() will be        => content://com.android.providers.downloads.documents/tree/downloads/document/msf%3A268
			// When creating files with makeFile() => content://com.android.providers.downloads.documents/tree/downloads/document/147
			// When creating directory  "IKO5"     => content://com.android.providers.downloads.documents/tree/downloads/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FIKO5
			// Seems that `com.android.providers.downloads.documents` no longer available on SAF's folder selector on API 30+.
			// You can create directory with authority `com.android.providers.downloads.documents` on API 29,
			// but unfortunately cannot create file in the directory. So creating directory with this authority is useless.
			// Hence, convert it to writable URI with DocumentFile.toWritableDownloadsDocumentFile()
			val downloadFolder = DocumentFile.fromTreeUri(context, Uri.parse(DkStorage.DOWNLOADS_TREE_URI))
			if (downloadFolder?.canRead() == true) { // compare since left-side is nullable
				downloadFolder.findChildDk(context, filePathFromDirectory, requiresWritable)
			}
			else {
				findByFullPath(context, atDirectory.absolutePath, useRawFile = false)
			}
		}
		else {
			findByFullPath(context, atDirectory.absolutePath, useRawFile = false)
		}

		return folder?.takeIf {
			it.canRead() && (requiresWritable && folder.isWritableDk(context) || ! requiresWritable)
		}
	}

	/**
	 * Get DocumentFile presents for given raw file.
	 * This function allows us to read and write files in external storage, regardless of API levels.
	 *
	 * Since api Q (Android 10), ONLY app's directory that is accessible by [File],
	 * for eg,. `/storage/emulated/0/Android/data/tool.compet.storage/files`
	 * -> To continue using [File], we need to request full storage access
	 * via [SimpleStorage.requestFullStorageAccess].
	 *
	 * @param file Raw file.
	 * @param useRawFile `true` if you want to consider faster performance with [File]
	 *
	 * @return `TreeDocumentFile` if `useRawFile` is false, or if the given [File]
	 * can be read with URI permission only, otherwise return `RawDocumentFile`
	 */
	@JvmOverloads
	fun findByFile(
		context: Context,
		file: File,
		documentType: DocumentFileType = DocumentFileType.ANY,
		requireWritable: Boolean = false,
		useRawFile: Boolean = true
	): DocumentFile? {
		if (file.checkRequirementsDk(context, requireWritable, useRawFile)) {
			val invalidDocumentType = (documentType == DocumentFileType.FILE && !file.isFile)
				|| (documentType == DocumentFileType.DIRECTORY && !file.isDirectory)

			return if (invalidDocumentType) null else DocumentFile.fromFile(file)
		}

		val basePath = file.getBasePathDk(context).removeForbiddenCharsFromFilenameDk().trim('/')
		val storageId = file.getStorageIdDk(context)

		val result = DkStorage.exploreFile(
			context,
			storageId,
			basePath,
			documentType,
			requireWritable,
			useRawFile
		)

		return result ?: DkStorage.findBySimplePath(
			context,
			storageId,
			basePath,
			documentType,
			requireWritable,
			useRawFile
		)
	}


	/**
	 * Example of `fullPath`:
	 * - For file in external storage => `/storage/emulated/0/Downloads/MyMovie.mp4`.
	 * - For file in SD card => `/storage/9016-4EF8/Downloads/MyMovie.mp4`, or
	 * 	you can input simple path like this `9016-4EF8:Downloads/MyMovie.mp4`.
	 * 	You can input `9016-4EF8:` or `/storage/9016-4EF8` for SD card's root path.
	 *
	 * @param fullPath Full path of the file.
	 *
	 * @see DocumentFile.getAbsolutePathDk
	 */
	@JvmOverloads
	fun findByFullPath(
		context: Context,
		fullPath: String,
		documentType: DocumentFileType = DocumentFileType.ANY,
		requireWritable: Boolean = false,
		useRawFile: Boolean = true
	): DocumentFile? {
		// Absolute path
		if (fullPath.startsWith('/')) {
			return findByFile(
				context,
				File(fullPath),
				documentType,
				requireWritable,
				useRawFile
			)
		}
		// Simple path
		return DkStorage.findBySimplePath(
			context,
			fullPath.substringBefore(':'),
			fullPath.substringAfter(':'),
			documentType,
			requireWritable,
			useRawFile
		)
	}

	/**
	 * Create file under `Downloads` directory with media fallback.
	 */
	fun createDownloadFileWithMediaStoreFallback(context: Context, file: FileDescription): Uri? {
		val publicFolder = getFileByPath(context, PublicDirectory.DOWNLOADS, requiresWritable = true)

		return if (publicFolder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			DkMediaStore.createDownloadFile(context, file)?.uri
		}
		else {
			publicFolder?.makeFileDk(context, file.name, file.mimeType)?.uri
		}
	}

	/**
	 * Create file under `Pictures` directory with media fallback.
	 */
	fun createPictureFileWithMediaStoreFallback(context: Context, file: FileDescription): Uri? {
		val publicFolder = getFileByPath(context, PublicDirectory.PICTURES, requiresWritable = true)

		return if (publicFolder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			DkMediaStore.createImageFile(context, file)?.uri
		}
		else {
			publicFolder?.makeFileDk(context, file.name, file.mimeType)?.uri
		}
	}
}