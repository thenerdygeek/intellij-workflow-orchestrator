# Spring Boot Performance Engineering — Comprehensive Reference

**Date:** 2026-04-06
**Stack:** Kotlin/Java, Spring Boot 3+, Maven, PostgreSQL/Aurora, Keycloak OAuth2, AWS (ECS, Lambda)
**Purpose:** Performance engineer AI agent prompt reference — code-level patterns, anti-patterns, detection, and fixes

---

## 1. Database Performance (PostgreSQL/Aurora)

### 1.1 JPA/Hibernate Optimization

#### Anti-pattern: Eager Fetching Everywhere
- **Problem:** `FetchType.EAGER` on associations causes the entire object graph to load on every query, even when child entities are not needed.
- **Detection pattern:** Search for `fetch = FetchType.EAGER` or `@ManyToOne` / `@OneToOne` without explicit `fetch = FetchType.LAZY`.
- **Impact:** HIGH — can cause 10-100x more data transfer per query

```kotlin
// BAD — eager loads all orders for every customer query
@Entity
class Customer(
    @OneToMany(fetch = FetchType.EAGER)
    val orders: List<Order> = emptyList()
)

// GOOD — lazy loading by default, JOIN FETCH when needed
@Entity
class Customer(
    @OneToMany(fetch = FetchType.LAZY)
    val orders: List<Order> = emptyList()
)
```

**Fix:** Set ALL associations to `FetchType.LAZY`. Use `JOIN FETCH` or `@EntityGraph` at the query level when you actually need related data.

#### Anti-pattern: Missing Batch Fetching Configuration
- **Problem:** Without batch fetching, each lazy-loaded collection triggers an individual SELECT.
- **Detection pattern:** Absence of `hibernate.default_batch_fetch_size` in `application.yml`.
- **Impact:** HIGH — reduces N+1 queries to N/batch_size+1 queries

```yaml
# application.yml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 25        # batch lazy loads into IN clauses
        jdbc:
          batch_size: 50                     # batch INSERT/UPDATE DML
          batch_versioned_data: true         # allow batching of versioned entities
        order_inserts: true                  # group inserts by entity type
        order_updates: true                  # group updates by entity type
        generate_statistics: true            # enable for dev/staging, disable prod
```

#### Anti-pattern: No Second-Level Cache
- **Problem:** Frequently accessed, rarely changed reference data is fetched from DB every session.
- **Detection pattern:** Missing `@Cache` annotations on stable entities, no `hibernate-jcache` dependency.
- **Impact:** MEDIUM — significant for reference/lookup tables (countries, config, enums)

```kotlin
// Entity annotation
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class ProductCategory(
    @Id val id: Long,
    val name: String,
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    val subcategories: List<Subcategory> = emptyList()
)
```

```kotlin
// Configuration — share JCache CacheManager with Hibernate
@Configuration(proxyBeanMethods = false)
class HibernateSecondLevelCacheConfig {
    @Bean
    fun hibernateSecondLevelCacheCustomizer(
        cacheManager: JCacheCacheManager
    ): HibernatePropertiesCustomizer = HibernatePropertiesCustomizer { properties ->
        properties[ConfigSettings.CACHE_MANAGER] = cacheManager.cacheManager
    }
}
```

```yaml
spring:
  jpa:
    properties:
      hibernate:
        cache:
          use_second_level_cache: true
          use_query_cache: true
          region.factory_class: jcache
      javax:
        persistence:
          sharedCache:
            mode: ENABLE_SELECTIVE
```

**Rule:** Cache entities with high read:write ratio (>100:1). Never cache user-specific or frequently changing entities.

### 1.2 N+1 Detection and All Fix Patterns

#### Detection Methods
1. **Hibernate statistics:** `spring.jpa.properties.hibernate.generate_statistics=true` — logs query counts per session
2. **Log SQL:** `logging.level.org.hibernate.SQL=DEBUG` + `spring.jpa.properties.hibernate.format_sql=true`
3. **Spring Boot Actuator:** enable `hibernate.generate_statistics` and check Micrometer metrics for `hibernate.query.executions`
4. **Automated detection:** Use libraries like `datasource-proxy` or `Digma` to detect N+1 at dev time

```kotlin
// Detection: Count queries per request using datasource-proxy
@Bean
fun dataSource(original: DataSource): DataSource = ProxyDataSourceBuilder.create(original)
    .countQuery()
    .logQueryBySlf4j(SLF4JLogLevel.WARN)
    .build()
```

#### Fix Pattern 1: JOIN FETCH in JPQL
```kotlin
// BAD — triggers N+1 when accessing order.items
@Query("SELECT o FROM Order o WHERE o.customer.id = :customerId")
fun findByCustomerId(customerId: Long): List<Order>

// GOOD — single query with JOIN FETCH
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.customer.id = :customerId")
fun findByCustomerIdWithItems(customerId: Long): List<Order>
```
**Caveat:** JOIN FETCH with multiple collections causes a Cartesian product. Use `@BatchSize` or multiple queries for multiple collections.

#### Fix Pattern 2: @EntityGraph
```kotlin
@EntityGraph(attributePaths = ["items", "items.product"])
fun findByCustomerId(customerId: Long): List<Order>

// Named EntityGraph on entity
@Entity
@NamedEntityGraph(
    name = "Order.withItemsAndProducts",
    attributeNodes = [
        NamedAttributeNode("items", subgraph = "items-subgraph")
    ],
    subgraphs = [
        NamedSubgraph(name = "items-subgraph", attributeNodes = [NamedAttributeNode("product")])
    ]
)
class Order { ... }

// Usage
@EntityGraph("Order.withItemsAndProducts")
fun findByStatus(status: OrderStatus): List<Order>
```

#### Fix Pattern 3: DTO Projections (Best for Read-Only)
```kotlin
// Interface projection — Hibernate generates optimized SQL selecting only declared fields
interface OrderSummary {
    fun getId(): Long
    fun getTotal(): BigDecimal
    fun getCustomerName(): String   // Spring Data resolves nested paths
    fun getItemCount(): Int          // Use @Value with SpEL for computed
}

@Query("SELECT o.id AS id, o.total AS total, c.name AS customerName, SIZE(o.items) AS itemCount " +
       "FROM Order o JOIN o.customer c WHERE o.status = :status")
fun findSummariesByStatus(status: OrderStatus): List<OrderSummary>

// Class-based DTO projection (even faster — no proxy overhead)
data class OrderDto(val id: Long, val total: BigDecimal, val customerName: String)

@Query("SELECT new com.example.OrderDto(o.id, o.total, c.name) FROM Order o JOIN o.customer c")
fun findAllDtos(): List<OrderDto>
```
**Impact:** HIGH — DTO projections can be 10-50x faster than full entity loading for read-heavy endpoints.

#### Fix Pattern 4: @BatchSize on Association
```kotlin
@Entity
class Department(
    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
    @BatchSize(size = 25)  // loads employees in batches of 25 using IN clause
    val employees: List<Employee> = emptyList()
)
```

#### Fix Pattern 5: Subselect Fetching
```kotlin
@Entity
class Department(
    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
    @Fetch(FetchMode.SUBSELECT)  // single subselect loads ALL uninitialized collections
    val employees: List<Employee> = emptyList()
)
```

### 1.3 HikariCP Connection Pool Tuning

#### Sizing Formula
```
connections = ((core_count * 2) + effective_spindle_count)
```
- For SSDs: effective spindle count = 1
- 4-core server with SSD: (4 * 2) + 1 = **9 connections**
- **Rule of thumb:** 10-20 connections per application instance is usually optimal. More connections != better performance.

#### Anti-pattern: Default Pool Size with Multiple Instances
- **Problem:** Default pool (10) x 10 instances = 100 connections, which may exceed DB max_connections.
- **Detection pattern:** Check `spring.datasource.hikari.maximum-pool-size` against total instance count.
- **Impact:** HIGH — pool exhaustion causes request timeouts; DB overload causes global outage

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10                    # NOT higher — see formula above
      minimum-idle: 5                          # maintain 5 warm connections
      connection-timeout: 30000                # 30s — fail fast if pool exhausted
      idle-timeout: 600000                     # 10 min — release idle connections
      max-lifetime: 1800000                    # 30 min — rotate before DB timeout
      leak-detection-threshold: 60000          # 60s — log warning if connection held too long
      validation-timeout: 5000                 # 5s timeout for connection validation
      connection-test-query: "SELECT 1"        # or use isValid() for JDBC4+
      pool-name: "app-hikari-pool"             # name for metrics/logs
