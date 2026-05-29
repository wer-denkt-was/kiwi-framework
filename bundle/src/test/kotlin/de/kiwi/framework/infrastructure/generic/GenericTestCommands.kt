package de.kiwi.framework.infrastructure.generic

import de.kiwi.api.domain.AccessControl
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.generic.GenericDelete
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.util.where
import org.springframework.data.jpa.domain.Specification

class CreatePublisher : GenericCreate<Publisher>() {
    override val entityFieldset = PublisherFieldsets.default
    override fun entityId(entity: Publisher) = entity.id
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

class UpdatePublisher : GenericUpdate<Publisher, PublisherId>() {
    override val entityFieldset = PublisherFieldsets.default
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

class DeletePublisher : GenericDelete<Publisher, PublisherId>() {
    override val entityFieldset = PublisherFieldsets.default
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

class PublisherQuery : GenericEntityQuery<Publisher>() {
    override fun filterById(id: EntityId): Specification<Publisher> = baseEntityFilter(id)

    override fun customFilter(user: UserDescriptor): Specification<Publisher>? = null

    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

class PublicationQuery : GenericEntityQuery<Publication>() {
    override fun filterById(id: EntityId) = baseEntityFilter<Publication>(id)

    override fun customFilter(user: UserDescriptor) = null

    override fun preAuthorize(user: UserDescriptor) {}
}

class AuthorQuery : GenericEntityQuery<Author>() {
    override fun filterById(id: EntityId) = baseEntityFilter<Author>(id)

    override fun customFilter(user: UserDescriptor) = null

    override fun preAuthorize(user: UserDescriptor) {}
}

open class PublicationReportQuery : AnalyticalReportQuery<Publication>() {
    override val root = Publication_.class_!!

    override val fields by defineFields {
        dimension("firstPublishedDate", date(attribute(Publication_.firstPublishedOn)))
        dimension("firstPublishedYear", year(attribute(Publication_.firstPublishedOn)))
        dimension("firstPublishedMonth", month(attribute(Publication_.firstPublishedOn)))
        dimension("firstPublishedYearMonth", yearMonth(attribute(Publication_.firstPublishedOn)))
        dimension("type", entityType())
        dimension("publisherId", attribute(Publication_.publisher, Publisher_.id))
        dimension("publisherName", attribute(Publication_.publisher, Publisher_.name))
        dimension("publisherCountry", attribute(Publication_.publisher, Publisher_.contact, Contact_.country))
        dimension("bookType", cast(Book::class, attribute(Book_.bookType)))
        dimension("numberOfAuthors", subquery(Publication_.authors, count()))

        metric("total", count())
        metric("books", countOfType(Book::class))
        metric("magazines", countOfType(Magazine::class))
    }

    override fun preAuthorize(user: UserDescriptor) {}
}

class AuthorReportQuery : AnalyticalReportQuery<Author>() {
    override val root = Author_.class_!!

    override val fields by defineFields {
        dimension("hasEmail", select { author -> isNotNull(author[Author_.email]) })
        dimension("country", attribute(Author_.contact, Contact_.country))

        metric("total", count())
        metric("authoredPublications", sum(subquery(Author_.publications, count())))
        metric("authoredBooks", sum(subquery(Author_.publications, countOfType(Book::class))))
        metric("authoredMagazines", sum(subquery(Author_.publications, countOfType(Magazine::class))))
    }

    override fun preAuthorize(user: UserDescriptor) {}
}
