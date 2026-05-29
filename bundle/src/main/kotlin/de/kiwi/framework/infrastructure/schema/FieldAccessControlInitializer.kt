package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.schema.FieldAccessControl
import de.kiwi.api.schema.FieldAccessUserContext
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Configuration
import java.util.*

private val log = slf4j<FieldAccessControlInitializer>()

@Configuration
class FieldAccessControlInitializer {

    @Autowired
    @ConditionalOnBean(UserDescriptorProvider::class)
    @ConditionalOnMissingBean(FieldAccessUserContext::class)
    fun setFieldAccessUserContextFromUserDescriptor(provider: UserDescriptorProvider) {
        log.info("Using UserDescriptorProvider [${provider.javaClass.name}] as source for FieldAccessUserContext")
        FieldAccessControl.provideFieldAccessUserContext { provider.currentUser() }
    }

    @Autowired
    @ConditionalOnBean(FieldAccessUserContext::class)
    fun setFieldAccessUserContextFromBean(bean: Optional<FieldAccessUserContext>) {
        bean.ifPresent { context ->
            log.info("Using bean instance [${context.javaClass.name}] as source for FieldAccessUserContext")
            FieldAccessControl.provideFieldAccessUserContext(context)
        }
    }
}
