package tool.compet.storage

import android.webkit.MimeTypeMap

/**
 * See [mime type list](https://www.freeformatter.com/mime-types-list.html)
 */
object MimeType {
	const val UNKNOWN = "*/*"
	const val BINARY_FILE = "application/octet-stream"
	const val IMAGE = "image/*"
	const val AUDIO = "audio/*"
	const val VIDEO = "video/*"
	const val TEXT = "text/*"
	const val FONT = "font/*"
	const val APPLICATION = "application/*"
	const val CHEMICAL = "chemical/*"
	const val MODEL = "model/*"

	/**
	 * - Given `name` = `thesong` AND `mimeType` = `video/mp4`, then return `thesong.mp4`
	 * - Given `name` = `thesong` AND `mimeType` = `null`, then return `thesong`
	 * - Given `name` = `thesong.mp4` AND `mimeType` = `video/mp4`, then return `thesong.mp4`
	 *
	 * @param name Can have file extension or not.
	 * @param mimeType When NULL, given `name` will be returned.
	 */
	fun getFullFileName(name: String, mimeType: String?): String {
		// Prior to API 29, MimeType.BINARY_FILE has no file extension
		return getExtensionFromMimeType(mimeType).let { ext ->
			if (ext.isEmpty() || name.endsWith(".$ext"))
				name
			else
				"$name.$ext".trimEnd('.')
		}
	}

	/**
	 * @see getExtensionFromMimeType
	 */
	fun getExtensionFromMimeTypeOrFileName(mimeType: String?, filename: String): String {
		return if (mimeType == null || mimeType == UNKNOWN)
			filename.substringAfterLast('.', "")
		else
			getExtensionFromMimeType(mimeType)
	}

	/**
	 * Some mime types return no file extension on older API levels.
	 * This function adds compatibility accross API levels.
	 *
	 * @see getExtensionFromMimeTypeOrFileName
	 */
	fun getExtensionFromMimeType(mimeType: String?): String {
		return mimeType?.let {
			if (it == BINARY_FILE)
				"bin"
			else
				MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
		}.orEmpty()
	}

	/**
	 * Some file types return no mime type on older API levels.
	 * This function adds compatibility accross API levels.
	 */
	fun getMimeTypeFromExtension(fileExtension: String): String {
		return if (fileExtension == "bin")
			BINARY_FILE
		else
			MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension) ?: UNKNOWN
	}
}