package de.kiwi.demo.access.instrument

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

class ExplainInstrument(private val objectMapper: ObjectMapper) {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    fun <T> executeWithExplain(execute: () -> T): WithExplain<T> {
        val explainResults = ArrayList<ExplainResult>()
        val explainScope = HibernateStatementCallbackInspector.withCallback<T> { sql ->
            // SQL queries with parameters can't trivially be analyzed, so skip these queries here
            if (!sql.contains("?")) {
                //val versionQuery = entityManager.createNativeQuery("SELECT VERSION()")
                //val versionResult = versionQuery.singleResult
                //println("VERSION >> $versionResult")

                //val analyzeQuery = entityManager.createNativeQuery("EXPLAIN ANALYZE $sql")
                //val analyzeResult = analyzeQuery.singleResult
                //println("ANALYZE RESULT >> $analyzeResult")

                val explainQuery = entityManager.createNativeQuery("EXPLAIN FORMAT=JSON $sql")
                val explainJsonString = explainQuery.singleResult as String
                //println("EXPLAIN JSON >> $explainJsonString")

                val explainJson = objectMapper.readValue<ExplainJson>(explainJsonString)
                explainResults.add(ExplainResult(sql, explainJsonString, explainJson))
            }
        }

        // Flush entities before query execution
        // NOTE: I'm not sure why this is required, but there will be
        // "No argument for ordinal parameter '?1'" errors if entities are not flushed before
        entityManager.flush()

        // Execute block within explain-scope, capturing queries and running explain
        val value = synchronized(this) {
            explainScope { execute() }
        }

        return WithExplain(value, explainResults)
    }
}

data class WithExplain<T>(val value: T, val explain: List<ExplainResult>)

data class ExplainResult(val sql: String, val explainRaw: String, val explainJson: ExplainJson)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExplainJson(
    @field:JsonProperty("query_block") val queryBlock: QueryBlock,
) {
    fun allTables() = queryBlock.allTables()

    fun allSubqueries() = allTables().flatMap { it.allSubqueries() } + queryBlock.optimizedAwaySubqueries

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class QueryBlock(
        @field:JsonProperty("select_id") val selectId: Int?,
        @field:JsonProperty("table") val table: QueryTable? = null,
        @field:JsonProperty("nested_loop") val nestedLoop: List<QueryBlock> = emptyList(),
        @field:JsonProperty("optimized_away_subqueries") val optimizedAwaySubqueries: List<Subquery> = emptyList(),
    ) {
        fun allTables(): List<QueryTable> {
            val nestedLoopTables = nestedLoop.flatMap { it.allTables() }
            return listOfNotNull(table) + nestedLoopTables
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class QueryTable(
        @field:JsonProperty("table_name") val tableName: String,
        @field:JsonProperty("access_type") val accessType: AccessType,
        @field:JsonProperty("key") val usedKey: String? = null,
        @field:JsonProperty("possible_keys") val possibleKeys: List<String> = emptyList(),
        @field:JsonProperty("attached_subqueries") val attachedSubqueries: List<Subquery> = emptyList(),
    ) {
        fun allSubqueries(): List<Subquery> {
            return attachedSubqueries.flatMap { subquery ->
                listOf(subquery) + subquery.queryBlock.allTables().flatMap { it.allSubqueries() }
            }
        }

        fun accessInfo() = AccessInfo(
            tableName = tableName,
            accessType = accessType,
            usedKey = usedKey
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Subquery(
        @field:JsonProperty("dependent") val dependent: Boolean,
        @field:JsonProperty("cacheable") val cacheable: Boolean,
        @field:JsonProperty("query_block") val queryBlock: QueryBlock,
    ) {
        fun accessInfos() = queryBlock.allTables().map { it.accessInfo() }
    }

    enum class AccessType(val quality: AccessTypeQuality) {
        @JsonProperty("system")
        System(AccessTypeQuality.Best),

        @JsonProperty("const")
        Const(AccessTypeQuality.Best),

        @JsonProperty("eq_ref")
        EqRef(AccessTypeQuality.Best),

        @JsonProperty("unique_subquery")
        UniqueSubquery(AccessTypeQuality.Best),

        @JsonProperty("ref")
        Ref(AccessTypeQuality.Good),

        @JsonProperty("index_subquery")
        IndexSubquery(AccessTypeQuality.Good),

        @JsonProperty("ref_or_null")
        RefOrNull(AccessTypeQuality.Good),

        @JsonProperty("range")
        Range(AccessTypeQuality.Good),

        @JsonProperty("index")
        Index(AccessTypeQuality.Okay),

        @JsonProperty("fulltext")
        Fulltext(AccessTypeQuality.Okay),

        @JsonProperty("index_merge")
        IndexMerge(AccessTypeQuality.Okay),

        @JsonProperty("ALL")
        All(AccessTypeQuality.Bad)
    }

    enum class AccessTypeQuality { Best, Good, Okay, Bad }

    data class AccessInfo(
        val tableName: String,
        val accessType: AccessType,
        val usedKey: String?,
    )
}
