package de.kiwi.framework.support

import tools.jackson.core.TreeNode
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.jsonFormatVisitors.*
import de.kiwi.api.domain.EntityId
import de.kiwi.framework.support.FieldsetSourceRenderer.FieldType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf

private const val TS_STRING = "string"
private const val TS_NUMBER = "number"
private const val TS_BOOLEAN = "boolean"
private const val TS_UNKNOWN = "unknown"
private const val TS_OBJECT = "object"
private const val TS_NEVER = "never"
private const val TS_NULL = "null"

// Helper class for rendering Kotlin/Java types as TypeScript source code
class TypescriptTypeRenderer(
    private val options: TypescriptOptions,
    private val objectMapper: ObjectMapper,
    private val inlineInterfaceProvider: InlineInterfaceProvider,
) {
    private val idTypeAliasesToDeclare = HashSet<IdTypeAlias>()
    private val declaredIdTypeAliases = HashMap<String, IdTypeAlias>()

    private data class IdTypeAlias(val typeAlias: String, val idType: String)

    fun interface InlineInterfaceProvider {
        fun provideInlineInterface(block: FieldsetSourceRenderer.InterfaceBuilder.() -> Unit): String
    }

    fun createPreambleTypes() = sequence {
        if (options.useNominalIdTypes) {
            // Nominal ID types
            val defaultType = options.defaultIdValueType
            yield("declare const idType: unique symbol")
            yield("export type IdType<ID extends string, T = $defaultType> = T & { [idType]: ID }")
            yield("")
        }
    }

    fun createIdAliasTypes() = sequence {
        if (options.useNominalIdTypes) {
            // Render queued idType aliases, make aliases available for later declarations
            idTypeAliasesToDeclare.forEach { idTypeAlias ->
                val (alias, idType) = idTypeAlias
                declaredIdTypeAliases[idType] = idTypeAlias
                yield("export type $alias = $idType")
            }
            if (idTypeAliasesToDeclare.isNotEmpty()) {
                idTypeAliasesToDeclare.clear()
                yield("")
            }
        }
    }

    fun createTypeDeclaration(fieldType: FieldType): String {
        return when (fieldType) {
            is FieldType.Named -> "I" + fieldType.typeName // Prepend "IFoo" prefix, per TypeScript convention
            is FieldType.Leaf -> javaTypeDeclaration(fieldType.leafType)
            is FieldType.IdType -> entityIdTypeDeclaration(fieldType.idType, fieldType.valueType)
            is FieldType.Array -> arrayTypeDeclaration(fieldType.itemType)
            is FieldType.Pair -> tupleTypeDeclaration(fieldType.firstType, fieldType.secondType)
            is FieldType.Nullable -> nullableTypeDeclaration(fieldType.innerType)
            is FieldType.Union -> unionTypeDeclaration(fieldType.unionTypes)
            is FieldType.Literal -> "\"${fieldType.literal}\"" // quick and dirty, but only used by class names
            is FieldType.Inline -> inlineInterfaceProvider.provideInlineInterface { fieldType.build(this) }
        }
    }

    fun javaTypeDeclaration(javaClass: Class<*>): String {
        val javaType = objectMapper.typeFactory.constructType(javaClass)
        return javaTypeDeclaration(javaType)
    }

    fun javaTypeDeclaration(javaType: JavaType): String {
        val formatVisitor = TypescriptJsonFormatVisitor()
        objectMapper.acceptJsonFormatVisitor(javaType, formatVisitor)
        return formatVisitor.result()
    }

    private fun arrayTypeDeclaration(itemType: FieldType): String {
        val itemType = createTypeDeclaration(itemType)
        return createArrayType(itemType)
    }

    private fun createArrayType(itemType: String) = when {
        options.readOnlyArrays -> "ReadonlyArray<$itemType>"
        else -> "Array<$itemType>"
    }

    private fun tupleTypeDeclaration(vararg elementTypes: FieldType): String {
        return createTupleType(elementTypes.map { createTypeDeclaration(it) })
    }

    private fun createTupleType(elementTypes: Iterable<String>): String {
        val elementList = elementTypes.joinToString(separator = ", ")
        return when {
            options.readOnlyArrays -> "readonly [${elementList}]"
            else -> "[${elementList}]"
        }
    }

    private fun nullableTypeDeclaration(innerType: FieldType): String {
        return createNullableType(createTypeDeclaration(innerType))
    }

    private fun createNullableType(innerType: String): String {
        return "($TS_NULL | $innerType)"
    }

    private fun unionTypeDeclaration(types: List<FieldType>): String {
        return createUnionType(types.map { createTypeDeclaration(it) })
    }

    private fun createUnionType(types: Iterable<String>): String {
        val distinctTypes = (types as? Set<String>) ?: types.distinct()
        return when (distinctTypes.size) {
            0 -> TS_NEVER
            1 -> distinctTypes.first()
            else -> distinctTypes.joinToString(separator = " | ", prefix = "(", postfix = ")")
        }
    }

    private fun entityIdTypeDeclaration(idType: Class<*>, valueType: Class<*> = String::class.java): String {
        val idValueType = javaTypeDeclaration(valueType)
        if (!options.useNominalIdTypes) {
            return idValueType
        }

        val idTypeBrand = idType.simpleName
        val idTypeDeclaration = when (idValueType) {
            options.defaultIdValueType -> "IdType<\"$idTypeBrand\">"
            else -> "IdType<\"$idTypeBrand\", $idValueType>"
        }

        // Use an existing alias for the ID type, or queue a new alias to be rendered if none is available
        val idTypeAlias = declaredIdTypeAliases.computeIfAbsent(idTypeDeclaration) {
            IdTypeAlias(typeAlias = idTypeBrand, idType = idTypeDeclaration).also { idTypeAlias ->
                idTypeAliasesToDeclare.add(idTypeAlias)
            }
        }
        return idTypeAlias.typeAlias
    }

    private inline fun JsonShape.map(crossinline mapper: (String) -> String) = JsonShape { mapper(result()) }

    private fun JsonShape.wrapInArray() = map { createArrayType(it) }

    private fun JsonShape.wrapNullable() = map { createNullableType(it) }

    // Serialization context is assigned by ObjectMapper.acceptJsonFormatVisitor
    private inner class TypescriptJsonFormatVisitor : JsonFormatVisitorWrapper.Base() {
        fun result() = shape.result()

        private var shape: JsonShape = JsonShape.Simple(TS_UNKNOWN)

        private fun recursiveShapeOf(subType: JavaType): JsonShape {
            val subVisitor = TypescriptJsonFormatVisitor()
            objectMapper.acceptJsonFormatVisitor(subType, subVisitor)
            return subVisitor.shape
        }

        private fun String.toSimpleShape() = JsonShape.Simple(this)
        private inline fun <reified T> JavaType.isSubtypeOf() = rawClass.kotlin.isSubclassOf(T::class)
        private inline fun <reified T> JavaType.isSuperclassOf() = rawClass.kotlin.isSuperclassOf(T::class)

        override fun expectAnyFormat(type: JavaType): Nothing? {
            shape = JsonShape.Simple(
                type = when {
                    type.isSuperclassOf<Any>() -> TS_OBJECT // Arbitrary object
                    type.isSubtypeOf<TreeNode>() -> TS_UNKNOWN // Unknown JSON type
                    type.isSubtypeOf<EntityId>() -> entityIdTypeDeclaration(type.rawClass) // EntityID type
                    else -> TS_UNKNOWN // Unknown type
                }
            )
            return null
        }

        override fun expectStringFormat(type: JavaType): JsonStringFormatVisitor {
            // Assume simple sting by default
            shape = TS_STRING.toSimpleShape()

            // Refine string-type if an enum is serialized
            return object : JsonStringFormatVisitor.Base() {
                override fun enumTypes(enums: Set<String>) {
                    shape = JsonShape.Enum(enums)
                }
            }
        }

        override fun expectNumberFormat(type: JavaType): Nothing? {
            shape = TS_NUMBER.toSimpleShape()
            return null
        }

        override fun expectIntegerFormat(type: JavaType): Nothing? {
            shape = TS_NUMBER.toSimpleShape()
            return null
        }

        override fun expectBooleanFormat(type: JavaType): Nothing? {
            shape = TS_BOOLEAN.toSimpleShape()
            return null
        }

        override fun expectNullFormat(type: JavaType): Nothing? {
            shape = TS_NULL.toSimpleShape()
            return null
        }

        override fun expectArrayFormat(type: JavaType): JsonArrayFormatVisitor {
            // Assume array of arbitrary type by default
            shape = TS_UNKNOWN.toSimpleShape().wrapInArray()

            // Refine array-type if item-type is known
            return object : JsonArrayFormatVisitor.Base(context) {
                override fun itemsFormat(handler: JsonFormatVisitable, elementType: JavaType) {
                    val itemTypeShape = recursiveShapeOf(elementType)
                    shape = itemTypeShape.wrapInArray()
                }

                override fun itemsFormat(format: JsonFormatTypes) {
                    val itemType = when (format) {
                        JsonFormatTypes.STRING -> TS_STRING
                        JsonFormatTypes.NUMBER -> TS_NUMBER
                        JsonFormatTypes.INTEGER -> TS_NUMBER
                        JsonFormatTypes.BOOLEAN -> TS_BOOLEAN
                        JsonFormatTypes.OBJECT -> TS_OBJECT
                        JsonFormatTypes.ARRAY -> createArrayType(TS_UNKNOWN)
                        JsonFormatTypes.NULL -> TS_NUMBER
                        JsonFormatTypes.ANY -> TS_UNKNOWN
                    }
                    shape = itemType.toSimpleShape().wrapInArray()
                }
            }
        }

        override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor {
            // Empty object by default, properties are added by object-format visitor
            val required = mutableSetOf<String>()
            val properties = mutableMapOf<String, JsonShape>()
            shape = JsonShape.Object(properties, required)

            // Refine object-type if object properties are known, by mutating referenced required/properties collections
            return object : JsonObjectFormatVisitor.Base(context) {
                override fun property(prop: BeanProperty) =
                    addProperty(prop.name, prop.type, isRequired = true)

                override fun property(name: String, handler: JsonFormatVisitable, propertyType: JavaType) =
                    addProperty(name, propertyType, isRequired = true)

                override fun optionalProperty(prop: BeanProperty) =
                    addProperty(prop.name, prop.type, isRequired = false)

                override fun optionalProperty(name: String, handler: JsonFormatVisitable, propertyType: JavaType) =
                    addProperty(name, propertyType, isRequired = false)

                private fun addProperty(name: String, valueType: JavaType, isRequired: Boolean) {
                    val valueShape = recursiveShapeOf(valueType)
                    if (isRequired) {
                        properties[name] = valueShape
                        required.add(name)
                    } else {
                        properties[name] = valueShape.wrapNullable()
                    }
                }
            }
        }

        override fun expectMapFormat(type: JavaType): JsonMapFormatVisitor {
            var keyShape: JsonShape = TS_STRING.toSimpleShape()
            var valueShape: JsonShape = TS_UNKNOWN.toSimpleShape()
            shape = JsonShape.Record(keyShape, valueShape)

            return object : JsonMapFormatVisitor.Base(context) {
                override fun keyFormat(handler: JsonFormatVisitable, keyType: JavaType) {
                    keyShape = recursiveShapeOf(keyType)
                    shape = JsonShape.Record(keyShape, valueShape)
                }

                override fun valueFormat(handler: JsonFormatVisitable, valueType: JavaType) {
                    valueShape = recursiveShapeOf(valueType)
                    shape = JsonShape.Record(keyShape, valueShape)
                }
            }
        }
    }
}

// Helper class representing the shape of a JSON data structure.
// Evaluation must be lazy to construct final source-code content *after* evaluation by visitor is completed!
private fun interface JsonShape {
    fun result(): String

    data class Simple(val type: String) : JsonShape {
        override fun result() = type
    }

    data class Enum(val entries: Iterable<String>) : JsonShape {
        override fun result(): String {
            return entries.joinToString(separator = " | ", prefix = "(", postfix = ")") { "\"${it}\"" }
        }
    }

    data class Object(val properties: Map<String, JsonShape>, val required: Set<String>) : JsonShape {
        override fun result(): String {
            return if (properties.isEmpty()) TS_UNKNOWN // No properties -> Unknown object type
            else properties.entries.joinToString(separator = ", ", prefix = "{ ", postfix = " }") { entry ->
                if (entry.key in required) "${entry.key}: ${entry.value.result()}"
                else "${entry.key}?: ${entry.value.result()}"
            }
        }
    }

    data class Record(val keyType: JsonShape, val valueType: JsonShape) : JsonShape {
        override fun result() = "Record<${keyType.result()}, ${valueType.result()}>"
    }
}
