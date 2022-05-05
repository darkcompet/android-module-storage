//package tool.compet.storage.extension

//import android.app.Activity
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent

//fun Context.getAppDirectory(type: String? = null) = "${getExternalFilesDir(type)}"
//
//fun Intent?.hasActivityHandler(context: Context) =
//	this?.resolveActivity(context.packageManager) != null
//
//fun Context.startActivitySafely(intent: Intent) {
//	if (intent.hasActivityHandler(this)) {
//		startActivity(intent)
//	}
//}
//
//fun Activity.startActivityForResultSafely(requestCode: Int, intent: Intent) {
//	if (intent.hasActivityHandler(this)) {
//		startActivityForResult(intent, requestCode)
//	}
//}
//
//fun Context.unregisterReceiverSafely(receiver: BroadcastReceiver?) {
//	try {
//		unregisterReceiver(receiver ?: return)
//	}
//	catch (e: IllegalArgumentException) {
//		// ignore
//	}
//}

//fun Context.getFileFromTreeUri(treeUri: Uri) : DocumentFile? = try {
//	DocumentFile.fromTreeUri(this, treeUri)
//}
//catch (e: Exception) {
//	null
//}

//fun Context.getFileFromSingleUri(fileUri: Uri) : DocumentFile? = try {
//	DocumentFile.fromSingleUri(this, fileUri)
//}
//catch (e: Exception) {
//	null
//}