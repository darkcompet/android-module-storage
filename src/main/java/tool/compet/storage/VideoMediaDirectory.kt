package tool.compet.storage

import android.os.Environment

/**
 * Defines media directories which associates with category `video`.
 */
enum class VideoMediaDirectory(val dirName: String) {
	MOVIES(Environment.DIRECTORY_MOVIES),
	DCIM(Environment.DIRECTORY_DCIM)
}