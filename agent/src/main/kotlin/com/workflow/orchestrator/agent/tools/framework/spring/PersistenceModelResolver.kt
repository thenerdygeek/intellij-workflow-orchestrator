package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.facet.FacetManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/**
 * Reflective facade over the IntelliJ Ultimate Persistence plugin's entity-model APIs.
 *
 * The `:agent` JAR ships against both Community and Ultimate, so all Persistence plugin
 * calls go through reflection. When the plugin is absent (Community edition, or Ultimate
 * without the Persistence plugin), every public method returns empty results without
 * raising, so callers see a clean "not found" rather than a reflection trace.
 *
 * Traversal: for each module, [FacetManager.getInstance(module).allFacets] is filtered
 * using [Api.persistenceFacetInterface].isInstance(facet) — a strict type check against
 * [com.intellij.persistence.facet.PersistenceFacet]. Each matching facet is then walked
 * via getAnnotationEntityMappings → getModelHelper → getPersistentEntities to build the
 * entity list. FacetManager is always available regardless of plugin presence; the
 * PersistenceFacet interface class is loaded once during initialization and stored on
 * [Api] for reuse.
 *
 * The resolver is self-gating — [ClassNotFoundException] when the Persistence plugin is
 * absent is caught during reflection initialization and every public method falls through
 * to empty results without raising. Callers do not need to consult
 * [com.workflow.orchestrator.agent.ide.IdeContext.hasPersistencePlugin] before invoking;
 * that flag exists for future registration-layer decisions.
 *
 * Signatures pinned by SpringToolTest.SpringPluginApiContract. See
 * docs/research/2026-04-21-intellij-persistence-api-signatures.md for the full surface
 * (the source of truth for all FQNs used here).
 */
internal object PersistenceModelResolver {

    // ─── Data classes ───────────────────────────────────────────────────────

    data class EntityMetadata(
        val className: String,              // entity qualified name
        val entityName: String?,            // @Entity(name=...) value
        val tableName: String?,             // primary table name
        val inheritanceStrategy: String?,   // SINGLE_TABLE / JOINED / TABLE_PER_CLASS or null
        val relationships: List<RelationshipMetadata>,
        val namedQueries: Map<String, String>,  // name → JPQL
    )

    data class RelationshipMetadata(
        val fieldName: String,              // attribute name
        val cardinality: String,            // ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY
        val targetEntity: String?,          // target PsiClass qualifiedName (best-effort)
        val fetchMode: String?,             // LAZY / EAGER
        val cascadeTypes: List<String>,     // strings from getCascadeTypes()
        val mappedBy: String?,              // getMappedByAttributeName()
        val isInverseSide: Boolean,
    )

    // ─── Constants ──────────────────────────────────────────────────────────

    /** Used for plugin-presence detection (Class.forName succeeds only on Ultimate with Persistence plugin). */
    private const val PERSISTENCE_HELPER_FQN = "com.intellij.persistence.PersistenceHelper"
    /** Strict type used to filter [FacetManager.allFacets] — avoids substring false-positives. */
    private const val PERSISTENCE_FACET_INTERFACE_FQN = "com.intellij.persistence.facet.PersistenceFacet"
    private const val RELATIONSHIP_ATTR_MODEL_HELPER_FQN =
        "com.intellij.persistence.model.helpers.PersistentRelationshipAttributeModelHelper"

    // ─── Reflection cache ───────────────────────────────────────────────────

