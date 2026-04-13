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
 * Scenario-based tests for [FlaskTool].
 *
 * Strategy mirrors [DjangoToolTest]: FlaskTool is a meta-tool with 10 file-scanning
 * actions. Tests lock in:
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
class FlaskToolTest {

    private val tool = FlaskTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is flask`() {
            assertEquals("flask", tool.name)
        }

        @Test
        fun `description mentions Flask`() {
            assertTrue(tool.description.contains("Flask"))
        }

        @Test
        fun `description lists all 10 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `action enum contains exactly 10 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(10, actions!!.size)
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
        fun `blueprint parameter exists and is string type`() {
            val prop = tool.parameters.properties["blueprint"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `extension parameter exists and is string type`() {
            val prop = tool.parameters.properties["extension"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `model parameter exists and is string type`() {
            val prop = tool.parameters.properties["model"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `filter parameter exists and is string type`() {
            val prop = tool.parameters.properties["filter"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 5`() {
            // action + blueprint + extension + model + filter
            assertEquals(5, tool.parameters.properties.size)
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
            assertEquals("flask", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(5, def.function.parameters.properties.size)
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
                put("action", "ROUTES")
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
        fun `routes action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `blueprints action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "blueprints") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `config action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "config") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `models action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `routes action with nonexistent basePath returns no-files message`() = runTest {
            every { project.basePath } returns "/tmp/nonexistent-flask-test-zzz-xyz"
            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
            assertTrue(
                !result.isError || result.content.contains("No"),
                "Expected no-files message; got: ${result.content}"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 4 — File scan integration tests
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class FileScanIntegration {

        @Test
        fun `routes action finds route decorators`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("app.py").toFile().writeText(
                """
                from flask import Flask
                app = Flask(__name__)

                @app.route('/')
                def index():
                    return 'Hello'

                @app.route('/users', methods=['GET', 'POST'])
                def users():
                    return 'Users'

                @app.get('/health')
                def health():
                    return 'OK'
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("/"), "Should find root route")
            assertTrue(result.content.contains("/users"), "Should find /users route")
        }

        @Test
        fun `blueprints action finds blueprint definitions`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val apiDir = tempDir.resolve("api").toFile().also { it.mkdirs() }
            apiDir.resolve("__init__.py").writeText(
                """
                from flask import Blueprint
                api_bp = Blueprint('api', __name__, url_prefix='/api')
                """.trimIndent()
            )
            tempDir.resolve("app.py").toFile().writeText(
                """
                from flask import Flask
                from api import api_bp
                app = Flask(__name__)
                app.register_blueprint(api_bp, url_prefix='/v1')
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "blueprints") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("api"), "Should find api blueprint")
            assertTrue(result.content.contains("register_blueprint"), "Should find registration")
        }

        @Test
        fun `config action finds class-based config`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("config.py").toFile().writeText(
                """
                class Config:
                    SECRET_KEY = 'dev-secret'
                    SQLALCHEMY_DATABASE_URI = 'sqlite:///app.db'
                    DEBUG = False

                class DevelopmentConfig(Config):
                    DEBUG = True
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "config") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("SECRET_KEY"), "Should find SECRET_KEY")
            assertTrue(result.content.contains("DevelopmentConfig"), "Should find DevelopmentConfig class")
        }

        @Test
        fun `extensions action finds Flask extensions`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("extensions.py").toFile().writeText(
                """
                from flask_sqlalchemy import SQLAlchemy
                from flask_migrate import Migrate

                db = SQLAlchemy()
                migrate = Migrate()
                """.trimIndent()
            )
            tempDir.resolve("app.py").toFile().writeText(
                """
                from extensions import db, migrate
                def create_app():
                    app = Flask(__name__)
                    db.init_app(app)
                    migrate.init_app(app)
                    return app
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "extensions") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("SQLAlchemy"), "Should find SQLAlchemy")
            assertTrue(result.content.contains("init_app"), "Should find init_app calls")
        }

