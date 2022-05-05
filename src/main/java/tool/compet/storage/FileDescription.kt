package tool.compet.storage

data class FileDescription @JvmOverloads constructor(
	/**
	 * Name of file (can contain extension), for eg,. `database.json`.
	 */
	var name: String,

	/**
	 * Path from public directory to the file.
	 * For eg,. if the file has relativePath as `Downloads/FileMan/Backup/database.json`,
	 * then subDir will be `FileMan/Backup/`.
	 */
	var subDir: String = "",

	/**
	 * Which type of the file (image, video, text,...).
	 *
	 * Note: when this is `MimeType.UNKNOWN`, `name` will be considered as `fullName`.
	 */
	var mimeType: String = MimeType.UNKNOWN
) {
	/**
	 * Calculate file name with its extension, for eg,. `thesong.mp4`.
	 */
	val fullName: String
		get() = MimeType.getFullFileName(name, mimeType)
}