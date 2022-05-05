package tool.compet.storage.callback

import android.content.Intent
import androidx.documentfile.provider.DocumentFile

interface CreateFileCallback {
	fun onCanceledByUser(requestCode: Int) {
		// default implementation
	}

	fun onActivityHandlerNotFound(requestCode: Int, intent: Intent) {
		// default implementation
	}

	fun onFileCreated(requestCode: Int, file: DocumentFile)
}