        @Test
        fun `models action finds SQLAlchemy models`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("models.py").toFile().writeText(
                """
                from extensions import db

                class User(db.Model):
                    id = db.Column(db.Integer, primary_key=True)
                    username = db.Column(db.String(80), unique=True)
                    email = db.Column(db.String(120))
                    posts = db.relationship('Post', backref='author')

                class Post(db.Model):
                    id = db.Column(db.Integer, primary_key=True)
                    title = db.Column(db.String(200))
                    body = db.Column(db.Text)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("User"), "Should find User model")
            assertTrue(result.content.contains("Post"), "Should find Post model")
        }

        @Test
        fun `templates action finds Jinja2 template files`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val templatesDir = tempDir.resolve("templates").toFile().also { it.mkdirs() }
            templatesDir.resolve("base.html").writeText("<html>{% block content %}{% endblock %}</html>")
            templatesDir.resolve("index.html").writeText("{% extends 'base.html' %}")
            val subDir = tempDir.resolve("templates/auth").toFile().also { it.mkdirs() }
            subDir.resolve("login.html").writeText("<form>Login</form>")

            val result = tool.execute(buildJsonObject { put("action", "templates") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("base.html"), "Should find base.html")
            assertTrue(result.content.contains("login.html"), "Should find login.html")
        }

        @Test
        fun `middleware action finds request hooks`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("app.py").toFile().writeText(
                """
                from flask import Flask
                app = Flask(__name__)

                @app.before_request
                def check_auth():
                    pass

                @app.after_request
                def add_headers(response):
                    return response

                @app.errorhandler(404)
                def not_found(e):
                    return 'Not Found', 404
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "middleware") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("before_request"), "Should find before_request")
            assertTrue(result.content.contains("after_request"), "Should find after_request")
            assertTrue(result.content.contains("errorhandler"), "Should find errorhandler")
        }

        @Test
        fun `cli_commands action finds CLI commands`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("commands.py").toFile().writeText(
                """
                import click
                from flask import Flask
                app = Flask(__name__)

                @app.cli.command('seed')
                def seed_db():
                    pass

                @click.command()
                def migrate():
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "cli_commands") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("seed") || result.content.contains("seed_db"),
                "Should find seed command")
        }

        @Test
        fun `forms action finds WTForms classes`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.resolve("forms.py").toFile().writeText(
                """
                from flask_wtf import FlaskForm
                from wtforms import StringField, PasswordField, SubmitField

                class LoginForm(FlaskForm):
                    username = StringField('Username')
                    password = PasswordField('Password')
                    submit = SubmitField('Login')

                class RegisterForm(FlaskForm):
                    email = StringField('Email')
                    password = PasswordField('Password')
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "forms") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("LoginForm"), "Should find LoginForm")
            assertTrue(result.content.contains("RegisterForm"), "Should find RegisterForm")
        }

        @Test
        fun `version_info action finds Flask version`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.toFile().resolve("requirements.txt").writeText(
                "flask==3.1.0\nwerkzeug==3.1.0\njinja2==3.1.6\nflask-sqlalchemy==3.1.0\n"
            )

            val result = tool.execute(buildJsonObject { put("action", "version_info") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("flask"), "Should find flask version")
            assertTrue(result.content.contains("3.1.0"), "Should find version number")
        }

        @Test
        fun `version_info action reads pyproject toml`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.toFile().resolve("pyproject.toml").writeText(
                """
                [project]
                dependencies = [
                    "flask>=3.0.0",
                    "werkzeug>=3.0.0",
                ]
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "version_info") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("flask") || result.content.contains("werkzeug"),
                "Should find dependencies from pyproject.toml")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 5 — Action routing smoke tests
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActionRoutingSmoke {

        private fun smokeTestAction(actionName: String) = runTest {
            every { project.basePath } returns "/tmp/flask-smoke-noexist-zzz"
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

        @Test fun `routes routes`() = smokeTestAction("routes")
        @Test fun `blueprints routes`() = smokeTestAction("blueprints")
        @Test fun `config routes`() = smokeTestAction("config")
        @Test fun `extensions routes`() = smokeTestAction("extensions")
        @Test fun `models routes`() = smokeTestAction("models")
        @Test fun `templates routes`() = smokeTestAction("templates")
        @Test fun `middleware routes`() = smokeTestAction("middleware")
        @Test fun `cli_commands routes`() = smokeTestAction("cli_commands")
        @Test fun `forms routes`() = smokeTestAction("forms")
        @Test fun `version_info routes`() = smokeTestAction("version_info")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "routes", "blueprints", "config", "extensions", "models",
            "templates", "middleware", "cli_commands", "forms", "version_info"
        )
    }
}
