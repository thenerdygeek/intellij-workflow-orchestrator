package com.workflow.orchestrator.agent.tools.framework.spring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [parseYamlToFlatProperties] — the SnakeYAML-based YAML parser
 * that replaces the old naive line-by-line parser.
 *
 * Each nested class covers a category of YAML features that the old parser
 * silently dropped or mangled.
 */
class SpringYamlParserTest {

    @Nested
    inner class BasicProperties {

        @Test
        fun `parses simple flat properties`() {
            val yaml = """
                server.port: 8080
                spring.application.name: my-app
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("8080", result["server.port"])
            assertEquals("my-app", result["spring.application.name"])
        }

        @Test
        fun `parses nested YAML into dotted keys`() {
            val yaml = """
                server:
                  port: 8080
                  servlet:
                    context-path: /api
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("8080", result["server.port"])
            assertEquals("/api", result["server.servlet.context-path"])
        }

        @Test
        fun `returns empty map for empty content`() {
            assertEquals(emptyMap<String, String>(), parseYamlToFlatProperties(""))
        }

        @Test
        fun `returns empty map for comment-only content`() {
            val yaml = """
                # This is a comment
                # Another comment
            """.trimIndent()
            assertEquals(emptyMap<String, String>(), parseYamlToFlatProperties(yaml))
        }

        @Test
        fun `handles quoted string values`() {
            val yaml = """
                spring:
                  datasource:
                    url: "jdbc:postgresql://localhost:5432/db"
                    username: 'admin'
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("jdbc:postgresql://localhost:5432/db", result["spring.datasource.url"])
            assertEquals("admin", result["spring.datasource.username"])
        }

        @Test
        fun `handles boolean and numeric values`() {
            val yaml = """
                spring:
                  jpa:
                    show-sql: true
                    hibernate:
                      ddl-auto: update
                server:
                  port: 8443
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("true", result["spring.jpa.show-sql"])
            assertEquals("update", result["spring.jpa.hibernate.ddl-auto"])
            assertEquals("8443", result["server.port"])
        }
    }

    @Nested
    inner class MultiLineValues {

        @Test
        fun `handles literal block scalar`() {
            val yaml = """
                app:
                  description: |
                    This is a multi-line
                    description for the app.
                  version: 1.0
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertTrue(result["app.description"]!!.contains("multi-line"))
            assertTrue(result["app.description"]!!.contains("description for the app"))
            assertEquals("1.0", result["app.version"])
        }

        @Test
        fun `handles folded block scalar`() {
            val yaml = """
                app:
                  motto: >
                    This is a long motto
                    that spans multiple lines
                    but folds into one.
                  active: true
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertTrue(result["app.motto"]!!.contains("motto"))
            assertEquals("true", result["app.active"])
        }
    }

    @Nested
    inner class FlowMappingsAndSequences {

        @Test
        fun `handles flow mapping`() {
            val yaml = """
                spring:
                  datasource: {url: "jdbc:h2:mem:test", username: sa, password: ""}
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("jdbc:h2:mem:test", result["spring.datasource.url"])
            assertEquals("sa", result["spring.datasource.username"])
        }

        @Test
        fun `handles flow sequence`() {
            val yaml = """
                spring:
                  profiles:
                    active: [dev, local]
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            // Should be comma-joined for scalar lists
            assertEquals("dev,local", result["spring.profiles.active"])
        }
    }

    @Nested
    inner class AnchorsAndAliases {

        @Test
        fun `handles anchors and aliases`() {
            val yaml = """
                defaults: &defaults
                  adapter: postgres
                  host: localhost

                development:
                  database: dev_db
                  <<: *defaults

                production:
                  database: prod_db
                  <<: *defaults
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("localhost", result["defaults.host"])
            assertEquals("dev_db", result["development.database"])
            assertEquals("postgres", result["development.adapter"])
            assertEquals("localhost", result["development.host"])
            assertEquals("prod_db", result["production.database"])
            assertEquals("postgres", result["production.adapter"])
        }
    }

    @Nested
    inner class InlineComments {

        @Test
        fun `handles inline comments after values`() {
            val yaml = """
                server:
                  port: 8080 # the default port
                  ssl:
                    enabled: false # will be true in prod
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("8080", result["server.port"])
            assertEquals("false", result["server.ssl.enabled"])
        }
    }

