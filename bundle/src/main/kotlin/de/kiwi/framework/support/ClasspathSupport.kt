package de.kiwi.framework.support

import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.findAnnotationOnBean
import org.springframework.beans.factory.getBeanNamesForAnnotation
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ComponentScan
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.core.type.filter.TypeFilter
import org.springframework.util.ClassUtils
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

private val log = slf4j<ClasspathSupport>()

/**
 * Helper class for classpath scanning using a Spring [ResourceLoader] and [Environment].
 */
internal class ClasspathSupport(
    private val resourceLoader: ResourceLoader,
    private val environment: Environment? = null,
) {
    inline fun <reified T : Any> findClassesOfTypeInPackages(
        packageNames: Collection<String>,
    ) = findClassesOfTypeInPackages(
        baseClass = T::class.java,
        packageNames = packageNames
    )

    fun <T : Any> findClassesOfTypeInPackages(
        baseClass: Class<T>,
        packageNames: Collection<String>,
    ): List<Class<out T>> {
        return findClassesByFilterInPackages(
            packageNames = packageNames,
            AssignableTypeFilter(baseClass),
        )
    }

    inline fun <reified A : Annotation, reified T : Any> findAnnotatedClassesInPackages(
        packageNames: Collection<String>,
    ) = findAnnotatedClassesInPackages(
        baseClass = T::class.java,
        annotationClass = A::class.java,
        packageNames = packageNames
    )

    fun <A : Annotation, T : Any> findAnnotatedClassesInPackages(
        baseClass: Class<T>,
        annotationClass: Class<A>,
        packageNames: Collection<String>,
    ): List<Class<out T>> {
        return findClassesByFilterInPackages(
            packageNames = packageNames,
            AssignableTypeFilter(baseClass),
            AnnotationTypeFilter(annotationClass),
        )
    }

    private fun <T : Any> findClassesByFilterInPackages(
        packageNames: Collection<String>,
        vararg filter: TypeFilter,
    ): List<Class<out T>> {
        val scanner = environment
            ?.let { ClassPathScanningCandidateComponentProvider(false, it) }
            ?: ClassPathScanningCandidateComponentProvider(false)

        scanner.resourceLoader = resourceLoader
        scanner.addIncludeFilter { r, rf -> filter.all { it.match(r, rf) } }

        return packageNames.flatMap { packageName ->
            val filterInfo = filter.joinToString(", ") { typeFilterInfo(it) }
            log.debug("Scanning [$packageName] for [$filterInfo] ...")
            scanner.findCandidateComponents(packageName).mapNotNull { beanDef ->
                beanDef.beanClassName.also { className -> log.debug("> Found $className") }
            }.map { className ->
                @Suppress("UNCHECKED_CAST")
                ClassUtils.forName(className, resourceLoader.classLoader) as Class<out T>
            }.filter { clazz ->
                // Ignore abstract or non-public classes
                !Modifier.isAbstract(clazz.modifiers) &&
                        Modifier.isPublic(clazz.modifiers)
            }
        }.distinct()
    }

    private fun typeFilterInfo(typeFilter: TypeFilter): String = when (typeFilter) {
        is AssignableTypeFilter -> "Assignable(${typeFilter.targetType.simpleName})"
        is AnnotationTypeFilter -> "Annotation(${typeFilter.annotationType.simpleName})"
        else -> typeFilter.toString()
    }

    inline fun <reified T : Any> findBeanClassesOfTypeInContext(context: ApplicationContext) =
        findBeanClassesOfTypeInContext(T::class.java, context)

    fun <T : Any> findBeanClassesOfTypeInContext(
        baseClass: Class<T>,
        context: ApplicationContext,
    ): List<Class<out T>> {
        log.debug("Scanning context [${context.id}] for [${baseClass.name}] ...")
        return context.getBeansOfType(baseClass).values.map {
            it.javaClass.also { beanClass -> log.debug("> Found ${beanClass.name}") }
        }
    }

    fun findEntityScanPackages(context: ApplicationContext): List<String> {
        log.debug("Scanning for EntityScan packages in [${context.id}] ...")
        return annotatedBeanClasses<EntityScan>(context)
            .flatMap { (cls, a) -> packagesFromAnnotation(cls, a.basePackages, a.basePackageClasses) }
            .onEach { log.debug("> Found EntityScan package: $it") }
    }

    fun findComponentScanPackages(context: ApplicationContext): List<String> {
        log.debug("Scanning for ComponentScan packages in [${context.id}] ...")
        return annotatedBeanClasses<ComponentScan>(context)
            .flatMap { (cls, a) -> packagesFromAnnotation(cls, a.basePackages, a.basePackageClasses) }
            .onEach { log.debug("> Found ComponentScan package: $it") }
    }

    private inline fun <reified A : Annotation> annotatedBeanClasses(
        context: ApplicationContext,
    ): List<Pair<Class<*>, A>> {
        return context
            .getBeanNamesForAnnotation<A>()
            .mapNotNull { beanName ->
                val beanClass = context.getBean(beanName).javaClass
                val annotation = context.findAnnotationOnBean<A>(beanName)
                annotation?.let { beanClass to it }
            }
    }

    private fun packagesFromAnnotation(
        beanClass: Class<*>,
        basePackages: Array<String>,
        basePackageClasses: Array<KClass<*>>,
    ): List<String> {
        return basePackages.takeIf { it.isNotEmpty() }?.toList()
            ?: basePackageClasses.takeIf { it.isNotEmpty() }?.map { ClassUtils.getPackageName(it.java) }
            ?: listOf(ClassUtils.getPackageName(beanClass))
    }
}
