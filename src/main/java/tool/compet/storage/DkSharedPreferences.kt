/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import tool.compet.core.DkLogcats
import tool.compet.core.*
import tool.compet.json.DkJsons

/**
 * App-specific access. An app cannot access to preferences of other apps.
 *
 * This works as memory-cache (after first time of retriving data from system file).
 * For back compability, this stores all value as `String` or `Set of String` since if we store with
 * other types (int, double...) then we maybe get an exception when load them with other type.
 *
 * By default, it does support for storing Json object.
 */
@SuppressLint("ApplySharedPref")
open class DkSharedPreferences {
	protected val preferences: SharedPreferences

	constructor(context: Context, prefName: String) {
		preferences = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
	}

	constructor(context: Context, prefName: String, prefMode: Int) {
		preferences = context.getSharedPreferences(prefName, prefMode)
	}

	operator fun contains(key: String): Boolean {
		return preferences.contains(key)
	}

	//
	// Integer
	//
	fun putInt(key: String, value: Int) {
		preferences.edit().putString(key, value.toString()).apply()
	}

	fun getInt(key: String): Int {
		return getString(key).parseIntDk()
	}

	fun getInt(key: String, defautValue: Int): Int {
		return if (contains(key)) getString(key).parseIntDk() else defautValue
	}

	fun storeInt(key: String, value: Int) {
		preferences.edit().putString(key, value.toString()).commit()
	}

	//
	// Float
	//
	fun putFloat(key: String, value: Float) {
		preferences.edit().putString(key, value.toString()).apply()
	}

	fun getFloat(key: String): Float {
		return getString(key).parseFloatDk()
	}

	fun getFloat(key: String, defaultValue: Float): Float {
		return if (contains(key)) getString(key).parseFloatDk() else defaultValue
	}

	fun storeFloat(key: String, value: Float) {
		preferences.edit().putString(key, value.toString()).commit()
	}

	//
	// Double
	//
	fun putDouble(key: String, value: Double) {
		preferences.edit().putString(key, value.toString()).apply()
	}

	fun getDouble(key: String): Double {
		return getString(key).parseDoubleDk()
	}

	fun getDouble(key: String, defaultValue: Double): Double {
		return if (contains(key)) getString(key).parseDoubleDk() else defaultValue
	}

	fun storeDouble(key: String, value: Double) {
		preferences.edit().putString(key, value.toString()).commit()
	}

	//
	// Boolean
	//
	fun putBoolean(key: String, value: Boolean) {
		preferences.edit().putString(key, value.toString()).apply()
	}

	fun getBoolean(key: String): Boolean {
		return getString(key).parseBooleanDk()
	}

	fun getBoolean(key: String, defaultValue: Boolean): Boolean {
		return if (contains(key)) getString(key).parseBooleanDk() else defaultValue
	}

	fun storeBoolean(key: String, value: Boolean) {
		preferences.edit().putString(key, value.toString()).commit()
	}

	//
	// Long
	//
	fun putLong(key: String, value: Long) {
		preferences.edit().putString(key, value.toString()).apply()
	}

	fun getLong(key: String): Long {
		return getString(key).parseLongDk()
	}

	fun getLong(key: String, defaultValue: Long): Long {
		return if (contains(key)) getString(key).parseLongDk() else defaultValue
	}

	fun storeLong(key: String, value: Long) {
		preferences.edit().putString(key, value.toString()).commit()
	}

	//
	// String
	//
	fun putString(key: String, value: String?) {
		preferences.edit().putString(key, value).apply()
	}

	/**
	 * We perform try/catch to archive back-compability (load other types will cause exception).
	 */
	fun getString(key: String): String? {
		try {
			return preferences.getString(key, null)
		}
		catch (e: Exception) {
			DkLogcats.error(this, e)
		}
		return null
	}

	/**
	 * We perform try/catch to archive back-compability (load other types will cause exception).
	 */
	fun getString(key: String, defaultValue: String?): String? {
		try {
			return preferences.getString(key, defaultValue)
		}
		catch (e: Exception) {
			DkLogcats.error(this, e)
		}
		return defaultValue
	}

	fun storeString(key: String, value: String?) {
		preferences.edit().putString(key, value).commit()
	}

	//
	// String set
	//
	fun putStringSet(key: String, values: Set<String?>?) {
		preferences.edit().putStringSet(key, values).apply()
	}

	/**
	 * We perform try/catch to archive back-compability (load other types will cause exception).
	 */
	fun getStringSet(key: String): Set<String>? {
		try {
			return preferences.getStringSet(key, null)
		}
		catch (e: Exception) {
			DkLogcats.error(this, e)
		}
		return null
	}

	/**
	 * We perform try/catch to archive back-compability (load other types will cause exception).
	 */
	fun getStringSet(key: String, defaultValue: Set<String>?): Set<String>? {
		try {
			return preferences.getStringSet(key, defaultValue)
		}
		catch (e: Exception) {
			DkLogcats.error(this, e)
		}
		return defaultValue
	}

	fun storeStringSet(key: String, values: Set<String?>?) {
		preferences.edit().putStringSet(key, values).commit()
	}

	//
	// Json object
	//
	fun putJsonObject(key: String, value: Any?) {
		putString(key, DkJsons.obj2json(value))
	}

	fun <T> getJsonObject(key: String, resClass: Class<T>): T? {
		return DkJsons.json2obj(getString(key), resClass)
	}

	fun <T> getJsonObject(key: String, resClass: Class<T>, defaultValue: T?): T? {
		return if (contains(key)) DkJsons.json2obj(getString(key), resClass) else defaultValue
	}

	fun storeJsonObject(key: String, value: Any?) {
		storeString(key, DkJsons.obj2json(value))
	}

	//
	// CRUD
	//
	fun edit(): SharedPreferences.Editor {
		return preferences.edit()
	}

	fun deleteAsync(key: String) {
		preferences.edit().remove(key).apply()
	}

	fun delete(key: String) {
		preferences.edit().remove(key).commit()
	}

	fun clearAsync() {
		preferences.edit().clear().apply()
	}

	fun clear() {
		preferences.edit().clear().commit()
	}
}
