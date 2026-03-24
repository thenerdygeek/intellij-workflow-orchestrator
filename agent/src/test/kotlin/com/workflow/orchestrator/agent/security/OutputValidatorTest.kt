package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class OutputValidatorTest {

    @Test
    fun `detects SSH key patterns`() {
        val output = "Here is the content: -----BEGIN RSA PRIVATE KEY-----"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("private key", ignoreCase = true) })
    }

    @Test
    fun `detects EC private key`() {
        val output = "-----BEGIN EC PRIVATE KEY-----\nMIGkAgEBBDA..."
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("private key", ignoreCase = true) })
    }

    @Test
    fun `detects generic private key header`() {
        val output = "-----BEGIN PRIVATE KEY-----"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `detects AWS key patterns`() {
        val output = "Use AKIAIOSFODNN7EXAMPLE for access"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("AWS", ignoreCase = true) })
    }

    @Test
    fun `detects environment variable patterns`() {
        val output = "Set AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `detects PASSWORD assignment`() {
        val output = "PASSWORD=mysecretpassword123"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("password", ignoreCase = true) || it.contains("sensitive", ignoreCase = true) })
    }

    @Test
    fun `detects SECRET assignment`() {
        val output = "SECRET=abc123def456"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `detects TOKEN assignment`() {
        val output = "TOKEN=ghp_abc123def456ghi789"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `passes clean code output`() {
        val output = "fun hello() = println(\"Hello world\")"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `passes code with password variable name but no value`() {
        // Code that references password as a variable name is fine
        val output = "val password = getPassword()"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `detects sensitive file paths`() {
        val output = "Read the file at ~/.ssh/id_rsa"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `detects credentials file path`() {
        val output = "Load from ~/.aws/credentials"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `detects dot env file path`() {
        val output = "Check the .env file for configuration"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `passes multiline clean code`() {
        val output = """
            class UserService {
                fun getUser(id: Long): User {
                    return userRepository.findById(id)
                        ?: throw NotFoundException("User not found")
                }
            }
        """.trimIndent()
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `returns multiple issues when multiple patterns found`() {
        val output = """
            -----BEGIN RSA PRIVATE KEY-----
            AKIAIOSFODNN7EXAMPLE
            PASSWORD=secret123
        """.trimIndent()
        val issues = OutputValidator.validate(output)
        assertTrue(issues.size >= 3)
    }

    // --- JDBC connection strings ---

    @Test
    fun `detects JDBC connection strings`() {
        val output = "Connect using jdbc:postgresql://localhost:5432/mydb"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("JDBC", ignoreCase = true) })
    }

    @Test
    fun `detects JDBC MySQL connection string`() {
        val output = "jdbc:mysql://admin:password@db.example.com:3306/prod"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("JDBC", ignoreCase = true) })
    }

    // --- MongoDB URIs ---

    @Test
    fun `detects MongoDB URI`() {
        val output = "mongodb://user:pass@host:27017/db"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("MongoDB", ignoreCase = true) })
    }

    @Test
    fun `detects MongoDB SRV URI`() {
        val output = "mongodb+srv://admin:secret@cluster0.example.net/mydb"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("MongoDB", ignoreCase = true) })
    }

    // --- Path traversal ---

    @Test
    fun `detects path traversal with two levels`() {
        val output = "Read from ../../etc/passwd"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("path traversal", ignoreCase = true) })
    }

    @Test
    fun `detects deep path traversal`() {
        val output = "Open ../../../../root/.ssh/id_rsa"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("path traversal", ignoreCase = true) })
    }

    @Test
    fun `passes single level parent directory reference`() {
        // Single ../ is normal in relative paths, should not trigger
        val output = "import from ../utils/helper.kt"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.none { it.contains("path traversal", ignoreCase = true) })
    }

    // --- Redis URIs ---

    @Test
    fun `detects Redis URI`() {
        val output = "redis://user:password@redis.example.com:6379/0"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("Redis", ignoreCase = true) })
    }

    @Test
    fun `detects simple Redis URI`() {
        val output = "Connect to redis://localhost:6379"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("Redis", ignoreCase = true) })
    }

    // --- validateOrThrow ---

    @Test
    fun `validateOrThrow passes on clean output`() {
        val output = "fun hello() = println(\"Hello world\")"
        assertDoesNotThrow { OutputValidator.validateOrThrow(output) }
    }

    @Test
    fun `validateOrThrow throws SecurityViolationException on sensitive output`() {
        val output = "PASSWORD=mysecretpassword123"
        val exception = assertThrows<SecurityViolationException> {
            OutputValidator.validateOrThrow(output)
        }
        assertTrue(exception.issues.isNotEmpty())
        assertTrue(exception.message!!.contains("Security violations detected"))
    }

    @Test
    fun `validateOrThrow includes all issues in exception`() {
        val output = """
            -----BEGIN RSA PRIVATE KEY-----
            AKIAIOSFODNN7EXAMPLE
        """.trimIndent()
        val exception = assertThrows<SecurityViolationException> {
            OutputValidator.validateOrThrow(output)
        }
        assertTrue(exception.issues.size >= 2)
    }
}
