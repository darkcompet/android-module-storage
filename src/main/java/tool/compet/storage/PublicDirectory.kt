package tool.compet.storage

import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi

/**
 * List of directory name in media storage.
 *
 * We don't package this to media or other packages
 * since maybe other places still use it or something change in future.
 */
enum class PublicDirectory(val dirName: String) {
	/**
	 */
	DOWNLOADS(Environment.DIRECTORY_DOWNLOADS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	MUSIC(Environment.DIRECTORY_MUSIC),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	PODCASTS(Environment.DIRECTORY_PODCASTS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	RINGTONES(Environment.DIRECTORY_RINGTONES),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	ALARMS(Environment.DIRECTORY_ALARMS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	NOTIFICATIONS(Environment.DIRECTORY_NOTIFICATIONS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	PICTURES(Environment.DIRECTORY_PICTURES),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	MOVIES(Environment.DIRECTORY_MOVIES),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	DCIM(Environment.DIRECTORY_DCIM),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	@RequiresApi(Build.VERSION_CODES.KITKAT)
	DOCUMENTS(Environment.DIRECTORY_DOCUMENTS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	@RequiresApi(Build.VERSION_CODES.Q)
	AUDIOBOOKS(Environment.DIRECTORY_AUDIOBOOKS),

	/**
	 * Returns `null` if you have no URI permissions for read and write in Android 10.
	 */
	@RequiresApi(Build.VERSION_CODES.Q)
	SCREENSHOTS(Environment.DIRECTORY_SCREENSHOTS)
}