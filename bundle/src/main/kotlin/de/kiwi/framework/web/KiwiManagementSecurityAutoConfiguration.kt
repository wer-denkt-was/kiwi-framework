package de.kiwi.framework.web

import de.kiwi.api.util.slf4j
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.KiwiFrameworkProperties.ManagementSecurityProperties.ManagementPasswordEncoding
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.core.userdetails.User as SpringUser

private val log = slf4j<KiwiManagementSecurityAutoConfiguration>()

/**
 * Autoconfiguration securing the actuator management endpoints using HTTP Basic authentication.
 * Must be enabled using `kiwi.web.management-security.enabled=true`.
 *
 * Ordered before the Spring Boot web security auto-configurations, as these back off
 * if a [SecurityFilterChain] has already been declared.
 */
@AutoConfiguration(
    before = [
        ServletWebSecurityAutoConfiguration::class,
        ManagementWebSecurityAutoConfiguration::class,
    ],
)
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HttpSecurity::class, EndpointRequest::class)
@ConditionalOnProperty("kiwi.web.management-security.enabled", matchIfMissing = false)
class KiwiManagementSecurityAutoConfiguration(private val props: KiwiFrameworkProperties) {

    companion object {
        const val MANAGEMENT_SECURITY_FILTER_CHAIN_ORDER = -100
    }

    // No @ConditionalOnBean(HttpSecurity): The prototype bean is registered by @EnableWebSecurity of the Spring Boot
    // security auto-configurations, which are processed after this auto-configuration
    @Bean
    @Order(MANAGEMENT_SECURITY_FILTER_CHAIN_ORDER) // Before other SecurityFilterChain beans
    fun kiwiManagementFilterChain(http: HttpSecurity): SecurityFilterChain {
        val config = props.web.managementSecurity
        check(config.enabled) { "Illegal configuration: ${::kiwiManagementFilterChain.name} should not be enabled" }

        val passwordEncoding = config.managementPasswordEncoding
        val passwordEncoder = managementPasswordEncoder(passwordEncoding)

        val username = config.managementUsername
        val password = requireNotNull(config.managementPassword?.takeIf { it.isNotBlank() }) {
            "Missing kiwi.web.management-security.management-password"
        }
        require(!passwordEncoder.upgradeEncoding(password)) {
            "Password kiwi.web.management-security.management-password is not properly encoded"
        }

        log.info("Kiwi ManagementSecurity enabled: Username \"$username\", ${passwordEncoding.name} encoded password")

        val managementRole = "KIWI_MANAGEMENT"
        val managementUser = SpringUser.withUsername(username)
            .password(password)
            .roles(managementRole)
            .build()

        val managementAuthenticationProvider = DaoAuthenticationProvider(InMemoryUserDetailsManager(managementUser))
        managementAuthenticationProvider.setPasswordEncoder(passwordEncoder)

        val permitAllEndpoints = config.permitAllEndpoints.toTypedArray()

        http {
            securityMatcher(EndpointRequest.toAnyEndpoint())
            authenticationManager = ProviderManager(managementAuthenticationProvider)
            authorizeHttpRequests {
                authorize(EndpointRequest.to(*permitAllEndpoints), permitAll)
                authorize(anyRequest, hasRole(managementRole))
            }

            anonymous { }
            httpBasic { realmName = config.httpBasicRealmName }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }

            cors { disable() }
            csrf { disable() }
            formLogin { disable() }
            logout { disable() }
        }

        return http.build()
    }

    @Suppress("DEPRECATION")
    private fun managementPasswordEncoder(passwordEncoding: ManagementPasswordEncoding): PasswordEncoder {
        return when (passwordEncoding) {
            ManagementPasswordEncoding.BCrypt -> BCryptPasswordEncoder()
            ManagementPasswordEncoding.Plain -> org.springframework.security.crypto.password.NoOpPasswordEncoder
                .getInstance().also { log.warn("Kiwi ManagementSecurity using plain-text management password") }
        }
    }
}
