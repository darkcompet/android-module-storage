package tool.compet.storage.callback

import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import tool.compet.storage.DkExternalStorage
import tool.compet.storage.StorageType

interface FolderPickerCallback {
	fun onCanceledByUser(requestCode: Int) {
		// default implementation
	}

	fun onActivityHandlerNotFound(requestCode: Int, intent: Intent) {
		// default implementation
	}

	fun onStoragePermissionDenied(requestCode: Int)

	/**
	 * Called when storage permissions are granted, but [DkExternalStorage.isStorageUriPermissionGranted] returns `false`
	 *
	 * @param folder selected folder that has no read and write permission
	 * @param storageType `null` if `folder`'s authority is not [DkExternalStorage.EXTERNAL_STORAGE_AUTHORITY]
	 */
	fun onStorageAccessDenied(requestCode: Int, folder: DocumentFile?, storageType: StorageType)

	fun onFolderSelected(requestCode: Int, folder: DocumentFile)
}