/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * By default, this uses [DocumentFile] instead of [File] for richer features support.
 */
object DkInternalStorage {
	/**
	 * @return NULL if target file does not exist. Otherwise the file.
	 */
	fun getFile(context: Context, basePath: String): DocumentFile? {
		val file = File(DkStorage.makeAbsolutePath(context, StorageId.DATA, basePath))
		return if (file.exists()) DocumentFile.fromFile(file) else null
	}

	/**
	 * Get or Create new file if not exist.
	 *
	 * @param basePath For eg,. "logs/today/abc.txt"
	 */
	fun createFile(context: Context, basePath: String) : DocumentFile? {
		return DkStorage.createFile(context, StorageId.DATA, basePath)
	}

	/**
	 * Create file under `cache` directory under root file.
	 */
//	fun createCacheFile(context: Context, basePath: String) : DocumentFile? {
//		context.noBackupFilesDir
//		context.cacheDir
//		context.getExternalFilesDir()
//		context.codeCacheDir
//		context.dataDir
//		context.filesDir
//		context.externalCacheDir
//		context.obbDir
//		return null
//	}

	fun getRootDir(context: Context) : DocumentFile? {
		return DkStorage.getRootDir(context, StorageId.DATA)
	}

	fun getRootRawDir(context: Context) : File? {
		return DkStorage.getRootRawDir(context, StorageId.DATA)
	}

	/**
	 * @return Byte count.
	 */
	fun getFreeSpace(context: Context) : Long {
		return SpaceManager.getFreeSpace(context, StorageId.DATA)
	}

	/**
	 * @return Byte count.
	 */
	fun getUsedSpace(context: Context) : Long {
		return SpaceManager.getUsedSpace(context, StorageId.DATA)
	}
}