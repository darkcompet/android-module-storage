package tool.compet.storage

import android.os.Environment

/**
 * Defines media directories which associates with category `audio`.
 */
enum class AudioMediaDirectory(val dirName: String) {
	MUSIC(Environment.DIRECTORY_MUSIC),
	PODCASTS(Environment.DIRECTORY_PODCASTS),
	RINGTONES(Environment.DIRECTORY_RINGTONES),
	ALARMS(Environment.DIRECTORY_ALARMS),
	NOTIFICATIONS(Environment.DIRECTORY_NOTIFICATIONS)
}