package de.kiwi.framework

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpMethod
import org.springframework.transaction.annotation.Isolation
import tools.jackson.databind.PropertyNamingStrategies
import java.nio.charset.Charset
import java.time.Duration
import java.util.*

/**
 * Configuration properties of the Kiwi framework (prefix `kiwi`).
 *
 * Properties are declared as `var`, nested property groups as `val`. Spring binds the values using the primary
 * constructor, but the configuration processor (`spring-configuration-metadata.json`) only documents properties with
 * setters: as all constructor parameters have default values, Kotlin generates an additional no-argument constructor,
 * so the processor can't detect constructor binding (and `@ConstructorBinding` is copied to both constructors).
 * Declaring properties as `val` would remove them from the metadata, which `ConfigurationMetadataTest` checks.
 */
@ConfigurationProperties("kiwi")
data class KiwiFrameworkProperties(
    val eventBus: EventProperties = EventProperties(),
    val queryBus: CommandQueryProperties = CommandQueryProperties(),
    val commandBus: CommandQueryProperties = CommandQueryProperties(),
    val auditLog: AuditLogProperties = AuditLogProperties(),
    val pagination: PaginationProperties = PaginationProperties(),
    val fieldsetValidation: FieldsetValidationProperties = FieldsetValidationProperties(),
    val clock: ClockProperties = ClockProperties(),
    val mail: MailProperties = MailProperties(),
    val web: WebProperties = WebProperties(),
    val security: SecurityProperties = SecurityProperties(),
    val transactions: TransactionalProperties = TransactionalProperties(),
) {
    data class CommandQueryProperties(
        var enableCaching: Boolean = true,
        var transactional: Boolean = true,
        var registerHandlerBeans: Boolean = true,
    )

    data class EventProperties(
        var enableCaching: Boolean = true,
        var maxRecursionDepth: Int = 10,
        var registerSubscriberBeans: Boolean = true,
    )

    data class AuditLogProperties(
        /**
         * Enable audit logging of commands and events, using all available `AuditLoggerService` beans,
         * e.g. the persistent audit-log provided by the Kiwi audit-log module.
         */
        var enabled: Boolean = true,

        /** Additionally log audit-log entries to the console */
        var console: Boolean = false,
    )

    data class ClockProperties(
        var zone: String? = null,
        var fixed: String? = null,
    )

    data class MailProperties(
        var enabled: Boolean = true,
        var fromAddress: String = "",
        var htmlMessage: Boolean = true,
        var sendToJavaMail: Boolean = true,
        var sendToConsole: Boolean = false,
        var disabledWarning: Boolean = true,
        var templateCharset: Charset = Charsets.UTF_8,
        var templateCacheSize: Int = 0,
    )

    data class PaginationProperties(
        var maxPageSize: Int = 1000,
        var defaultPageSize: Int = 50,
        var pageNumberParameter: String = "_page",
        var pageSizeParameter: String = "_size",
        var sortParameter: String = "_sort",
    )

    data class FieldsetValidationProperties(
        var noCycles: Boolean = true,
        var maxDepth: Int = 3,
    )

    data class WebProperties(
        /** Enable the Kiwi web layer, including the generic entity and analytical report endpoints */
        var enabled: Boolean = true,
        var genericController: Boolean = true,
        var reportController: Boolean = true,
        var namingStrategy: Set<NamingStrategy> = setOf(
            NamingStrategy.CamelCase,
            NamingStrategy.SnakeCase,
            NamingStrategy.Lowercase,
        ),
        val openApi: OpenApiProperties = OpenApiProperties(),
        val globalCors: GlobalCorsProperties = GlobalCorsProperties(),
        val managementSecurity: ManagementSecurityProperties = ManagementSecurityProperties(),
    ) {
        enum class NamingStrategy { CamelCase, SnakeCase, Lowercase }

        fun entityTypeNameVariants(entityName: String) = buildSet<String> {
            if (NamingStrategy.CamelCase in namingStrategy) add(entityName)
            if (NamingStrategy.SnakeCase in namingStrategy) add(snakeCaseStrategy.nameForField(null, null, entityName))
            if (NamingStrategy.Lowercase in namingStrategy) add(entityName.lowercase())
        }
    }

    data class OpenApiProperties(
        /** Enable documenting the Kiwi web endpoints using SpringDoc OpenAPI, if available */
        var enabled: Boolean = true,
        var locale: Locale = Locale.ENGLISH,
        var queryParamsDetails: QueryParamsDetailLevel = QueryParamsDetailLevel.EntityFiltersOnly,
    ) {
        enum class QueryParamsDetailLevel(
            val entityFilters: Boolean,
            val defaultColumnFilters: Boolean,
            val operatorColumnFilters: Boolean,
        ) {
            NoFilters(
                entityFilters = false, defaultColumnFilters = false, operatorColumnFilters = false,
            ),
            EntityFiltersOnly(
                entityFilters = true, defaultColumnFilters = false, operatorColumnFilters = false,
            ),
            EntityAndDefaultColumnFilters(
                entityFilters = true, defaultColumnFilters = true, operatorColumnFilters = false,
            ),
            EntityAndAllColumnFilters(
                entityFilters = true, defaultColumnFilters = true, operatorColumnFilters = true,
            ),
        }
    }

    data class GlobalCorsProperties(
        var enabled: Boolean = false,
        var allowedOrigins: Set<String> = setOf(),
        var allowedMethods: Set<HttpMethod> = setOf(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
        ),
        var allowedHeaders: Set<String> = setOf(),
        var allowCredentials: Boolean = false,
        var maxAge: Duration = Duration.ofMinutes(30),
    )

    data class ManagementSecurityProperties(
        var enabled: Boolean = false,
        var managementUsername: String = "management",
        var managementPassword: String? = null,
        var managementPasswordEncoding: ManagementPasswordEncoding = ManagementPasswordEncoding.BCrypt,
        var httpBasicRealmName: String = "Kiwi Management-Security",
        var permitAllEndpoints: Set<String> = setOf("health"),
    ) {
        enum class ManagementPasswordEncoding { BCrypt, Plain }
    }

    data class SecurityProperties(
        /** Prefix removed from granted authorities to determine the roles of a user, empty to keep authorities as-is */
        var rolePrefix: String = "ROLE_",
    )

    data class TransactionalProperties(
        var readIsolation: Isolation = Isolation.DEFAULT,
        var writeIsolation: Isolation = Isolation.DEFAULT,
    )
}

private val snakeCaseStrategy = PropertyNamingStrategies.SNAKE_CASE as PropertyNamingStrategies.NamingBase
