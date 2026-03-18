package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
}
