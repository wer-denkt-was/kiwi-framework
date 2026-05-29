package de.kiwi.framework

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpMethod
import java.nio.charset.Charset
import java.time.Duration
import java.util.*

@ConfigurationProperties("kiwi")
data class KiwiFrameworkProperties(
    val eventBus: EventProperties = EventProperties(),
    val queryBus: CommandQueryProperties = CommandQueryProperties(),
    val commandBus: CommandQueryProperties = CommandQueryProperties(),
    var enableAuditLogging: Boolean = true,
    var auditLogConsole: Boolean = false,
    val pagination: PaginationProperties = PaginationProperties(),
    val fieldsetValidation: FieldsetValidationProperties = FieldsetValidationProperties(),
    val clock: ClockProperties = ClockProperties(),
    val mail: MailProperties = MailProperties(),
    val jwt: JwtProperties = JwtProperties(),
    val web: WebProperties = WebProperties(),
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
        val templateRenderers: List<String> = listOf("mustache", "mjml"),
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

    data class JwtProperties(
        var enabled: Boolean = true,

        /** Issuer name included in the JWT claims */
        var issuer: String? = null,

        /** If true, the configured issuer must be included in the JWT claims */
        var requireIssuer: Boolean = true,

        /**
         * Must be a valid base64-string.
         * Recommended to be a 512-bit secret key, generated using the command line:
         * ```sh
         * head -c 64 /dev/random | base64
         * ```
         */
        var secretKey: String = "",

        /** Should expire before the next workday to force new logins every day but not during the day */
        var expiration: Duration = Duration.ofHours(12),
    )

    data class WebProperties(
        var genericController: Boolean = true,
        var reportController: Boolean = true,
        var namingStrategy: Set<NamingStrategy> = setOf(
            NamingStrategy.CamelCase,
            NamingStrategy.SnakeCase,
            NamingStrategy.Lowercase
        ),
        var openApi: OpenApiProperties = OpenApiProperties(),
        var globalCors: GlobalCorsProperties = GlobalCorsProperties(),
        var managementSecurity: ManagementSecurityProperties = ManagementSecurityProperties(),
    ) {
        enum class NamingStrategy { CamelCase, SnakeCase, Lowercase }

        fun entityTypeNameVariants(entityName: String) = buildSet<String> {
            if (NamingStrategy.CamelCase in namingStrategy) add(entityName)
            if (NamingStrategy.SnakeCase in namingStrategy) add(snakeCaseStrategy.translate(entityName))
            if (NamingStrategy.Lowercase in namingStrategy) add(entityName.lowercase())
        }
    }

    data class OpenApiProperties(
        var locale: Locale = Locale.ENGLISH,
        var queryParamsDetails: QueryParamsDetailLevel = QueryParamsDetailLevel.EntityFiltersOnly,
    ) {
        enum class QueryParamsDetailLevel(
            val entityFilters: Boolean,
            val defaultColumnFilters: Boolean,
            val operatorColumnFilters: Boolean,
        ) {
            NoFilters(
                entityFilters = false, defaultColumnFilters = false, operatorColumnFilters = false
            ),
            EntityFiltersOnly(
                entityFilters = true, defaultColumnFilters = false, operatorColumnFilters = false
            ),
            EntityAndDefaultColumnFilters(
                entityFilters = true, defaultColumnFilters = true, operatorColumnFilters = false
            ),
            EntityAndAllColumnFilters(
                entityFilters = true, defaultColumnFilters = true, operatorColumnFilters = true
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
}

private val snakeCaseStrategy = PropertyNamingStrategies.SNAKE_CASE as PropertyNamingStrategies.NamingBase
