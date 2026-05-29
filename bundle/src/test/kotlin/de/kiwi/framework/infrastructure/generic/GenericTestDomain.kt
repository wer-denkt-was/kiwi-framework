package de.kiwi.framework.infrastructure.generic

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.Validation
import de.kiwi.api.domain.and
import de.kiwi.api.domain.validateColumn
import jakarta.persistence.*
import java.time.LocalDate

@Embeddable
class PublisherId(value: Long) : EntityId(value)

@Entity
@Table(name = "publisher")
class Publisher(
    id: PublisherId,
    @Column(name = "name", length = 1024) var name: String,
    @Embedded var contact: Contact,
) : BaseEntity<PublisherId>(id) {
    @OneToMany(mappedBy = "publisher")
    val publications: MutableSet<Publication> = HashSet()

    companion object {
        val validName = Publisher::name.validateColumn() and Validation.notBlank
    }
}

@Embeddable
data class Contact(
    @Column(name = "country", length = 255, nullable = true) val country: String? = null,
    @Column(name = "state", length = 255, nullable = true) val state: String? = null,
    @Column(name = "city", length = 1024, nullable = true) val city: String? = null,
    @Column(name = "zip_code", length = 255, nullable = true) val zipCode: String? = null,
    @Column(name = "street_line_1", length = 1024, nullable = true) val streetAddressLine1: String? = null,
    @Column(name = "street_line_2", length = 1024, nullable = true) val streetAddressLine2: String? = null,
    @Column(name = "phone", length = 255, nullable = true) val phoneNumber: String? = null,
)

@Embeddable
class AuthorId(value: Long) : EntityId(value)

@Entity
@Table(name = "author")
class Author(
    id: AuthorId,
    @Column(name = "email", length = 255, nullable = true) var email: String?,
    @Column(name = "full_name", columnDefinition = "mediumtext", nullable = false) var fullName: String,
    @Embedded var contact: Contact,
) : BaseEntity<AuthorId>(id) {

    @ManyToMany
    @JoinTable(
        name = "author_publication",
        joinColumns = [JoinColumn(name = "author_id")],
        inverseJoinColumns = [JoinColumn(name = "publication_id")],
    )
    val publications: MutableSet<Publication> = HashSet()

    fun addPublication(publication: Publication) {
        if (publications.add(publication)) {
            publication.authors.add(this)
        }
    }

    fun removePublication(publication: Publication) {
        if (publications.remove(publication)) {
            publication.authors.remove(this)
        }
    }
}

@Embeddable
class PublicationId(value: Long) : EntityId(value)

@Entity
@Table(name = "publication")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING, length = 255)
abstract class Publication(
    id: PublicationId,
    @ManyToOne(optional = false) @JoinColumn(name = "publisher_id") val publisher: Publisher,
    @Column(name = "title", length = 1024, nullable = false) var title: String,
    @Column(name = "description", columnDefinition = "MEDIUMTEXT", nullable = true) var description: String?,
    @Column(name = "first_published_on", columnDefinition = "DATE", nullable = true) var firstPublishedOn: LocalDate?,
) : BaseEntity<PublicationId>(id) {

    @ManyToMany(mappedBy = "publications")
    val authors: MutableSet<Author> = HashSet()
}

@Entity
@DiscriminatorValue("book")
class Book(
    id: PublicationId,
    publisher: Publisher,
    title: String,
    description: String?,
    firstPublishedOn: LocalDate?,
    @Column(name = "isbn", unique = true) var isbn: String,
    @Column(name = "book_type") @Enumerated(EnumType.STRING) var bookType: BookType,
    @ManyToOne(optional = true) @JoinColumn(name = "main_author_id", nullable = true) var mainAuthor: Author?,
) : Publication(
    id = id,
    publisher = publisher,
    title = title,
    description = description,
    firstPublishedOn = firstPublishedOn,
)

enum class BookType {
    Paperback,
    Softcover,
    Hardcover,
    EBook,
    Other,
}

@Entity
@DiscriminatorValue("magazine")
class Magazine(
    id: PublicationId,
    publisher: Publisher,
    title: String,
    description: String?,
    firstPublishedOn: LocalDate?,
    @Column(name = "last_published_on", columnDefinition = "DATE") val lastPublishedOn: LocalDate?,
    @Column(name = "publish_cycle") @Enumerated(EnumType.STRING) val publishCycle: PublishCycle,
) : Publication(
    id = id,
    publisher = publisher,
    title = title,
    description = description,
    firstPublishedOn = firstPublishedOn
)

enum class PublishCycle {
    Daily,
    Weekly,
    Biweekly,
    Monthly,
    Bimonthly,
    Quarterly,
    Yearly,
    Irregular,
    Other,
}
