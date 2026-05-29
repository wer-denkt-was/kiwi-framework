package de.kiwi.framework.infrastructure.generic

import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.*
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Path

private val searchableColumnType = ColumnType.identity<String>() withFilters StandardColumnFilters.patternMatching

object PublisherSchema : EntitySchema<Publisher>() {
    val id by entityId()
    val name by column(Publisher::name, type = searchableColumnType)
    val contact by column(Publisher::contact)
    val publications by toMany(Publisher::publications, PublicationSchema)

    override fun fieldAccessControl(field: EntitySchemaField<Publisher>): FieldAccessControl<Publisher>? {
        return when (field) {
            contact -> FieldAccessControl(require = { add(name) }) { context, user ->
                println("!!! ACCESS CONTROL [${user.username}] Publisher.contact -> id=${context[id]}, name=${context[name]}")
                true
            }

            publications -> FieldAccessControl(require = { add(name) }) { context, user ->
                println("!!! ACCESS CONTROL [${user.username}] Publisher.publications -> id=${context[id]}, name=${context[name]}")
                true
            }

            else -> null
        }
    }
}

class PublisherFilter : GenericEntityFilter<Publisher>() {
    var nameLike by filter(likeIgnoreCase(attribute(Publisher_.name)) { "%$it%" })
}

object AuthorSchema : EntitySchema<Author>() {
    val id by entityId()
    val email by column(Author::email)
    val fullName by column(Author::fullName)
    val contact by column(Author::contact)
    val publications by toMany(Author::publications, PublicationSchema)
    val publicationCount by column(computed(PublicationCount))

    override fun fieldAccessControl(field: EntitySchemaField<Author>): FieldAccessControl<Author>? {
        return when (field) {
            contact -> FieldAccessControl(require = { add(fullName, email) }) { context, user ->
                println("!!! ACCESS CONTROL [${user.username}] Author.contact -> id=${context[id]}, fullName=${context[fullName]}, email=${context[email]}")
                true
            }

            email -> FieldAccessControl(require = { add(fullName) }) { context, user ->
                println("!!! ACCESS CONTROL [${user.username}] Author.email -> id=${context[id]}, fullName=${context[fullName]}, email=${context[email]}")
                true
            }

            else -> null
        }
    }
}

object PublicationCount : EntityComputation<Author, Int> {
    override fun compute(entity: Author): Int =
        entity.publications.size

    override fun CriteriaBuilder.select(fromEntity: Path<Author>): Expression<Int> =
        size(fromEntity[Author_.publications])
}

class AuthorFilter : GenericEntityFilter<Author>() {
    var fullNameLike by filter(likeIgnoreCase(attribute(Author_.fullName)) { "%$it%" })
    var country by filter(equalTo(attribute(Author_.contact, Contact_.country)))
    var hasPublication by filter(hasChild(Author_.publications, childEntityId()))
}

object PublicationSchema : EntitySchema<Publication>() {
    val id by entityId()
    val type by entityType()
    val discriminator by entityDiscriminator()
    val publisher by toOne(Publication::publisher, PublisherSchema)
    val publisherId by parentEntityId(Publication::publisher)
    val publisherName by column(attribute(Publication::publisher, Publisher::name))
    val authors by toMany(Publication::authors, AuthorSchema)
    val title by column(Publication::title, type = searchableColumnType)
    val description by column(Publication::description)
    val shortDescription by column(Publication::description, ColumnType.map { it.take(140) })
    val firstPublishedOn by column(Publication::firstPublishedOn, IsoLocalDateType)
    val book by subtype(BookSubtype)
    val magazine by subtype(MagazineSubtype)

    object BookSubtype : EntitySchema<Book>() {
        val isbn by column(Book::isbn)
        val bookType by column(Book::bookType, ColumnType.enum<BookType>())
        val mainAuthor by toOne(Book::mainAuthor, AuthorSchema)
        val mainAuthorId by parentEntityId(Book::mainAuthor, nullable = true)
    }

