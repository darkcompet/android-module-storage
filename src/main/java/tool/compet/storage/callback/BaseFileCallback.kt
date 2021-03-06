package tool.compet.storage.callback

import androidx.annotation.RestrictTo
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope

@RestrictTo(RestrictTo.Scope.LIBRARY)
abstract class BaseFileCallback<ErrorCode, Report, Result>(var uiScope: CoroutineScope) {
	@UiThread
	open fun onValidate() {
		// default implementation
	}

	@UiThread
	open fun onPrepare() {
		// default implementation
	}

	/**
	 * Called after the user chooses [DirCallback.ConflictResolution.REPLACE] or [FileCallback.ConflictResolution.REPLACE]
	 */
	@UiThread
	open fun onDeleteConflictedFiles() {
		// default implementation
	}

	/**
	 * Given `freeSpace` and `fileSize`, then you decide whether the process will be continued or not.
	 * You can give space tolerant here, e.g. 100MB
	 *
	 * @param freeSpace of target path
	 * @return `true` to continue process
	 */
	@WorkerThread
	open fun onCheckFreeSpace(freeSpace: Long, fileSize: Long): Boolean {
		return fileSize + 100 * 1024 * 1024 < freeSpace // Give tolerant 100MB
	}

	@UiThread
	open fun onReport(report: Report) {
		// default implementation
	}

	@UiThread
	open fun onCompleted(result: Result) {
		// default implementation
	}

	@UiThread
	open fun onFailed(errorCode: ErrorCode) {
		// default implementation
	}
}