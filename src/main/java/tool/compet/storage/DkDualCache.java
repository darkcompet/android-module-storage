/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */

package tool.compet.storage;

/**
 * This is singleton class, is combination of LruCache and DiskLruCache.
 * <p>
 * When you have a big amount of data to cache, this will store it in memory first
 * as possible until given momery-limit-up (MLU) via LruCache. For remained data which can't
 * store in memory since MLU, this will store them into internal storage via DiskLruCache.
 * <p>
 * Note that, each data (inside snapshot) will have own priority to be kept in memory. Lower
 * priority will be popped and stored into disk when MLU happen.
 */
public class DkDualCache {
}