    object MagazineSubtype : EntitySchema<Magazine>() {
        val lastPublishedOn by column(Magazine::lastPublishedOn, IsoLocalDateType)
        val publishCycle by column(Magazine::publishCycle, ColumnType.enum<PublishCycle>())
    }

    override fun fieldAccessControl(field: EntitySchemaField<Publication>): FieldAccessControl<Publication>? {
        return when (field) {
            publisher -> FieldAccessControl(require = { add(id, title) }) { context, user ->
                println("!!! ACCESS CONTROL [${user.username}] Publication.publisher -> id=${context[id]}, name=${context[title]}")
                true
            }

            else -> null
        }
    }
}

class PublicationFilter : GenericEntityFilter<Publication>() {
    var titleLike by filter(onlyWhen({ it.length >= 3 }, likeIgnoreCase(attribute(Publication_.title)) { "%$it%" }))
    var isPublished by filter(ifTrue(attribute(Publication_.firstPublishedOn).isNotNull()))
    var publishedAfter by filter(greaterOrEqual(attribute(Publication_.firstPublishedOn)))
}

object PublisherFieldsets : FieldsetsAvailable<Publisher>(PublisherSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        extendsFieldset(default)
        exclude(PublisherSchema.contact)
    }

    val veryShort by defineFieldset {
        include(PublisherSchema.id, PublisherSchema.name)
    }

    val full by defineFieldset {
        extendsFieldset(default)
        includeToMany(PublisherSchema.publications, PublicationFieldsets.short)
    }

    val extreme by defineFieldset {
        extendsFieldset(full)
        includeToMany(PublisherSchema.publications, PublicationFieldsets.full.extend {
            exclude(PublicationSchema.publisher)
        })
    }
}

object AuthorFieldsets : FieldsetsAvailable<Author>(AuthorSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        extendsFieldset(default)
        exclude(AuthorSchema.contact)
    }

    val veryShort by defineFieldset {
        include(AuthorSchema.id)
    }

    val nameOnly by singleColumnFieldset(AuthorSchema.fullName)

    val withPublications by defineFieldset {
        extendsFieldset(default)
        includeToMany(AuthorSchema.publications, PublicationFieldsets.default.extend {
            //includeToOne(PublicationSchema.publisher, PublisherFieldsets.veryShort)
            includeToOne(PublicationSchema.publisher, PublisherSchema.buildFieldset {
                include(PublisherSchema.id, PublisherSchema.name)
            })
        })
    }
}

object PublicationFieldsets : FieldsetsAvailable<Publication>(PublicationSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        includeSubtype(PublicationSchema.book) { includeAllColumns() }
        //includeSubtype(PublicationSchema.magazine) { includeAllColumns() }
    }

    val short by defineFieldset {
        include(
            PublicationSchema.id,
            PublicationSchema.type,
            PublicationSchema.title,
        )
        includeSubtype(PublicationSchema.book) {
            include(PublicationSchema.BookSubtype.isbn)
        }
    }

    val extended by defineFieldset {
        extendsFieldset(default)
        includeToOne(PublicationSchema.publisher, PublisherFieldsets.short)
        includeToMany(PublicationSchema.authors, AuthorFieldsets.nameOnly)
    }

    val full by defineFieldset {
        extendsFieldset(default)
        includeToOne(PublicationSchema.publisher, PublisherFieldsets.full)
        includeToMany(PublicationSchema.authors, AuthorFieldsets.default)
    }

    val veryInefficientDontDoThis by defineFieldset {
        // This queries Publication -> Publisher -< Publication
        extendsFieldset(default)
        includeToOne(PublicationSchema.publisher, PublisherSchema.buildFieldset {
            include(PublisherSchema.id, PublisherSchema.name)
            includeToMany(PublisherSchema.publications, PublicationFieldsets.short)
        })
    }
}