    @Volatile private var cached: Api? = null
    @Volatile private var initialized: Boolean = false

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Enumerate every @Entity across every module that has a PersistenceFacet.
     * Returns empty when the Persistence plugin is absent.
     *
     * Facet enumeration strategy: iterate FacetManager.getInstance(module).allFacets
     * and filter via [Api.persistenceFacetInterface].isInstance(facet). This is a strict
     * type check that avoids false positives from unrelated plugins whose class names
     * happen to contain "PersistenceFacet". FacetManager is always available regardless
     * of whether the Persistence plugin is installed.
     */
    fun allEntities(project: Project): List<EntityMetadata> {
        val api = api() ?: return emptyList()
        val out = mutableListOf<EntityMetadata>()
        for (module in ModuleManager.getInstance(project).modules) {
            val facets = try {
                FacetManager.getInstance(module).allFacets
                    .filter { facet -> api.persistenceFacetInterface.isInstance(facet) }
            } catch (_: Exception) { emptyList() }

            for (facet in facets) {
                val mappings = try {
                    facet.javaClass.getMethod("getAnnotationEntityMappings").invoke(facet)
                } catch (_: Exception) { null } ?: continue

                val mappingsHelper = try {
                    mappings.javaClass.getMethod("getModelHelper").invoke(mappings)
                } catch (_: Exception) { null } ?: continue

                val entities: List<*> = try {
                    @Suppress("UNCHECKED_CAST")
                    mappingsHelper.javaClass.getMethod("getPersistentEntities").invoke(mappingsHelper) as? List<*>
                } catch (_: Exception) { null } ?: continue

                for (entity in entities) {
                    if (entity == null) continue
                    val metadata = extractEntityMetadata(entity, api) ?: continue
                    out.add(metadata)
                }
            }
        }
        return out
    }

    /**
     * Find by simple or qualified class name OR entity name (@Entity(name="...")).
     */
    fun findEntity(project: Project, name: String): EntityMetadata? =
        allEntities(project).firstOrNull { entity ->
            entity.className.equals(name, ignoreCase = true) ||
                entity.className.substringAfterLast('.').equals(name, ignoreCase = true) ||
                entity.entityName?.equals(name, ignoreCase = true) == true
        }

    // ─── Internal extraction ────────────────────────────────────────────────