```

**Critical rule:** `max-lifetime` MUST be at least 30 seconds shorter than the database's connection timeout (PostgreSQL `idle_in_transaction_session_timeout` or Aurora's `wait_timeout`).

#### Monitoring Pool Health
```kotlin
// Custom metric for pool utilization
@Component
class HikariMetrics(
    private val dataSource: DataSource,
    private val meterRegistry: MeterRegistry
) {
    @Scheduled(fixedRate = 10_000)
    fun recordPoolMetrics() {
        val hikariPool = (dataSource as HikariDataSource).hikariPoolMXBean ?: return
        meterRegistry.gauge("hikari.active", hikariPool) { it.activeConnections.toDouble() }
        meterRegistry.gauge("hikari.idle", hikariPool) { it.idleConnections.toDouble() }
        meterRegistry.gauge("hikari.waiting", hikariPool) { it.threadsAwaitingConnection.toDouble() }
        meterRegistry.gauge("hikari.total", hikariPool) { it.totalConnections.toDouble() }
    }
}
```

**Alert thresholds:**
- `threadsAwaitingConnection > 0` sustained for 30s = pool contention
- `activeConnections / maximum-pool-size > 0.8` = approaching exhaustion
- `leakDetectionThreshold` warnings in logs = connection leak

### 1.4 PostgreSQL-Specific Optimization

#### Reading EXPLAIN ANALYZE Output
```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT o.* FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE c.email = 'user@example.com' AND o.status = 'ACTIVE';
```

**Key indicators in EXPLAIN output:**
| Indicator | What It Means | Action |
|---|---|---|
| `Seq Scan` on large table | Missing index | Add appropriate index |
| `actual rows` >> `plan rows` | Stale statistics | Run `ANALYZE table_name` |
| `Nested Loop` with high `loops` | N+1 at SQL level | Rewrite with JOIN or EXISTS |
| `Sort` with `external merge Disk` | Insufficient `work_mem` | Increase `work_mem` for session |
| `Buffers: shared read` >> `shared hit` | Cold cache / table too large | Check shared_buffers, index |
| `Hash Join` with `Batches > 1` | Insufficient `work_mem` | Increase `work_mem` |

#### Index Types — When to Use Which
```sql
-- B-tree (default): equality, range, ORDER BY, BETWEEN
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at DESC);

-- Composite B-tree: multiple columns, leftmost prefix rule
CREATE INDEX idx_orders_status_date ON orders(status, created_at DESC);

-- Partial index: index only relevant subset — 90%+ size reduction for skewed data
CREATE INDEX idx_active_orders ON orders(created_at DESC)
    WHERE status = 'ACTIVE';   -- only indexes active orders

-- GIN (Generalized Inverted): JSONB, arrays, full-text search
CREATE INDEX idx_order_metadata ON orders USING GIN (metadata jsonb_path_ops);

-- Full-text search with GIN
CREATE INDEX idx_product_search ON products USING GIN (to_tsvector('english', name || ' ' || description));

-- GiST: geometric/range data, nearest neighbor, overlap
CREATE INDEX idx_events_during ON events USING GiST (tsrange(start_time, end_time));

-- BRIN (Block Range Index): large naturally-ordered tables (time-series)
-- 1000x smaller than B-tree for append-only time-series
CREATE INDEX idx_logs_timestamp ON audit_logs USING BRIN (created_at);

-- Covering index: include non-key columns to enable index-only scans
CREATE INDEX idx_orders_covering ON orders(customer_id, status) INCLUDE (total, created_at);
```

#### PostgreSQL 18 Features (September 2025)
- **Skip scan on multicolumn B-tree:** Efficient lookups even when leading columns are not filtered
- **Parallel GIN index builds:** Full-text search index creation substantially faster on multi-core
- **Incremental sort improvements:** Better use of partial indexes

### 1.5 Aurora-Specific Configuration

#### Max Connections by Instance Type
```
Formula: max_connections = DBInstanceClassMemory / 12582880

db.r6g.large   (16 GB):  ~1000 connections
db.r6g.xlarge  (32 GB):  ~2000 connections
db.r6g.2xlarge (64 GB):  ~4000 connections
db.r6g.4xlarge (128 GB): ~5000 connections (capped)
```

**Critical calculation:**
```
Total app connections = instances x pool_size_per_instance
Reserved = 3 (AWS automation) + monitoring connections
Available = max_connections - reserved

Example: 10 ECS tasks x 10 pool per task = 100 connections
db.r6g.large supports 1000, so comfortable.
But: 50 ECS tasks x 20 pool = 1000, hitting the limit!
```

#### Read/Write Endpoint Routing
```kotlin
// Routing DataSource for Aurora read replicas
@Configuration
class AuroraRoutingConfig {

    @Bean
    @Primary
    fun routingDataSource(
        @Qualifier("writerDataSource") writer: DataSource,
        @Qualifier("readerDataSource") reader: DataSource
    ): DataSource {
        val routing = ReplicationRoutingDataSource()
        routing.setTargetDataSources(mapOf(
            "writer" to writer,
            "reader" to reader
        ))
        routing.setDefaultTargetDataSource(writer)
        return routing
    }
}

class ReplicationRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any =
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) "reader" else "writer"
}
```

```yaml
# Aurora endpoints
app:
  datasource:
    writer:
      url: jdbc:postgresql://my-cluster.cluster-xxx.us-east-1.rds.amazonaws.com:5432/mydb
    reader:
      url: jdbc:postgresql://my-cluster.cluster-ro-xxx.us-east-1.rds.amazonaws.com:5432/mydb
```

#### Aurora Failover Handling
```yaml
# JDBC URL parameters for Aurora failover
spring:
  datasource:
    url: >-
      jdbc:postgresql://my-cluster.cluster-xxx.us-east-1.rds.amazonaws.com:5432/mydb
      ?targetServerType=primary
      &loginTimeout=2
      &connectTimeout=2
      &socketTimeout=60
      &cancelSignalTimeout=5
```

**Use RDS Proxy** for:
- Connection pooling at the proxy level (reduces DB connection count)
- Faster failover (proxy handles reconnection)
- IAM authentication support
- Pinning avoidance strategies

### 1.6 Pagination: Offset vs Keyset

#### Anti-pattern: Offset Pagination on Large Tables
- **Problem:** `OFFSET 500000 LIMIT 20` scans and discards 500,000 rows before returning 20.
- **Detection pattern:** Search for `Pageable` with deep page access, or `OFFSET` in native queries.
- **Impact:** HIGH — 177x performance degradation at page 1000 vs page 1

```kotlin
// BAD — O(offset + limit) cost, gets worse as user pages deeper
@Query("SELECT o FROM Order o ORDER BY o.createdAt DESC")
fun findAll(pageable: Pageable): Page<Order>
// Page 10000: SELECT ... ORDER BY created_at DESC OFFSET 200000 LIMIT 20  — scans 200K rows

// GOOD — Keyset pagination: O(limit) constant cost regardless of position
@Query("""
    SELECT o FROM Order o
    WHERE o.createdAt < :cursor
    ORDER BY o.createdAt DESC
""")
fun findAfterCursor(
    @Param("cursor") cursor: Instant,
    pageable: Pageable
): List<Order>
```

```kotlin
// Spring Data JPA ScrollPosition (Spring Boot 3.1+)
// Built-in keyset pagination support
fun findByStatusOrderByCreatedAtDesc(
    status: OrderStatus,
    scrollPosition: ScrollPosition,
    limit: Limit
): Window<Order>

// Usage
val firstPage = repository.findByStatusOrderByCreatedAtDesc(
    OrderStatus.ACTIVE,
    ScrollPosition.keyset(),
    Limit.of(20)
)
val nextPage = repository.findByStatusOrderByCreatedAtDesc(
    OrderStatus.ACTIVE,
    firstPage.positionAt(firstPage.size() - 1),
    Limit.of(20)
)
```

| Approach | Page 1 | Page 100 | Page 10000 | Consistency |
|---|---|---|---|---|
| Offset | 2ms | 45ms | 4200ms | Duplicates/gaps on insert |
| Keyset/Cursor | 2ms | 2ms | 2ms | Consistent |

### 1.7 Bulk Operations

#### Anti-pattern: save() in a Loop
- **Problem:** Calling `repository.save(entity)` in a loop generates individual INSERT statements.
- **Detection pattern:** `forEach { repository.save(it) }` or `map { repository.save(it) }`.
- **Impact:** HIGH — 100x slower than batched inserts

```kotlin
// BAD — 10,000 individual INSERT statements
items.forEach { repository.save(it) }

