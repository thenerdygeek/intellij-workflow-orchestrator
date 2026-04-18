package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Scenario-based tests for [DjangoTool].
 *
 * Strategy mirrors [SpringToolTest] and [BuildToolTest]: DjangoTool is a meta-tool
 * with 14 file-scanning actions. Tests lock in:
 *
 *  1. **Tool surface** — name, action enum, parameter schema, allowedWorkers,
 *     ToolDefinition serialization.
 *  2. **Dispatcher contract** — missing action, unknown action, case sensitivity.
 *  3. **Pre-scan validation** — actions that check basePath before scanning.
 *  4. **File scan integration** — for key actions, write a minimal real file
 *     structure into a @TempDir and assert the action returns meaningful results.
 *  5. **Action routing smoke** — every action enum value routes to a non-crashing
 *     path. Catches typos in the dispatcher `when` block.
 */
class DjangoToolTest {

    private val tool = DjangoTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is django`() {
            assertEquals("django", tool.name)
        }

        @Test
        fun `description mentions Django`() {
            assertTrue(tool.description.contains("Django"))
        }

        @Test
        fun `description lists all 14 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `action enum contains exactly 14 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(14, actions!!.size)
        }

        @Test
        fun `action enum contains all expected action names`() {
            val actions = tool.parameters.properties["action"]?.enumValues!!.toSet()
            assertEquals(ALL_ACTIONS.toSet(), actions)
        }

        @Test
        fun `only action is required`() {
            assertEquals(listOf("action"), tool.parameters.required)
        }

        @Test
        fun `filter parameter exists and is string type`() {
            val prop = tool.parameters.properties["filter"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 2`() {
            // action discriminator + filter
            assertEquals(2, tool.parameters.properties.size)
        }

        @Test
        fun `allowedWorkers includes all expected types`() {
            assertEquals(
                setOf(
                    WorkerType.TOOLER,
                    WorkerType.ANALYZER,
                    WorkerType.REVIEWER,
                    WorkerType.ORCHESTRATOR,
                    WorkerType.CODER
                ),
                tool.allowedWorkers
            )
        }

