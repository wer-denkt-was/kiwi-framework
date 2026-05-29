package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.domain.Validation
import de.kiwi.api.schema.EntityFieldset
import de.kiwi.api.schema.Fieldset
import de.kiwi.api.schema.FieldsetValidation
import de.kiwi.api.schema.FieldsetValidationService
import de.kiwi.framework.KiwiFrameworkProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class FieldsetValidationConfiguration(private val props: KiwiFrameworkProperties) {

    @Bean
    fun fieldsetValidationService(
        fieldsetValidationProvider: ObjectProvider<FieldsetValidation>,
    ): FieldsetValidationService {
        val fieldsetValidations = fieldsetValidationProvider.toList()
        return FieldsetValidationService(fieldsetValidations)
    }

    @Bean
    @ConditionalOnProperty(prefix = "kiwi.fieldset-validation", name = ["no-cycles"], matchIfMissing = true)
    fun nonRecursiveFieldsetValidation(): FieldsetValidation {
        return NonRecursiveFieldsetValidation()
    }

    @Bean
    fun maxDepthFieldsetValidation(): FieldsetValidation {
        return MaxDepthFieldsetValidation(maxDepth = props.fieldsetValidation.maxDepth)
    }
}

private typealias FieldsetValidationResult = Validation.Result<Fieldset<*>>

private inline fun FieldsetValidationResult.ifInvalid(block: (Validation.Result.Invalid<Fieldset<*>>) -> Unit) {
    if (this is Validation.Result.Invalid<Fieldset<*>>) {
        block(this)
    }
}

internal class MaxDepthFieldsetValidation(private val maxDepth: Int) : FieldsetValidation {
    override val order = 0

    override fun validate(value: Fieldset<*>): FieldsetValidationResult {
        // terminate validation if max-depth is negative (no limit)
        return if (maxDepth < 0) Validation.valid(value)
        else validateMaxDepth(fieldset = value, depth = 0)
    }

    private fun validateMaxDepth(fieldset: Fieldset<*>, depth: Int): FieldsetValidationResult {
        when { // check for exit conditions
            depth > maxDepth -> return Validation.invalid("Max depth of $maxDepth exceeded")
            fieldset !is EntityFieldset<*> -> return Validation.valid(fieldset) // no more relations
        }

        // recursively check depth of relations
        fieldset.toOne.forEach { (_, targetFieldset) ->
            // to-one relations don't increase depth, as they are integrated into a single SQL query
            validateMaxDepth(targetFieldset, depth = depth).ifInvalid { return it }
        }
        fieldset.toMany.forEach { (_, targetFieldset) ->
            validateMaxDepth(targetFieldset, depth = depth + 1).ifInvalid { return it }
        }
        fieldset.toManyMap.forEach { (_, targetFieldsets) ->
            validateMaxDepth(targetFieldsets.first, depth = depth + 1).ifInvalid { return it }
            validateMaxDepth(targetFieldsets.second, depth = depth + 1).ifInvalid { return it }
        }
        return Validation.valid(fieldset)
    }
}

internal class NonRecursiveFieldsetValidation : FieldsetValidation {
    override val order = 1

    override fun validate(value: Fieldset<*>) =
        if (value !is EntityFieldset<*>) Validation.valid(value)
        else findRecursiveCycle(fieldset = value, visited = VisitedPath())
            ?.let { cycle -> Validation.invalid(reason = "Cycle in fieldset: $cycle") }
            ?: Validation.valid(value)

    private fun findRecursiveCycle(fieldset: Fieldset<*>, visited: VisitedPath): CyclicPath? {
        // terminate search if fieldset does not have children
        if (fieldset !is EntityFieldset<*>) {
            return null
        }

        // collect all to-one/to-many relations included in fieldset
        val relations = sequence {
            yieldAll(fieldset.toOne.map { (field, targetFieldset) -> field to targetFieldset })
            yieldAll(fieldset.toMany.map { (field, targetFieldset) -> field to targetFieldset })
            yieldAll(fieldset.toManyMap.map { (field, targetFieldsets) -> field to targetFieldsets.first })
            yieldAll(fieldset.toManyMap.map { (field, targetFieldsets) -> field to targetFieldsets.second })
        }

        // recursively search for cyclic relations
        return relations.firstNotNullOfOrNull { (field, targetFieldset) ->
            val item = PathItem(field.parentSchema.entityClass, field.fieldName)
            if (item in visited) (visited + item).cycle(targetFieldset)
            else findRecursiveCycle(targetFieldset, visited + item)
        }
    }

    private data class PathItem(val entityType: Class<*>, val fieldName: String) {
        override fun toString() = "${entityType.simpleName}.$fieldName ->"
    }

    private data class VisitedPath(val items: List<PathItem> = emptyList()) {
        fun cycle(targetFieldset: Fieldset<*>) = CyclicPath(this, targetFieldset.entityType)
        operator fun plus(item: PathItem): VisitedPath = copy(items = items + item)
        operator fun contains(item: PathItem) = item in items
        override fun toString() = items.joinToString(separator = " ")
    }

    private data class CyclicPath(val visited: VisitedPath, val finalEntityType: Class<*>) {
        override fun toString() = "$visited ${finalEntityType.simpleName}"
    }
}
