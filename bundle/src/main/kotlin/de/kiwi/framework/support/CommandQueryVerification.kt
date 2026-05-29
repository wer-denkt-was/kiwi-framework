package de.kiwi.framework.support

import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.Query
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.api.util.slf4j
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ResourceLoader
import kotlin.reflect.KClass

private val log = slf4j<CommandQueryVerification>()

/**
 * Helper class verifying that each declared [Command] class has exactly one registered [CommandHandler],
 * and that each declared [Query] class has exactly one registered [QueryHandler].
 *
 * This should be used in an application integration test to verify that all commands and queries can
 * be handled without encountering missing or ambiguous command- or query-handlers.
 */
class CommandQueryVerification(
    private val queryClasses: List<Class<out Query<*>>>,
    private val commandClasses: List<Class<out Command<*>>>,
    private val queryHandlerClasses: List<Class<out QueryHandler<*, *>>>,
    private val commandHandlerClasses: List<Class<out CommandHandler<*, *>>>,
) {
    companion object {
        fun run(resourceLoader: ResourceLoader, context: ApplicationContext) {
            val scanner = ClasspathSupport(resourceLoader, context.environment)

            val queryHandlers = scanner.findBeanClassesOfTypeInContext<QueryHandler<*, *>>(context)
            val commandHandlers = scanner.findBeanClassesOfTypeInContext<CommandHandler<*, *>>(context)
            val entityPackageNames = scanner.findEntityScanPackages(context)
            val queryClasses = scanner.findClassesOfTypeInPackages<Query<*>>(entityPackageNames)
            val commandClasses = scanner.findClassesOfTypeInPackages<Command<*>>(entityPackageNames)

            val verification = CommandQueryVerification(
                queryClasses = queryClasses,
                commandClasses = commandClasses,
                queryHandlerClasses = queryHandlers,
                commandHandlerClasses = commandHandlers,
            )
            verification.assertCommandQueryIntegrity()
        }

        fun run(resourceLoader: ResourceLoader, build: Builder.() -> Unit) {
            val builder = SimpleBuilder()
            build(builder)
            val domainPackages = builder.domainPackages
            val applicationPackages = builder.applicationPackages

            val scanner = ClasspathSupport(resourceLoader)
            val queryClasses = scanner.findClassesOfTypeInPackages<Query<*>>(domainPackages)
            val commandClasses = scanner.findClassesOfTypeInPackages<Command<*>>(domainPackages)
            val queryHandlerClasses = scanner.findClassesOfTypeInPackages<QueryHandler<*, *>>(applicationPackages)
            val commandHandlerClasses = scanner.findClassesOfTypeInPackages<CommandHandler<*, *>>(applicationPackages)

            val verification = CommandQueryVerification(
                queryClasses = queryClasses,
                commandClasses = commandClasses,
                queryHandlerClasses = queryHandlerClasses,
                commandHandlerClasses = commandHandlerClasses,
            )
            verification.assertCommandQueryIntegrity()
        }

    }

    interface Builder {
        fun domain(vararg packageName: String)
        fun application(vararg packageName: String)
    }

    fun assertCommandQueryIntegrity() {
        val handlerInfos = ArrayList<HandlerInfo>()
        handlerInfos += runVerification(QueryHandler::class, queryClasses, queryHandlerClasses)
        handlerInfos += runVerification(CommandHandler::class, commandClasses, commandHandlerClasses)

        handlerInfos.sortedBy { it.valid.ordinal }.forEach {
            when (it.valid) {
                HandlerValid.Valid -> log.info(ansiGreen("OKAY [${it.subjectName}] => [${it.handlerNames}]"))
                HandlerValid.Ambiguous -> log.error(ansiRed("TOO MANY HANDLERS [${it.subjectName}] => [${it.handlerNames}]"))
                HandlerValid.Missing -> log.error(ansiRed("MISSING [${it.subjectName}] HANDLER"))
            }
        }

        val handlerErrors = handlerInfos.filter { it.valid != HandlerValid.Valid }
        if (handlerErrors.isNotEmpty()) {
            throw VerificationError(handlerErrors)
        }
    }

    private fun runVerification(
        handlerSupertype: KClass<*>,
        subjectTypes: List<Class<*>>,
        handlerTypes: List<Class<*>>,
    ): List<HandlerInfo> {
        val handlersBySubjectType = handlerTypes
            .groupBy { it.lookupSupertypeArgument(handlerSupertype, 0) }

        return subjectTypes.map { subjectType ->
            val subjectHandlers = handlersBySubjectType
                .filter { (handledType, _) -> handledType.isAssignableFrom(subjectType) }
                .flatMap { (_, handlerTypes) -> handlerTypes }

            HandlerInfo(subjectType, subjectHandlers)
        }
    }

    class VerificationError(val handlerErrors: List<HandlerInfo>) :
        AssertionError("Command-Query verification failed for ${handlerErrors.size} handlers")

    data class HandlerInfo(
        val subjectType: Class<*>,
        val subjectHandlers: List<Class<*>>,
    ) {
        val subjectName: String get() = subjectType.name
        val handlerNames: String get() = subjectHandlers.joinToString(", ") { it.name }

        val valid = when {
            subjectHandlers.isEmpty() -> HandlerValid.Missing
            subjectHandlers.size > 1 -> HandlerValid.Ambiguous
            else -> HandlerValid.Valid
        }
    }

    enum class HandlerValid { Valid, Ambiguous, Missing }
}

private const val ANSI_RESET = "\u001B[0m"
private const val ANSI_RED = "\u001B[31m"
private const val ANSI_GREEN = "\u001B[32m"

private fun ansiRed(text: String) = ANSI_RED + text + ANSI_RESET
private fun ansiGreen(text: String) = ANSI_GREEN + text + ANSI_RESET

private class SimpleBuilder : CommandQueryVerification.Builder {
    val domainPackages = HashSet<String>()
    val applicationPackages = HashSet<String>()

    override fun domain(vararg packageName: String) {
        domainPackages.addAll(packageName.toList())
    }

    override fun application(vararg packageName: String) {
        applicationPackages.addAll(packageName.toList())
    }
}
