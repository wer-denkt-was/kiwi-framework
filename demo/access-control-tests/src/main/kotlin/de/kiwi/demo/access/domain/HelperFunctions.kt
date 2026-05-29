package de.kiwi.demo.access.domain

inline fun <T> T.changeTo(newValue: T, ifChanged: (before: T, after: T) -> Unit): T {
    if (this != newValue) {
        ifChanged(this, newValue)
    }
    return newValue
}

inline fun <T> MutableCollection<T>.addOrRemove(
    item: T,
    present: Boolean,
    ifChanged: (modified: Collection<T>) -> Unit
) {
    val changed = if (present) add(item) else remove(item)
    if (changed) ifChanged(this)
}
