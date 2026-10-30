package de.kiwi.framework.web

import de.kiwi.framework.KiwiFrameworkProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class PaginationResolverTest {
    private val props = KiwiFrameworkProperties.PaginationProperties(defaultPageSize = 20, maxPageSize = 100)
    private val resolver = PaginationResolver(props)

    private fun resolve(vararg params: Pair<String, String>): Any {
        val request = MockHttpServletRequest("GET", "/entity")
        params.forEach { (name, value) -> request.addParameter(name, value) }
        return resolver.resolvePagination(ServerRequest.create(request, emptyList()))
    }

    @Test
    fun coercePageAndSize() {
        assertEquals(PageRequest.of(0, 20), resolve())
        assertEquals(PageRequest.of(0, 100), resolve("_page" to "-1", "_size" to "1000"))
        assertEquals(PageRequest.of(2, 20), resolve("_page" to "2", "_size" to "0"))
    }

    @Test
    fun coercePageAndSizeWithSort() {
        val sort = Sort.by(Sort.Order.desc("title"), Sort.Order.asc("id"))
        assertEquals(PageRequest.of(0, 20, sort), resolve("_sort" to "title,desc", "_sort" to "id"))
        assertEquals(
            PageRequest.of(0, 100, Sort.by("title")),
            resolve("_page" to "-1", "_size" to "1000", "_sort" to "title"),
        )
        assertEquals(PageRequest.of(1, 20, Sort.by("title")), resolve("_page" to "1", "_size" to "0", "_sort" to "title"))
    }
}
