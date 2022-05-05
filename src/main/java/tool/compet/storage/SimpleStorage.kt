package tool.compet.storage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import tool.compet.storage.StorageId.PRIMARY
import tool.compet.storage.callback.*
import tool.compet.storage.extension.*
import java.io.File
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.KITKAT)
class SimpleStorage private constructor(private val wrapper: ComponentWrapper) {
	// For unknown Activity type
	constructor(activity: Activity, savedState: Bundle? = null) : this(ActivityWrapper(activity)) {
		savedState?.let { onRestoreInstanceState(it) }
	}

	constructor(activity: ComponentActivity, savedState: Bundle? = null) : this(ComponentActivityWrapper(activity)) {
		savedState?.let { onRestoreInstanceState(it) }
		(wrapper as ComponentActivityWrapper).storage = this
	}

	constructor(fragment: Fragment, savedState: Bundle? = null) : this(FragmentWrapper(fragment)) {
		savedState?.let { onRestoreInstanceState(it) }
		(wrapper as FragmentWrapper).storage = this
	}

	/**
	 * Caller should invoke this when the component (Activity, Fragment,...) goto this state.
	 */
	fun onSaveInstanceState(outState: Bundle) {
		outState.putString(KEY_EXPECTED_BASE_PATH_FOR_ACCESS_REQUEST, expectedBasePathForAccessRequest)
		outState.putInt(KEY_REQUEST_CODE_STORAGE_ACCESS, expectedStorageTypeForAccessRequest.ordinal)
		outState.putInt(KEY_REQUEST_CODE_STORAGE_ACCESS, requestCodeStorageAccess)
		outState.putInt(KEY_REQUEST_CODE_FOLDER_PICKER, requestCodeFolderPicker)
		outState.putInt(KEY_REQUEST_CODE_FILE_PICKER, requestCodeFilePicker)
		outState.putInt(KEY_REQUEST_CODE_CREATE_FILE, requestCodeCreateFile)
		if (wrapper is FragmentWrapper) {
			outState.putInt(KEY_REQUEST_CODE_FRAGMENT_PICKER, wrapper.requestCode)
		}
	}

	/**
	 * Caller should invoke this when the component (Activity, Fragment,...) goto this state.
	 */
	fun onRestoreInstanceState(inState: Bundle) {
		expectedBasePathForAccessRequest = inState.getString(KEY_EXPECTED_BASE_PATH_FOR_ACCESS_REQUEST)
		expectedStorageTypeForAccessRequest = StorageType.values()[inState.getInt(KEY_EXPECTED_STORAGE_TYPE_FOR_ACCESS_REQUEST)]
		requestCodeStorageAccess = inState.getInt(KEY_REQUEST_CODE_STORAGE_ACCESS)
		requestCodeFolderPicker = inState.getInt(KEY_REQUEST_CODE_FOLDER_PICKER)
		requestCodeFilePicker = inState.getInt(KEY_REQUEST_CODE_FILE_PICKER)
		requestCodeCreateFile = inState.getInt(KEY_REQUEST_CODE_CREATE_FILE)
		if (wrapper is FragmentWrapper && inState.containsKey(KEY_REQUEST_CODE_FRAGMENT_PICKER)) {
			wrapper.requestCode = inState.getInt(KEY_REQUEST_CODE_FRAGMENT_PICKER)
		}
	}

	var storageAccessCallback: StorageAccessCallback? = null

	var folderPickerCallback: FolderPickerCallback? = null

	var filePickerCallback: FilePickerCallback? = null

	var createFileCallback: CreateFileCallback? = null

	var fileReceiverCallback: FileReceiverCallback? = null

	var requestCodeStorageAccess = 1
		set(value) {
			field = value
			checkRequestCode()
		}

	var requestCodeFolderPicker = 2
		set(value) {
			field = value
			checkRequestCode()
		}

