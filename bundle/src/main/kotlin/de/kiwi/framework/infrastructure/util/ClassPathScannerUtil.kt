package de.kiwi.framework.infrastructure.util

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.EnvironmentAware
import org.springframework.context.ResourceLoaderAware
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ComponentScan
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.core.type.filter.TypeFilter
import org.springframework.util.ClassUtils
import kotlin.reflect.KClass

/**
 * Utility for scanning and processing beans or classes on the application classpath
 */
class ClassPathScannerUtil : BeanFactoryAware, ResourceLoaderAware, EnvironmentAware {
    private lateinit var beanFactory: ConfigurableListableBeanFactory
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var environment: Environment

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ConfigurableListableBeanFactory
    }

    override fun setResourceLoader(resourceLoader: ResourceLoader) {
        this.resourceLoader = resourceLoader
    }

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    fun findEntityAndComponentBasePackages(): List<String> {
        val entityPackages = findBaseScanPackages<EntityScan>({ it.basePackages }, { it.basePackageClasses })
        val componentPackages = findBaseScanPackages<ComponentScan>({ it.basePackages }, { it.basePackageClasses })
        return entityPackages + componentPackages
    }

    private inline fun <reified A : Annotation> findBaseScanPackages(
        noinline getPackages: (A) -> Array<String> = { emptyArray<String>() },
        noinline getPackageClasses: (A) -> Array<KClass<*>> = { emptyArray<KClass<*>>() },
    ): List<String> =
        findBaseScanPackages(A::class.java, getPackages, getPackageClasses)

    private fun <A : Annotation> findBaseScanPackages(
        annotationClass: Class<A>,
        getPackages: (A) -> Array<String> = { emptyArray<String>() },
        getPackageClasses: (A) -> Array<KClass<*>> = { emptyArray<KClass<*>>() },
    ): List<String> {
        return beanFactory
            .getBeanNamesForAnnotation(annotationClass)
            .mapNotNull { name ->
                val def = beanFactory.getBeanDefinition(name)
                val annotation = beanFactory.findAnnotationOnBean(name, annotationClass)
                annotation?.let { def to it }
            }
            .flatMap { (beanDefinition, annotation) ->
                getPackages(annotation).ifNotEmpty()?.toList()
                    ?: getPackageClasses(annotation).ifNotEmpty()?.toPackageNames()
                    ?: beanDefinition.beanPackageName?.let { setOf(it) }
                    ?: emptyList()
            }
    }

    private fun <T> Array<T>.ifNotEmpty() = takeIf { it.isNotEmpty() }

    private fun Array<KClass<*>>.toPackageNames() = map { ClassUtils.getPackageName(it.java) }

    private val BeanDefinition.beanPackageName get() = beanClassName?.let { ClassUtils.getPackageName(it) }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> findClassesOfTypeInPackages(
        scanPackages: Collection<String>,
    ): List<Class<out T>> =
        findClassesOfTypeInPackages(T::class.java, scanPackages)
            .map { it as Class<out T> }

    fun <T : Any> findClassesOfTypeInPackages(
        baseClass: Class<T>,
        scanPackages: Collection<String>,
    ): List<Class<*>> =
        findClassesWithFilterInPackages(AssignableTypeFilter(baseClass), scanPackages)

    inline fun <reified A : Annotation> findAnnotatedClassesInPackages(
        scanPackages: Collection<String>,
    ): List<Class<*>> =
        findAnnotatedClassesInPackages(A::class.java, scanPackages)

    fun findAnnotatedClassesInPackages(
        annotationClass: Class<out Annotation>,
        scanPackages: Collection<String>,
    ): List<Class<*>> =
        findClassesWithFilterInPackages(AnnotationTypeFilter(annotationClass), scanPackages)

    private fun findClassesWithFilterInPackages(
        includeFilter: TypeFilter,
        scanPackages: Collection<String>,
    ): List<Class<*>> {
        val classLoader = resourceLoader.classLoader
        val scanner = ClassPathScanningCandidateComponentProvider(false, environment)
        scanner.resourceLoader = resourceLoader
        scanner.addIncludeFilter(includeFilter)

        return scanPackages
            .flatMap { scanner.findCandidateComponents(it) }
            .filterNot { it.isAbstract } // Ignore abstract classes
            .mapNotNull { it.beanClassName }
            .map { ClassUtils.forName(it, classLoader) }
    }
}
