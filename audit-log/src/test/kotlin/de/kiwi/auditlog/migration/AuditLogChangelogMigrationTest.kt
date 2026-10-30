package de.kiwi.auditlog.migration

import liquibase.Scope
import liquibase.command.CommandScope
import liquibase.command.core.UpdateCommandStep
import liquibase.command.core.UpdateCountCommandStep
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep
import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime

private const val MASTER_CHANGELOG = "db/changelog/db.audit-log-master.xml"

/** Change sets up to and including 1.4: create, rename to "kiwi_audit_log", fix binary column type */
private const val CHANGESETS_BEFORE_1_5 = 3

/**
 * Tests the audit-log changelog migration of binary event details (1.4) into a JSON column (1.5),
 * on all database systems supported by the changelog.
 */
class AuditLogChangelogMigrationTest {
    enum class DatabaseVendor(
        val detailsPlaceholder: String,
        val createContainer: (imageName: String) -> JdbcDatabaseContainer<*>,
    ) {
        MySQL("?", ::MySQLContainer),
        MariaDB("?", ::MariaDBContainer),
        PostgreSQL("cast(? as jsonb)", ::PostgreSQLContainer),
    }

    enum class DatabaseSystem(val imageName: String, val vendor: DatabaseVendor) {
        MySQL8("mysql:8", DatabaseVendor.MySQL),
        MySQL9("mysql:9", DatabaseVendor.MySQL),
        MariaDB11("mariadb:11", DatabaseVendor.MariaDB),
        MariaDB12("mariadb:12", DatabaseVendor.MariaDB),
        MariaDB13("mariadb:13", DatabaseVendor.MariaDB),
        PostgreSQL17("postgres:17", DatabaseVendor.PostgreSQL),
        PostgreSQL18("postgres:18", DatabaseVendor.PostgreSQL);

        fun createContainer(): JdbcDatabaseContainer<*> = vendor.createContainer(imageName)
    }

    private val jsonMapper = JsonMapper()

    private val legacyDetails = listOf(
        """ {"name": "Größe", "unicode": "✓ 🥝", "nested": {"list":[1,2,3],"null":null}} """,
        """ {} """,
        """ "plain string" """,
    )

    @ParameterizedTest
    @EnumSource(DatabaseSystem::class)
    @Execution(ExecutionMode.CONCURRENT)
    fun migratesBinaryDetailsToJson(system: DatabaseSystem) {
        withCleanDatabase(system) { connection, database ->
            updateChangelog(database, count = CHANGESETS_BEFORE_1_5)

            legacyDetails.forEachIndexed { index, json ->
                val id = index + 1L
                insertLegacyEntry(connection, id, json.toByteArray())
            }

            updateChangelog(database)

            // Details are preserved as equivalent JSON (JSON columns may normalize key order and whitespace)
            val migratedDetails = selectDetails(connection, system)
            assertEquals(legacyDetails.map(jsonMapper::readTree), migratedDetails.map(jsonMapper::readTree))

            // Column only accepts valid JSON, and is not nullable
            insertEntry(connection, id = 100, details = """{"valid":true}""", system)
            assertThrows<SQLException> { insertEntry(connection, id = 101, details = "not json", system) }
            assertThrows<SQLException> { insertEntry(connection, id = 102, details = null, system) }
            assertFalse(columnExists(connection, "__tmp_event_details"))
        }
    }

    @ParameterizedTest
    @EnumSource(DatabaseSystem::class)
    @Execution(ExecutionMode.CONCURRENT)
    fun invalidDetailsHaltMigrationWithoutChanges(system: DatabaseSystem) {
        withCleanDatabase(system) { connection, database ->
            updateChangelog(database, count = CHANGESETS_BEFORE_1_5)
            insertLegacyEntry(connection, id = 1, """{"valid":true}""".toByteArray())
            insertLegacyEntry(connection, id = 2, "INVALID: NOT JSON FORMAT".toByteArray())

            assertThrows<Exception> { updateChangelog(database) }

            // MySQL/MariaDB: precondition halts before any DDL, PostgreSQL: transaction is rolled back
            assertFalse(columnExists(connection, "__tmp_event_details"))
            assertTrue(columnExists(connection, "event_details"))
            assertEquals(2, countEntries(connection))
        }
    }

