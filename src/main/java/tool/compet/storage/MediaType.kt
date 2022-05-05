package tool.compet.storage

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * For [DkMediaStore].
 *
 * From Q, we use `MediaStore.*.Media` to handle with file.
 * Before Q, we use directly `Environment.DIRECTORY_*` so can handle with more directories than
 * below media types.
 */
enum class MediaType(val readUri: Uri?, val writeUri: Uri?) {
	IMAGE(
		MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Images.Media.getContentUri(DkMediaStore.MEDIA_VOLUMN_NAME)
	),
	AUDIO(
		MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Audio.Media.getContentUri(DkMediaStore.MEDIA_VOLUMN_NAME)
	),
	VIDEO(
		MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Video.Media.getContentUri(DkMediaStore.MEDIA_VOLUMN_NAME)
	),
	DOWNLOADS(
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
		else MediaStore.Downloads.EXTERNAL_CONTENT_URI,

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
		else MediaStore.Downloads.getContentUri(DkMediaStore.MEDIA_VOLUMN_NAME)
	);

	/**
	 * NOTE: Only use this for api before Q (29), if not you cannot access to children from result directories.
	 *
	 * Get all directories associated with this media type.
	 * For eg,. `MediaType.AUDIO` will associate with directories: `Music`, `Audio`, `Podcast`,...
	 */
	@Deprecated("Only use this before Q (29)", ReplaceWith(""))
	val directories: List<File>
		@Suppress("DEPRECATION")
		get() = when (this) {
			IMAGE -> ImageMediaDirectory.values().map { Environment.getExternalStoragePublicDirectory(it.dirName) }
			AUDIO -> AudioMediaDirectory.values().map { Environment.getExternalStoragePublicDirectory(it.dirName) }
			VIDEO -> VideoMediaDirectory.values().map { Environment.getExternalStoragePublicDirectory(it.dirName) }
			DOWNLOADS -> listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
		}

	val mimeType: String
		get() = when (this) {
			IMAGE -> MimeType.IMAGE
			AUDIO -> MimeType.AUDIO
			VIDEO -> MimeType.VIDEO
			else -> MimeType.UNKNOWN
		}
}