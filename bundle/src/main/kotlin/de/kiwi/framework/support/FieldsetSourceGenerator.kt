package de.kiwi.framework.support

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.type.TypeFactory
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityId
import de.kiwi.api.generic.GenericEntityCommand
import de.kiwi.api.schema.*
import de.kiwi.api.util.slf4j
import de.kiwi.framework.support.FieldsetSourceRenderer.*
import org.springframework.beans.BeanInstantiationException
import org.springframework.beans.BeanUtils
import java.lang.reflect.Modifier
import java.lang.reflect.Type

data class FieldsetSources(
    val entityClasses: List<Class<*>>,
    val entitySchemas: List<EntitySchema<*>>,
    val fieldsetAggregates: List<FieldsetsAvailable<*>>,
    val createCommandTypes: List<Class<out Command<*>>>,
    val updateCommandTypes: List<Class<out Command<*>>>,
    val genericCommandTypes: List<Class<out GenericEntityCommand<*>>>,
)

interface FieldsetSourceRenderer {
    fun moduleFileName(moduleName: String): String
    fun renderSourceFile(build: FileBuilder.() -> Unit): String

    interface FileBuilder {
        fun addDocComment(lines: List<String>)
        fun addLineComment(line: String)
        fun declarePreamble()
        fun declareInterface(name: String, build: InterfaceBuilder.() -> Unit)
        fun declareTypeAlias(name: String, aliasedType: Class<*>)
        fun declareFieldsetMapping(name: String, mapping: Map<String, String>)
    }

    interface InterfaceBuilder {
        fun declareField(fieldName: String, fieldType: FieldType, optional: Boolean, mutable: Boolean = false)
        fun declareFieldsOfType(beanType: Class<*>, mode: BeanMode)
        enum class BeanMode { Create, Update }
    }

    sealed interface FieldType {
        data class Leaf(val leafType: JavaType) : FieldType
        data class Named(val typeName: String) : FieldType
        data class Array(val itemType: FieldType) : FieldType
        data class Nullable(val innerType: FieldType) : FieldType
        data class Pair(val firstType: FieldType, val secondType: FieldType) : FieldType
        data class IdType(val idType: Class<out EntityId>, val valueType: Class<*>) : FieldType
        data class Inline(val build: InterfaceBuilder.() -> Unit) : FieldType
        data class Union(val unionTypes: List<FieldType>) : FieldType
        data class Literal(val literal: String) : FieldType
    }
}

private val log = slf4j<FieldsetSourceGenerator>()

private val jacksonTypeFactory = TypeFactory.defaultInstance()!!
private fun jacksonJavaType(type: Type) = jacksonTypeFactory.constructType(type)

