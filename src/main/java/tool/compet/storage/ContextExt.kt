package tool.compet.storage

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File


/**
 * Get private data root directory in internal storage for the app.
 */
val Context.dataRootDirDk: File
	get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		dataDir
	}
	else {
		filesDir.parentFile!!
	}

/**
 * Collect all app-specific writable dirs for the app.
 *
 * Note that: these directories do not require storage permissions.
 * They are always writable with full disk access.
 */
val Context.writableDirsDk: Set<File>
	get() {
		val dirs = mutableSetOf(this.dataRootDirDk)
		dirs.addAll(ContextCompat.getObbDirs(this).filterNotNull())
		dirs.addAll(ContextCompat.getExternalFilesDirs(this, null).mapNotNull { it?.parentFile })
		return dirs
	}
