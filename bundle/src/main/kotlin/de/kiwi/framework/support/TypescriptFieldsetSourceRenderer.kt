package de.kiwi.framework.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityIdJsonModule
import de.kiwi.framework.support.FieldsetSourceRenderer.*

private val camelCasePattern = "(?<=.)[A-Z]".toRegex()

class TypescriptFieldsetSourceRenderer(
    private val options: TypescriptOptions = TypescriptOptions(),
) : FieldsetSourceRenderer {

    override fun moduleFileName(moduleName: String): String =
        modelTsFilename(moduleName)

    override fun renderSourceFile(build: FileBuilder.() -> Unit): String {
        val builder = TsBuilderContext(options)
        builder.build()
        return builder.result()
    }

    private fun modelTsFilename(moduleName: String): String {
        // Convert CamelCase to snake-case for TS module naming convention
        val snakeCaseModuleName = moduleName.replace(camelCasePattern, "-\$0").lowercase()
        return snakeCaseModuleName + options.moduleFileExtension
    }
}

data class TypescriptOptions(
    val indentDepth: Int = 2,
    val readOnlyFields: Boolean = true,
    val readOnlyArrays: Boolean = true,
    val useNominalIdTypes: Boolean = true,
    val includeSemicolon: Boolean = true,
    val defaultIdValueType: String = "string",
    val moduleFileExtension: String = ".model.ts",
    val endOfLine: String = "\n",
)

private val defaultObjectMapper by lazy {
    jacksonObjectMapper()
        .registerModule(EntityIdJsonModule())
        .registerModule(JavaTimeModule())
}

private class TsBuilderContext(
    private val options: TypescriptOptions,
    private val objectMapper: ObjectMapper = defaultObjectMapper,
) : FileBuilder, InterfaceBuilder {

    private val buffer = StringBuilder()
    private var indentLevel: Int = 0

    private val semi = if (options.includeSemicolon) ";" else ""
    private val indentCount get() = indentLevel * options.indentDepth

    private val typeRenderer = TypescriptTypeRenderer(
        options = options,
        objectMapper = objectMapper,
        inlineInterfaceProvider = { build ->
            val builder = InlineInterfaceBuilder()
            builder.build()
            builder.result()
        },
    )

    fun result() = buffer.toString()

    override fun addDocComment(lines: List<String>) {
        appendLine("/**")
        lines.forEach { appendLine(" * $it") }
        appendLine(" */")
        appendNewline()
    }

    override fun addLineComment(line: String) {
        val oneLine = line.replace('\n', ' ')
        appendLine("// $oneLine")
        appendNewline()
    }

    override fun declarePreamble() {
        typeRenderer.createPreambleTypes().appendStatements()
    }

    override fun declareInterface(name: String, build: InterfaceBuilder.() -> Unit) {
        appendLine("export interface I$name {")
        blockIndent { build() }
        appendLine("}")
        appendNewline()
        declareIdTypeAliases()
    }

    override fun declareFieldsetMapping(name: String, mapping: Map<String, String>) {
        appendLine("export type I$name = {")
        blockIndent {
            mapping.forEach { (fieldsetName, typeName) ->
                appendLine("readonly \"$fieldsetName\": I$typeName")
            }
        }
        appendLine("}$semi")
        appendNewline()
    }

    private fun declareIdTypeAliases() {
        typeRenderer.createIdAliasTypes().appendStatements()
    }

    override fun declareTypeAlias(name: String, aliasedType: Class<*>) {
        appendLine("export type I$name = ${typeRenderer.javaTypeDeclaration(aliasedType)}$semi")
        appendNewline()
    }

    override fun declareField(fieldName: String, fieldType: FieldType, optional: Boolean, mutable: Boolean) {
        appendLine(createField(fieldName, fieldType, optional = optional, mutable = mutable))
    }

    override fun declareFieldsOfType(beanType: Class<*>, mode: InterfaceBuilder.BeanMode) {
        val deserialization = objectMapper.deserializationConfig
        val javaType = objectMapper.typeFactory.constructType(beanType)
        val description = when (mode) {
            InterfaceBuilder.BeanMode.Create -> deserialization.introspectForCreation(javaType)
            InterfaceBuilder.BeanMode.Update -> deserialization.introspect(javaType)
        }

        description.findProperties().forEach { prop ->
            val propType = prop.primaryType
            val fieldType = when {
                propType.isTypeOrSubTypeOf(EntityId::class.java) -> {
                    @Suppress("UNCHECKED_CAST")
                    FieldType.IdType(propType.rawClass as Class<out EntityId>, String::class.java)
                }

                else -> FieldType.Leaf(propType)
            }
            val included = when (mode) {
                InterfaceBuilder.BeanMode.Create -> prop.hasConstructorParameter()
                InterfaceBuilder.BeanMode.Update -> prop.hasSetter()
            }
            if (included) {
                val optional = !prop.isRequired || (mode == InterfaceBuilder.BeanMode.Update)
                val nullable = !prop.isRequired && (mode == InterfaceBuilder.BeanMode.Create)
                val declareField = createField(
                    fieldName = prop.name,
                    fieldType = fieldType,
                    optional = optional,
                    nullable = nullable,
                    mutable = true
                )
                appendLine(declareField)
            }
        }
    }

    private fun createField(
        fieldName: String,
        fieldType: FieldType,
        optional: Boolean,
        nullable: Boolean = false,
        mutable: Boolean = false,
    ): String {
        val modifier = buildModifier {
            if (options.readOnlyFields && !mutable) add("readonly")
        }
        val maybeNullableFieldType = if (nullable) FieldType.Nullable(fieldType) else fieldType
        val typeDeclaration = typeRenderer.createTypeDeclaration(maybeNullableFieldType)
        val separator = if (optional) "?:" else ":"
        return "$modifier $fieldName$separator $typeDeclaration$semi"
    }

    private fun append(text: String) {
        buffer.append(text)
    }

    private fun appendIndent() {
        repeat(indentCount) { buffer.append(' ') }
    }

    private fun appendNewline() {
        buffer.append(options.endOfLine)
    }

    private fun appendLine(line: String) {
        appendIndent()
        append(line)
        appendNewline()
    }

    private fun Sequence<String>.appendStatements() = forEach { statement ->
        if (statement.isNotEmpty()) appendLine(statement + semi)
        else appendNewline()
    }

    private fun blockIndent(block: () -> Unit) {
        indentLevel++
        block()
        indentLevel--
    }

    private fun buildModifier(builder: MutableList<String>.() -> Unit): String {
        return buildList(builder).joinToString(" ")
    }

    private inner class InlineInterfaceBuilder : InterfaceBuilder {
        private val memberLines = ArrayList<String>()

        private fun indentSpace(): String = " ".repeat(indentCount)

        fun result() = buildString {
            append('{')
            append(options.endOfLine)

            memberLines.forEach { memberLine ->
                append(memberLine)
                append(options.endOfLine)
            }

            append(indentSpace())
            append('}')
        }

        override fun declareField(fieldName: String, fieldType: FieldType, optional: Boolean, mutable: Boolean) {
            blockIndent {
                val indent = indentSpace()
                val field = createField(fieldName, fieldType, optional = optional, mutable = mutable)
                memberLines.add(indent + field)
            }
        }

        override fun declareFieldsOfType(beanType: Class<*>, mode: InterfaceBuilder.BeanMode) {
            throw UnsupportedOperationException()
        }
    }
}
