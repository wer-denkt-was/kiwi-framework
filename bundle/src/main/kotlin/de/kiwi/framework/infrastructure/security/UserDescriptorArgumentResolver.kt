package de.kiwi.framework.infrastructure.security

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class UserDescriptorArgumentResolver(
    private val userDescriptorProvider: UserDescriptorProvider,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return UserDescriptor::class.java.isAssignableFrom(parameter.parameterType)
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): UserDescriptor {
        return userDescriptorProvider.currentUser()
    }
}
