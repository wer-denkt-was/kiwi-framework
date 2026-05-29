package de.kiwi.framework.infrastructure.spring

import de.kiwi.api.application.repository.JpaAccessControlExecutor
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.AccessControl
import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.Parameter
import jakarta.persistence.TypedQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import jakarta.persistence.metamodel.SingularAttribute
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.query.QueryUtils
import org.springframework.data.jpa.repository.support.CrudMethodMetadata
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport
import org.springframework.data.jpa.repository.support.JpaRepositoryConfigurationAware
import org.springframework.data.jpa.support.PageableUtils
import org.springframework.data.repository.core.RepositoryMethodContext
import org.springframework.data.repository.core.support.RepositoryMetadataAccess
import org.springframework.data.support.PageableExecutionUtils
import java.util.*
import kotlin.jvm.optionals.getOrNull

class SpringJpaAccessControlExecutor<T : Any, ID : Any>(
    private val entityManager: EntityManager,
    private val userDescriptorProvider: Optional<UserDescriptorProvider>,
) : JpaAccessControlExecutor<T, ID>,
    JpaRepositoryConfigurationAware,
    RepositoryMetadataAccess {

    private var repositoryMetadata: CrudMethodMetadata? = null

    override fun setRepositoryMethodMetadata(metadata: CrudMethodMetadata) {
        repositoryMetadata = metadata
    }

    override fun findAuthorized(
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): List<T> {
        return findAuthorized(
            sort = Sort.unsorted(),
            accessControl = accessControl,
            user = user,
            allowAdmin = allowAdmin
        )
    }

    override fun findAuthorized(
        sort: Sort,
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): List<T> {
        val specification = accessControl.toSpecification(user, allowAdmin)
        val query = createEntityQuery(specification, sort)
        return query.toTypedQuery().resultList
    }

    override fun findAuthorized(
        pageable: Pageable,
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): Page<T> {
        val specification = accessControl.toSpecification(user, allowAdmin)
        val entityQuery = createEntityQuery(specification, pageable.sort)
        val typedQuery = entityQuery.toTypedQuery()
        return if (pageable.isUnpaged) PageImpl(typedQuery.resultList)
        else typedQuery.resultPage(pageable, specification)
    }

    override fun findAuthorizedByIds(
        ids: Iterable<ID>,
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): List<T> {
        val idList = ids.toList().ifEmpty { return emptyList() }
        val idInListSpecification = ByIdInListSpecification()
        val specification = idInListSpecification.and(accessControl.toSpecification(user, allowAdmin))

        val query = createEntityQuery(specification, Sort.unsorted())
        return query.toTypedQuery()
            .setParameter(idInListSpecification.idParameter, idList)
            .resultList
    }

    override fun findAuthorizedById(
        id: ID,
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): Optional<T> {
        val byIdSpecification = ByIdSpecification(id)
        val specification = byIdSpecification.and(accessControl.toSpecification(user, allowAdmin))
        val query = createEntityQuery(specification, Sort.unsorted())

        return try {
            Optional.of(query.toTypedQuery().singleResult)
        } catch (_: NoResultException) {
            Optional.empty()
        }
    }

    override fun existsAuthorizedById(
        id: ID,
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): Boolean {
        val byIdSpecification = ByIdSpecification(id)
        val specification = byIdSpecification.and(accessControl.toSpecification(user, allowAdmin))

        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(Int::class.java)
        query.fromDomainRootApplySpecification(specification)
        query.select(builder.literal(1))
        query.orderBy(emptyList()) // Remove irrelevant order-by clause that may have been added by the Specification

        val resultCount = query.toTypedQuery()
            .setMaxResults(1)
            .resultList.size

        return resultCount == 1
    }

    override fun countAuthorized(
        accessControl: AccessControl<T>,
        user: UserDescriptor?,
        allowAdmin: Boolean,
    ): Long {
        val specification = accessControl.toSpecification(user, allowAdmin)
        val countQuery = createCountQuery(specification)
        return countQuery.toTypedQuery(forCount = true).resultTotalCount()
    }

    private fun createEntityQuery(specification: Specification<T>, sort: Sort): CriteriaQuery<T> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(domainEntityClass())
        val root = query.fromDomainRootApplySpecification(specification)
        query.select(root)
        if (sort.isSorted) query.orderBy(QueryUtils.toOrders(sort, root, builder))
        return query
    }

    private fun createCountQuery(specification: Specification<T>): CriteriaQuery<Long> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(Long::class.java)
        val root = query.fromDomainRootApplySpecification(specification)
        query.select(if (query.isDistinct) builder.countDistinct(root) else builder.count(root))
        query.orderBy(emptyList()) // Remove irrelevant order-by clause that may have been added by the Specification
        return query
    }

    @Suppress("UNCHECKED_CAST")
    private fun domainEntityClass(): Class<T> {
        val context = RepositoryMethodContext.getContext()
        return context.metadata.domainType as Class<T>
    }

    @Suppress("UNCHECKED_CAST")
    private fun domainEntityInfo(): JpaEntityInformation<T, ID> {
        val entityClass = domainEntityClass()
        val entityInfo = JpaEntityInformationSupport.getEntityInformation(entityClass, entityManager)
        return entityInfo as JpaEntityInformation<T, ID>
    }

    private fun AccessControl<T>.toSpecification(
        givenUser: UserDescriptor?,
        allowAdmin: Boolean,
    ): Specification<T> {
        val actualUser = givenUser ?: currentProvidedUser() ?: UserDescriptor.ANONYMOUS
        return if (allowAdmin) adminOrCheckAuthorized(actualUser)
        else checkAuthorized(actualUser)
    }

    private fun currentProvidedUser(): UserDescriptor? {
        val provider = userDescriptorProvider.getOrNull() ?: return null
        return provider.currentUser()
    }

    private fun <R> CriteriaQuery<R>.fromDomainRootApplySpecification(specification: Specification<T>?): Root<T> {
        val builder = entityManager.criteriaBuilder
        val root = from(domainEntityClass())
        specification?.toPredicate(root, this, builder)?.let { where(it) }
        return root
    }

    private fun <R> CriteriaQuery<R>.toTypedQuery(forCount: Boolean = false): TypedQuery<R> {
        val typedQuery = entityManager.createQuery(this)

        repositoryMetadata?.let { metadata ->
            metadata.lockModeType?.let { typedQuery.setLockMode(it) }
            val hints = if (forCount) metadata.queryHintsForCount else metadata.queryHints
            hints.withFetchGraphs(entityManager).forEach(typedQuery::setHint)
        }

        return typedQuery
    }

    private fun TypedQuery<T>.resultPage(
        pageable: Pageable,
        specification: Specification<T>,
    ): Page<T> {
        if (pageable.isPaged) {
            firstResult = PageableUtils.getOffsetAsInteger(pageable)
            maxResults = pageable.pageSize
        }
        return PageableExecutionUtils.getPage(resultList, pageable) {
            createCountQuery(specification).toTypedQuery(forCount = true).resultTotalCount()
        }
    }

    private fun TypedQuery<Long>.resultTotalCount(): Long {
        val totals = resultList.ifEmpty { return 0L }
        return totals.fold(0L) { sum, item -> sum + (item ?: 0) }
    }

    private inner class ByIdInListSpecification : Specification<T> {
        lateinit var idParameter: Parameter<Collection<ID>>

        @Suppress("UNCHECKED_CAST")
        override fun toPredicate(root: Root<T>, query: CriteriaQuery<*>?, builder: CriteriaBuilder): Predicate {
            val entityInformation = domainEntityInfo()
            val idAttribute = entityInformation.requiredIdAttribute as SingularAttribute<T, ID>
            idParameter = builder.parameter(Collection::class.java as Class<Collection<ID>>)
            return root[idAttribute].`in`(idParameter)
        }
    }

    private inner class ByIdSpecification(private val id: ID) : Specification<T> {
        @Suppress("UNCHECKED_CAST")
        override fun toPredicate(root: Root<T>, query: CriteriaQuery<*>?, builder: CriteriaBuilder): Predicate {
            val entityInformation = domainEntityInfo()
            val idAttribute = entityInformation.requiredIdAttribute as SingularAttribute<T, ID>
            return builder.equal(root[idAttribute], id)
        }
    }
}
