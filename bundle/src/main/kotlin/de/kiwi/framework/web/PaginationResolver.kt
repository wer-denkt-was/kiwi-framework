package de.kiwi.framework.web

import de.kiwi.framework.KiwiFrameworkProperties
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.domain.Sort.Order
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.paramOrNull

private const val SORT_PROPERTY_DELIMITER = ","

class PaginationResolver(private val props: KiwiFrameworkProperties.PaginationProperties) {
    private val defaultPage = 0
    private val defaultSize = props.defaultPageSize
    private val maxSize = props.maxPageSize

    fun resolvePagination(request: ServerRequest): Pageable {
        val page = request.paramOrNull(props.pageNumberParameter)?.toIntOrNull() ?: defaultPage
        val size = request.paramOrNull(props.pageSizeParameter)?.toIntOrNull() ?: defaultSize
        val sortParams = request.params()[props.sortParameter] ?: emptyList()

        val sort = sortParams.takeIf { it.isNotEmpty() }?.let { createSort(it) } ?: Sort.unsorted()
        val pageable = createPageable(page, size)

        return when {
            sort.isUnsorted -> pageable
            pageable.isPaged -> PageRequest.of(page, size, sort)
            else -> Pageable.unpaged(sort)
        }
    }

    private fun createPageable(pageValue: Int, sizeValue: Int): Pageable {
        val page = pageValue.coerceAtLeast(0)
        val size = if (sizeValue < 1) defaultSize else sizeValue.coerceAtMost(maxSize)
        return PageRequest.of(page, size)
    }

    private fun createSort(values: List<String?>): Sort {
        // Bail if a single empty parameter like "sort=" is given
        values.singleOrNull()?.let { singleValue -> if (singleValue.isEmpty()) return Sort.unsorted() }

        // Parse "{property},{direction}" or "{property}" into Order items
        val orders = values.filterNotNull().mapNotNull { value ->
            val parts = value.split(SORT_PROPERTY_DELIMITER, limit = 2).filter { it.notOnlyDots() }
            val property = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val direction = parts.getOrNull(1)?.let { Sort.Direction.fromString(it) }
            property?.let { Order(direction, it) }
        }

        return if (orders.isEmpty()) Sort.unsorted()
        else Sort.by(orders)
    }
}

private fun String.notOnlyDots() = replace(".", "").isNotBlank()
