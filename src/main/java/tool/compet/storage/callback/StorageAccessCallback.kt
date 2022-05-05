package tool.compet.storage.callback

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import tool.compet.storage.StorageType

interface StorageAccessCallback {
	fun onCanceledByUser(requestCode: Int) {
		// default implementation
	}

	fun onActivityHandlerNotFound(requestCode: Int, intent: Intent) {
		// default implementation
	}

	/**
	 * Triggered on Android 10 and lower.
	 */
	fun onRootPathNotSelected(
		requestCode: Int,
		rootPath: String,
		uri: Uri,
		selectedStorageType: StorageType,
		expectedStorageType: StorageType
	)

	/**
	 * Triggered on Android 11 and higher.
	 */
	fun onExpectedStorageNotSelected(
		requestCode: Int,
		selectedFolder: DocumentFile,
		selectedStorageType: StorageType,
		expectedBasePath: String,
		expectedStorageType: StorageType
	)

	/**
	 * Triggered on Android 9 and lower.
	 */
	fun onStoragePermissionDenied(requestCode: Int)

	fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile)
}