// GOOD — batch with Hibernate batching (configure batch_size first)
@Transactional
fun bulkInsert(items: List<Item>) {
    items.chunked(50).forEach { batch ->
        batch.forEach { entityManager.persist(it) }
        entityManager.flush()
        entityManager.clear()  // prevent memory bloat from persistence context
    }
}

// BEST — JdbcTemplate for maximum throughput
@Transactional
fun bulkInsertJdbc(items: List<Item>) {
    jdbcTemplate.batchUpdate(
        "INSERT INTO items (name, price, category_id) VALUES (?, ?, ?)",
        items,
        1000  // batch size
    ) { ps, item ->
        ps.setString(1, item.name)
        ps.setBigDecimal(2, item.price)
        ps.setLong(3, item.categoryId)
    }
}
```

**PostgreSQL JDBC optimization:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://host:5432/db?reWriteBatchedInserts=true
    # reWriteBatchedInserts=true: driver rewrites batch INSERTs to multi-value INSERT
    # Provides ~30% improvement for batch INSERT
```

**PostgreSQL COPY for maximum throughput:**
```kotlin
fun bulkCopy(items: List<Item>) {
    val conn = dataSource.connection.unwrap(PgConnection::class.java)
    val copyManager = CopyManager(conn)
    val sql = "COPY items (name, price, category_id) FROM STDIN WITH CSV"
    val writer = copyManager.copyIn(sql)

    items.forEach { item ->
        val row = "${item.name},${item.price},${item.categoryId}\n"
        val bytes = row.toByteArray()
        writer.writeToCopy(bytes, 0, bytes.size)
    }
    writer.endCopy()
}
// COPY is 5-10x faster than batch INSERT for large volumes (100K+ rows)
```

### 1.8 Query Approach Performance Comparison

| Approach | Dynamic Queries | Type Safety | Performance | Best For |
|---|---|---|---|---|
| JPQL `@Query` | No | Moderate | Fast (HQL parsed once) | Fixed queries |
| Native SQL | No | None | Fastest | DB-specific, complex SQL |
| Specification | Yes | High | Moderate (Criteria overhead) | Dynamic filtering |
| QueryDSL | Yes | Highest | Moderate | Complex dynamic queries |
| Spring Data derived | No | High | Fast | Simple CRUD |
| DTO Projection | No | Moderate | Fastest (no entity) | Read-heavy endpoints |

**Rule:** Use DTO projections for read-heavy APIs. Use JPQL for fixed queries. Use Specification for dynamic search/filter APIs. Use native SQL only for PostgreSQL-specific features (CTEs, window functions, JSONB).

### 1.9 Flyway Migration Performance

#### Anti-pattern: ALTER TABLE on Large Tables Without Planning
- **Problem:** `ALTER TABLE ADD COLUMN ... DEFAULT value` on a table with millions of rows acquires `ACCESS EXCLUSIVE` lock, blocking all reads and writes.
- **Detection pattern:** Flyway migrations with `ALTER TABLE` on large tables without `SET lock_timeout`.
- **Impact:** HIGH — can cause minutes of downtime on large tables

```sql
-- BAD — locks the entire table for the duration of the ALTER
ALTER TABLE orders ADD COLUMN priority INTEGER DEFAULT 0;

-- GOOD (PostgreSQL 11+) — ADD COLUMN with DEFAULT is instant (metadata-only)
-- BUT: adding NOT NULL constraint still rewrites the table
ALTER TABLE orders ADD COLUMN priority INTEGER DEFAULT 0;  -- instant in PG 11+

-- For NOT NULL on existing column with data:
-- Step 1: Add column nullable
ALTER TABLE orders ADD COLUMN priority INTEGER;
-- Step 2: Backfill in batches
UPDATE orders SET priority = 0 WHERE priority IS NULL AND id BETWEEN 1 AND 100000;
-- Step 3: Add constraint
ALTER TABLE orders ALTER COLUMN priority SET NOT NULL;
ALTER TABLE orders ALTER COLUMN priority SET DEFAULT 0;

-- ALWAYS set lock_timeout in migrations
SET lock_timeout = '5s';
-- If lock can't be acquired in 5s, migration fails instead of blocking
```

**Index creation:**
```sql
-- BAD — blocks writes during index creation
CREATE INDEX idx_orders_customer ON orders(customer_id);

-- GOOD — CONCURRENTLY allows writes during index creation
CREATE INDEX CONCURRENTLY idx_orders_customer ON orders(customer_id);
-- Note: CONCURRENTLY cannot run inside a transaction — use Flyway's non-transactional mode
```

---

## 2. Caching

### 2.1 Spring @Cacheable Best Practices

#### Anti-pattern: Self-Invocation Bypasses Cache
- **Problem:** Calling a `@Cacheable` method from within the same class bypasses the proxy.
- **Detection pattern:** Method A in class X calls method B (annotated `@Cacheable`) also in class X.
- **Impact:** MEDIUM — cache is never consulted, defeating the purpose entirely

```kotlin
// BAD — self-invocation bypasses proxy, cache never hit
@Service
class ProductService {
    fun getProductWithDiscount(id: Long): ProductDto {
        val product = getProduct(id)  // THIS BYPASSES CACHE — direct method call
        return product.withDiscount(calculateDiscount(id))
    }

    @Cacheable("products")
    fun getProduct(id: Long): Product = repository.findById(id).orElseThrow()
}

// FIX 1: Inject self (recommended)
@Service
class ProductService(
    @Lazy private val self: ProductService  // inject proxy of self
) {
    fun getProductWithDiscount(id: Long): ProductDto {
        val product = self.getProduct(id)  // goes through proxy — cache works
        return product.withDiscount(calculateDiscount(id))
    }

    @Cacheable("products")
    fun getProduct(id: Long): Product = repository.findById(id).orElseThrow()
}

// FIX 2: Extract to separate service
@Service
class ProductCacheService {
    @Cacheable("products")
    fun getProduct(id: Long): Product = repository.findById(id).orElseThrow()
}
```

#### Anti-pattern: Caching Null or Exception Results
```kotlin
// BAD — caches null, subsequent calls never hit DB even if data is added
@Cacheable("products")
fun getProduct(id: Long): Product?

// GOOD — don't cache null results
@Cacheable("products", unless = "#result == null")
fun getProduct(id: Long): Product?
```

#### Anti-pattern: Missing Cache Key for User-Specific Data
```kotlin
// BAD — all users get the same cached dashboard (SECURITY BUG + correctness bug)
@Cacheable("dashboard")
fun getDashboard(): DashboardDto

// GOOD — include user ID in cache key
@Cacheable("dashboard", key = "#userId")
fun getDashboard(userId: Long): DashboardDto
```

#### What to Cache vs What NOT to Cache
| Cache | Do NOT Cache |
|---|---|
| Reference data (countries, categories, configs) | User-specific data without user key |
| API responses from slow external services | Frequently changing data (< 5s TTL) |
| Computed/aggregated data (reports, stats) | Large objects (> 1MB) |
| Authentication tokens/permissions | Paginated results (key explosion) |
| Database query results for stable data | Stream/reactive results |

### 2.2 Caffeine Cache Configuration

```kotlin
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CaffeineCacheManager {
        val manager = CaffeineCacheManager()
        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(10_000)           // max entries
                .expireAfterWrite(10, TimeUnit.MINUTES) // TTL
                .recordStats()                  // enable hit/miss metrics
        )
        return manager
    }

    // Per-cache configuration with different policies
    @Bean
    fun cacheManager(): CacheManager {
        val caffeineCacheManager = SimpleCacheManager()
        caffeineCacheManager.setCaches(listOf(
            buildCache("products", 5000, 30, TimeUnit.MINUTES),
            buildCache("categories", 500, 60, TimeUnit.MINUTES),
            buildCache("user-permissions", 10000, 5, TimeUnit.MINUTES),
            buildCache("external-api", 1000, 2, TimeUnit.MINUTES)
        ))
        return caffeineCacheManager
    }

    private fun buildCache(name: String, maxSize: Long, duration: Long, unit: TimeUnit): CaffeineCache =
        CaffeineCache(name, Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(duration, unit)
            .recordStats()
            .build())
}
```

