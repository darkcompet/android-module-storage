/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.storage

import android.graphics.Bitmap
import android.os.SystemClock
import tool.compet.core.sizeDk
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Thread-safe memory cache (LruCache).
 * Each cache-entry has own priority, expired time.
 */
object DkMemoryCache {
	private val cache: TreeMap<String, Snapshot> = TreeMap()
	private val listeners = ArrayList<Listener>()
	private var size: Long = 0

	private var maxSize = Runtime.getRuntime().maxMemory() shr 2
		set(value) {
			field = if (value <= 0) 1L else value
		}

	fun newSnapshot(target: Any?): Snapshot {
		return Snapshot(target)
	}

	fun put(key: String?, value: Bitmap) {
		put(key, Snapshot(value, value.sizeDk()))
	}

	@Synchronized
	fun put(key: String?, snapshot: Snapshot?) {
		if (key == null || snapshot == null) {
			throw RuntimeException("Cannot put null-key or null-snapshot")
		}
		val more = snapshot.size
		removeExpiredObjects()
		if (size + more >= maxSize) {
			trimToSize(maxSize - more)
		}
		size += more
		cache[key] = snapshot
	}

	@Synchronized
	fun remove(key: String) {
		val snapshot = this.cache[key]
		if (snapshot != null) {
			this.size -= snapshot.size
		}
		this.cache.remove(key)
		for (listener in listeners) {
			listener.onRemoved(key, snapshot)
		}
	}

	@Synchronized
	operator fun <T> get(key: String): T? {
		val snapshot = cache[key]
		return if (snapshot != null) {
			snapshot.target as T?
		}
		else null
	}

	/**
	 * 優先度の昇順でnewSizeに下がるまでオブジェクトを削除していきます。
	 */
	@Synchronized
	fun trimToSize(_newSize: Long) {
		val newSize = if (_newSize < 0L) 0L else _newSize
		var curSize = this.size

		// Remove low priority and older objects from start to end
		while (curSize > newSize) {
			cache.pollFirstEntry()?.let { entry ->
				val snapshot = entry.value
				curSize -= snapshot.size

				for (listener in listeners) {
					listener.onRemoved(entry.key, snapshot)
				}
			}
		}
		this.size = if (curSize < 0) 0 else curSize
	}

	/**
	 * 期限切れたオブジェクトを全て削除します。
	 */
	@Synchronized
	fun removeExpiredObjects() {
		var curSize = this.size
		val now = SystemClock.uptimeMillis()
		val it: MutableIterator<Map.Entry<String, Snapshot>> = cache.entries.iterator()

		while (it.hasNext()) {
			val entry = it.next()
			val snapshot = entry.value
			if (snapshot.expiredTime >= now) {
				curSize -= snapshot.size
				it.remove()

				for (listener in listeners) {
					listener.onRemoved(entry.key, snapshot)
				}
			}
		}
		this.size = if (curSize < 0) 0 else curSize
	}

	fun register(listener: Listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener)
		}
	}

	fun unregister(listener: Listener) {
		listeners.remove(listener)
	}

	interface Listener {
		fun onRemoved(key: String?, snapshot: Snapshot?)
	}

	class Snapshot {
		// 定まったメモリ量を超えた場合、優先度の低いものから削除していきます。
		// 基本的に昇順で0から10までの数字で十分だと思います。
		var priority = 0

		// SystemClock.uptimeMillis()の時間、デフォルト値は無限値です。
		// 期限切れたものはキャッシュから削除されます
		var expiredTime: Long = 0

		// キャッシュ対象オブジェクト
		var target: Any? = null

		var size: Long = 0
			set(value) {
				field = if (value < 0L) 0L else value
			}

		constructor() {}
		constructor(target: Any?) {
			this.target = target
		}

		constructor(target: Any?, size: Long) {
			this.target = target
			this.size = if (size < 1) 1 else size
		}

		fun setExpiredTime(duration: Long, timeUnit: TimeUnit): Snapshot {
			expiredTime = SystemClock.uptimeMillis() + timeUnit.toNanos(duration)
			return this
		}
	}
}