package de.kiwi.framework.example

import de.kiwi.api.domain.*
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Embeddable
class ExampleId(value: Long) : EntityId(value)

@Entity
@Table(name = "example")
class ExampleEntity(
    id: ExampleId,

    @Column(name = "name", length = 255, nullable = false)
    var name: String,

    @Column(name = "optional", length = 255, nullable = true)
    var optional: String? = null,
) : BaseEntity<ExampleId>(id) {
    companion object {
        val validName = ExampleEntity::name.validateColumn() and Validation.notBlank

        fun create(
            name: String,
            optional: String? = null,
        ) = DomainResult { _ ->
            //TODO: Test with returning DomainEvent in create function
            ExampleEntity(
                id = EntityId.generate<ExampleId>(),
                name = validName(name),
                optional = optional
            )
        }
    }
}
