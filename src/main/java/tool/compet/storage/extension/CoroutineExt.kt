package tool.compet.storage.extension

import kotlinx.coroutines.*

internal inline fun CoroutineScope.postToUiDk(crossinline action: () -> Unit) {
	launch(Dispatchers.Main) { action() }
}

internal fun startCoroutineTimer(
	delayMillis: Long = 0,
	repeatMillis: Long = 0,
	runActionOnUiThread: Boolean = false,
	action: () -> Unit
) = GlobalScope.launch {
	delay(delayMillis)
	if (repeatMillis > 0) {
		while (true) {
			if (runActionOnUiThread) {
				launchOnUiThread { action() }
			}
			else {
				action()
			}
			delay(repeatMillis)
		}
	}
	else {
		if (runActionOnUiThread) {
			launchOnUiThread { action() }
		}
		else {
			action()
		}
	}
}

internal fun launchOnUiThread(action: suspend CoroutineScope.() -> Unit) : Job {
	return GlobalScope.launch(Dispatchers.Main, block = action)
}

internal inline fun <R> awaitUiResultWithPending(
	uiScope: CoroutineScope,
	crossinline action: (CancellableContinuation<R>) -> Unit
): R {
	return runBlocking {
		suspendCancellableCoroutine {
			uiScope.launch(Dispatchers.Main) { action(it) }
		}
	}
}

inline fun <R> awaitUiResult(uiScope: CoroutineScope, crossinline action: () -> R): R {
	return runBlocking {
		suspendCancellableCoroutine {
			uiScope.launch(Dispatchers.Main) {
				it.resumeWith(Result.success(action()))
			}
		}
	}
}
