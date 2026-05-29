package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.domain.Validation
import de.kiwi.api.schema.*
import de.kiwi.framework.setupEntityManagerFactory
import de.kiwi.framework.web.DynamicFieldsetParser
import jakarta.persistence.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue

// Uses schema of Entities "A", "B", "C", and "D"
class InternalFieldsetValidationsTest {

    companion object {
        lateinit var entityManagerFactory: EntityManagerFactory

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            entityManagerFactory = setupEntityManagerFactory(
                packagesToScan = listOf("de.kiwi.framework.infrastructure.schema"),
                showSql = false,
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            entityManagerFactory.close()
        }
    }

    lateinit var entityManager: EntityManager
    private val dynamicFieldsetParser = DynamicFieldsetParser(InternalTestSchemaProvider)

    @BeforeEach
    fun setUp() {
        entityManager = entityManagerFactory.createEntityManager()
    }

    @AfterEach
    fun tearDown() {
        entityManager.close()
    }

    @Test
    fun validateMaxDepth() {
        val maxDepth2 = MaxDepthFieldsetValidation(maxDepth = 2)

        maxDepth2.assertValid<A>("id") // A
        maxDepth2.assertValid<A>("id,bs:(id)") // A -< 1 B
        maxDepth2.assertValid<A>("id,bs:(id,cs:(id))") // A -< 1 B -< 2 C
        maxDepth2.assertInvalid<A>( // A -< 1 B -< 2 C -< 3 D
            "id,bs:(id,cs:(id,ds:(id)))",
            "Max depth of 2 exceeded"
        )

        maxDepth2.assertValid<A>("id,bs:(id,a:(id,bs:(id)))") // A -< 1 B -> A -< 2 B
        maxDepth2.assertValid<A>("id,bs:(id,a:(id,bs:(id,a:(id))))") // A -< 1 B -> A -< 2 B -> A
        maxDepth2.assertInvalid<A>( // A -< 1 B -> A -< 2 B -> A -< 3 B
            "id,bs:(id,a:(id,bs:(id,a:(id,bs:(id)))))",
            "Max depth of 2 exceeded"
        )
        maxDepth2.assertInvalid<A>( // A -< 1 B -> A -< 2 B -< 3 C
            "id,bs:(id,a:(id,bs:(id,cs:(id))))",
            "Max depth of 2 exceeded"
        )

        maxDepth2.assertValid<D>("id,c:(b:(a:(id)))") // D -> C -> B -> A
        maxDepth2.assertValid<D>("id,c:(b:(a:(bs:(id))))") // D -> C -> B -> A -< 1 B
        maxDepth2.assertValid<D>("id,c:(b:(a:(bs:(cs:(id)))))") // D -> C -> B -> A -< 1 B -< 2 C
        maxDepth2.assertInvalid<D>( // D -> C -> B -> A -< 1 B -< 2 C -< 3 D
            "id,c:(b:(a:(bs:(cs:(ds:(id))))))",
            "Max depth of 2 exceeded"
        )
    }

    @Test
    fun validateNoCycles() {
        val noCycles = NonRecursiveFieldsetValidation()

        noCycles.assertValid<A>("id") // A
        noCycles.assertValid<A>("id,bs:(id)") // A -< B
        noCycles.assertValid<A>("id,bs:(cs:(id))") // A -< B -< C
        noCycles.assertValid<A>("id,bs:(cs:(ds:(id)))") // A -< B -< C -< D
        noCycles.assertInvalid<A>( // A -< B -< C -< * D -> C -< * D
            "id,bs:(cs:(ds:(c:(ds:(id)))))",
            """Cycle in fieldset: A.bs -> B.cs -> C.ds -> D.c -> C.ds -> D"""
        )
    }

    private inline fun <reified E : Any> FieldsetValidation.assertValid(fieldsetExpression: String) {
        val fieldset = parseFieldset(E::class.java, fieldsetExpression)
        println("valid fieldset ${fieldset.info()}")
        val result = validate(fieldset)
        println("-> actual ${result.info()}")

        result.getOrElse { reason ->
            val entityTypeName = E::class.java.simpleName
            fail("Fieldset [$entityTypeName] \"$fieldsetExpression\" is invalid: $reason")
        }
    }