**Eviction policies:**
- `maximumSize` — evicts using Window TinyLFU (near-optimal hit rate)
- `expireAfterWrite` — TTL from write time (use for consistency)
- `expireAfterAccess` — TTL from last access (use for session-like data)
- `refreshAfterWrite` — async refresh after duration (use with `CacheLoader`)

### 2.3 Multi-Level Cache: Caffeine L1 + Redis L2

```kotlin
@Component
class TwoLevelCacheManager(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val caffeine: Cache<String, Any> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()
) {
    suspend fun <T> get(key: String, ttl: Duration, loader: suspend () -> T): T {
        // L1: Caffeine (in-process, sub-microsecond)
        caffeine.getIfPresent(key)?.let {
            @Suppress("UNCHECKED_CAST")
            return it as T
        }

        // L2: Redis (distributed, sub-millisecond)
        redisTemplate.opsForValue().get(key)?.let {
            caffeine.put(key, it)  // backfill L1
            @Suppress("UNCHECKED_CAST")
            return it as T
        }

        // L3: Data source
        val value = loader()
        redisTemplate.opsForValue().set(key, value as Any, ttl)  // populate L2
        caffeine.put(key, value as Any)                           // populate L1
        return value
    }

    fun evict(key: String) {
        caffeine.invalidate(key)
        redisTemplate.delete(key)
    }
}
```

**Access latency comparison:**
| Level | Latency | Scope |
|---|---|---|
| L1 Caffeine | < 100ns | Single JVM |
| L2 Redis/Elasticache | 0.5-2ms | Cluster-wide |
| Database | 5-50ms | Persistent |

### 2.4 Redis/Elasticache Configuration

#### Lettuce vs Jedis
| Feature | Lettuce (Default) | Jedis |
|---|---|---|
| Thread safety | Yes (shared connection) | No (needs pool) |
| Async support | Yes (Reactive) | No |
| Connection model | Single shared | Pool-per-thread |
| Cluster support | Built-in | Built-in |
| Recommended for | Most applications | Legacy/simple |

```yaml
spring:
  data:
    redis:
      host: my-elasticache.xxx.cache.amazonaws.com
      port: 6379
      ssl:
        enabled: true
      lettuce:
        pool:
          max-active: 16       # max connections
          max-idle: 8          # max idle connections
          min-idle: 4          # min idle (keep warm)
          max-wait: 2000ms     # max wait for connection
        shutdown-timeout: 200ms
      timeout: 2000ms          # command timeout
```

#### Serialization Performance
```kotlin
@Bean
fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
    val template = RedisTemplate<String, Any>()
    template.connectionFactory = connectionFactory
    template.keySerializer = StringRedisSerializer()

    // Jackson — human-readable, debuggable, moderate speed
    template.valueSerializer = GenericJackson2JsonRedisSerializer(objectMapper)

    // For high-throughput: Kryo serialization (2-10x faster, 50% smaller)
    // template.valueSerializer = KryoRedisSerializer()

    return template
}
```

| Serializer | Speed | Size | Human Readable | Schema Evolution |
|---|---|---|---|---|
| Jackson JSON | Moderate | Largest | Yes | Good (@JsonIgnore etc.) |
| Kryo | Fast (2-10x) | Small | No | Requires registration |
| Protobuf | Fast | Smallest | No | Best (schemas) |
| JDK Serialization | Slow | Large | No | Fragile |

**Rule:** Use Jackson for most cases (debuggability matters). Use Kryo/Protobuf only for high-throughput caches with verified serialization compatibility.

### 2.5 Cache Invalidation Strategies

```kotlin
// TTL-based (simplest, eventual consistency)
@Cacheable("products", unless = "#result == null")
fun getProduct(id: Long): Product?

// Event-driven invalidation (strongest consistency)
@CacheEvict("products", key = "#product.id")
fun updateProduct(product: Product): Product

// Invalidate all entries in a cache
@CacheEvict("products", allEntries = true)
fun refreshProductCatalog()

// Conditional eviction
@CacheEvict("products", key = "#id", condition = "#result != null")
fun deleteProduct(id: Long): Boolean

// Put (update cache without evict)
@CachePut("products", key = "#product.id")
fun updateProduct(product: Product): Product

// Composite operations
@Caching(
    evict = [
        CacheEvict("products", key = "#product.id"),
        CacheEvict("product-search", allEntries = true)
    ]
)
fun updateProduct(product: Product): Product
```

### 2.6 Cache Warming for Cold Starts

```kotlin
@Component
class CacheWarmer(
    private val productService: ProductService,
    private val categoryService: CategoryService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("Warming caches...")
        val start = System.nanoTime()

        // Warm critical caches in parallel
        runBlocking {
            launch { categoryService.getAllCategories() }  // triggers @Cacheable
            launch { productService.getTopProducts(100) }
            launch { productService.getActivePromotions() }
        }

        val elapsed = Duration.ofNanos(System.nanoTime() - start)
        log.info("Cache warming completed in {}ms", elapsed.toMillis())
    }
}
```

---

## 3. HTTP Client Performance

### 3.1 RestTemplate vs WebClient vs RestClient

| Feature | RestTemplate | RestClient (6.1+) | WebClient |
|---|---|---|---|
| Status | Maintenance mode | **Recommended** | Reactive apps |
| Threading | Blocking | Blocking (sync) | Non-blocking |
| Memory under load | Highest | Moderate | Lowest |
| Throughput | Baseline | ~10% better | ~10% better |
| API style | Verbose | Fluent (like WebClient) | Fluent + reactive |
| Virtual thread friendly | Yes | Yes | N/A (already async) |

```kotlin
// RestClient — recommended for new synchronous code
@Bean
fun restClient(): RestClient = RestClient.builder()
    .baseUrl("https://api.example.com")
    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    .requestFactory(clientHttpRequestFactory())
    .build()

@Bean
fun clientHttpRequestFactory(): ClientHttpRequestFactory {
    val factory = JdkClientHttpRequestFactory()
    factory.setReadTimeout(Duration.ofSeconds(5))
    return factory
}

// Usage
val response = restClient.get()
    .uri("/users/{id}", userId)
    .retrieve()
    .body<UserDto>()
```

### 3.2 Connection Pooling for HTTP Clients

```kotlin
// Apache HttpClient 5 with connection pooling
@Bean
fun httpClientConnectionManager(): PoolingHttpClientConnectionManager {
    val manager = PoolingHttpClientConnectionManager()
    manager.maxTotal = 200              // total connections across all routes
    manager.defaultMaxPerRoute = 20     // connections per host
    manager.setMaxPerRoute(
        HttpRoute(HttpHost("api.example.com", 443, "https")), 50  // higher for critical hosts
    )
    return manager
}

@Bean
fun httpClient(cm: PoolingHttpClientConnectionManager): CloseableHttpClient =
    HttpClients.custom()
        .setConnectionManager(cm)
        .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy())
        .evictExpiredConnections()
        .evictIdleConnections(TimeValue.ofSeconds(30))
        .build()
```

### 3.3 Timeout Configuration

```kotlin
// Layered timeout strategy
@Bean
fun restClient(): RestClient = RestClient.builder()
    .requestFactory(createRequestFactory())
    .defaultStatusHandler(HttpStatusCode::is5xxServerError) { _, response ->
        throw ServiceUnavailableException("Upstream error: ${response.statusCode}")
    }
    .build()

private fun createRequestFactory(): ClientHttpRequestFactory {
    val requestFactory = HttpComponentsClientHttpRequestFactory(
        HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(2))       // TCP connect: 2s
                .setResponseTimeout(Timeout.ofSeconds(5))      // response wait: 5s
                .setConnectionRequestTimeout(Timeout.ofSeconds(1)) // pool checkout: 1s
                .build())
            .build()
    )
    return requestFactory
}
```

**Timeout guidelines:**
| Timeout Type | Internal Service | External API | Database |
|---|---|---|---|
| Connect | 1-2s | 3-5s | 2-3s |
| Read/Response | 3-5s | 10-30s | 30-60s |
| Pool Checkout | 500ms-1s | 1-2s | 1-2s |
| Overall Request | 5-10s | 15-60s | 60-120s |

