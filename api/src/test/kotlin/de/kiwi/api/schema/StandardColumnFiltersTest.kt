package de.kiwi.api.schema

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class StandardColumnFiltersTest {
    private val basicOperators = arrayOf("eq", "ne", "in", "not", "null")
    private val standardOperators = arrayOf(*basicOperators, "gt", "ge", "lt", "le")
    private val patternMatchOperators = arrayOf(*standardOperators, "contains", "startsWith")

    private fun assertBasicFilterOperators(set: ColumnFilterSet) {
        assertSame(set.defaultOperator, InListOperator)
        assertSame(set["eq"], EqualToOperator)
        assertSame(set["ne"], NotEqualOperator)
        assertSame(set["in"], InListOperator)
        assertSame(set["not"], NotInListOperator)
        assertSame(set["null"], IsNullOperator)
    }

    private fun assertStandardFilterOperators(set: ColumnFilterSet) {
        assertBasicFilterOperators(set)
        assertSame(set["gt"], GreaterThanOperator)
        assertSame(set["ge"], GreaterOrEqualOperator)
        assertSame(set["lt"], LessThanOperator)
        assertSame(set["le"], LessOrEqualOperator)
    }

    private fun assertPatternFilterOperators(set: ColumnFilterSet) {
        assertStandardFilterOperators(set)
        assertSame(set["contains"], StringContainsOperator)
        assertSame(set["startsWith"], StringStartsWithOperator)
    }

    @Test
    fun basicColumnFilterOperators() {
        assertThat(StandardColumnFilters.basic.operators, containsInAnyOrder(*basicOperators))
        assertBasicFilterOperators(StandardColumnFilters.basic)
    }

    @Test
    fun standardColumnFilterOperators() {
        assertThat(StandardColumnFilters.default.operators, containsInAnyOrder(*standardOperators))
        assertStandardFilterOperators(StandardColumnFilters.default)
    }

    @Test
    fun patternMatchFilterOperators() {
        assertThat(StandardColumnFilters.patternMatching.operators, containsInAnyOrder(*patternMatchOperators))
        assertPatternFilterOperators(StandardColumnFilters.patternMatching)
    }
}
