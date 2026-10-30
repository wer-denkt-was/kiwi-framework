package de.kiwi.api.util

private const val DEFAULT_SPLIT = ','
private const val DEFAULT_ESCAPE = '\\'

/**
 * For each parameter, if a single value is passed, split that value using [splitWithEscape].
 * Otherwise, parameter values are returned as-is.
 */
fun Map<String, List<String>>.splitMultiValueParameters(
    split: Char = DEFAULT_SPLIT,
    escape: Char = DEFAULT_ESCAPE,
): Map<String, List<String>> =
    mapValues { (_, values) -> values.splitMultiValueParameters(split, escape) }

/**
 * If a single value is passed, split that value using [splitWithEscape].
 * Otherwise, the collection is returned unmodified as a list.
 */
fun Collection<String>.splitMultiValueParameters(
    split: Char = DEFAULT_SPLIT,
    escape: Char = DEFAULT_ESCAPE,
): List<String> = when (size) {
    0 -> emptyList()
    1 -> first().splitWithEscape(split, escape)
    else -> toList()
}

// https://www.baeldung.com/java-split-string-commas
fun String.splitWithEscape(
    split: Char = DEFAULT_SPLIT,
    escape: Char = DEFAULT_ESCAPE,
): List<String> {
    val tokens = ArrayList<String>()
    val buffer = StringBuilder(length)
    var inEscape = false

    for (char in this) {
        if (inEscape) {
            inEscape = false
        } else if (char == escape) {
            inEscape = true
            continue
        } else if (char == split) {
            tokens.add(buffer.toString())
            buffer.setLength(0)
            continue
        }
        buffer.append(char)
    }
    // A trailing escape character doesn't escape anything, keep it as part of the last token
    if (inEscape) {
        buffer.append(escape)
    }
    tokens.add(buffer.toString())

    return tokens
}
