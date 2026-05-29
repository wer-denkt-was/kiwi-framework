package de.kiwi.framework.web

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainException
import de.kiwi.api.schema.*
import de.kiwi.framework.grammar.DynamicFieldsetGrammarLexer
import de.kiwi.framework.grammar.DynamicFieldsetGrammarParser
import de.kiwi.framework.grammar.DynamicFieldsetGrammarParser.*
import org.antlr.v4.runtime.*

/**
 * Helper class for parsing a list of comma-separated field names into a dynamic [Fieldset].
 * For example, a string like `"a,b,c"` would be parsed as a fieldset containing three fields.
 * Field names for columns are simple case-insensitive strings matching the given column fieldName.
 * Field names for to-one or to-many relations are either simple strings, in which case the
 * default fieldset of the referenced schema will be queried, or include the referenced fieldset
 * as `"<relation>:<fieldset>"`.
 *
 * Field names for to-many map relations are either simple strings, in which case the default
 * key- and value-fieldset of the referenced key- and value-schemata will be queried, or include either
 * the referenced value fieldset as `"<relation>:<value-fieldset>"`, or include both the referenced value
 * and key fieldset as `"<relation>:<key-fieldset>:<value-fieldset>"`.
 *
 * A fieldset name reference for relations can either be the case-insensitive name of the referenced fieldset,
 * or can in turn be a dynamic fieldset specified as `"<relation>:(<fieldName>, <fieldName>, ...)"`.
 * If the field names within a relation's dynamic fieldset are themselves relations, they may contain
 * recursive fieldset references like `"<relation>:(<child>:<fieldset>)"` or `"<relation>:(<child>:(<grandchild>))"`.
 *
 * A single-column fieldset can be specified using `"!<field>"`. Single-column fieldsets are not returned as
 * objects with field names and values, but only the values of the single given field.
 * Fieldset references in relations can use single-value fieldsets as `"<relation>:(!<field>)"` or
 * `"<relation>:!<field>"`.
 *
 * For field names grammar, see ``antlr/de/kiwi/framework/grammar/DynamicFieldsetGrammar.g4``.
 *
 * @see DynamicFieldsetGrammarParser
 */
class DynamicFieldsetParser(
    private val provider: QuerySchemaProvider,
) {
    fun <E : Any> parseFieldset(
        fieldsets: FieldsetsAvailable<E>,
        fieldNames: String,
    ): Fieldset<E> {
        val fieldsetRef = parseFieldsString(fieldNames)
        return fieldsetRef.resolve(provider, fieldsets)
    }

    class FieldsetParseException(reason: String) : DomainException(
        status = Status.BadRequest,
        code = "parse-dynamic-fieldset",
        args = mapOf("reason" to reason),
    ) {
        override val severity = Auditable.Severity.Disabled // Don't audit log
    }
}

private data class FieldAndRefs(
    val fieldName: String,
    val keyFieldset: FieldsetRef,
    val valueFieldset: FieldsetRef,
)

private sealed interface FieldsetRef {
    fun <E : Any> resolve(provider: QuerySchemaProvider, fieldsets: FieldsetsAvailable<E>): Fieldset<E>

    data object Default : FieldsetRef {
        override fun <E : Any> resolve(provider: QuerySchemaProvider, fieldsets: FieldsetsAvailable<E>) =
            fieldsets.default
    }

    data class Named(val name: String) : FieldsetRef {
        override fun <E : Any> resolve(provider: QuerySchemaProvider, fieldsets: FieldsetsAvailable<E>) =
            fieldsets.require(name)
    }

    data class SingleColumn(val field: FieldAndRefs) : FieldsetRef {
        private fun isIncluded(name: String): Boolean =
            field.fieldName.equals(name, ignoreCase = true)

        override fun <E : Any> resolve(provider: QuerySchemaProvider, fieldsets: FieldsetsAvailable<E>): Fieldset<E> {
            val column = fieldsets.schema.columns.find { isIncluded(it.fieldName) }
                ?: throw IllegalArgumentException("Invalid single-column field: ${field.fieldName}")

            return column.columnFieldset()
        }
    }

    data class Dynamic(val fields: List<FieldAndRefs>) : FieldsetRef {
        private fun findIncluded(name: String): FieldAndRefs? =
            fields.find { it.fieldName.equals(name, ignoreCase = true) }

        override fun <E : Any> resolve(provider: QuerySchemaProvider, fieldsets: FieldsetsAvailable<E>): Fieldset<E> {
            fun <R : Any> requireFieldsets(entityType: Class<R>): FieldsetsAvailable<R> {
                val typeName = entityType.simpleName
                val schema = requireNotNull(provider.findEntitySchema(entityType)) { "Missing EntitySchema<$typeName>" }
                return provider.findAvailableFieldsets(entityType) ?: implicitFieldsetsAvailable(schema)
            }

            val schema = fieldsets.schema

            val columns = schema.columns.filter { findIncluded(it.fieldName) != null }

            val toOne = schema.toOneReferences.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                findIncluded(it.fieldName)?.let { (_, _, ref) ->
                    val entityType = it.toOne.entityClass
                    it to ref.resolve(provider, requireFieldsets(entityType))
                } as Pair<ToOneField<E, Any>, Fieldset<Any>>?
            }