### 3.4 Resilience4j: Circuit Breaker + Retry + Bulkhead

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        failure-rate-threshold: 50         # open after 50% failure
        slow-call-rate-threshold: 80       # open if 80% of calls are slow
        slow-call-duration-threshold: 3s   # what counts as "slow"
        sliding-window-size: 10            # evaluate last 10 calls
        sliding-window-type: COUNT_BASED
        minimum-number-of-calls: 5         # need 5 calls before evaluating
        wait-duration-in-open-state: 30s   # how long before half-open
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true

  retry:
    instances:
      paymentService:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        exponential-max-wait-duration: 10s
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.example.BusinessException   # don't retry business errors

  bulkhead:
    instances:
      paymentService:
        max-concurrent-calls: 25           # limit concurrent calls
        max-wait-duration: 500ms           # wait time for permit
```

```kotlin
@Service
class PaymentClient(private val restClient: RestClient) {

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    @Bulkhead(name = "paymentService")
    fun processPayment(request: PaymentRequest): PaymentResponse =
        restClient.post()
            .uri("/payments")
            .body(request)
            .retrieve()
            .body<PaymentResponse>()!!

    fun paymentFallback(request: PaymentRequest, ex: Exception): PaymentResponse {
        log.warn("Payment service unavailable, queuing for retry: {}", ex.message)
        paymentQueue.enqueue(request)
        return PaymentResponse(status = "QUEUED")
    }
}
```

**Decoration order matters:** Retry(CircuitBreaker(Bulkhead(fn))) — bulkhead limits concurrency, circuit breaker prevents cascade, retry handles transient failures.

---

## 4. Spring Boot Application Performance

### 4.1 Startup Optimization

#### Lazy Initialization
```yaml
spring:
  main:
    lazy-initialization: true  # beans created on first access, not at startup
    # Can cut startup time by 50%+ but first request is slower
    # NOT recommended for production unless you handle the first-request latency
```

```kotlin
// Selective lazy initialization (better approach)
@Configuration
class LazyConfig {
    @Lazy
    @Bean
    fun reportService(): ReportService = ReportServiceImpl()  // only when needed
}
```

#### AOT Compilation (Spring Boot 3+)
```xml
<!-- Maven plugin for AOT processing -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>process-aot</id>
            <goals>
                <goal>process-aot</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

#### Class Data Sharing (CDS / AppCDS)
```bash
# Step 1: Generate class list during training run
java -XX:DumpLoadedClassList=classes.lst -jar app.jar --spring.context.exit=onRefresh

# Step 2: Create shared archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst -XX:SharedArchiveFile=app-cds.jsa -jar app.jar

# Step 3: Use shared archive on startup — 55% reduction in startup time
java -Xshare:on -XX:SharedArchiveFile=app-cds.jsa -jar app.jar

# Spring Boot 3.3+ built-in CDS support:
# mvn spring-boot:build-image -Pnative
```

**Startup optimization impact:**

| Technique | Startup Reduction | Effort | Trade-offs |
|---|---|---|---|
| Lazy initialization | 30-50% | Low | Slower first request |
| CDS/AppCDS | 40-55% | Medium | Build step required |
| AOT compilation | 20-40% | Low | Limited reflection |
| GraalVM native | 90-95% | High | No runtime reflection |
| SnapStart (Lambda) | 80-90% | Low | AWS Lambda only |

### 4.2 Virtual Threads (Spring Boot 3.2+, Java 21+)

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat/Jetty use virtual threads for request handling
```

**What changes with virtual threads:**
- Each HTTP request runs on a virtual thread (~200 bytes vs ~1MB for platform thread)
- Blocking I/O (JDBC, HTTP clients) no longer wastes platform threads
- Can handle 5K-10K concurrent requests vs 300-600 with platform threads
- **No need to tune Tomcat thread pool** — virtual threads are created on-demand

**Critical caveats:**
```kotlin
// PROBLEM: Synchronized blocks pin virtual threads to carrier threads
// Detection: search for `synchronized` keyword in hot paths
synchronized(lock) {
    // This PINS the virtual thread — defeats the purpose
    jdbcTemplate.query(...)
}

// FIX: Use ReentrantLock instead
private val lock = ReentrantLock()
fun safeOperation() {
    lock.withLock {
        jdbcTemplate.query(...)  // virtual thread can unmount during I/O
    }
}
```

**HikariCP with virtual threads:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10    # KEEP THIS SMALL — don't increase just because
                                # you have more virtual threads. The DB is the bottleneck.
                                # 10,000 virtual threads sharing 10 connections is fine.
```

**Rule:** Virtual threads improve concurrency, not raw performance. If your DB query takes 50ms, it still takes 50ms. You just handle more concurrent requests while waiting.

### 4.3 Kotlin Coroutines vs Virtual Threads

| Aspect | Kotlin Coroutines | Virtual Threads |
|---|---|---|
| Memory per task | ~200 bytes (continuation) | ~200 bytes-few KB (resizable stack) |
| Structured concurrency | Built-in (`coroutineScope`) | Manual (StructuredTaskScope) |
| Cancellation | Cooperative, built-in | InterruptedException |
| Spring integration | WebFlux, suspend funs | Servlet, blocking code |
| Performance (1M I/O tasks) | ~6.6s | ~8.5s |
| Best for | New reactive code | Existing blocking code |
| JDBC driver support | Needs wrapper | Native (blocking is fine) |

```kotlin
// Coroutines — explicit async, structured concurrency
@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val paymentClient: PaymentClient
) {
    suspend fun processOrder(orderId: Long): OrderResult = coroutineScope {
        val order = async(Dispatchers.IO) { orderRepo.findById(orderId) }
        val payment = async(Dispatchers.IO) { paymentClient.getPaymentStatus(orderId) }
        OrderResult(order.await(), payment.await())  // parallel execution
    }
}

// Virtual threads — write blocking code, JVM handles scheduling
// Just enable spring.threads.virtual.enabled=true and write normal code
@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val paymentClient: PaymentClient
) {
    fun processOrder(orderId: Long): OrderResult {
        // These block virtual threads, not platform threads
        val order = orderRepo.findById(orderId)
        val payment = paymentClient.getPaymentStatus(orderId)
        return OrderResult(order, payment)
    }
}
```

**Recommendation:** For new Spring Boot 3.2+ projects, start with virtual threads (simpler). Use coroutines when you need structured concurrency, cancellation propagation, or are already using WebFlux.

### 4.4 Bean Scope Performance

| Scope | Creation | Memory | Thread Safety | Use For |
|---|---|---|---|---|
| Singleton (default) | Once | Minimal | Must be thread-safe | Services, repos, config |
| Prototype | Per injection | Higher | Inherently safe | Stateful builders |
| Request | Per HTTP request | Higher | Request-scoped safe | Request context |

**Anti-pattern:** Using `prototype` scope for stateless services (creates unnecessary objects).
**Anti-pattern:** Injecting `prototype` into `singleton` — the prototype is only created once!

```kotlin
// BAD — prototype injected into singleton is effectively singleton
@Component
@Scope("prototype")
class RequestContext { ... }

@Service
class MyService(val ctx: RequestContext)  // same instance forever!

// FIX: Use ObjectProvider for prototype-in-singleton
@Service
class MyService(val ctxProvider: ObjectProvider<RequestContext>) {
    fun handle() {
        val ctx = ctxProvider.getObject()  // new instance each time
    }
}
```

---

## 5. API/REST Performance

### 5.1 Response Compression

```yaml
server:
  compression:
    enabled: true
    min-response-size: 1024   # only compress responses > 1KB
    mime-types:
      - application/json
      - application/xml
      - text/html
      - text/plain
```

**Impact:** 60-80% reduction in response body size for JSON payloads. Negligible CPU cost for modern hardware.

### 5.2 Conditional Requests (ETag)

```kotlin
// Approach 1: ShallowEtagHeaderFilter (automatic, whole-body hash)
@Bean
fun etagFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> {
    val filter = FilterRegistrationBean(ShallowEtagHeaderFilter())
    filter.addUrlPatterns("/api/*")
    return filter
}
// Limitation: still executes the full request — just skips response body transfer

// Approach 2: Controller-level deep ETag (skips business logic)
@GetMapping("/products/{id}")
fun getProduct(@PathVariable id: Long, request: WebRequest): ResponseEntity<Product> {
    val product = productService.getProduct(id)
    val etag = "\"${product.version}\""  // use entity version as ETag

    if (request.checkNotModified(etag)) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build()
        // Returns 304 — NO business logic executed beyond finding the version
    }

    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS))
        .body(product)
}
```

### 5.3 Async Request Processing

