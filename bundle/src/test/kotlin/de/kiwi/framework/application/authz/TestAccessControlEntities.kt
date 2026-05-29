package de.kiwi.framework.application.authz

import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.*

class TestUserDescriptor(id: Long) : UserDescriptor {
    override val userId = id
    override val identity = id
    override val username = "descriptor(user:$id)"
    override val authenticated = true
    override val roles = emptySet<String>()
    override val principal = id
}

@Entity
class TestUser(
    @Id @Column(nullable = false) @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val accesses: MutableSet<TestAccess> = HashSet(),
) {
    override fun toString() = "user:$id"
    override fun equals(other: Any?) = (this === other) || (other is TestUser && id == other.id)
    override fun hashCode() = id.hashCode()
}

@Entity
class TestAccess(
    @Id @Column(nullable = false) @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(optional = false) @JoinColumn(nullable = false)
    val user: TestUser,
    @ManyToOne(optional = false) @JoinColumn(nullable = false)
    val granted: TestParent,
    @Column
    var allowChildren: Boolean,
) {
    override fun toString() = "user:${user.id} access[children=$allowChildren] parent:${granted.id}"
    override fun equals(other: Any?) = (this === other) || (other is TestAccess && id == other.id)
    override fun hashCode() = id.hashCode()
}

@Entity
class TestParent(
    @Id @Column(nullable = false) @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL], orphanRemoval = true)
    val children: MutableSet<TestChild> = HashSet(),
) {
    override fun toString() = "parent:$id"
    override fun equals(other: Any?) = (this === other) || (other is TestParent && id == other.id)
    override fun hashCode() = id.hashCode()
}

@Entity
class TestChild(
    @Id @Column(nullable = false) @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(optional = false) @JoinColumn(nullable = false)
    val parent: TestParent,
) {
    override fun toString() = "child:$id"
    override fun equals(other: Any?) = (this === other) || (other is TestChild && id == other.id)
    override fun hashCode() = id.hashCode()
}