internal class FieldsetSourceGenerator(
    private val moduleName: String,
    private val fileHeader: List<String>,
    private val renderer: FieldsetSourceRenderer,
) {
    data class GeneratedSource(
        val filename: String,
        val sourceCode: String,
    )

    fun generateSource(sources: FieldsetSources): GeneratedSource {
        val filename = renderer.moduleFileName(moduleName)

        val sortedEntitySchemas = sources.entitySchemas.sortedBy { schemaTypeName(it) }
        val sortedFieldsetAggregates = sources.fieldsetAggregates.sortedBy { schemaTypeName(it.schema) }
        val sortedCreateCommandTypes = sources.createCommandTypes.sortedBy { it.simpleName }
        val sortedUpdateCommandTypes = sources.updateCommandTypes.sortedBy { it.simpleName }
        val sortedGenericCommandTypes = sources.genericCommandTypes.sortedBy { it.simpleName }

        val entityClassHierarchy = ClassHierarchyIndex(sources.entityClasses)

        val sourceCode = renderer.renderSourceFile {
            addDocComment(fileHeader)
            declarePreamble()

            addLineComment("--- Entity Schema ---")
            sortedEntitySchemas.forEach { entitySchema ->
                generateSchemaType(
                    schema = entitySchema,
                    fieldsetAggregates = sources.fieldsetAggregates,
                    entityClassHierarchy = entityClassHierarchy
                )
            }

            addLineComment("--- Available Fieldsets ---")
            val fieldsetTypeNames = sources.fieldsetAggregates.flatMap { evaluateAllFieldsetTypeNames(it) }.toMap()
            sortedFieldsetAggregates.forEach { fieldsetAggregate ->
                generateAllFieldsetInterfaces(
                    fieldsets = fieldsetAggregate,
                    fieldsetTypeNames = fieldsetTypeNames,
                    entityClassHierarchy = entityClassHierarchy
                )
            }

            addLineComment("--- Entity Commands ---")
            sortedCreateCommandTypes.forEach { generateCommandInterface(it, InterfaceBuilder.BeanMode.Create) }
            sortedUpdateCommandTypes.forEach { generateCommandInterface(it, InterfaceBuilder.BeanMode.Update) }
            sortedGenericCommandTypes.forEach { generateGenericCommandInterface(it, entityClassHierarchy) }
        }

        return GeneratedSource(
            filename = filename,
            sourceCode = sourceCode,
        )
    }

    private fun FileBuilder.generateSchemaType(
        schema: EntitySchema<*>,
        fieldsetAggregates: List<FieldsetsAvailable<*>>,
        entityClassHierarchy: ClassHierarchyIndex,
    ) {
        declareInterface(schemaTypeName(schema)) {
            schema.columns.forEach { column ->
                declareField(
                    fieldName = column.fieldName,
                    fieldType = columnFieldType(column, entityClassHierarchy),
                    optional = true,
                )
            }
            schema.toOneReferences.forEach { toOne ->
                val fieldType = referencedSchemaFieldType(toOne.toOne, fieldsetAggregates)
                declareField(
                    fieldName = toOne.fieldName,
                    fieldType = if (toOne.nullable) FieldType.Nullable(fieldType) else fieldType,
                    optional = true,
                )
            }
            schema.toManyReferences.forEach { toMany ->
                declareField(
                    fieldName = toMany.fieldName,
                    fieldType = FieldType.Array(referencedSchemaFieldType(toMany.toMany, fieldsetAggregates)),
                    optional = true,
                )
            }
            schema.toManyMapReferences.forEach { toMany ->
                val keyFieldType = referencedSchemaFieldType(toMany.toManyKeys, fieldsetAggregates)
                val valueFieldType = referencedSchemaFieldType(toMany.toManyValues, fieldsetAggregates)
                declareField(
                    fieldName = toMany.fieldName,
                    fieldType = FieldType.Array(FieldType.Pair(keyFieldType, valueFieldType)),
                    optional = true
                )
            }
            schema.subtypeReferences.forEach { subtype ->
                val subSchemaType = referencedSchemaFieldType(subtype.subtypeSchema, fieldsetAggregates)
                declareField(
                    fieldName = subtype.fieldName,
                    fieldType = FieldType.Nullable(subSchemaType),
                    optional = false,
                    mutable = false,
                )
            }
        }
    }

    private fun columnFieldType(column: ColumnField<*, *, *>, entityClassHierarchy: ClassHierarchyIndex): FieldType {
        val fieldType = when (val columnType = column.type) {
            is EntityIdColumnType<*> -> FieldType.IdType(columnType.entityIdType, columnType.valueType)
            is EnumColumnType<*> -> FieldType.Leaf(jacksonJavaType(columnType.enumType))
            is EntityClassNameType if (column.selector is ColumnSelector.EntityTypeSelector<*>) ->
                entityClassNameFieldType(column.parentSchema.entityClass, columnType, entityClassHierarchy)

            is EntityDiscriminatorValueType if (column.selector is ColumnSelector.EntityTypeSelector<*>) ->
                entityClassNameFieldType(column.parentSchema.entityClass, columnType, entityClassHierarchy)

            else -> FieldType.Leaf(jacksonJavaType(columnType.valueType))
        }
        return if (column.nullable) FieldType.Nullable(fieldType)
        else fieldType
    }

    private fun entityClassNameFieldType(
        entityClass: Class<*>,
        classColumnType: ColumnType<Class<*>, String>,
        entityClassHierarchy: ClassHierarchyIndex,
    ): FieldType {
        val entitySubclasses = entityClassHierarchy.allSubclassesOf(entityClass)
        val allEntityClasses = setOf(entityClass) + entitySubclasses

        val allEntityClassValues = allEntityClasses
            .filterNot { it.isInterface }
            .filterNot { Modifier.isAbstract(it.modifiers) }
            .map { classColumnType.format(it) }
            .map { FieldType.Literal(it) }

        return FieldType.Union(allEntityClassValues)
    }

    private fun schemaTypeName(schema: AbstractSchema<*, *>): String {
        return schema.entityClass.simpleName
    }

    private fun referencedSchemaFieldType(
        referencedSchema: AbstractSchema<*, *>,
        fieldsetAggregates: List<FieldsetsAvailable<*>>,
    ): FieldType {
        val schemaType = when (referencedSchema) {
            is EntitySchema<*> -> FieldType.Named(schemaTypeName(referencedSchema))
            is SingleValueSchema<*, *> -> FieldType.Leaf(jacksonJavaType(referencedSchema.valueType))
        }

        val possibleReferencedFieldsets = fieldsetAggregates.find { it.schema == referencedSchema }
        val fieldsetTypes = possibleReferencedFieldsets?.allFieldsets?.mapNotNull {
            when (it) {
                is EntityFieldset -> null
                is SingleColumnFieldset<*, *, *> -> FieldType.Leaf(jacksonJavaType(it.singleColumn.type.valueType))
                is SingleValueFieldset<*, *> -> FieldType.Leaf(jacksonJavaType(it.schema.valueType))
            }
        }

        val possibleTypes = listOf(schemaType) + (fieldsetTypes ?: emptyList())
        return FieldType.Union(possibleTypes)
    }

    private fun FileBuilder.generateAllFieldsetInterfaces(
        fieldsets: FieldsetsAvailable<*>,
        fieldsetTypeNames: Map<Fieldset<*>, String>,
        entityClassHierarchy: ClassHierarchyIndex,
    ) {
        // Generate fieldset types
        fieldsets.allFieldsets.sortedBy { it.name }.forEach { fieldset ->
            val typeName = fieldsetTypeNames[fieldset]
            if (typeName == null) {
                log.warn("Illegal fieldset name in ${fieldset.javaClass.name}: ${fieldset.name}")
            } else when (fieldset) {
                is EntityFieldset -> generateFieldsetInterface(
                    typeName = typeName,
                    fieldset = fieldset,
                    fieldsetTypeNames = fieldsetTypeNames,
                    entityClassHierarchy = entityClassHierarchy
                )

                is SingleColumnFieldset<*, *, *> -> generateFieldsetTypeAlias(
                    typeName = typeName,
                    valueType = fieldset.singleColumn.type.valueType
                )

                is SingleValueFieldset<*, *> -> Unit // Ignore single-value fieldsets
            }
        }

        // Generate fieldsets mapping
        val fieldsetsMappingName = fieldsets.javaClass.simpleName
        val fieldsetsMapping = fieldsets.allFieldsets
            .mapNotNull { fieldsetTypeNames[it]?.let { typeName -> it.name to typeName } }
            .toMap()

        declareFieldsetMapping(fieldsetsMappingName, fieldsetsMapping)
    }

    private fun evaluateAllFieldsetTypeNames(
        fieldsets: FieldsetsAvailable<*>,
    ): List<Pair<Fieldset<*>, String>> =
        fieldsets.allFieldsets.mapNotNull { fieldset ->
            fieldsetTypeName(fieldset)
                ?.let { typeName -> fieldset to typeName }
        }

    private fun fieldsetTypeName(fieldset: Fieldset<*>): String? {
        if (!fieldset.name.isLegalIdentifier()) {
            return null
        }
        val entitySchema = when (fieldset) {
            is EntityFieldset -> fieldset.schema
            is SingleColumnFieldset<*, *, *> -> fieldset.schema
            is SingleValueFieldset<*, *> -> return null
        }
        val schemaName = schemaTypeName(entitySchema)
        val fieldsetName = fieldset.name.replaceFirstChar { it.titlecaseChar() }
        return schemaName + fieldsetName
    }

    private fun FileBuilder.generateFieldsetInterface(
        typeName: String,
        fieldset: EntityFieldset<*>,
        fieldsetTypeNames: Map<Fieldset<*>, String>,
        entityClassHierarchy: ClassHierarchyIndex,
    ) {
        declareInterface(typeName) {
            generateFieldsetMembers(fieldset, fieldsetTypeNames, entityClassHierarchy)
        }
    }

    private fun InterfaceBuilder.generateFieldsetMembers(
        fieldset: EntityFieldset<*>,
        fieldsetTypeNames: Map<Fieldset<*>, String>,
        entityClassHierarchy: ClassHierarchyIndex,
    ) {
        fieldset.columns.forEach { column ->
            declareField(
                fieldName = column.fieldName,
                fieldType = columnFieldType(column, entityClassHierarchy),
                optional = false,
            )
        }
        fieldset.toOne.forEach { (toOne, refFieldset) ->
            val fieldType = createTargetFieldsetType(refFieldset, fieldsetTypeNames, entityClassHierarchy)
            declareField(
                fieldName = toOne.fieldName,
                fieldType = if (toOne.nullable) FieldType.Nullable(fieldType) else fieldType,
                optional = toOne.nullable,
            )
        }
        fieldset.toMany.forEach { (toMany, itemFieldset) ->
            val itemFieldType = createTargetFieldsetType(itemFieldset, fieldsetTypeNames, entityClassHierarchy)
            val fieldType = FieldType.Array(itemFieldType)
            declareField(
                fieldName = toMany.fieldName,
                fieldType = fieldType,
                optional = false,
            )
        }
        fieldset.toManyMap.forEach { (toMany, entryFieldsets) ->
            val (keyFieldset, valueFieldset) = entryFieldsets
            val keyFieldType = createTargetFieldsetType(keyFieldset, fieldsetTypeNames, entityClassHierarchy)
            val valueFieldType = createTargetFieldsetType(valueFieldset, fieldsetTypeNames, entityClassHierarchy)
            val itemFieldType = FieldType.Pair(keyFieldType, valueFieldType)
            val fieldType = FieldType.Array(itemFieldType)
            declareField(
                fieldName = toMany.fieldName,
                fieldType = fieldType,
                optional = false,
            )
        }
        fieldset.subtypes.forEach { (subtype, subtypeFieldset) ->
            val subFieldType = createTargetFieldsetType(subtypeFieldset, fieldsetTypeNames, entityClassHierarchy)
            declareField(
                fieldName = subtype.fieldName,
                fieldType = FieldType.Nullable(subFieldType),
                optional = false,
                mutable = false,
            )
        }
    }

    private fun FileBuilder.generateFieldsetTypeAlias(typeName: String, valueType: Class<*>) {
        declareTypeAlias(typeName, valueType)
    }

    private fun createTargetFieldsetType(
        fieldset: Fieldset<*>,
        fieldsetTypeNames: Map<Fieldset<*>, String>,
        entityClassHierarchy: ClassHierarchyIndex,
    ): FieldType {
        // Shortcut if a named field is available
        val typeName = fieldsetTypeNames[fieldset]
        return if (typeName != null) FieldType.Named(typeName)
        else when (fieldset) {
            is SingleColumnFieldset<*, *, *> -> FieldType.Leaf(jacksonJavaType(fieldset.singleColumn.type.valueType))
            is SingleValueFieldset<*, *> -> FieldType.Leaf(jacksonJavaType(fieldset.schema.valueType))
            is EntityFieldset<*> -> FieldType.Inline {
                generateFieldsetMembers(fieldset, fieldsetTypeNames, entityClassHierarchy)
            }
        }
    }

    private fun FileBuilder.generateCommandInterface(
        commandType: Class<out Command<*>>,
        mode: InterfaceBuilder.BeanMode,
    ) {
        val typeName = commandType.simpleName
        declareInterface(typeName) {
            declareFieldsOfType(commandType, mode)
        }
    }

    private fun FileBuilder.generateGenericCommandInterface(
        genericCommandType: Class<out GenericEntityCommand<*>>,
        entityClassHierarchy: ClassHierarchyIndex,
    ) {
        val commandInstance = try {
            BeanUtils.instantiateClass(genericCommandType)
        } catch (ex: BeanInstantiationException) {
            log.warn("Failed to instantiate generic entity command [${genericCommandType.name}]: ${ex.message}", ex)
            return
        }

        // Generate interface for generic command, including all non-primary columns
        val schema = commandInstance.entityFieldset.schema
        declareInterface(genericCommandType.simpleName) {
            schema.columns.filterNot { it.isReadOnly }.forEach { column ->
                declareField(
                    fieldName = column.fieldName,
                    fieldType = columnFieldType(column, entityClassHierarchy),
                    optional = true,
                    mutable = true,
                )
            }
        }
    }
}

private fun String.isLegalIdentifier() = when {
    isEmpty() -> false
    !get(0).isJavaIdentifierStart() -> false
    else -> drop(1).all { it.isJavaIdentifierPart() }
}