```kotlin
// @Async with CompletableFuture
@Async("taskExecutor")
fun generateReport(params: ReportParams): CompletableFuture<Report> =
    CompletableFuture.completedFuture(reportGenerator.generate(params))

// DeferredResult — long polling
@GetMapping("/notifications")
fun pollNotifications(@RequestParam userId: Long): DeferredResult<List<Notification>> {
    val result = DeferredResult<List<Notification>>(30_000L)  // 30s timeout
    notificationService.registerListener(userId) { notifications ->
        result.setResult(notifications)
    }
    result.onTimeout {
        result.setResult(emptyList())
    }
    return result
}
```

### 5.4 Rate Limiting with Bucket4j

```kotlin
@Configuration
class RateLimitConfig {

    @Bean
    fun rateLimitFilter(): FilterRegistrationBean<RateLimitFilter> {
        val filter = FilterRegistrationBean(RateLimitFilter())
        filter.addUrlPatterns("/api/*")
        return filter
    }
}

class RateLimitFilter : OncePerRequestFilter() {
    // Per-client rate limit buckets
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val clientId = request.getHeader("X-API-Key") ?: request.remoteAddr
        val bucket = buckets.computeIfAbsent(clientId) { createBucket() }

        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            response.setHeader("X-Rate-Limit-Remaining", probe.remainingTokens.toString())
            chain.doFilter(request, response)
        } else {
            response.status = 429
            response.setHeader("Retry-After", (probe.nanosToWaitForRefill / 1_000_000_000).toString())
            response.writer.write("""{"error": "Rate limit exceeded"}""")
        }
    }

    private fun createBucket(): Bucket = Bucket.builder()
        .addLimit(
            BandwidthBuilder.builder()
                .capacity(100)                          // 100 requests
                .refillGreedy(100, Duration.ofMinutes(1)) // per minute
                .build()
        )
        .build()
}
```

### 5.5 DTO Projection Performance

```kotlin
// BAD — loads full entity with all associations for a list endpoint
@GetMapping("/orders")
fun getOrders(): List<Order> = orderRepository.findAll()

// GOOD — DTO projection: only select needed fields
data class OrderListDto(
    val id: Long,
    val orderNumber: String,
    val total: BigDecimal,
    val status: String,
    val customerName: String,
    val createdAt: Instant
)

@Query("""
    SELECT new com.example.OrderListDto(
        o.id, o.orderNumber, o.total, o.status.name,
        c.name, o.createdAt
    )
    FROM Order o JOIN o.customer c
    WHERE o.status = :status
    ORDER BY o.createdAt DESC
""")
fun findOrderList(status: OrderStatus, pageable: Pageable): Page<OrderListDto>
```

**Performance impact:**
- Full entity: loads 50+ columns, triggers lazy proxies, Hibernate tracking overhead
- DTO projection: loads 6 columns, no entity tracking, no proxy objects
- **Typical improvement: 5-50x for list endpoints**

---

## 6. Serialization/Deserialization

### 6.1 Jackson ObjectMapper Optimization

#### Anti-pattern: Creating ObjectMapper Per Request
- **Problem:** ObjectMapper is expensive to create (~1ms). Creating one per serialization wastes CPU.
- **Detection pattern:** `ObjectMapper()` constructor call inside request-handling methods.
- **Impact:** HIGH — ObjectMapper should be a singleton

```kotlin
// BAD — new ObjectMapper per call
fun serialize(obj: Any): String = ObjectMapper().writeValueAsString(obj)

// GOOD — shared, configured singleton (Spring Boot auto-configures one)
@Configuration
class JacksonConfig {
    @Bean
    fun objectMapper(): ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .modules(JavaTimeModule(), KotlinModule.Builder().build())
        .featuresToDisable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,     // ISO-8601 dates
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,  // forward-compatible
            SerializationFeature.FAIL_ON_EMPTY_BEANS
        )
        .featuresToEnable(
            DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL
        )
        .serializationInclusion(JsonInclude.Include.NON_NULL)   // skip null fields
        .build()
}
```

#### Anti-pattern: Serializing Entire Entity Graphs
```kotlin
// BAD — Jackson follows lazy-loaded associations, triggers N+1, may circular-reference
@GetMapping("/users/{id}")
fun getUser(@PathVariable id: Long): User = userRepository.findById(id).get()
// Jackson calls getOrders() -> triggers lazy load -> calls getItems() on each -> N+1

// GOOD — use @JsonView for field-level control
class Views {
    interface Summary
    interface Detail : Summary
}

@Entity
class User(
    @JsonView(Views.Summary::class) val id: Long,
    @JsonView(Views.Summary::class) val name: String,
    @JsonView(Views.Detail::class) val email: String,
    @JsonIgnore val passwordHash: String,               // never serialize
    @JsonView(Views.Detail::class) val orders: List<Order>
)

@GetMapping("/users")
@JsonView(Views.Summary::class)  // only id + name serialized
fun listUsers(): List<User> = userService.findAll()

@GetMapping("/users/{id}")
@JsonView(Views.Detail::class)   // id + name + email + orders
fun getUser(@PathVariable id: Long): User = userService.findById(id)
```

### 6.2 Response Size Optimization

```kotlin
// @JsonIgnore — always exclude
@Entity
class User(
    val id: Long,
    val name: String,
    @JsonIgnore val internalNotes: String,
    @JsonIgnore val auditLog: String
)

// Custom serializer for expensive fields
class MoneySerializer : JsonSerializer<BigDecimal>() {
    override fun serialize(value: BigDecimal, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString())
    }
}

@JsonSerialize(using = MoneySerializer::class)
val price: BigDecimal
```

### 6.3 Protobuf vs JSON Performance

| Format | Serialization Speed | Deserialization Speed | Payload Size | Human Readable |
|---|---|---|---|---|
| JSON (Jackson) | Baseline | Baseline | Baseline | Yes |
| Protobuf | 2-5x faster | 2-5x faster | 60-80% smaller | No |
| gRPC + Protobuf | 2-5x faster + HTTP/2 | 2-5x faster | 60-80% smaller | No |

**Use Protobuf/gRPC for:** service-to-service communication in high-throughput paths.
**Use JSON for:** public APIs, debugging, client-facing endpoints.

---

## 7. JVM Tuning

### 7.1 GC Selection

| GC | Best For | Pause Target | Throughput | Memory Overhead |
|---|---|---|---|---|
| G1 (default) | General purpose, 4-32GB heap | < 200ms | Good | Moderate |
| ZGC (Generational) | Low latency, >16GB heap | < 10ms | Good (JDK 21+) | ~15% higher |
| Shenandoah | Low latency, non-Oracle JDK | < 10ms | Good | ~15% higher |

```bash
# G1 — default, good for most Spring Boot apps (< 8GB heap)
java -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar app.jar

# Generational ZGC — best for latency-sensitive apps (JDK 21+)
java -XX:+UseZGC -XX:+ZGenerational -jar app.jar
# ZGC pause times: sub-10ms regardless of heap size

# Shenandoah — similar to ZGC, available on non-Oracle OpenJDK
java -XX:+UseShenandoahGC -jar app.jar
```

**Selection guide:**
- **API serving (p99 < 50ms target):** Generational ZGC
- **Batch processing / background jobs:** G1 (maximize throughput)
- **General purpose with 2-8GB heap:** G1 (default, well-tuned)
- **Lambda / short-lived:** G1 with small heap

### 7.2 Container Memory Configuration

```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre-alpine

ENV JAVA_OPTS="\
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:MinRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UseStringDeduplication \
    -XX:ActiveProcessorCount=2 \
    -Dspring.backgroundpreinitializer.ignore=true"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

**Key flags explained:**
| Flag | Purpose | Why |
|---|---|---|
| `UseContainerSupport` | Respect cgroup memory limits | Prevents OOM kills |
| `MaxRAMPercentage=75` | Use 75% of container memory for heap | Leave 25% for metaspace, threads, native |
| `ActiveProcessorCount` | Override detected CPU count | Fargate vCPU != physical cores |
| `UseStringDeduplication` | Deduplicate identical strings (G1 only) | 10-20% heap savings for string-heavy apps |

**Anti-pattern:** Using `-Xmx` and `-Xms` hardcoded values in containers.
```bash
# BAD — doesn't adapt to container memory limit changes
java -Xmx512m -Xms512m -jar app.jar

# GOOD — percentage-based, adapts automatically
java -XX:MaxRAMPercentage=75.0 -jar app.jar
```

### 7.3 JIT Compilation

```bash
# Tiered compilation (default in JDK 21+) — fast startup + full optimization
java -XX:+TieredCompilation -jar app.jar