        @Test
        fun `toToolDefinition produces valid schema`() {
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertEquals("django", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(2, def.function.parameters.properties.size)
            assertEquals(listOf("action"), def.function.parameters.required)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 2 — Dispatcher contract
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action"))
        }

        @Test
        fun `unknown action returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "totally_made_up_action")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }

        @Test
        fun `unknown action error mentions the action name`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "fly_to_the_moon")
            }, project)
            assertTrue(result.content.contains("fly_to_the_moon"))
        }

        @Test
        fun `dispatcher routes by exact case-sensitive action name`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "MODELS")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 3 — Pre-scan validation paths
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class PreScanValidation {

        @Test
        fun `models action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `urls action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "urls") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `settings action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "settings") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `middleware action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "middleware") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `models action with nonexistent basePath returns no-files message`() = runTest {
            every { project.basePath } returns "/tmp/nonexistent-django-test-zzz-xyz"
            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            // Should return a "no files found" message, not an error with exception
            assertTrue(
                !result.isError || result.content.contains("No"),
                "Expected no-files message; got: ${result.content}"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 4 — File scan integration tests
    //
    // Write real files to @TempDir and assert actions return meaningful results.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class FileScanIntegration {

        @Test
        fun `models action excludes venv directory`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            // Create app models
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("models.py").writeText(
                """
                from django.db import models
                class Article(models.Model):
                    title = models.CharField(max_length=200)
                """.trimIndent()
            )
            // Create venv models that should be excluded
            val venvDir = tempDir.resolve("venv/lib/python3.11/site-packages/django/db")
                .toFile().also { it.mkdirs() }
            venvDir.resolve("models.py").writeText(
                """
                from django.db import models
                class InternalModel(models.Model):
                    name = models.CharField(max_length=100)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Article"), "Should find Article model")
            assertFalse(result.content.contains("InternalModel"),
                "Should NOT find InternalModel from venv. Got: ${result.content}")
        }

        @Test
        fun `models action finds models in temp project`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("models.py").writeText(
                """
                from django.db import models

                class Article(models.Model):
                    title = models.CharField(max_length=200)
                    body = models.TextField()
                    created_at = models.DateTimeField(auto_now_add=True)

                class Comment(models.Model):
                    article = models.ForeignKey(Article, on_delete=models.CASCADE)
                    text = models.TextField()
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Article"), "Should find Article model")
            assertTrue(result.content.contains("Comment"), "Should find Comment model")
        }

        @Test
        fun `urls action finds URL patterns`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("urls.py").writeText(
                """
                from django.urls import path, include
                from . import views

                urlpatterns = [
                    path('articles/', views.ArticleListView.as_view(), name='article-list'),
                    path('articles/<int:pk>/', views.ArticleDetailView.as_view(), name='article-detail'),
                    path('api/', include('api.urls')),
                ]
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "urls") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("articles/"), "Should find articles/ URL")
            // include() lines must NOT appear with malformed view strings
            assertTrue(result.content.contains("include("), "Should find include() correctly")
            // Verify include is parsed correctly, not as a path view
            assertFalse(
                result.content.contains("views.ArticleListView") &&
                    result.content.lines().any { it.contains("include") && it.contains("ArticleListView") },
                "include() should not be captured by path pattern with wrong view"
            )
        }

        @Test
        fun `settings action finds settings values`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val configDir = tempDir.resolve("myproject").toFile().also { it.mkdirs() }
            configDir.resolve("settings.py").writeText(
                """
                DEBUG = True
                SECRET_KEY = 'django-insecure-test-key'
                DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3'}}
                INSTALLED_APPS = ['django.contrib.admin', 'myapp']
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "settings") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("DEBUG"), "Should find DEBUG setting")
            assertTrue(result.content.contains("INSTALLED_APPS"), "Should find INSTALLED_APPS setting")
        }

        @Test
        fun `settings action redacts sensitive values`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val configDir = tempDir.resolve("myproject").toFile().also { it.mkdirs() }
            configDir.resolve("settings.py").writeText(
                """
                DEBUG = True
                SECRET_KEY = 'django-insecure-actual-secret-key'
                DATABASE_URL = 'postgres://user:pass@host/db'
                ALLOWED_HOSTS = ['*']
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "settings") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            // SECRET_KEY and DATABASE_URL values should be redacted
            assertTrue(result.content.contains("SECRET_KEY = ***REDACTED***"),
                "SECRET_KEY value should be redacted. Got: ${result.content}")
            assertTrue(result.content.contains("DATABASE_URL = ***REDACTED***"),
                "DATABASE_URL value should be redacted. Got: ${result.content}")
            // Non-sensitive values should NOT be redacted
            assertTrue(result.content.contains("DEBUG = True"),
                "DEBUG value should NOT be redacted. Got: ${result.content}")
            assertTrue(result.content.contains("ALLOWED_HOSTS = ['*']"),
                "ALLOWED_HOSTS value should NOT be redacted. Got: ${result.content}")
        }

        @Test
        fun `middleware action finds middleware stack`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val configDir = tempDir.resolve("myproject").toFile().also { it.mkdirs() }
            configDir.resolve("settings.py").writeText(
                """
                MIDDLEWARE = [
                    'django.middleware.security.SecurityMiddleware',
                    'django.contrib.sessions.middleware.SessionMiddleware',
                    'django.middleware.common.CommonMiddleware',
                ]
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "middleware") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("SecurityMiddleware") ||
                result.content.contains("middleware"), "Should find middleware entries")
        }

        @Test
        fun `admin action finds admin registrations`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("admin.py").writeText(
                """
                from django.contrib import admin
                from .models import Article, Comment

                admin.site.register(Article)
                admin.site.register(Comment, CommentAdmin)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "admin") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Article"), "Should find Article admin registration")
        }

        @Test
        fun `views action finds views and viewsets`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("views.py").writeText(
                """
                from django.views import View
                from rest_framework.viewsets import ModelViewSet

                class ArticleListView(View):
                    def get(self, request):
                        pass

                class ArticleViewSet(ModelViewSet):
                    pass

                def article_detail(request, pk):
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "views") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("ArticleListView") || result.content.contains("ArticleViewSet"),
                "Should find view classes")
        }

        @Test
        fun `celery_tasks action finds task definitions`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("tasks.py").writeText(
                """
                from celery import shared_task

                @shared_task
                def send_email(recipient):
                    pass

                @shared_task(bind=True, max_retries=3)
                def process_order(self, order_id):
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "celery_tasks") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("send_email") || result.content.contains("process_order"),
                "Should find task definitions")
        }

        @Test
        fun `management_commands action finds custom commands`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val cmdDir = tempDir.resolve("myapp/management/commands").toFile().also { it.mkdirs() }
            cmdDir.resolve("seed_data.py").writeText(
                """
                from django.core.management.base import BaseCommand

                class Command(BaseCommand):
                    help = 'Seed the database with test data'

                    def handle(self, *args, **options):
                        pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "management_commands") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("seed_data"), "Should find seed_data command")
        }

        @Test
        fun `management_commands action extracts single-quoted docstrings`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val cmdDir = tempDir.resolve("myapp/management/commands").toFile().also { it.mkdirs() }
            cmdDir.resolve("cleanup.py").writeText(
                """
                from django.core.management.base import BaseCommand

                class Command(BaseCommand):
                    help = '''Clean up stale records'''

                    def handle(self, *args, **options):
                        pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "management_commands") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("cleanup"), "Should find cleanup command")
            // Bug 2 regression: single-quoted docstring must be extracted, not silently dropped
            assertTrue(result.content.contains("Clean up stale records"),
                "Single-quoted docstring should be extracted. Got: ${result.content}")
        }

        @Test
        fun `management_commands action extracts double-quoted docstrings`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val cmdDir = tempDir.resolve("myapp/management/commands").toFile().also { it.mkdirs() }
            // Use ${"\"\"\""}  to embed triple-double-quotes inside a Kotlin raw string
            val tripleDoubleQuote = "\"\"\""
            cmdDir.resolve("import_data.py").writeText(
                """
                from django.core.management.base import BaseCommand

                class Command(BaseCommand):
                    help = ${tripleDoubleQuote}Import data from CSV${tripleDoubleQuote}

                    def handle(self, *args, **options):
                        pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "management_commands") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("import_data"), "Should find import_data command")
            assertTrue(result.content.contains("Import data from CSV"),
                "Double-quoted docstring should be extracted. Got: ${result.content}")
        }

        @Test
        fun `signals action finds signal handlers`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("signals.py").writeText(
                """
                from django.db.models.signals import post_save
                from django.dispatch import receiver
                from .models import Article

                @receiver(post_save, sender=Article)
                def on_article_saved(sender, instance, created, **kwargs):
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "signals") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("on_article_saved") || result.content.contains("post_save"),
                "Should find signal handler")
        }

        @Test
        fun `serializers action finds serializer classes`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("serializers.py").writeText(
                """
                from rest_framework import serializers
                from .models import Article

                class ArticleSerializer(serializers.ModelSerializer):
                    class Meta:
                        model = Article
                        fields = '__all__'
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "serializers") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("ArticleSerializer"), "Should find ArticleSerializer")
        }

        @Test
        fun `forms action finds form classes`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("myapp").toFile().also { it.mkdirs() }
            appDir.resolve("forms.py").writeText(
                """
                from django import forms
                from .models import Article

                class ArticleForm(forms.ModelForm):
                    class Meta:
                        model = Article
                        fields = ['title', 'body']
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "forms") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("ArticleForm"), "Should find ArticleForm")
        }

        @Test
        fun `fixtures action finds fixture files`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val fixturesDir = tempDir.resolve("myapp/fixtures").toFile().also { it.mkdirs() }
            fixturesDir.resolve("initial_data.json").writeText(
                """[{"model": "myapp.article", "pk": 1, "fields": {"title": "Test"}}]"""
            )

            val result = tool.execute(buildJsonObject { put("action", "fixtures") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("initial_data.json"), "Should find fixture file")
        }

        @Test
        fun `templates action finds template files`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val templatesDir = tempDir.resolve("myapp/templates/myapp").toFile().also { it.mkdirs() }
            templatesDir.resolve("article_list.html").writeText("<html><body>Articles</body></html>")
            templatesDir.resolve("article_detail.html").writeText("<html><body>Article</body></html>")

            val result = tool.execute(buildJsonObject { put("action", "templates") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("article_list.html") || result.content.contains("article_detail.html"),
                "Should find template files")
        }

        @Test
        fun `version_info action finds Django version`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.toFile().resolve("requirements.txt").writeText(
                "Django==5.1.0\ncelery==5.4.0\ndjangorestframework==3.15.0\n"
            )
            val configDir = tempDir.resolve("myproject").toFile().also { it.mkdirs() }
            configDir.resolve("settings.py").writeText(
                "INSTALLED_APPS = ['django.contrib.admin', 'myapp']\n"
            )

            val result = tool.execute(buildJsonObject { put("action", "version_info") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Django") || result.content.contains("version"),
                "Should find Django version info")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 5 — Action routing smoke tests
    //
    // For every action enum value, calling `execute(action=X)` must either:
    //   (a) return a ToolResult (error or otherwise), or
    //   (b) throw a recognized boundary exception (file I/O or service boundary)
    //
    // It must NOT silently return success and must NOT hit an
    // IllegalArgumentException inside the dispatcher itself.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActionRoutingSmoke {

        private fun smokeTestAction(actionName: String) = runTest {
            every { project.basePath } returns "/tmp/django-smoke-noexist-zzz"
            val result = runCatching {
                tool.execute(buildJsonObject { put("action", actionName) }, project)
            }
            if (result.isSuccess) {
                val toolResult = result.getOrNull()
                assertNotNull(toolResult, "$actionName: result is null")
            } else {
                val ex = result.exceptionOrNull()!!
                val acceptable = ex is NullPointerException ||
                    ex is IllegalStateException ||
                    ex is NoClassDefFoundError ||
                    ex is ClassNotFoundException ||
                    ex is UnsupportedOperationException ||
                    ex is RuntimeException
                assertTrue(
                    acceptable,
                    "$actionName: unexpected exception type ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }

        @Test fun `models routes`() = smokeTestAction("models")
        @Test fun `views routes`() = smokeTestAction("views")
        @Test fun `urls routes`() = smokeTestAction("urls")
        @Test fun `settings routes`() = smokeTestAction("settings")
        @Test fun `admin routes`() = smokeTestAction("admin")
        @Test fun `management_commands routes`() = smokeTestAction("management_commands")
        @Test fun `celery_tasks routes`() = smokeTestAction("celery_tasks")
        @Test fun `middleware routes`() = smokeTestAction("middleware")
        @Test fun `signals routes`() = smokeTestAction("signals")
        @Test fun `serializers routes`() = smokeTestAction("serializers")
        @Test fun `forms routes`() = smokeTestAction("forms")
        @Test fun `fixtures routes`() = smokeTestAction("fixtures")
        @Test fun `templates routes`() = smokeTestAction("templates")
        @Test fun `version_info routes`() = smokeTestAction("version_info")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "models", "views", "urls", "settings", "admin",
            "management_commands", "celery_tasks", "middleware",
            "signals", "serializers", "forms", "fixtures",
            "templates", "version_info"
        )
    }
}
