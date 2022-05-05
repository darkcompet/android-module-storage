/*
 * Copyright (c) 2017-2021 DarkCompet. All rights reserved.
 */

package tool.compet.storage;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import tool.compet.core.DkDateTimes;
import tool.compet.core.DkLogger;
import tool.compet.core.DkRunner2;
import tool.compet.core.DkStrings;
import tool.compet.core.DkUtils;

/**
 * File log. It also provide benchmark for debugging.
 * Because a lot of places call this log, so this does not provide log-callback
 * to avoid infinitive looping.
 */
public class DkFileLogger implements DkLogger.LogType {
	// Enable this to log back trace of current thread
	public static boolean logBackTrace;
	// For benchmark
	private static long benchmarkStartTime;
	private static ArrayDeque<String> benchmarkTaskNames;
	// To persist log to file
	private DkRunner2<Integer, String> storage; // logType vs logMessage
	private final DkLogger logger = new DkLogger(this::logActual);

	public DkFileLogger(DkRunner2<Integer, String> storage) {
		this.storage = storage;
	}

	public void setStorage(DkRunner2<Integer, String> storage) {
		this.storage = storage;
	}

	/**
	 * Debug log. Can't be invoked in production.
	 * Note that, we should remove all debug code when release.
	 */
	// todo: Remove all debug line which call this
	public void debug(@Nullable Object where, @Nullable String format, Object... args) {
		if (!BuildConfig.DEBUG) {
			DkUtils.complainAt(DkFileLogger.class, "Can not use debug-log at product version");
		}
		logger.debug(where, format, args);
	}

	/**
	 * Log info. Can be invoked in production.
	 */
	public void info(@Nullable Object where, @Nullable String format, Object... args) {
		logger.info(where, format, args);
	}

	/**
	 * Log notice. Can be invoked in production.
	 */
	public void notice(@Nullable Object where, @Nullable String format, Object... args) {
		logger.notice(where, format, args);
	}

	/**
	 * Warning log. Can be invoked in production.
	 */
	public void warning(@Nullable Object where, @Nullable String format, Object... args) {
		logger.warning(where, format, args);
	}

	/**
	 * Error log. Can be invoked in production.
	 */
	public void error(@Nullable Object where, @Nullable String format, Object... args) {
		logger.error(where, format, args);
	}

	/**
	 * Exception log. Can be invoked in production.
	 */
	public void error(@Nullable Object where, Throwable e) {
		error(where, e, null);
	}

	/**
	 * Exception log. Can be invoked in production.
	 */
	public void error(@Nullable Object where, Throwable e, @Nullable String format, Object... args) {
		logger.error(where, e, format, args);
	}

	/**
	 * Critical log. Run at both debug and production env.
	 */
	public void critical(@Nullable Object where, @Nullable String format, Object... args) {
		logger.critical(where, format, args);
	}

	/**
	 * Emergency log. Run at both debug and production env.
	 */
	public void emergency(@Nullable Object where, @Nullable String format, Object... args) {
		logger.emergency(where, format, args);
	}

	/**
	 * Log with specific log-type.
	 */
	public void log(Object where, int type, String format, Object... args) {
		if (type == TYPE_DEBUG) {
			debug(where, format, args);
		}
		else if (type == TYPE_INFO) {
			info(where, format, args);
		}
		else if (type == TYPE_NOTICE) {
			notice(where, format, args);
		}
		else if (type == TYPE_WARNING) {
			warning(where, format, args);
		}
		else if (type == TYPE_ERROR) {
			error(where, format, args);
		}
		else if (type == TYPE_CRITICAL) {
			critical(where, format, args);
		}
		else if (type == TYPE_EMERGENCY) {
			emergency(where, format, args);
		}
		else {
			throw new RuntimeException("Invalid log type: " + type);
		}
	}

	/**
	 * Start benchmark. Can't be invoked in production.
	 */
	public void tick(@Nullable Object where, String task) {
		if (benchmarkTaskNames == null) {
			benchmarkTaskNames = new ArrayDeque<>();
		}

		benchmarkTaskNames.push(task);
		logger.debug(where, "Task [%s] was started", task);
		benchmarkStartTime = System.currentTimeMillis();
	}

	/**
	 * End benchmark. Can't be invoked in production.
	 */
	public void tock(@Nullable Object where) {
		long elapsed = System.currentTimeMillis() - benchmarkStartTime;
		logger.debug(where,
			"Task [%s] end in: %d s %d ms",
			benchmarkTaskNames.pop(),
			elapsed / 1000,
			(elapsed - 1000 * (elapsed / 1000)));
	}

	private void logActual(int logType, String message) {
		if (logBackTrace) {
			List<String> descriptions = new ArrayList<>();
			for (StackTraceElement elm : Thread.currentThread().getStackTrace()) {
				String description = DkStrings.format("%s (%d) ==> %s.%s()", elm.getFileName(), elm.getLineNumber(), elm.getClassName(), elm.getMethodName());
				descriptions.add(description);
			}
			String trace = DkStrings.join('\n', descriptions);
			message += "\nStack Trace:\n" + trace;
		}

		String logMessage = "[" + DkLogger.LogType.name(logType).toUpperCase() + "] " + DkDateTimes.formatNow() + ": " + message;

		try {
			storage.run(logType, logMessage);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
}
