package tool.compet.storage

import android.app.Activity
import android.content.Context
import android.content.Intent

internal interface ComponentWrapper {
	val context: Context

	val activity: Activity

	fun startActivityForResult(intent: Intent, requestCode: Int): Boolean
}