package de.kiwi.framework.support

internal class ClassHierarchyIndex(classes: Iterable<Class<*>>) {
    private val children = mutableMapOf<Class<*>, MutableSet<Class<*>>>()
    private val descendantsCache = mutableMapOf<Class<*>, MutableSet<Class<*>>>()

    init {
        // Leaves
        classes.forEach { clazz ->
            children[clazz] = mutableSetOf()
        }
        // Graph edges
        classes.forEach { clazz ->
            sequence {
                clazz.superclass?.let { yield(it) }
                yieldAll(clazz.interfaces.iterator())
            }.forEach { superClazz ->
                children.computeIfAbsent(superClazz) { mutableSetOf() }.add(clazz)
            }
        }
    }

    fun allSubclassesOf(baseClazz: Class<*>): Set<Class<*>> {
        return descendantsCache.computeIfAbsent(baseClazz) {
            mutableSetOf<Class<*>>().also { results ->
                val stack = ArrayDeque<Class<*>>()
                children[baseClazz]?.forEach { stack.addLast(it) }

                while (stack.isNotEmpty()) {
                    val last = stack.removeLast()
                    if (results.add(last)) {
                        children[last]?.forEach { stack.addLast(it) }
                    }
                }
            }
        }
    }
}