    // --- Internal helpers ---

    /**
     * Runs the test block against a new database container, since Liquibase caches database state per database URL
     * within the JVM, e.g. the changelog history and snapshots used by preconditions.
     */
    private inline fun withCleanDatabase(system: DatabaseSystem, block: (Connection, Database) -> Unit) {
        system.createContainer().use { container ->
            container.start()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
                val factory = DatabaseFactory.getInstance()
                val database = factory.findCorrectDatabaseImplementation(JdbcConnection(connection))
                block(connection, database)
            }
        }
    }

    private fun updateChangelog(database: Database, count: Int? = null) {
        val scopeValues = mapOf(Scope.Attr.resourceAccessor.name to ClassLoaderResourceAccessor())
        try {
            runUpdate(scopeValues, database, count)
        } finally {
            // Liquibase disables auto-commit, which the statements of the test rely on
            database.connection.autoCommit = true
        }
    }

    private fun runUpdate(scopeValues: Map<String, Any>, database: Database, count: Int?) {
        Scope.child(scopeValues) {
            val command = if (count == null) {
                CommandScope(*UpdateCommandStep.COMMAND_NAME)
                    .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, MASTER_CHANGELOG)
            } else {
                CommandScope(*UpdateCountCommandStep.COMMAND_NAME)
                    .addArgumentValue(UpdateCountCommandStep.CHANGELOG_FILE_ARG, MASTER_CHANGELOG)
                    .addArgumentValue(UpdateCountCommandStep.COUNT_ARG, count)
            }
            command.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database).execute()
        }
    }

    private fun insertLegacyEntry(connection: Connection, id: Long, details: ByteArray) {
        connection.prepareStatement(insertSql("?")).use { statement ->
            bindEntryColumns(statement, id)
            statement.setBytes(6, details)
            statement.executeUpdate()
        }
    }

    private fun insertEntry(connection: Connection, id: Long, details: String?, system: DatabaseSystem) {
        val placeholder = system.vendor.detailsPlaceholder
        connection.prepareStatement(insertSql(placeholder)).use { statement ->
            bindEntryColumns(statement, id)
            statement.setString(6, details)
            statement.executeUpdate()
        }
    }

    @Language("GenericSQL")
    private fun insertSql(detailsPlaceholder: String) = """
    |insert into
    | kiwi_audit_log (id, user_id, user_name, event_name, severity, occurred_on, event_details)
    | values (?, null, ?, ?, ?, ?, $detailsPlaceholder);
    |""".trimMargin()

    private fun bindEntryColumns(statement: java.sql.PreparedStatement, id: Long) {
        statement.setLong(1, id)
        statement.setString(2, "test-user")
        statement.setString(3, "TestEvent")
        statement.setString(4, "Event")
        statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 12, 0)))
    }

    private fun selectDetails(connection: Connection, system: DatabaseSystem): List<String> {
        val detailsAsText = if (system == DatabaseSystem.PostgreSQL17) "event_details::text" else "event_details"
        connection.createStatement().use { statement ->
            statement.executeQuery("select $detailsAsText from kiwi_audit_log order by id").use { result ->
                return buildList { while (result.next()) add(result.getString(1)) }
            }
        }
    }

    private fun countEntries(connection: Connection): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from kiwi_audit_log").use { result ->
                result.next()
                return result.getInt(1)
            }
        }
    }

    private fun columnExists(connection: Connection, column: String): Boolean {
        connection.metaData.getColumns(connection.catalog, null, "kiwi_audit_log", null).use { result ->
            val foundColumns = sequence { while (result.next()) yield(result.getString("COLUMN_NAME")) }
            return foundColumns.any { found -> found.equals(column, ignoreCase = true) }
        }
    }
}
