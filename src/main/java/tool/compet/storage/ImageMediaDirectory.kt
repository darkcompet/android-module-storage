package tool.compet.storage

import android.os.Environment

/**
 * Defines media directories which associates with category `image`.
 */
enum class ImageMediaDirectory(val dirName: String) {
	PICTURES(Environment.DIRECTORY_PICTURES),
	DCIM(Environment.DIRECTORY_DCIM)
}