package tool.compet.storage.callback

import android.content.Intent
import androidx.documentfile.provider.DocumentFile

interface FileReceiverCallback {

	fun onFileReceived(files: List<DocumentFile>)
	fun onNonFileReceived(intent: Intent)
}