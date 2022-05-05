package tool.compet.storage.extension

import tool.compet.core.DkLogcats
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader

/**
 * Closing stream safely with try/catch.
 */
fun OutputStream?.tryCloseDk() {
	try {
		this?.close()
	}
	catch (e: IOException) {
		DkLogcats.error(this, "Could not close stream", e)
	}
}

/**
 * Closing stream safely with try/catch.
 */
fun InputStream?.tryCloseDk() {
	try {
		this?.close()
	}
	catch (e: IOException) {
		DkLogcats.error(this, "Could not close stream", e)
	}
}

/**
 * Closing stream safely with try/catch.
 */
fun Reader?.tryCloseDk() {
	try {
		this?.close()
	}
	catch (e: IOException) {
		DkLogcats.error(this, "Could not close stream", e)
	}
}