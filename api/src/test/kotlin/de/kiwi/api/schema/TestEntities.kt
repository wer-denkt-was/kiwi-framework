package de.kiwi.api.schema

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*
import java.time.LocalDate

@Embeddable
class MyId(value: Long) : EntityId(value)

@Entity
class MyEntity(
    id: MyId,

    @Column(name = "foo", nullable = false)
    var foo: String,

    @Column(name = "bar", nullable = true)
    var bar: Int?,

    @Column(name = "baz", nullable = false, updatable = false)
    val baz: Boolean,

    @Column(name = "date", nullable = true)
    val date: LocalDate,

    @Embedded
    val qux: NestedQux,
) : BaseEntity<MyId>(id) {

    @OneToMany(mappedBy = "parent")
    val children: MutableSet<MyChild> = HashSet()
}

@Embeddable
data class NestedQux(
    @Column(name = "`first`")
    val first: String,
    @Column(name = "`second`")
    val second: String,
)

@Embeddable
class ChildId(value: Long) : EntityId(value)

@Entity
class MyChild(
    id: ChildId,

    @ManyToOne
    @JoinColumn(name = "parent_id")
    var parent: MyEntity,

    @Column(name = "`message`", nullable = true)
    var message: String,
) : BaseEntity<ChildId>(id)
