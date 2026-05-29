package de.kiwi.framework.support

import de.kiwi.api.domain.Command
import de.kiwi.api.generic.CreateCommand
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.api.generic.UpdateCommand
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.util.slf4j
import jakarta.persistence.Entity
import org.springframework.boot.info.BuildProperties
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ResourceLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

private val log = slf4j<FieldsetSourceGeneratorTool>()

/**
 * Tool for generating source code representing declared fieldsets as types.
 */
class FieldsetSourceGeneratorTool(
    private val sources: FieldsetSources,
    private val buildInfo: BuildProperties? = null,
) {
    companion object {
        fun scanPackages(
            resourceLoader: ResourceLoader,
            vararg packageName: String,
        ): FieldsetSourceGeneratorTool {
            val scanner = ClasspathSupport(resourceLoader)
            val packageNames = packageName.toSet()

            val entitySchemaTypes =
                scanner.findClassesOfTypeInPackages<EntitySchema<*>>(packageNames)
            val fieldsetsAvailableClasses =
                scanner.findClassesOfTypeInPackages<FieldsetsAvailable<*>>(packageNames)

            val entitySchemas = entitySchemaTypes.mapToSingletonObjects()
            val fieldsetAggregates = fieldsetsAvailableClasses.mapToSingletonObjects()

            val entityClasses = scanner.findAnnotatedClassesInPackages<Entity, Any>(packageNames)
            val genericCreateClasses = scanner.findClassesOfTypeInPackages<GenericCreate<*>>(packageNames)
            val genericUpdateClasses = scanner.findClassesOfTypeInPackages<GenericUpdate<*, *>>(packageNames)
            val customCreateClasses = scanner.findAnnotatedClassesInPackages<CreateCommand, Command<*>>(packageNames)
            val customUpdateClasses = scanner.findAnnotatedClassesInPackages<UpdateCommand, Command<*>>(packageNames)

            return FieldsetSourceGeneratorTool(
                FieldsetSources(
                    entityClasses = entityClasses,
                    entitySchemas = entitySchemas,
                    fieldsetAggregates = fieldsetAggregates,
                    createCommandTypes = customCreateClasses,
                    updateCommandTypes = customUpdateClasses,
                    genericCommandTypes = (genericCreateClasses + genericUpdateClasses),
                )
            )
        }

        fun scanContext(
            resourceLoader: ResourceLoader,
            context: ApplicationContext,
        ): FieldsetSourceGeneratorTool {
            val scanner = ClasspathSupport(resourceLoader)
            val entityPackages = scanner.findEntityScanPackages(context)
            val componentPackages = scanner.findComponentScanPackages(context)
            val baseScanPackages = entityPackages + componentPackages
            val packageNames = baseScanPackages.toSet().toTypedArray()
            return scanPackages(resourceLoader, *packageNames)
        }

        private fun <T> Collection<Class<out T>>.mapToSingletonObjects(): List<T> {
            return mapNotNull { clazz ->
                clazz.kotlin.objectInstance.also { instance ->
                    if (instance == null) {
                        log.warn("Invalid ${clazz.superclass.simpleName} class: ${clazz.name} is not a singleton object")
                    }
                }
            }
        }
    }

    fun withBuildInfo(buildInfo: BuildProperties?) = FieldsetSourceGeneratorTool(
        sources = sources,
        buildInfo = buildInfo ?: this.buildInfo
    )

    fun generateBuildArtifacts(
        moduleName: String,
        renderer: FieldsetSourceRenderer,
        targetDirectory: String = "",
    ): Path {
        // Class resource located in "build/classes/java/{sourceSet}"
        val classUri = javaClass.getResource("/")!!.toURI()
        val buildPath = Path.of(classUri).parent.parent.parent
        val outputDirectory = buildPath.resolve(targetDirectory)
        return generateSources(moduleName, outputDirectory, renderer)
    }

    fun generateSources(
        moduleName: String,
        outputDirectory: Path,
        renderer: FieldsetSourceRenderer,
    ): Path {
        val buildTimestamp = buildInfo?.time ?: Instant.now()

        val fieldsetAggregateNames = sources.fieldsetAggregates.map { it.javaClass.simpleName }.sorted()
        val createCommandNames = sources.createCommandTypes.map { it.simpleName }.sorted()
        val updateCommandNames = sources.updateCommandTypes.map { it.simpleName }.sorted()
        val genericCommandNames = sources.genericCommandTypes.map { it.simpleName }.sorted()

        val fileHeader = buildList {
            add("-- Kiwi Framework generated Fieldset types --")
            add("Generated on: ${buildTimestamp.atOffset(ZoneOffset.UTC)}")
            add("Fieldsets: ${fieldsetAggregateNames.joinToString(", ")}")
            add("Create commands: ${createCommandNames.joinToString(", ")}")
            add("Update commands: ${updateCommandNames.joinToString(", ")}")
            add("Generic commands: ${genericCommandNames.joinToString(", ")}")
            buildInfo?.let {
                add("Artifact: ${it.group}:${it.artifact}")
                add("Version: ${it.version}")
            }
        }

        val generator = FieldsetSourceGenerator(moduleName, fileHeader, renderer)
        val (filename, sourceCode) = generator.generateSource(sources)

        Files.createDirectories(outputDirectory)
        val outputFile = outputDirectory.resolve(filename)
        outputFile.writeText(sourceCode, Charsets.UTF_8)
        log.info("Generated Fieldset source module: $outputFile")
        return outputFile
    }
}