    @Nested
    inner class ManagementProperties {

        @Test
        fun `parses management properties for actuator`() {
            val yaml = """
                management:
                  endpoints:
                    web:
                      exposure:
                        include: "*"
                      base-path: /manage
                  server:
                    port: 9090
                  endpoint:
                    health:
                      show-details: always
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("*", result["management.endpoints.web.exposure.include"])
            assertEquals("/manage", result["management.endpoints.web.base-path"])
            assertEquals("9090", result["management.server.port"])
            assertEquals("always", result["management.endpoint.health.show-details"])
        }
    }

    @Nested
    inner class SpringProfiles {

        @Test
        fun `parses spring profiles active property`() {
            val yaml = """
                spring:
                  profiles:
                    active: dev
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("dev", result["spring.profiles.active"])
        }

        @Test
        fun `parses spring profiles active as list`() {
            val yaml = """
                spring:
                  profiles:
                    active:
                      - dev
                      - local
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("dev,local", result["spring.profiles.active"])
        }
    }

    @Nested
    inner class ContextPath {

        @Test
        fun `parses server servlet context-path`() {
            val yaml = """
                server:
                  servlet:
                    context-path: /my-app
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("/my-app", result["server.servlet.context-path"])
        }
    }

    @Nested
    inner class ListValues {

        @Test
        fun `flattens list items with bracket-index keys`() {
            val yaml = """
                spring:
                  datasource:
                    urls:
                      - jdbc:h2:mem:db1
                      - jdbc:h2:mem:db2
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("jdbc:h2:mem:db1", result["spring.datasource.urls[0]"])
            assertEquals("jdbc:h2:mem:db2", result["spring.datasource.urls[1]"])
            // Also comma-joined
            assertEquals("jdbc:h2:mem:db1,jdbc:h2:mem:db2", result["spring.datasource.urls"])
        }

        @Test
        fun `handles list of maps`() {
            val yaml = """
                spring:
                  security:
                    oauth2:
                      client:
                        registration:
                          - clientId: google
                            scope: openid
                          - clientId: github
                            scope: read:user
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("google", result["spring.security.oauth2.client.registration[0].clientId"])
            assertEquals("github", result["spring.security.oauth2.client.registration[1].clientId"])
        }
    }

    @Nested
    inner class InvalidYaml {

        @Test
        fun `returns empty map for malformed YAML`() {
            val yaml = """
                this is: [not valid: yaml
                  broken: {{{{
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            // Should not throw — returns empty map
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class DocumentSeparators {

        @Test
        fun `handles document with separator`() {
            val yaml = """
                ---
                server:
                  port: 8080
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)
            assertEquals("8080", result["server.port"])
        }
    }

    @Nested
    inner class RealWorldSpringConfig {

        @Test
        fun `parses a realistic Spring Boot application yml`() {
            val yaml = """
                spring:
                  application:
                    name: workflow-service
                  datasource:
                    url: jdbc:postgresql://localhost:5432/workflow
                    username: app_user
                    password: secret123
                    hikari:
                      maximum-pool-size: 20
                      minimum-idle: 5
                  jpa:
                    show-sql: false
                    hibernate:
                      ddl-auto: validate
                    properties:
                      hibernate:
                        format_sql: true
                  profiles:
                    active: dev

                server:
                  port: 8080
                  servlet:
                    context-path: /api/v1

                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info,metrics,prometheus
                      base-path: /actuator
                  endpoint:
                    health:
                      show-details: when-authorized
                  server:
                    port: 9090

                logging:
                  level:
                    root: INFO
                    com.workflow: DEBUG
            """.trimIndent()
            val result = parseYamlToFlatProperties(yaml)

            // Application
            assertEquals("workflow-service", result["spring.application.name"])

            // Datasource
            assertEquals("jdbc:postgresql://localhost:5432/workflow", result["spring.datasource.url"])
            assertEquals("20", result["spring.datasource.hikari.maximum-pool-size"])

            // JPA
            assertEquals("false", result["spring.jpa.show-sql"])
            assertEquals("validate", result["spring.jpa.hibernate.ddl-auto"])
            assertEquals("true", result["spring.jpa.properties.hibernate.format_sql"])

            // Profiles
            assertEquals("dev", result["spring.profiles.active"])

            // Server
            assertEquals("8080", result["server.port"])
            assertEquals("/api/v1", result["server.servlet.context-path"])

            // Management (Actuator)
            assertEquals("health,info,metrics,prometheus", result["management.endpoints.web.exposure.include"])
            assertEquals("/actuator", result["management.endpoints.web.base-path"])
            assertEquals("when-authorized", result["management.endpoint.health.show-details"])
            assertEquals("9090", result["management.server.port"])

            // Logging
            assertEquals("INFO", result["logging.level.root"])
            assertEquals("DEBUG", result["logging.level.com.workflow"])
        }
    }
}