    private fun extractEntityMetadata(entity: Any, api: Api): EntityMetadata? {
        return try {
            // entity name: GenericValue<String> — call .getValue() to unwrap
            val entityNameGv = try {
                entity.javaClass.getMethod("getName").invoke(entity)
            } catch (_: Exception) { null }
            val entityName = unwrapGenericValue(entityNameGv)

            // backing PsiClass: GenericValue<PsiClass> — call .getValue()
            val clazzGv = try {
                entity.javaClass.getMethod("getClazz").invoke(entity)
            } catch (_: Exception) { null }
            val psiClass = unwrapGenericValueRaw(clazzGv) ?: return null
            val className = (try { psiClass.javaClass.getMethod("getQualifiedName").invoke(psiClass) } catch (_: Exception) { null } as? String)
                ?: (try { psiClass.javaClass.getMethod("getName").invoke(psiClass) } catch (_: Exception) { null } as? String)
                ?: return null

            // entity model helper — resolve PersistentEntityModelHelper for entity-specific methods.
            // getObjectModelHelper() is overloaded 2-3 times with different return subtypes;
            // we use getMethods() and pick the one whose return type simple name is PersistentEntityModelHelper.
            val entityHelper = resolveEntityModelHelper(entity)

            val tableName = if (entityHelper != null) {
                try {
                    val tableProvider = entityHelper.javaClass.getMethod("getTable").invoke(entityHelper)
                    if (tableProvider != null) unwrapGenericValue(
                        tableProvider.javaClass.getMethod("getTableName").invoke(tableProvider)
                    ) else null
                } catch (_: Exception) { null }
            } else null

            val inheritanceStrategy = if (entityHelper != null) {
                try {
                    // getInheritanceType(PersistentEntity) takes the entity as parameter
                    val method = entityHelper.javaClass.methods.firstOrNull { m ->
                        m.name == "getInheritanceType" && m.parameterCount == 1
                    }
                    method?.invoke(entityHelper, entity)?.toString()
                } catch (_: Exception) { null }
            } else null

            val relationships = if (entityHelper != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val attrs = entityHelper.javaClass.getMethod("getAttributes").invoke(entityHelper) as? List<*>
                    extractRelationships(attrs ?: emptyList<Any>(), api)
                } catch (_: Exception) { emptyList() }
            } else emptyList()

            val namedQueries = if (entityHelper != null) {
                try {
                    val nq = extractNamedQueries(entityHelper, "getNamedQueries")
                    val nnq = extractNamedQueries(entityHelper, "getNamedNativeQueries")
                    nq + nnq
                } catch (_: Exception) { emptyMap() }
            } else emptyMap()

            EntityMetadata(
                className = className,
                entityName = entityName,
                tableName = tableName,
                inheritanceStrategy = inheritanceStrategy,
                relationships = relationships,
                namedQueries = namedQueries,
            )
        } catch (_: Exception) { null }
    }

    /**
     * Resolves PersistentEntityModelHelper from an entity object by iterating getMethods()
     * and picking the no-arg overload whose return type simple name is "PersistentEntityModelHelper".
     * Falls back to any overload named "getObjectModelHelper" if the specific type is not found.
     */
    private fun resolveEntityModelHelper(entity: Any): Any? = try {
        val methods = entity.javaClass.methods.filter { it.name == "getObjectModelHelper" && it.parameterCount == 0 }
        // Prefer the one whose return type is PersistentEntityModelHelper
        val preferred = methods.firstOrNull { m ->
            m.returnType.simpleName == "PersistentEntityModelHelper"
        } ?: methods.firstOrNull()
        preferred?.invoke(entity)
    } catch (_: Exception) { null }

    private fun extractRelationships(attributes: List<*>, api: Api): List<RelationshipMetadata> {
        val result = mutableListOf<RelationshipMetadata>()
        for (attr in attributes) {
            if (attr == null) continue
            val rel = extractRelationship(attr, api) ?: continue
            result.add(rel)
        }
        return result
    }

    private fun extractRelationship(attr: Any, api: Api): RelationshipMetadata? {
        return try {
            // getAttributeModelHelper() — check if result is a PersistentRelationshipAttributeModelHelper
            val attrHelper = try {
                attr.javaClass.getMethod("getAttributeModelHelper").invoke(attr)
            } catch (_: Exception) { null } ?: return null

            // Only proceed if the helper is a relationship helper
            val relHelperClass = api.relationshipHelperClass ?: return null
            if (!relHelperClass.isInstance(attrHelper)) return null

            val fieldNameGv = try {
                attr.javaClass.getMethod("getName").invoke(attr)
            } catch (_: Exception) { null }
            val fieldName = unwrapGenericValue(fieldNameGv) ?: return null

            val cardinality = try {
                attrHelper.javaClass.getMethod("getRelationshipType").invoke(attrHelper)?.toString()
            } catch (_: Exception) { null } ?: return null

            val fetchMode = try {
                attrHelper.javaClass.getMethod("getFetchType").invoke(attrHelper) as? String
            } catch (_: Exception) { null }

            val cascadeTypes: List<String> = try {
                @Suppress("UNCHECKED_CAST")
                (attrHelper.javaClass.getMethod("getCascadeTypes").invoke(attrHelper) as? Collection<String>)?.toList()
            } catch (_: Exception) { null } ?: emptyList()

            val mappedBy = try {
                attrHelper.javaClass.getMethod("getMappedByAttributeName").invoke(attrHelper) as? String
            } catch (_: Exception) { null }

            val isInverseSide = try {
                attrHelper.javaClass.getMethod("isInverseSide").invoke(attrHelper) as? Boolean
            } catch (_: Exception) { null } ?: false

            // Target entity: PersistentRelationshipAttribute.getTargetEntityClass() → GenericValue<PsiClass>
            val targetEntity = try {
                val targetGv = attr.javaClass.getMethod("getTargetEntityClass").invoke(attr)
                val targetPsiClass = unwrapGenericValueRaw(targetGv)
                if (targetPsiClass != null) {
                    (try { targetPsiClass.javaClass.getMethod("getQualifiedName").invoke(targetPsiClass) } catch (_: Exception) { null } as? String)
                        ?: (try { targetPsiClass.javaClass.getMethod("getName").invoke(targetPsiClass) } catch (_: Exception) { null } as? String)
                } else null
            } catch (_: Exception) { null }

            RelationshipMetadata(
                fieldName = fieldName,
                cardinality = cardinality,
                targetEntity = targetEntity,
                fetchMode = fetchMode,
                cascadeTypes = cascadeTypes,
                mappedBy = mappedBy,
                isInverseSide = isInverseSide,
            )
        } catch (_: Exception) { null }
    }

    private fun extractNamedQueries(helper: Any, methodName: String): Map<String, String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val queries = helper.javaClass.getMethod(methodName).invoke(helper) as? List<*>
                ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (q in queries) {
                if (q == null) continue
                val nameGv = try { q.javaClass.getMethod("getName").invoke(q) } catch (_: Exception) { null }
                val queryGv = try { q.javaClass.getMethod("getQuery").invoke(q) } catch (_: Exception) { null }
                val name = unwrapGenericValue(nameGv) ?: continue
                val query = unwrapGenericValue(queryGv) ?: continue
                result[name] = query
            }
            result
        } catch (_: Exception) { emptyMap() }
    }

    // ─── GenericValue<T> unwrapping ─────────────────────────────────────────

    /**
     * Unwrap GenericValue<String> → String? by calling getValue().
     * If getValue() is not found, tries getStringValue() as a fallback.
     */
    private fun unwrapGenericValue(gv: Any?): String? {
        if (gv == null) return null
        return try {
            gv.javaClass.getMethod("getValue").invoke(gv) as? String
        } catch (_: Exception) {
            try { gv.javaClass.getMethod("getStringValue").invoke(gv) as? String } catch (_: Exception) { null }
        }
    }

    /**
     * Unwrap GenericValue<T> → T? by calling getValue() when T is not String.
     */
    private fun unwrapGenericValueRaw(gv: Any?): Any? {
        if (gv == null) return null
        return try {
            gv.javaClass.getMethod("getValue").invoke(gv)
        } catch (_: Exception) { null }
    }

    // ─── Reflection initialization ──────────────────────────────────────────

    private fun api(): Api? {
        if (initialized) return cached
        synchronized(this) {
            if (initialized) return cached
            cached = buildApi()
            initialized = true
            return cached
        }
    }

    private fun buildApi(): Api? {
        return try {
            // Plugin-presence check: Class.forName succeeds only on Ultimate with the
            // Persistence plugin installed. The helper class itself is never invoked —
            // the actual traversal goes through FacetManager + PersistenceFacet.
            Class.forName(PERSISTENCE_HELPER_FQN).getMethod("getHelper")
            // Load PersistenceFacet interface for strict facet filtering (replaces the
            // former substring match on class names).
            val facetInterface = try {
                Class.forName(PERSISTENCE_FACET_INTERFACE_FQN)
            } catch (_: ClassNotFoundException) { null } ?: return null
            // Cache the relationship helper class for instanceof checks at call time.
            val relHelperClass = try {
                Class.forName(RELATIONSHIP_ATTR_MODEL_HELPER_FQN)
            } catch (_: ClassNotFoundException) { null }
            Api(
                persistenceFacetInterface = facetInterface,
                relationshipHelperClass = relHelperClass,
            )
        } catch (_: Throwable) { null }
    }

    private class Api(
        /** [com.intellij.persistence.facet.PersistenceFacet] interface — used to filter allFacets. */
        val persistenceFacetInterface: Class<*>,
        val relationshipHelperClass: Class<*>?,
    )
}