    private inline fun <reified E : Any> FieldsetValidation.assertInvalid(
        fieldsetExpression: String,
        expectedReason: String,
    ) {
        val fieldset = parseFieldset(E::class.java, fieldsetExpression)
        println("invalid fieldset ${fieldset.info()}")
        val result = validate(fieldset)
        println("-> actual ${result.info()}")

        when (result) {
            is Validation.Result.Valid<*> -> {
                val entityTypeName = E::class.java.simpleName
                fail("""Fieldset [$entityTypeName] "$fieldsetExpression" should be invalid""")
            }

            is Validation.Result.Invalid<*> -> {
                val reasonMatch = result.reason.equals(expectedReason, ignoreCase = true)
                assertTrue(reasonMatch) {
                    val entityTypeName = E::class.java.simpleName
                    """Fieldset [$entityTypeName] "$fieldsetExpression" invalid reason doesn't match: Expected "$expectedReason", but was "${result.reason}""""
                }
            }
        }
    }

    private fun <E : Any> parseFieldset(entityClass: Class<E>, fieldsetExpression: String): Fieldset<E> {
        val schema = InternalTestSchemaProvider.findEntitySchema(entityClass)!!
        val fieldsets = object : FieldsetsAvailable<E>(schema) {
            override val default get() = schema.implicitDefaultFieldset()
        }
        return dynamicFieldsetParser.parseFieldset(fieldsets, fieldsetExpression)
    }

    private fun Fieldset<*>.info(): String = when (this) {
        is EntityFieldset<*> -> buildString {
            val fieldInfos = buildList {
                addAll(columns.map { it.fieldName })
                addAll(toOne.map { (field, targetFieldset) ->
                    "${field.fieldName} -> ${targetFieldset.info()}"
                })
                addAll(toMany.map { (field, targetFieldset) ->
                    "${field.fieldName} -< ${targetFieldset.info()}"
                })
                addAll(toManyMap.map { (field, targetFieldsets) ->
                    "${field.fieldName} -< [${targetFieldsets.first.info()}, ${targetFieldsets.second.info()}]"
                })
            }

            this.append("(${entityType.simpleName}) { ")
            this.append(fieldInfos.joinToString(separator = ", "))
            this.append(" }")
        }

        is SingleColumnFieldset<*, *, *> -> "SingleColumn(${this.entityType.simpleName}.${this.singleColumn.fieldName})"
        is SingleValueFieldset<*, *> -> "SingleValue(${this.entityType.simpleName})"
    }

    private fun Validation.Result<*>.info() = when (this) {
        is Validation.Result.Valid<*> -> "Valid"
        is Validation.Result.Invalid<*> -> "Invalid, reason = ${this.reason}"
    }
}

// -- Entities --

@Entity
private class A(@Id val id: Int) {
    @OneToMany(mappedBy = "a")
    val bs = mutableSetOf<B>()
}

@Entity
private class B(@Id val id: Int, @ManyToOne val a: A) {
    @OneToMany(mappedBy = "b")
    val cs = mutableSetOf<C>()
}

@Entity
private class C(@Id val id: Int, @ManyToOne val b: B) {
    @OneToMany(mappedBy = "c")
    val ds = mutableSetOf<D>()
}

@Entity
private class D(@Id val id: Int, @ManyToOne val c: C)

// -- Schemas --

private object ASchema : EntitySchema<A>() {
    val id by primaryKey(A::id)
    val bs by toMany(A::bs, BSchema)
}

private object BSchema : EntitySchema<B>() {
    val id by primaryKey(B::id)
    val a by toOne(B::a, ASchema)
    val cs by toMany(B::cs, CSchema)
}

private object CSchema : EntitySchema<C>() {
    val id by primaryKey(C::id)
    val b by toOne(C::b, BSchema)
    val ds by toMany(C::ds, DSchema)
}

private object DSchema : EntitySchema<D>() {
    val id by primaryKey(D::id)
    val c by toOne(D::c, CSchema)
}

// -- Schema Provider --

private object InternalTestSchemaProvider : QuerySchemaProvider {
    override val schemas get() = listOf(ASchema, BSchema, CSchema, DSchema)

    @Suppress("UNCHECKED_CAST")
    override fun <E : Any> findEntitySchema(entityType: Class<E>): EntitySchema<E>? {
        return schemas.find { it.entityClass == entityType } as EntitySchema<E>?
    }

    override fun <E : Any> findAvailableFieldsets(entityType: Class<E>) = null

    override fun <E : Any> findFilterType(entityType: Class<E>) = null
}
