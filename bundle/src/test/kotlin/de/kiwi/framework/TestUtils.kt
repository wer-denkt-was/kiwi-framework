package de.kiwi.framework

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityCreated
import de.kiwi.api.generic.EntityDeleted
import de.kiwi.api.generic.EntityUpdated
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.*
import org.hamcrest.TypeSafeMatcher
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.Database
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter

fun <T : Any> EntityManager.mergeAndDetach(entity: T): T {
    return merge(entity).also {
        flush()
        detach(it)
    }
}

fun <T> matchExample(name: Matcher<Any?>, optional: Matcher<Any?> = anything()): Matcher<T> {
    return allOf(
        hasProperty("name", name),
        hasProperty("optional", optional)
    )
}

fun <T> matchEntityCreated(
    createdName: Matcher<Any?> = anything(),
): Matcher<T> = allOf(
    instanceOf(EntityCreated::class.java),
    hasProperty("entityId", anything()),
    hasProperty("createdDetails", jsonObjectHasEntry(equalTo("name"), jsonStringIs(createdName))),
)

fun <T> matchEntityUpdated(
    id: Any,
    nameBefore: Matcher<*> = anything(),
    nameAfter: Matcher<*> = anything(),
): Matcher<T> = allOf(
    notNullValue(),
    instanceOf(EntityUpdated::class.java),
    hasProperty("entityId", equalTo(id)),
    hasProperty("beforeDetails", jsonObjectHasEntry(equalTo("name"), jsonStringIs(nameBefore))),
    hasProperty("afterDetails", jsonObjectHasEntry(equalTo("name"), jsonStringIs(nameAfter))),
)

fun <T> matchEntityDeleted(
    id: Any,
    deletedName: Matcher<Any> = anything(),
): Matcher<T> = allOf(
    instanceOf(EntityDeleted::class.java),
    hasProperty("entityId", equalTo(id)),
    hasProperty("deletedDetails", jsonObjectHasEntry(equalTo("name"), jsonStringIs(deletedName))),
)

fun testUserDescriptor(
    userId: Long = 0,
    username: String = "",
    authenticated: Boolean = true,
    roles: Set<String> = emptySet(),
    principal: Any? = null,
) = object : UserDescriptor {
    override val userId get() = userId
    override val username get() = username
    override val identity get() = "$username:$userId"
    override val authenticated get() = authenticated
    override val roles: Set<String> get() = roles
    override val principal: Any? get() = principal
}

private fun jsonStringIs(
    matchString: Matcher<*>,
): Matcher<out JsonNode> = object : TypeSafeMatcher<JsonNode>() {
    override fun matchesSafely(item: JsonNode): Boolean {
        return item is StringNode && matchString.matches(item.stringValue())
    }

    override fun describeTo(description: Description) {
        description.appendText("JSON string matching: ")
        description.appendDescriptionOf(matchString)
    }
}

private fun jsonObjectHasEntry(
    matchKey: Matcher<String>,
    matchValue: Matcher<out JsonNode>,
): Matcher<out JsonNode> = object : TypeSafeMatcher<JsonNode>() {
    override fun matchesSafely(item: JsonNode): Boolean {
        return item is ObjectNode && item.properties().any { (key, value) ->
            matchKey.matches(key) && matchValue.matches(value)
        }
    }

    override fun describeTo(description: Description) {
        description.appendText("JSON object with key: ")
        description.appendDescriptionOf(matchKey)
        description.appendText(", and value: ")
        description.appendDescriptionOf(matchValue)
    }
}

fun setupEntityManagerFactory(
    packagesToScan: List<String>,
    showSql: Boolean = true,
    dbUrl: String = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    dbUsername: String = "sa",
    dbPassword: String = "",
): EntityManagerFactory {
    val dataSource = DataSourceBuilder.create()
        .url(dbUrl)
        .username(dbUsername)
        .password(dbPassword)
        .build()

    val vendor = HibernateJpaVendorAdapter()
    vendor.setDatabase(Database.H2)
    vendor.setGenerateDdl(true)
    vendor.setShowSql(showSql)

    val bean = LocalContainerEntityManagerFactoryBean()
    bean.jpaVendorAdapter = vendor
    bean.dataSource = dataSource
    bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create-drop"))
    bean.setPackagesToScan(*packagesToScan.toTypedArray())
    bean.afterPropertiesSet()

    return bean.`object` ?: throw IllegalStateException("Could not setup EntityManagerFactory")
}
