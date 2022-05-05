package tool.compet.storage

import android.net.Uri
import tool.compet.storage.extension.getStorageIdDk

enum class StorageType {
	/**
	 * Equals to primary storage.
	 *
	 * @see [DkStorage.externalStoragePath]
	 */
	EXTERNAL,
	DATA,
	SD_CARD,
	UNKNOWN;

	fun isExpected(actualStorageType: StorageType) = (this == UNKNOWN) || (this == actualStorageType)

	companion object {
		/**
		 * @param storageId get it from [Uri.getStorageIdDk]
		 */
		@JvmStatic
		fun fromStorageId(storageId: String) = when {
			storageId == StorageId.PRIMARY -> EXTERNAL
			storageId == StorageId.DATA -> DATA
			storageId.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")) -> SD_CARD
			else -> UNKNOWN
		}
	}
}