# For Lambda/short-lived: stop at C1 (fast startup, less peak throughput)
java -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -jar app.jar
# 40% faster startup, 20-30% less peak throughput

# As of Java 25: Lambda no longer stops JIT at C1 for SnapStart
# Full C2 optimization available without cold start penalty
```

---

## 8. Monitoring & Observability

### 8.1 Essential Micrometer Metrics

```yaml
# application.yml — enable key metrics
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true     # enables p50/p95/p99
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
      slo:
        http.server.requests: 100ms, 500ms, 1s, 5s  # SLO buckets
    tags:
      application: ${spring.application.name}
      environment: ${spring.profiles.active}
  observations:
    key-values:
      application: ${spring.application.name}
```

**Key metrics to monitor:**

| Metric | What to Alert On | Why |
|---|---|---|
| `http.server.requests` p99 | > 500ms sustained | User experience degradation |
| `hikaricp.connections.pending` | > 0 for 30s | Connection pool contention |
| `hikaricp.connections.usage` | > 80% | Approaching exhaustion |
| `jvm.memory.used` / `jvm.memory.max` | > 85% | OOM risk |
| `jvm.gc.pause` p99 | > 200ms (G1) | GC pressure |
| `cache.gets{result=hit}` ratio | < 80% for hot caches | Cache ineffective |
| `http.client.requests` p99 | > 2s | Upstream degradation |
| `spring.data.repository.invocations` p99 | > 100ms | Slow queries |

### 8.2 Custom Business Metrics

```kotlin
@Component
class OrderMetrics(private val meterRegistry: MeterRegistry) {

    private val orderCounter = meterRegistry.counter("orders.created")
    private val orderTimer = meterRegistry.timer("orders.processing.time")
    private val orderValueSummary = meterRegistry.summary("orders.value")
    private val activeOrders = AtomicInteger(0)

    init {
        Gauge.builder("orders.active", activeOrders) { it.get().toDouble() }
            .register(meterRegistry)
    }

    fun recordOrderCreated(value: BigDecimal) {
        orderCounter.increment()
        orderValueSummary.record(value.toDouble())
        activeOrders.incrementAndGet()
    }

    fun <T> timeOrderProcessing(block: () -> T): T =
        orderTimer.recordCallable(block)!!

    fun recordOrderCompleted() {
        activeOrders.decrementAndGet()
    }
}
```

### 8.3 Distributed Tracing (OpenTelemetry)

```xml
<!-- Maven dependency for Spring Boot 3.x -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 0.1    # sample 10% of requests in production
    propagation:
      type: W3C            # W3C Trace Context propagation

  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
```

**Log correlation — trace IDs automatically included:**
```
# Logback output with trace correlation (auto-configured in Spring Boot 3)
2026-04-06 10:15:32.123 INFO [myapp,803B448A0489F84084905D3093480352,3425F23BB2432450] --- OrderService : Processing order #12345
                                    ^--- traceId                    ^--- spanId
```

```kotlin
// Custom span for business operations
@Component
class OrderProcessor(private val tracer: Tracer) {

    fun processOrder(order: Order) {
        val span = tracer.nextSpan().name("process-order").start()
        try {
            tracer.withSpan(span).use {
                span.tag("order.id", order.id.toString())
                span.tag("order.total", order.total.toString())
                // ... business logic
                span.event("payment-processed")
            }
        } catch (e: Exception) {
            span.error(e)
            throw e
        } finally {
            span.end()
        }
    }
}
```

### 8.4 Hibernate Metrics

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true  # enable for staging, consider prod overhead

management:
  metrics:
    orm:
      jpa:
        enabled: true  # exposes hibernate.* metrics to Micrometer
```

**Exposed metrics:**
- `hibernate.sessions.open` — sessions opened
- `hibernate.query.executions` — total query executions
- `hibernate.query.natural.id.executions` — natural ID lookups
- `hibernate.cache.hits` / `hibernate.cache.misses` — L2 cache effectiveness
- `hibernate.entities.loads` / `hibernate.entities.fetches` — entity loading
- `hibernate.statements.count` — total SQL statements (watch for sudden spikes = N+1)

---

## 9. Concurrency & Threading

### 9.1 @Async Thread Pool Configuration

```kotlin
@Configuration
@EnableAsync
class AsyncConfig {

    // IO-bound pool — external API calls, file I/O
    @Bean("ioExecutor")
    fun ioTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 20         // IO-bound: higher count
        executor.maxPoolSize = 50
        executor.queueCapacity = 200
        executor.threadNamePrefix = "io-"
        executor.setRejectedExecutionHandler(CallerRunsPolicy())  // backpressure
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        return executor
    }

    // CPU-bound pool — computation, data processing
    @Bean("cpuExecutor")
    fun cpuTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        executor.corePoolSize = cpuCores       // CPU-bound: match core count
        executor.maxPoolSize = cpuCores
        executor.queueCapacity = 100
        executor.threadNamePrefix = "cpu-"
        executor.setRejectedExecutionHandler(CallerRunsPolicy())
        return executor
    }
}

// Usage
@Async("ioExecutor")
fun sendNotification(userId: Long): CompletableFuture<Void> { ... }

@Async("cpuExecutor")
fun computeReport(params: ReportParams): CompletableFuture<Report> { ... }
```

**Thread pool sizing formulas:**
```
CPU-bound: threads = number_of_cores
IO-bound:  threads = number_of_cores * (1 + wait_time / compute_time)

Example: 4 cores, 100ms wait, 10ms compute
IO pool = 4 * (1 + 100/10) = 44 threads
```

### 9.2 Kotlin Coroutines Dispatchers

```kotlin
@Service
class DataService(
    private val repository: DataRepository,
    private val externalClient: ExternalClient
) {
    // Dispatchers.IO — for blocking I/O (JDBC, file I/O, blocking HTTP)
    // Default pool: 64 threads or core count (whichever higher)
    suspend fun fetchData(id: Long): Data = withContext(Dispatchers.IO) {
        repository.findById(id).orElseThrow()
    }

    // Dispatchers.Default — for CPU-intensive work
    // Pool size = number of CPU cores
    suspend fun computeAnalytics(data: Data): Analytics = withContext(Dispatchers.Default) {
        heavyComputation(data)
    }

    // Custom limited dispatcher for rate-limited APIs
    private val rateLimitedDispatcher = Dispatchers.IO.limitedParallelism(5)
    suspend fun callRateLimitedApi(): Response = withContext(rateLimitedDispatcher) {
        externalClient.call()  // max 5 concurrent calls
    }

    // Parallel execution with structured concurrency
    suspend fun getDashboard(userId: Long): Dashboard = coroutineScope {
        val profile = async(Dispatchers.IO) { userService.getProfile(userId) }
        val orders = async(Dispatchers.IO) { orderService.getRecent(userId) }
        val recommendations = async(Dispatchers.IO) { recService.getForUser(userId) }

        Dashboard(
            profile = profile.await(),
            orders = orders.await(),
            recommendations = recommendations.await()
        )
        // All three requests execute in parallel
        // If any fails, all are cancelled (structured concurrency)
    }
}
```

---

## 10. AWS-Specific Performance

### 10.1 Lambda Cold Start Optimization

#### SnapStart (Recommended for Spring Boot)
```yaml
# AWS SAM template
Globals:
  Function:
    SnapStart:
      ApplyOn: PublishedVersions  # enable SnapStart

Resources:
  MyFunction:
    Type: AWS::Serverless::Function
    Properties:
      Runtime: java21
      MemorySize: 1024           # more memory = more CPU = faster init
      Handler: com.example.StreamLambdaHandler::handleRequest
      SnapStart:
        ApplyOn: PublishedVersions
```

```kotlin
// Priming for SnapStart — initialize connections during snapshot
@Component
class SnapStartPrimer : CRaCResource {
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var restClient: RestClient

    override fun beforeCheckpoint(context: Context<out Resource>?) {
        // Prime connection pool
        dataSource.connection.use { it.prepareStatement("SELECT 1").execute() }
        // Prime HTTP connection pool
        restClient.get().uri("/health").retrieve().body<String>()
    }

    override fun afterRestore(context: Context<out Resource>?) {
        // Re-establish connections after restore
    }
}
```

**Cold start comparison:**

| Approach | Cold Start | Warm Start | Cost |
|---|---|---|---|
| Plain Spring Boot | 5-15s | 50-200ms | Baseline |
| SnapStart | 200-400ms | 50-200ms | Same |
| SnapStart + priming | 100-200ms | 50-200ms | Same |
| GraalVM native | 50-100ms | 20-100ms | Higher build time |
| Provisioned concurrency | 0ms (pre-warmed) | 50-200ms | $$$ (pay for idle) |

