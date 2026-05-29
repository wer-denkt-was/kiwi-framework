package de.kiwi.framework.infrastructure.generic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityMethodUnavailable
import de.kiwi.api.domain.EntityMismatchedInput
import de.kiwi.api.generic.*
import de.kiwi.api.generic.*
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.api.util.slf4j
import de.kiwi.framework.application.generic.GenericHandlerProvider
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.annotation.AnnotationUtils
import java.lang.reflect.Constructor
import kotlin.reflect.KClass

private val log = slf4j<SpringGenericHandlerProvider>()

class SpringGenericHandlerProvider(
    private val objectMapper: ObjectMapper,
    private val classPathScanner: ClassPathScannerUtil,
) : GenericHandlerProvider, InitializingBean {

    private lateinit var createTypes: Map<Class<*>, Class<out Command<*>>>
    private lateinit var updateTypes: Map<Class<*>, Class<out Command<*>>>
    private lateinit var deleteTypes: Map<Class<*>, Class<out Command<*>>>
    private lateinit var queryTypes: Map<Class<*>, Class<out GenericEntityQuery<*>>>

    override val queryClasses get() = queryTypes.toMap()
    override val createClasses get() = createTypes.toMap()
    override val updateClasses get() = updateTypes.toMap()
    override val deleteClasses get() = deleteTypes.toMap()

    override fun afterPropertiesSet() {
        // Search for command/query classes in @EntityScan and @ComponentScan package locations
        val basePackages = classPathScanner.findEntityAndComponentBasePackages()

        // Find classes extending GenericCreate, GenericUpdate, GenericDelete and GenericEntityQuery
        val genericCreateClasses = classPathScanner.findClassesOfTypeInPackages<GenericCreate<*>>(basePackages)
        val genericUpdateClasses = classPathScanner.findClassesOfTypeInPackages<GenericUpdate<*, *>>(basePackages)
        val genericDeleteClasses = classPathScanner.findClassesOfTypeInPackages<GenericDelete<*, *>>(basePackages)
        val genericQueryClasses = classPathScanner.findClassesOfTypeInPackages<GenericEntityQuery<*>>(basePackages)
        val genericCreates = genericCreateClasses.associateBy { it.lookupSupertypeArgument<GenericCreate<*>>(0) }
        val genericUpdates = genericUpdateClasses.associateBy { it.lookupSupertypeArgument<GenericUpdate<*, *>>(0) }
        val genericDeletes = genericDeleteClasses.associateBy { it.lookupSupertypeArgument<GenericDelete<*, *>>(0) }
        val genericQueries = genericQueryClasses.associateBy { it.lookupSupertypeArgument<GenericEntityQuery<*>>(0) }

        // Find classes annotated with @CustomCreate, @CustomUpdate and @CustomDelete
        val customCreateClasses = classPathScanner.findAnnotatedClassesInPackages<CreateCommand>(basePackages)
        val customUpdateClasses = classPathScanner.findAnnotatedClassesInPackages<UpdateCommand>(basePackages)
        val customDeleteClasses = classPathScanner.findAnnotatedClassesInPackages<DeleteCommand>(basePackages)
        val customCreates =
            customCreateClasses.associate { it.commandAnnotation<CreateCommand> { c -> c.value } to it.asCommand() }
        val customUpdates =
            customUpdateClasses.associate { it.commandAnnotation<UpdateCommand> { c -> c.value } to it.asCommand() }
        val customDeletes =
            customDeleteClasses.associate { it.commandAnnotation<DeleteCommand> { c -> c.value } to it.asCommand() }

        createTypes = genericCreates + customCreates
        updateTypes = genericUpdates + customUpdates
        deleteTypes = genericDeletes + customDeletes
        queryTypes = genericQueries

        // Log create, update and query commands/queries grouped by entity type
        val entityTypes = (createTypes.keys + updateTypes.keys + queryTypes.keys).distinct()
        entityTypes.forEach { entityType ->
            val query = queryTypes[entityType]
            val create = createTypes[entityType]
            val update = updateTypes[entityType]
            val delete = deleteTypes[entityType]
            log.info("Entity [${entityType.name}]: Generic Query = [${query?.name}], Create = [${create?.name}], Update = [${update?.name}], Delete = [${delete?.name}]")
        }
    }

    override fun createCommand(entityType: Class<*>, body: JsonNode): Command<*> {
        val createType = createTypes[entityType]
            ?: throw EntityMethodUnavailable(entityType, EntityMethodUnavailable.EntityMethod.Create)

        return if (GenericCreate::class.java.isAssignableFrom(createType)) {
            // Instantiate generic command and assign body JSON data
            val genericCreate = BeanUtils.instantiateClass(createType) as GenericCreate<*>
            genericCreate.jsonBody = body
            genericCreate
        } else {
            // Instantiate command annotated with @CreateCommand from body JSON data
            handleMismatchException(entityType) {
                objectMapper.treeToValue(body, createType)
            }
        }
    }

    override fun updateCommand(entityType: Class<*>, id: EntityId, body: JsonNode): Command<*> {
        val updateType = updateTypes[entityType]
            ?: throw EntityMethodUnavailable(entityType, EntityMethodUnavailable.EntityMethod.Update)

        @Suppress("UNCHECKED_CAST")
        if (GenericUpdate::class.java.isAssignableFrom(updateType)) {
            // Instantiate GenericUpdate command, assign ID and JSON body
            val entityIdType = updateType.lookupSupertypeArgument<GenericUpdate<*, *>>(1)
            val typedEntityId = id.cast(entityIdType as Class<EntityId>)
            val command = BeanUtils.instantiateClass(updateType) as GenericUpdate<*, EntityId>
            command.entityId = typedEntityId
            command.jsonBody = body
            return command
        } else {
            // Instantiate command annotated with @UpdateCommand
            val entityIdType = updateType.commandAnnotation<UpdateCommand> { it.idType } as Class<EntityId>
            val entityIdConstructor = lookupEntityIdConstructor(updateType)
            val typedEntityId = id.cast(entityIdType)
            val command = BeanUtils.instantiateClass(entityIdConstructor, typedEntityId)

            // Update command properties from body JSON data
            handleMismatchException(entityType) { objectMapper.updateValue(command, body) }
            return command
        }
    }

    override fun deleteCommand(entityType: Class<*>, id: EntityId): Command<*> {
        val deleteType = deleteTypes[entityType]
            ?: throw EntityMethodUnavailable(entityType, EntityMethodUnavailable.EntityMethod.Delete)

        @Suppress("UNCHECKED_CAST")
        if (GenericDelete::class.java.isAssignableFrom(deleteType)) {
            // Instantiate GenericDelete command, assign ID
            val entityIdType = deleteType.lookupSupertypeArgument<GenericDelete<*, *>>(1)
            val typedEntityId = id.cast(entityIdType as Class<EntityId>)
            val command = BeanUtils.instantiateClass(deleteType) as GenericDelete<*, EntityId>
            command.entityId = typedEntityId
            return command
        } else {
            // Instantiate command annotated with @DeleteCommand
            val entityIdType = deleteType.commandAnnotation<DeleteCommand> { it.idType } as Class<EntityId>
            val entityIdConstructor = lookupEntityIdConstructor(deleteType)
            val typedEntityId = id.cast(entityIdType)
            return BeanUtils.instantiateClass(entityIdConstructor, typedEntityId)
        }
    }

    override fun <E : Any> entityQuery(entityType: Class<E>): GenericEntityQuery<E> {
        val queryType = queryTypes[entityType]
            ?: throw EntityMethodUnavailable(entityType, EntityMethodUnavailable.EntityMethod.Query)

        @Suppress("UNCHECKED_CAST")
        return BeanUtils.instantiateClass(queryType) as GenericEntityQuery<E>
    }

    private fun <C : Command<*>> lookupEntityIdConstructor(commandType: Class<C>): Constructor<C> {
        val constructor = BeanUtils.getResolvableConstructor(commandType)
        if (constructor.parameterCount != 1) {
            throw AssertionError("Command class ${commandType.name} must have a single-parameter EntityId constructor")
        }

        val parameterType = constructor.parameterTypes[0]
        if (!EntityId::class.java.isAssignableFrom(parameterType)) {
            throw AssertionError("Command class ${commandType.name} constructor parameter must be an EntityId")
        }

        return constructor
    }

    private inline fun <T> handleMismatchException(entityType: Class<*>, action: () -> T): T {
        try {
            return action()
        } catch (ex: MismatchedInputException) {
            val mismatchedFields = ex.path.map { it.description }
            throw EntityMismatchedInput(entityType, mismatchedFields)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Class<*>.asCommand(): Class<out Command<*>> {
    if (Command::class.java.isAssignableFrom(this)) return this as Class<out Command<*>>
    else throw AssertionError("Invalid annotation on non-command class $name")
}

private inline fun <reified A : Annotation> Class<*>.commandAnnotation(getValue: (A) -> KClass<*>): Class<*> {
    val annotationName = A::class.java.simpleName
    val annotation = AnnotationUtils.getAnnotation(this, A::class.java)
        ?: throw AssertionError("Missing @${annotationName} on command class $name")

    val type = getValue(annotation)
    return type.java
}
