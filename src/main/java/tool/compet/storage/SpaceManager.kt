package tool.compet.storage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import tool.compet.core.DkLogcats
import tool.compet.storage.extension.isRawFileDk
import java.io.File

internal object SpaceManager {
	/**
	 * @return Remain byte count of free-space inside given storage. For case of failure, return -1 instead.
	 */
	@SuppressLint("ObsoleteSdkInt")
	fun getFreeSpace(context: Context, storageId: String): Long {
		return try {
			val file = getStoragePresentFile(context, storageId) ?: return -1L
			when {
				file.uri.isRawFileDk -> {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
						StatFs(file.uri.path!!).availableBytes
					}
					else {
						-1L
					}
				}
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
					context.contentResolver.openFileDescriptor(file.uri, "r")?.use {
						val stats = Os.fstatvfs(it.fileDescriptor)
						stats.f_bavail * stats.f_frsize
					} ?: -1L
				}
				else -> {
					-1L
				}
			}
		}
		catch (e: Throwable) {
			DkLogcats.warning(this, "Could not get free space of storage `$storageId`", e)
			-1L
		}
	}

	/**
	 * @param storageId Value at [StorageId].
	 *
	 * @return Used byte count of given storage. For case of failure, return -1 instead.
	 */
	@SuppressLint("ObsoleteSdkInt")
	fun getUsedSpace(context: Context, storageId: String): Long {
		return try {
			val file = getStoragePresentFile(context, storageId) ?: return -1L
			when {
				file.uri.isRawFileDk -> {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
						StatFs(file.uri.path!!).run { totalBytes - availableBytes }
					}
					else {
						-1L
					}
				}
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
					context.contentResolver.openFileDescriptor(file.uri, "r")?.use {
						val stats = Os.fstatvfs(it.fileDescriptor)
						stats.f_blocks * stats.f_frsize - stats.f_bavail * stats.f_frsize
					} ?: -1L
				}
				else -> {
					-1L
				}
			}
		}
		catch (e: Throwable) {
			DkLogcats.warning(this, "Could not get used space of storage `$storageId`", e)
			-1L
		}
	}

	/**
	 * @param storageId Value at [StorageId].
	 *
	 * @return Total byte count of given storage. For case of failure, return -1 instead.
	 */
	@SuppressLint("ObsoleteSdkInt")
	fun getStorageCapacity(context: Context, storageId: String): Long {
		return try {
			val file = getStoragePresentFile(context, storageId) ?: return -1L

			when {
				file.uri.isRawFileDk -> {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
						StatFs(file.uri.path!!).totalBytes
					}
					else {
						-1L
					}
				}
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
					context.contentResolver.openFileDescriptor(file.uri, "r")?.use {
						val stats = Os.fstatvfs(it.fileDescriptor)
						stats.f_blocks * stats.f_frsize
					} ?: -1L
				}
				else -> {
					-1L
				}
			}
		}
		catch (e: Throwable) {
			DkLogcats.warning(this, "Could not get capacity of storage `$storageId`", e)
			-1L
		}
	}

	private fun getStoragePresentFile(context: Context, storageId: String): DocumentFile? {
		return when (storageId) {
			StorageId.PRIMARY -> {
				// Use app private directory, so no permissions required
				val directory = context.getExternalFilesDir(null) ?: return null
				DocumentFile.fromFile(directory)
			}
			StorageId.DATA -> {
				DocumentFile.fromFile(context.dataRootDirDk)
			}
			else -> {
				// /storage/131D-261A/Android/data/tool.compet.storage.sample/files
				val folder = File("/storage/$storageId/Android/data/${context.packageName}/files")
				folder.mkdirs()

				if (folder.canRead()) {
					DocumentFile.fromFile(folder)
				}
				else {
					DkStorage.getAccessibleRootDocumentFile(context, folder.absolutePath, useRawFile = false)
				}
			}
		}
	}
}