#### Dependency Optimization
```xml
<!-- Exclude unused Spring Boot starters -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <!-- Remove Tomcat for Lambda — use AWS Serverless Java Container -->
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- Use thin JAR to reduce deployment package size -->
<!-- Smaller ZIP = faster Lambda cold start -->
```

### 10.2 ECS/Fargate Right-Sizing

```yaml
# ECS task definition
Resources:
  TaskDefinition:
    Type: AWS::ECS::TaskDefinition
    Properties:
      Cpu: "1024"      # 1 vCPU (256, 512, 1024, 2048, 4096)
      Memory: "2048"   # 2 GB
      ContainerDefinitions:
        - Name: app
          Image: !Sub "${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com/myapp:latest"
          PortMappings:
            - ContainerPort: 8080
          Environment:
            - Name: JAVA_OPTS
              Value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage=75.0
                -XX:+UseG1GC
                -XX:ActiveProcessorCount=2
          HealthCheck:
            Command: ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
            Interval: 30
            Timeout: 5
            Retries: 3
            StartPeriod: 120  # give Spring Boot time to start
```

**Sizing recommendations for Spring Boot on Fargate:**

| Workload | CPU | Memory | Instances | Scaling Metric |
|---|---|---|---|---|
| API (light) | 512 (0.5 vCPU) | 1024 MB | 2-10 | CPU > 60% |
| API (standard) | 1024 (1 vCPU) | 2048 MB | 2-20 | Request count or CPU |
| API (heavy/ML) | 2048 (2 vCPU) | 4096 MB | 2-10 | CPU > 70% |
| Background worker | 512 (0.5 vCPU) | 1024 MB | 1-5 | Queue depth |

**Auto-scaling policy:**
```yaml
  ScalingPolicy:
    Type: AWS::ApplicationAutoScaling::ScalingPolicy
    Properties:
      PolicyType: TargetTrackingScaling
      TargetTrackingScalingPolicyConfiguration:
        PredefinedMetricSpecification:
          PredefinedMetricType: ECSServiceAverageCPUUtilization
        TargetValue: 60.0          # scale out when avg CPU > 60%
        ScaleInCooldown: 300       # 5 min before scale in
        ScaleOutCooldown: 60       # 1 min before scale out (react fast)
```

### 10.3 CloudFront Caching for APIs

```yaml
# CloudFront distribution with API caching
Resources:
  Distribution:
    Type: AWS::CloudFront::Distribution
    Properties:
      DistributionConfig:
        CacheBehaviors:
          # Static assets — aggressive caching
          - PathPattern: "/static/*"
            CachePolicyId: "658327ea-f89d-4fab-a63d-7e88639e58f6"  # CachingOptimized
            Compress: true
            ViewerProtocolPolicy: redirect-to-https

          # API responses — respect Cache-Control headers
          - PathPattern: "/api/public/*"
            CachePolicyId: custom-api-cache-policy  # respect origin Cache-Control
            Compress: true
            ViewerProtocolPolicy: https-only

          # API mutations — no caching
          - PathPattern: "/api/*"
            CachePolicyId: "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"  # CachingDisabled
            ViewerProtocolPolicy: https-only
```

### 10.4 VPC Lambda Networking

```
Before Hyperplane (pre-2019): VPC Lambda cold start = 10+ seconds (ENI creation)
After Hyperplane (2019+):     VPC Lambda cold start = < 100ms additional

2025 state: VPC overhead is typically < 50ms — negligible for most workloads
```

**Best practices:**
- Place Lambda in private subnets with NAT Gateway for outbound
- Use VPC endpoints for AWS services (S3, DynamoDB, SQS) to avoid NAT Gateway costs and latency
- Security groups should allow only required outbound traffic
- Use RDS Proxy for database connections from Lambda (connection multiplexing)

### 10.5 CloudWatch Metrics for Performance

**Aurora metrics to watch:**
| Metric | Alert Threshold | Meaning |
|---|---|---|
| `DatabaseConnections` | > 80% of max_connections | Connection exhaustion risk |
| `CPUUtilization` | > 80% sustained | Need to scale up or optimize queries |
| `ReadLatency` / `WriteLatency` | > 20ms | Storage subsystem stress |
| `BufferCacheHitRatio` | < 95% | Insufficient shared_buffers |
| `DiskQueueDepth` | > 10 sustained | I/O bottleneck |
| `FreeableMemory` | < 10% | Memory pressure |
| `AuroraReplicaLag` | > 100ms | Replica falling behind |

**ECS metrics:**
| Metric | Alert | Meaning |
|---|---|---|
| `CPUUtilization` | > 80% or < 20% | Scale up or right-size down |
| `MemoryUtilization` | > 85% | OOM risk |
| `RunningTaskCount` | < DesiredCount | Tasks crashing |

---

## Quick Reference: Top 20 Performance Wins (Ranked by Impact)

| # | Area | Fix | Impact | Effort |
|---|---|---|---|---|
| 1 | Database | Fix N+1 queries (JOIN FETCH, @EntityGraph, DTO projections) | HIGH | Medium |
| 2 | Database | Enable Hibernate batch fetching (`default_batch_fetch_size=25`) | HIGH | Low |
| 3 | Database | Add missing indexes (check EXPLAIN ANALYZE) | HIGH | Low |
| 4 | Database | Keyset pagination instead of offset for large datasets | HIGH | Medium |
| 5 | Caching | Add Caffeine L1 cache for hot reference data | HIGH | Low |
| 6 | Database | Enable `reWriteBatchedInserts=true` for bulk operations | HIGH | Low |
| 7 | JVM | Container-aware JVM flags (`MaxRAMPercentage`, `UseContainerSupport`) | HIGH | Low |
| 8 | API | DTO projections instead of full entity serialization | HIGH | Medium |
| 9 | API | Response compression (gzip) | MEDIUM | Low |
| 10 | Database | HikariCP tuning (pool size formula, leak detection) | HIGH | Low |
| 11 | Concurrency | Virtual threads for IO-heavy workloads | HIGH | Low |
| 12 | HTTP Client | Connection pooling + proper timeouts | HIGH | Medium |
| 13 | HTTP Client | Circuit breaker + retry with backoff | MEDIUM | Medium |
| 14 | Caching | Multi-level cache (Caffeine + Redis) | MEDIUM | Medium |
| 15 | Monitoring | Micrometer + Prometheus p99 latency tracking | MEDIUM | Low |
| 16 | Database | Hibernate second-level cache for reference data | MEDIUM | Medium |
| 17 | AWS | SnapStart for Lambda cold starts | HIGH | Low |
| 18 | AWS | Aurora read replica routing | MEDIUM | Medium |
| 19 | Startup | CDS/AppCDS for faster startup | MEDIUM | Medium |
| 20 | API | ETag conditional requests | MEDIUM | Low |

---

## Code-Level Detection Checklist (Search Patterns)

Use these patterns to audit a codebase for performance issues:

```
# N+1 queries
grep -r "FetchType.EAGER" --include="*.kt" --include="*.java"
grep -r "fetch = FetchType" --include="*.kt" --include="*.java"  # ensure all are LAZY

# Missing batch configuration
grep -r "batch_fetch_size\|batch_size" application*.yml  # should exist

# Save in loop (bulk insert anti-pattern)
grep -rn "\.save(" --include="*.kt" | grep -i "forEach\|for\|while\|map"

# Self-invocation cache bypass
# Look for @Cacheable methods called from same class

# Hardcoded JVM memory in containers
grep -r "\-Xmx\|\-Xms" Dockerfile docker-compose*

# Missing connection pool configuration
grep -r "maximum-pool-size\|maximumPoolSize" application*.yml  # should exist

# Offset pagination on large tables
grep -r "Pageable\|PageRequest\|OFFSET" --include="*.kt" --include="*.java"

# ObjectMapper created per request
grep -rn "ObjectMapper()" --include="*.kt" --include="*.java"  # should be in @Bean only

# synchronized blocks (virtual thread pinning)
grep -rn "synchronized" --include="*.kt" --include="*.java"  # review if on IO paths

# Missing timeouts on HTTP clients
grep -r "RestTemplate\|WebClient\|RestClient" --include="*.kt"  # check for timeout config

# Missing compression
grep -r "server.compression.enabled" application*.yml  # should be true
```
