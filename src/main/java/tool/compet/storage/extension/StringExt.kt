package tool.compet.storage.extension

internal fun String.removeForbiddenCharsFromFilenameDk(): String = replace(":", "_").replaceCompletelyDk("//", "/")

internal fun String.replaceCompletelyDk(oldValue: String, newValue: String) = let {
	var path = it
	do {
		path = path.replace(oldValue, newValue)
	}
	while (path.isNotEmpty() && path.contains(oldValue))

	path
}

internal fun String.hasParentDk(parentPath: String): Boolean {
	val parentTree = parentPath.split('/')
		.map { it.trim('/') }
		.filter { it.isNotEmpty() }

	val subFolderTree = split('/')
		.map { it.trim('/') }
		.filter { it.isNotEmpty() }

	return parentTree.size <= subFolderTree.size && subFolderTree.take(parentTree.size) == parentTree
}

/**
 * For eg,. given `Downloads/Video/Sports/` will produce array `["Downloads", "Video", "Sports"]`
 */
internal fun String.splitToDirsDk() = this.split('/').filterNot { it.isBlank() }

/**
 * Given the following text `abcdeabcjklab` and count how many `abc`, then should return 2.
 */
//internal fun String.countDk(pattern: String): Int {
//	var index = indexOf(pattern)
//	if (pattern.isEmpty() || index == -1) {
//		return 0
//	}
//	var count = 0
//
//	do {
//		count++
//		index = indexOf(pattern, startIndex = index + pattern.length)
//	}
//	while (index in 1 until length)
//
//	return count
//}