	var requestCodeFilePicker = 3
		set(value) {
			field = value
			checkRequestCode()
		}

	var requestCodeCreateFile = 4
		set(value) {
			field = value
			checkRequestCode()
		}

	val context: Context
		get() = wrapper.context

	/**
	 * It returns an intent to be dispatched via [Activity.startActivityForResult]
	 */
	private val externalStorageRootAccessIntent: Intent
		get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
			sm.primaryStorageVolume.createOpenDocumentTreeIntent()
		}
		else {
			defaultExternalStorageIntent
		}

	/**
	 * It returns an intent to be dispatched via [Activity.startActivityForResult] to access to
	 * the first removable no primary storage. This function requires at least Nougat
	 * because on previous Android versions there's no reliable way to get the
	 * volume/path of SdCard, and no, SdCard != External Storage.
	 */
	private val sdCardRootAccessIntent: Intent
		@Suppress("DEPRECATION")
		@RequiresApi(api = Build.VERSION_CODES.N)
		get() {
			val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

			return storageManager.storageVolumes.firstOrNull { it.isRemovable }?.let {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					it.createOpenDocumentTreeIntent()
				}
				else {
					// Access to the entire volume is only available for non-primary volumes
					if (it.isPrimary) {
						defaultExternalStorageIntent
					}
					else {
						it.createAccessIntent(null)
					}
				}
			} ?: defaultExternalStorageIntent
		}

	/**
	 * Even though storage permission has been granted via [hasStoragePermission],
	 * read and write access may have not been granted yet.
	 *
	 * @param storageId Use [PRIMARY] for external storage. Or use SD Card storage ID.
	 * @return `true` if storage pemissions and URI permissions are granted for read and write access.
	 *
	 * @see [DkExternalStorage.listExternalStorageIds]
	 */
	fun isStorageAccessGranted(storageId: String) = DkStorage.isAccessGranted(context, storageId)

	private var expectedStorageTypeForAccessRequest = StorageType.UNKNOWN

	private var expectedBasePathForAccessRequest: String? = null

	/**
	 * Managing files in direct storage requires root access. Thus we need to make sure users select root path.
	 *
	 * @param initialRootPath it has no effect on API 23 and lower
	 * @param expectedStorageType for example, if you set [StorageType.SD_CARD] but
	 * the user selects [StorageType.EXTERNAL], then trigger [StorageAccessCallback.onRootPathNotSelected].
	 * Set to [StorageType.UNKNOWN] to accept any storage type.
	 * @param expectedBasePath applicable for API 30+ only, because Android 11 does not allow selecting the root path.
	 */
	@JvmOverloads
	fun requestStorageAccess(
		requestCode: Int = requestCodeStorageAccess,
		initialRootPath: StorageType = StorageType.EXTERNAL,
		expectedStorageType: StorageType = StorageType.UNKNOWN,
		expectedBasePath: String = ""
	) {
		if (initialRootPath == StorageType.DATA || expectedStorageType == StorageType.DATA) {
			throw IllegalArgumentException("Cannot use StorageType.DATA because it is never available in Storage Access Framework's folder selector.")
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ! hasStoragePermission(context)) {
			storageAccessCallback?.onStoragePermissionDenied(requestCode)
			return
		}

		if (initialRootPath == StorageType.EXTERNAL && expectedStorageType.isExpected(initialRootPath) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !isSdCardPresent) {
			val root = DkStorage.getRootDir(context, PRIMARY, true) ?: return
			saveUriPermission(root.uri)
			storageAccessCallback?.onRootPathPermissionGranted(requestCode, root)
			return
		}

		val intent = if (initialRootPath == StorageType.SD_CARD && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			sdCardRootAccessIntent
		}
		else {
			externalStorageRootAccessIntent
		}

		if (wrapper.startActivityForResult(intent, requestCode)) {
			requestCodeStorageAccess = requestCode
			expectedStorageTypeForAccessRequest = expectedStorageType
			expectedBasePathForAccessRequest = expectedBasePath
		}
		else {
			storageAccessCallback?.onActivityHandlerNotFound(requestCode, intent)
		}
	}

	/**
	 * Makes your app can access [direct file paths](https://developer.android.com/training/data-storage/shared/media#direct-file-paths)
	 *
	 * See [Manage all files on a storage device](https://developer.android.com/training/data-storage/manage-all-files)
	 */
	@RequiresPermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
	@RequiresApi(Build.VERSION_CODES.R)
	fun requestFullStorageAccess() {
		context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
	}

	@JvmOverloads
	fun createFile(mimeType: String, fileName: String? = null, requestCode: Int = requestCodeCreateFile) {
		requestCodeCreateFile = requestCode

		val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mimeType)
		fileName?.let { intent.putExtra(Intent.EXTRA_TITLE, it) }

		if (! wrapper.startActivityForResult(intent, requestCode)) {
			createFileCallback?.onActivityHandlerNotFound(requestCode, intent)
		}
	}

	@SuppressLint("InlinedApi")
	@JvmOverloads
	fun openFolderPicker(requestCode: Int = requestCodeFolderPicker) {
		requestCodeFolderPicker = requestCode

		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasStoragePermission(context)) {
			val intent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
			}
			else {
				externalStorageRootAccessIntent
			}

			// Open activity for picking
			if (! wrapper.startActivityForResult(intent, requestCode)) {
				folderPickerCallback?.onActivityHandlerNotFound(requestCode, intent)
			}
		}
		else {
			folderPickerCallback?.onStoragePermissionDenied(requestCode)
		}
	}

	@JvmOverloads
	fun openFilePicker(
		requestCode: Int = requestCodeFilePicker,
		allowMultiple: Boolean = false,
		vararg filterMimeTypes: String
	) {
		requestCodeFilePicker = requestCode
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
		if (filterMimeTypes.size > 1) {
			intent.setType(MimeType.UNKNOWN).putExtra(Intent.EXTRA_MIME_TYPES, filterMimeTypes)
		}
		else {
			intent.type = filterMimeTypes.firstOrNull() ?: MimeType.UNKNOWN
		}

		if (!wrapper.startActivityForResult(intent, requestCode)) {
			filePickerCallback?.onActivityHandlerNotFound(requestCode, intent)
		}
	}

	private fun handleActivityResultForStorageAccess(requestCode: Int, uri: Uri) {
		val storageId = uri.getStorageIdDk(context)
		val storageType = StorageType.fromStorageId(storageId)

		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
			val selectedFolder = DocumentFile.fromTreeUri(context, uri) ?: return
			if (
				!expectedStorageTypeForAccessRequest.isExpected(storageType) ||
				!expectedBasePathForAccessRequest.isNullOrEmpty() && selectedFolder.getBasePathDk(context) != expectedBasePathForAccessRequest
			) {
				storageAccessCallback?.onExpectedStorageNotSelected(
					requestCode,
					selectedFolder,
					storageType,
					expectedBasePathForAccessRequest!!,
					expectedStorageTypeForAccessRequest
				)
				return
			}
		}
		else if (!expectedStorageTypeForAccessRequest.isExpected(storageType)) {
			val rootPath = DocumentFile.fromTreeUri(context, uri)?.getAbsolutePathDk(context).orEmpty()
			storageAccessCallback?.onRootPathNotSelected(
				requestCode,
				rootPath,
				uri,
				storageType,
				expectedStorageTypeForAccessRequest
			)
			return
		}

		if (uri.isDownloadsDocumentDk) {
			if (uri.toString() == DkStorage.DOWNLOADS_TREE_URI) {
				saveUriPermission(uri)
				storageAccessCallback?.onRootPathPermissionGranted(requestCode, DocumentFile.fromTreeUri(context, uri) ?: return)
			}
			else {
				storageAccessCallback?.onRootPathNotSelected(
					requestCode,
					"${DkStorage.externalStoragePath}/${Environment.DIRECTORY_DOWNLOADS}",
					uri,
					StorageType.EXTERNAL,
					expectedStorageTypeForAccessRequest
				)
			}
			return
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !uri.isExternalStorageDocumentDk) {
			storageAccessCallback?.onRootPathNotSelected(
				requestCode,
				DkStorage.externalStoragePath,
				uri,
				StorageType.EXTERNAL,
				expectedStorageTypeForAccessRequest
			)
			return
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && storageId == PRIMARY) {
			saveUriPermission(uri)
			storageAccessCallback?.onRootPathPermissionGranted(requestCode, DocumentFile.fromTreeUri(context, uri) ?: return)
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || isRootUri(uri)) {
			if (saveUriPermission(uri)) {
				storageAccessCallback?.onRootPathPermissionGranted(requestCode, DocumentFile.fromTreeUri(context, uri) ?: return)
			}
			else {
				storageAccessCallback?.onStoragePermissionDenied(requestCode)
			}
		}
		else {
			if (storageId == PRIMARY) {
				storageAccessCallback?.onRootPathNotSelected(
					requestCode,
					DkStorage.externalStoragePath,
					uri,
					StorageType.EXTERNAL,
					expectedStorageTypeForAccessRequest
				)
			}
			else {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
					val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
					@Suppress("DEPRECATION")
					sm.storageVolumes.firstOrNull { !it.isPrimary }?.createAccessIntent(null)?.let {
						if (!wrapper.startActivityForResult(it, requestCode)) {
							storageAccessCallback?.onActivityHandlerNotFound(requestCode, it)
						}
						return
					}
				}
				storageAccessCallback?.onRootPathNotSelected(
					requestCode,
					"/storage/$storageId",
					uri,
					StorageType.SD_CARD,
					expectedStorageTypeForAccessRequest
				)
			}
		}
	}

	private fun handleActivityResultForFolderPicker(requestCode: Int, uri: Uri) {
		val folder = DocumentFile.fromTreeUri(context, uri)
		val storageId = uri.getStorageIdDk(context)
		val storageType = StorageType.fromStorageId(storageId)

		if (folder == null || !folder.canModifyDk(context)) {
			folderPickerCallback?.onStorageAccessDenied(requestCode, folder, storageType)
			return
		}
		if (uri.toString() == DkStorage.DOWNLOADS_TREE_URI
			|| isRootUri(uri)
			&& (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && storageType == StorageType.SD_CARD || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
			&& !DkStorage.isStorageUriPermissionGranted(context, storageId)
		) {
			saveUriPermission(uri)
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && storageType == StorageType.EXTERNAL
			|| Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && saveUriPermission(uri)
			|| !uri.isExternalStorageDocumentDk && folder.canModifyDk(context)
			|| DkStorage.isStorageUriPermissionGranted(context, storageId)
		) {
			folderPickerCallback?.onFolderSelected(requestCode, folder)
		}
		else {
			folderPickerCallback?.onStorageAccessDenied(requestCode, folder, storageType)
		}
	}

	private fun isRootUri(uri: Uri) : Boolean {
		val path = uri.path ?: return false
		return uri.isExternalStorageDocumentDk && path.indexOf(':') == path.length - 1
	}

	private fun intentToDocumentFiles(intent: Intent?): List<DocumentFile> {
		val uris = intent?.clipData?.run {
			val list = mutableListOf<Uri>()
			for (i in 0 until itemCount) {
				list.add(getItemAt(i).uri)
			}
			list.takeIf { it.isNotEmpty() }
		} ?: listOf(intent?.data ?: return emptyList())

		return uris.mapNotNull { uri ->
			if (uri.isDownloadsDocumentDk && Build.VERSION.SDK_INT < 28 && uri.path?.startsWith("/document/raw:") == true) {
				val fullPath = uri.path.orEmpty().substringAfterLast("/document/raw:")
				DocumentFile.fromFile(File(fullPath))
			}
			else {
				DocumentFile.fromSingleUri(context, uri) // context.getFileFromSingleUri(uri)
			}
		}.filter { it.isFile }
	}

	fun checkIfFileReceived(intent: Intent?) {
		when (intent?.action) {
			Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
				val files = intentToDocumentFiles(intent)
				if (files.isEmpty()) {
					fileReceiverCallback?.onNonFileReceived(intent)
				}
				else {
					fileReceiverCallback?.onFileReceived(files)
				}
			}
		}
	}

	private fun handleActivityResultForFilePicker(requestCode: Int, data: Intent) {
		val files = intentToDocumentFiles(data)
		if (files.isNotEmpty() && files.all { it.canRead() }) {
			filePickerCallback?.onFileSelected(requestCode, files)
		}
		else {
			filePickerCallback?.onStoragePermissionDenied(requestCode, files)
		}
	}

	private fun handleActivityResultForCreateFile(requestCode: Int, uri: Uri) {
		uri.getFileDk(context)?.let {
			createFileCallback?.onFileCreated(requestCode, it)
		}
	}

	fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		checkRequestCode()

		when (requestCode) {
			requestCodeStorageAccess -> {
				if (resultCode == Activity.RESULT_OK) {
					handleActivityResultForStorageAccess(requestCode, data?.data ?: return)
				}
				else {
					storageAccessCallback?.onCanceledByUser(requestCode)
				}
			}

			requestCodeFolderPicker -> {
				if (resultCode == Activity.RESULT_OK) {
					handleActivityResultForFolderPicker(requestCode, data?.data ?: return)
				}
				else {
					folderPickerCallback?.onCanceledByUser(requestCode)
				}
			}

			requestCodeFilePicker -> {
				if (resultCode == Activity.RESULT_OK) {
					handleActivityResultForFilePicker(requestCode, data ?: return)
				}
				else {
					filePickerCallback?.onCanceledByUser(requestCode)
				}
			}

			requestCodeCreateFile -> {
				// resultCode is always OK for creating files
				val uri = data?.data
				if (uri != null) {
					handleActivityResultForCreateFile(requestCode, uri)
				}
				else {
					createFileCallback?.onCanceledByUser(requestCode)
				}
			}
		}
	}

	private fun checkRequestCode() {
		val set = setOf(requestCodeFilePicker, requestCodeFolderPicker, requestCodeStorageAccess, requestCodeCreateFile)
		if (set.size < 4)
			throw IllegalArgumentException(
				"Request codes must be unique. File picker=$requestCodeFilePicker, Folder picker=$requestCodeFolderPicker, " +
					"Storage access=$requestCodeStorageAccess, Create file=$requestCodeCreateFile"
			)
	}

	private fun saveUriPermission(root: Uri) = try {
		val writeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		context.contentResolver.takePersistableUriPermission(root, writeFlags)
		cleanupRedundantUriPermissions(context.applicationContext)
		true
	}
	catch (e: SecurityException) {
		false
	}

	companion object {
		private const val LIBRARY_PACKAGE_NAME = "BuildConfig.APPLICATION_ID"
		private const val KEY_REQUEST_CODE_STORAGE_ACCESS = "$LIBRARY_PACKAGE_NAME.requestCodeStorageAccess"
		private const val KEY_REQUEST_CODE_FOLDER_PICKER = "$LIBRARY_PACKAGE_NAME.requestCodeFolderPicker"
		private const val KEY_REQUEST_CODE_FILE_PICKER = "$LIBRARY_PACKAGE_NAME.requestCodeFilePicker"
		private const val KEY_REQUEST_CODE_CREATE_FILE = "$LIBRARY_PACKAGE_NAME.requestCodeCreateFile"
		private const val KEY_REQUEST_CODE_FRAGMENT_PICKER = "$LIBRARY_PACKAGE_NAME.requestCodeFragmentPicker"
		private const val KEY_EXPECTED_STORAGE_TYPE_FOR_ACCESS_REQUEST = "$LIBRARY_PACKAGE_NAME.expectedStorageTypeForAccessRequest"
		private const val KEY_EXPECTED_BASE_PATH_FOR_ACCESS_REQUEST = "$LIBRARY_PACKAGE_NAME.expectedBasePathForAccessRequest"

		@JvmStatic
		val isSdCardPresent: Boolean
			get() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

		@JvmStatic
		val defaultExternalStorageIntent: Intent
			@SuppressLint("InlinedApi")
			get() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
				if (Build.VERSION.SDK_INT >= 26) {
					putExtra(DocumentsContract.EXTRA_INITIAL_URI, DkStorage.createDocumentUri(PRIMARY))
				}
			}

		/**
		 * For read and write permissions
		 */
		@JvmStatic
		fun hasStoragePermission(context: Context): Boolean {
			return checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
				PackageManager.PERMISSION_GRANTED && hasStorageReadPermission(context)
		}

		/**
		 * For read permission only
		 */
		@JvmStatic
		fun hasStorageReadPermission(context: Context): Boolean {
			return checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
				PackageManager.PERMISSION_GRANTED
		}

		@JvmStatic
		fun hasFullDiskAccess(context: Context, storageId: String): Boolean {
			return hasStorageAccess(context, DkStorage.makeAbsolutePath(context, storageId, ""))
		}

		/**
		 * In API 29+, `/storage/emulated/0` may not be granted for URI permission,
		 * but all directories under `/storage/emulated/0/Download` are granted and accessible.
		 *
		 * @param requireWritable `true` if you expect this path should be writable
		 * @return `true` if you have URI access to this path
		 * @see [DkExternalStorage.getAbsolutePath]
		 * @see [DkExternalStorage.getSimplePath]
		 */
		@JvmStatic
		@JvmOverloads
		fun hasStorageAccess(context: Context, fullPath: String, requireWritable: Boolean = true): Boolean {
			return ((requireWritable && hasStoragePermission(context)) || (!requireWritable && hasStorageReadPermission(context)))
				&& DkStorage.getAccessibleRootDocumentFile(context, fullPath, requireWritable) != null
		}

		/**
		 * Max persistable URI per app is 128, so cleanup redundant URI permissions. Given the following URIs:
		 * 1) `content://com.android.externalstorage.documents/tree/primary%3AMovies`
		 * 2) `content://com.android.externalstorage.documents/tree/primary%3AMovies%2FHorror`
		 *
		 * Then remove the second URI, because it has been covered by the first URI.
		 *
		 * Read [Count Your SAF Uri Persisted Permissions!](https://commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html)
		 */
		@JvmStatic
		fun cleanupRedundantUriPermissions(context: Context) {
			thread {
				val resolver = context.contentResolver
				// e.g. content://com.android.externalstorage.documents/tree/primary%3AMusic
				val persistedUris = resolver.persistedUriPermissions
					.filter { it.isReadPermission && it.isWritePermission && it.uri.isExternalStorageDocumentDk }
					.map { it.uri }
				val writeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				val uniqueUriParents = DkStorage.findUniqueParents(
					context,
					persistedUris.mapNotNull { it.path?.substringAfter("/tree/") })
				persistedUris.forEach {
					if (DkStorage.makeAbsolutePath(
							context,
							it.path.orEmpty().substringAfter("/tree/")
						) !in uniqueUriParents
					) {
						resolver.releasePersistableUriPermission(it, writeFlags)
						//DkLogcats.debug("Removed redundant URI permission => $it")
					}
				}
			}
		}
	}
}
