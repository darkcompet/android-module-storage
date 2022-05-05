package tool.compet.storage

/**
 * Device may contain a lot of type of storage, for eg,. sdcard, internal, external,...
 * This provides pre-defined storage in `String` type, so unknown storage can be adapt with this.
 */
object StorageId {
	/**
	 * It presents for `internal storage` for app-specific directory.
	 * For eg,. files under `Context.getFilesDir()` or `Context.getDataDir()`.
	 * Files under this directory can be ONLY accessed by the app. That is,
	 * other users or apps cannot access to the files.
	 *
	 * It is not really a storage ID, and can't be used in file tree URI.
	 */
	const val DATA = "data"

	/**
	 * It presents for `primary` external storage which is built-in memory in a device.
	 * We can consider it is like with internal storage, but other apps and users can access it.
	 *
	 * About `secondary` external storage, they can be: SD card, removable plugged card,...
	 */
	const val PRIMARY = "primary"
}