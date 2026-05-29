package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.domain.EntityId
import de.kiwi.api.util.flushAndClear
import de.kiwi.framework.infrastructure.generic.*
import jakarta.persistence.EntityManager
import java.time.LocalDate
import kotlin.reflect.KClass

class GenericExampleData(entityManager: EntityManager) {
    val publisher1: Publisher = entityManager.merge(
        Publisher(
            id = EntityId.generate<PublisherId>(),
            name = "Big Books Inc.",
            contact = Contact(/*TODO*/)
        )
    )
    val publisher2: Publisher = entityManager.merge(
        Publisher(
            id = EntityId.generate<PublisherId>(),
            name = "Small Mom'n'Pop Books Ltd.",
            contact = Contact(/*TODO*/)
        )
    )
    val author1: Author = entityManager.merge(
        Author(
            id = EntityId.generate<AuthorId>(),
            fullName = "Foo Barington the Third",
            email = "foo@bar.com",
            contact = Contact(/*TODO*/),
        )
    )
    val author2: Author = entityManager.merge(
        Author(
            id = EntityId.generate<AuthorId>(),
            fullName = "Prof. Nutty Nutmeg",
            email = "nut@meg.co.uk",
            contact = Contact(country = "Great Britain" /*TODO*/)
        )
    )
    val author3: Author = entityManager.merge(
        Author(
            id = EntityId.generate<AuthorId>(),
            fullName = "Shrimply Pibbles",
            email = "shrimply@example.galaxy",
            contact = Contact(country = "Galactic Federation"),
        )
    )
    val book1: Book = entityManager.merge(
        Book(
            id = EntityId.generate<PublicationId>(),
            publisher = publisher1,
            title = "How to draw birds and cook potatoes at midnight",
            description = "wat",
            firstPublishedOn = LocalDate.parse("1972-03-28"),
            isbn = "WAT-WAT-WAT-WAT",
            bookType = BookType.Hardcover,
            mainAuthor = author1,
        )
    )
    val book2: Book = entityManager.merge(
        Book(
            id = EntityId.generate<PublicationId>(),
            publisher = publisher1,
            title = "Some other book without a main author",
            description = null,
            firstPublishedOn = LocalDate.parse("1984-08-04"),
            isbn = "TEST-TEST-TEST",
            bookType = BookType.Paperback,
            mainAuthor = null,
        )
    )
    val book3: Book = entityManager.merge(
        Book(
            id = EntityId.generate<PublicationId>(),
            publisher = publisher2,
            title = "How to achieve intergalactic peace",
            description = null,
            firstPublishedOn = LocalDate.parse("3107-09-11"),
            isbn = "999999999999999999999996",
            bookType = BookType.Other,
            mainAuthor = author3,
        )
    )
    val book4: Book = entityManager.merge(
        Book(
            id = EntityId.generate<PublicationId>(),
            publisher = publisher1,
            title = "How to achieve intergalactic peace, Part 2",
            description = "Turns out there was still some war around",
            firstPublishedOn = LocalDate.parse("3108-12-24"),
            isbn = "999999999999999999999997",
            bookType = BookType.Other,
            mainAuthor = author3,
        )
    )

    val magazine1: Magazine = entityManager.merge(
        Magazine(
            id = EntityId.generate<PublicationId>(),
            publisher = publisher1,
            title = "Dirty pictures (this kitchen really has not been cleaned in a while, jeez)",
            description = null,
            firstPublishedOn = LocalDate.parse("2024-04-01"),
            lastPublishedOn = LocalDate.parse("2024-06-01"),
            publishCycle = PublishCycle.Monthly,
        )
    )

    init {
        author1.addPublication(book1)
        author2.addPublication(book1)
        entityManager.merge(book1)

        author1.addPublication(book2)
        author2.addPublication(book2)
        entityManager.merge(book2)

        author1.addPublication(magazine1)
        entityManager.merge(magazine1)

        author3.addPublication(book3)
        entityManager.merge(book3)
        author3.addPublication(book4)
        entityManager.merge(book4)

        entityManager.flushAndClear()
    }

    fun clear(entityManager: EntityManager) {
        entityManager.deleteAbsolutelyEverything(Publication::class, Author::class, Publisher::class)
        entityManager.flushAndClear()
    }

    private fun EntityManager.deleteAbsolutelyEverything(vararg deleteOrder: KClass<*>) {
        val orderMap = deleteOrder.mapIndexed { index, kClass -> kClass.java to index }.toMap()
        metamodel.entities
            .mapNotNull { entityType -> orderMap[entityType.javaType]?.let { order -> order to entityType } }
            .sortedBy { (order, _) -> order }
            .forEach { (_, entityType) ->
                val builder = criteriaBuilder
                @Suppress("UNCHECKED_CAST") val entityClass = entityType.javaType as Class<Any>
                val delete = builder.createCriteriaDelete(entityClass)
                delete.from(entityClass)
                delete.where(builder.conjunction())
                val query = createQuery(delete)
                val deletedCount = query.executeUpdate()
                println("> Deleted $deletedCount entities of type ${entityType.name}")
            }
    }
}