            val toMany = schema.toManyReferences.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                findIncluded(it.fieldName)?.let { (_, _, ref) ->
                    val entityType = it.toMany.entityClass
                    it to ref.resolve(provider, requireFieldsets(entityType))
                } as Pair<ToManyField<E, *, Any, *>, Fieldset<Any>>?
            }

            val toManyMap = schema.toManyMapReferences.mapNotNull {
                @Suppress("UNCHECKED_CAST")
                findIncluded(it.fieldName)?.let { (_, keyRef, valRef) ->
                    val keyType = it.toManyKeys.entityClass
                    val valType = it.toManyValues.entityClass
                    val keyFieldset = keyRef.resolve(provider, requireFieldsets(keyType))
                    val valFieldset = valRef.resolve(provider, requireFieldsets(valType))
                    it to (keyFieldset to valFieldset)
                } as Pair<ToManyMapField<E, Any, Any, *, *>, FieldsetPair<Any, Any>>?
            }

            return schema.buildFieldset {
                include(columns)
                toOne.forEach { (ref, fs) -> includeToOne(ref, fs) }
                toMany.forEach { (ref, fs) -> includeToMany(ref, fs) }
                toManyMap.forEach { (ref, fs) -> includeToMany(ref, fs) }
            }
        }
    }
}

private fun <E : Any> implicitFieldsetsAvailable(schema: EntitySchema<E>): FieldsetsAvailable<E> {
    return object : FieldsetsAvailable<E>(schema) {
        override val default: Fieldset<E> = schema.implicitDefaultFieldset()
    }
}

private object FieldsetParserErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException,
    ): Nothing {
        throw DynamicFieldsetParser.FieldsetParseException(msg)
    }
}

private fun parseFieldsString(fields: String): FieldsetRef {
    val lexer = DynamicFieldsetGrammarLexer(CharStreams.fromString(fields))
    lexer.addErrorListener(FieldsetParserErrorListener)

    val parser = DynamicFieldsetGrammarParser(CommonTokenStream(lexer))
    parser.addErrorListener(FieldsetParserErrorListener)

    val rootFieldset = parser.root()!!.fieldset()!!
    return compileFieldsetAst(rootFieldset)
}

private fun compileFieldsetAst(fieldset: FieldsetContext): FieldsetRef {
    return when (fieldset) {
        is EntityFieldsetContext -> compileEntityFieldsetAst(fieldset)
        is SingleColumnFieldsetContext -> compileSingleColumnAst(fieldset)
        else -> throw AssertionError(fieldset.javaClass.name)
    }
}

private fun compileEntityFieldsetAst(entityFieldset: EntityFieldsetContext): FieldsetRef {
    val fields = entityFieldset.field()?.map { compileFieldAst(it) } ?: emptyList()
    return FieldsetRef.Dynamic(fields)
}

private fun compileSingleColumnAst(singleColumn: SingleColumnFieldsetContext): FieldsetRef {
    val field = compileFieldAst(singleColumn.field())
    return FieldsetRef.SingleColumn(field)
}

private fun compileFieldAst(field: FieldContext): FieldAndRefs = when (field) {
    is SimpleFieldContext -> FieldAndRefs(
        fieldName = field.name.text,
        keyFieldset = FieldsetRef.Default,
        valueFieldset = FieldsetRef.Default
    )

    is RefFieldContext -> FieldAndRefs(
        fieldName = field.name.text,
        keyFieldset = FieldsetRef.Default,
        valueFieldset = compileFieldsetRefAst(field.ref)
    )

    is KeyValFieldContext -> FieldAndRefs(
        fieldName = field.name.text,
        keyFieldset = compileFieldsetRefAst(field.key),
        valueFieldset = compileFieldsetRefAst(field.`val`)
    )

    else -> throw AssertionError(field.javaClass.name)
}

private fun compileFieldsetRefAst(ref: ReferenceContext): FieldsetRef = when (ref) {
    is EmptyRefContext -> FieldsetRef.Default
    is NameRefContext -> FieldsetRef.Named(ref.name.text)
    is DynamicRefContext -> ref.fieldset()?.let { compileFieldsetAst(it) } ?: FieldsetRef.Default
    is SingleColumnRefContext -> FieldsetRef.SingleColumn(compileFieldAst(ref.field()))
    else -> throw AssertionError(ref.javaClass.name)
}
