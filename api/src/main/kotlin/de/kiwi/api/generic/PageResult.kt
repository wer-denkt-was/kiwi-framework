package de.kiwi.api.generic

import org.springframework.data.domain.Page as SpringDataPage

/**
 * Representation of one page of values returned from a query.
 * @param T Item type contained in the page
 */
data class PageResult<T>(
    val items: List<T>,
    val pageSize: Int,
    val pageNumber: Int,
    val totalPages: Int,
    val totalItems: Long,
) : Iterable<T> by items {
    companion object {
        fun <T : Any> fromPage(page: SpringDataPage<T>) = PageResult<T>(
            items = page.content,
            pageSize = page.size,
            pageNumber = page.number,
            totalPages = page.totalPages,
            totalItems = page.totalElements,
        )
    }

    fun <R> map(mapper: (T) -> R) = withItems(items.map(mapper))

    fun <R> withItems(items: List<R>) = PageResult(
        items = items,
        pageSize = pageSize,
        pageNumber = pageNumber,
        totalPages = totalPages,
        totalItems = totalItems,
    )
}
