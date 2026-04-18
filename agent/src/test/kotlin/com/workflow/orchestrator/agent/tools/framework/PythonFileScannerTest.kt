package com.workflow.orchestrator.agent.tools.framework

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [PythonFileScanner] — shared utility for Python framework file scanning.
 *
 * Covers:
 *  1. Directory exclusion — venv, __pycache__, node_modules, hidden dirs
 *  2. File scanning — correct filtering with directory exclusions
 *  3. Sensitive value redaction — SECRET_KEY, PASSWORD, API_KEY, etc.
 */
class PythonFileScannerTest {

    // ────────────────────────────────────────────────────────────────────────
    // Directory exclusion
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DirectoryExclusion {

        @Test
        fun `shouldScanDir excludes venv directories`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("venv")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".venv")))
        }

        @Test
        fun `shouldScanDir excludes node_modules`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("node_modules")))
        }

        @Test
        fun `shouldScanDir excludes __pycache__`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("__pycache__")))
        }

        @Test
        fun `shouldScanDir excludes dot-git`() {
            assertFalse(PythonFileScanner.shouldScanDir(File(".git")))
        }

        @Test
        fun `shouldScanDir excludes hidden directories`() {
            assertFalse(PythonFileScanner.shouldScanDir(File(".mypy_cache")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".pytest_cache")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".tox")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".idea")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".ruff_cache")))
        }

        @Test
        fun `shouldScanDir excludes build directories`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("dist")))
            assertFalse(PythonFileScanner.shouldScanDir(File("build")))
            assertFalse(PythonFileScanner.shouldScanDir(File(".eggs")))
        }

        @Test
        fun `shouldScanDir excludes egg-info directories`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("mypackage.egg-info")))
        }

        @Test
        fun `shouldScanDir excludes site-packages`() {
            assertFalse(PythonFileScanner.shouldScanDir(File("site-packages")))
        }

        @Test
        fun `shouldScanDir allows normal directories`() {
            assertTrue(PythonFileScanner.shouldScanDir(File("myapp")))
            assertTrue(PythonFileScanner.shouldScanDir(File("api")))
            assertTrue(PythonFileScanner.shouldScanDir(File("tests")))
            assertTrue(PythonFileScanner.shouldScanDir(File("src")))
            assertTrue(PythonFileScanner.shouldScanDir(File("management")))
        }

        @Test
        fun `shouldScanDir allows current directory dot`() {
            // Single dot "." should not be excluded (it's the current directory)
            assertTrue(PythonFileScanner.shouldScanDir(File(".")))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // File scanning with exclusions
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class FileScanIntegration {

        @Test
        fun `scanPythonFiles skips venv directory`(@TempDir tempDir: Path) {
            // Create app files
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("models.py").writeText("class User(models.Model): pass")

            // Create venv files that should be excluded
            val venvDir = tempDir.resolve("venv/lib/python3.11/site-packages/django")
                .toFile().also { it.mkdirs() }
            venvDir.resolve("models.py").writeText("class Model: pass")

            val files = PythonFileScanner.scanPythonFiles(tempDir.toFile()) {
                it.name == "models.py"
            }

            assertEquals(1, files.size, "Should only find app models.py, not venv one")
            assertTrue(files[0].absolutePath.contains("myapp"))
        }

        @Test
        fun `scanPythonFiles skips __pycache__`(@TempDir tempDir: Path) {
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("views.py").writeText("def index(): pass")

            val cacheDir = tempDir.resolve("myapp/__pycache__").toFile().also { it.mkdirs() }
            cacheDir.resolve("views.cpython-311.py").writeText("compiled")

            val files = PythonFileScanner.scanAllPyFiles(tempDir.toFile())
            assertEquals(1, files.size, "Should not find __pycache__ files")
        }

        @Test
        fun `scanPythonFiles skips node_modules`(@TempDir tempDir: Path) {
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("app.py").writeText("from flask import Flask")

            val nmDir = tempDir.resolve("node_modules/some-package").toFile().also { it.mkdirs() }
            nmDir.resolve("config.py").writeText("# not a real Python file")

            val files = PythonFileScanner.scanAllPyFiles(tempDir.toFile())
            assertEquals(1, files.size, "Should not find node_modules files")
        }

        @Test
        fun `scanPythonFiles skips dot-venv`(@TempDir tempDir: Path) {
            val appDir = tempDir.resolve("src").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText("import fastapi")

            val dotVenvDir = tempDir.resolve(".venv/lib/python3.11/site-packages")
                .toFile().also { it.mkdirs() }
            dotVenvDir.resolve("starlette.py").writeText("# starlette source")

            val files = PythonFileScanner.scanAllPyFiles(tempDir.toFile())
            assertEquals(1, files.size, "Should not find .venv files")
        }

        @Test
        fun `scanAllPyFiles finds all py files in non-excluded dirs`(@TempDir tempDir: Path) {
            val dir1 = tempDir.resolve("app").toFile().also { it.mkdirs() }
            dir1.resolve("main.py").writeText("# main")
            dir1.resolve("utils.py").writeText("# utils")
            val dir2 = tempDir.resolve("tests").toFile().also { it.mkdirs() }
            dir2.resolve("test_main.py").writeText("# test")

            val files = PythonFileScanner.scanAllPyFiles(tempDir.toFile())
            assertEquals(3, files.size, "Should find 3 py files")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Sensitive value redaction
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SensitiveValueRedaction {

        @Test
        fun `redacts SECRET_KEY`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("SECRET_KEY", "'django-insecure-xyz'"))
        }

        @Test
        fun `redacts password fields`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("DATABASE_PASSWORD", "mysecretpw"))
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("DB_PASSWORD", "pass123"))
        }

        @Test
        fun `redacts API_KEY`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("OPENAI_API_KEY", "sk-1234"))
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("API_KEY", "some-key"))
        }

        @Test
        fun `redacts TOKEN`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("AUTH_TOKEN", "eyJ..."))
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("ACCESS_TOKEN", "gho_xxx"))
        }

        @Test
        fun `redacts DATABASE_URL`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("DATABASE_URL", "postgres://user:pass@host/db"))
        }

        @Test
        fun `redacts AWS_SECRET`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("AWS_SECRET_ACCESS_KEY", "wJalrXUtn..."))
        }

        @Test
        fun `redacts REDIS_URL`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("REDIS_URL", "redis://localhost:6379"))
        }

        @Test
        fun `redacts PRIVATE_KEY`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("JWT_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----"))
        }

        @Test
        fun `redacts case insensitively`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("secret_key", "'my-secret'"))
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("database_url", "sqlite:///app.db"))
        }

        @Test
        fun `does not redact non-sensitive keys`() {
            assertEquals("True", PythonFileScanner.redactIfSensitive("DEBUG", "True"))
            assertEquals("['django.contrib.admin']", PythonFileScanner.redactIfSensitive("INSTALLED_APPS", "['django.contrib.admin']"))
            assertEquals("'en-us'", PythonFileScanner.redactIfSensitive("LANGUAGE_CODE", "'en-us'"))
            assertEquals("'UTC'", PythonFileScanner.redactIfSensitive("TIME_ZONE", "'UTC'"))
        }

        @Test
        fun `does not redact ALLOWED_HOSTS`() {
            assertEquals("['*']", PythonFileScanner.redactIfSensitive("ALLOWED_HOSTS", "['*']"))
        }

        @Test
        fun `redacts BROKER_URL`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("CELERY_BROKER_URL", "amqp://guest:guest@localhost"))
        }

        @Test
        fun `redacts SIGNING_KEY`() {
            assertEquals("***REDACTED***", PythonFileScanner.redactIfSensitive("SIGNING_KEY", "'my-signing-key'"))
        }
    }
}
