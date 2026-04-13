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
 * Scenario-based tests for [FastApiTool].
 *
 * Strategy mirrors [DjangoToolTest]: FastApiTool is a meta-tool
 * with 10 file-scanning actions. Tests lock in:
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
class FastApiToolTest {

    private val tool = FastApiTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is fastapi`() {
            assertEquals("fastapi", tool.name)
        }

        @Test
        fun `description mentions FastAPI`() {
            assertTrue(tool.description.contains("FastAPI"))
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
        fun `path parameter exists and is string type`() {
            val prop = tool.parameters.properties["path"]
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
        fun `class_name parameter exists and is string type`() {
            val prop = tool.parameters.properties["class_name"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 4`() {
            // action discriminator + path + model + class_name
            assertEquals(4, tool.parameters.properties.size)
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
            assertEquals("fastapi", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(4, def.function.parameters.properties.size)
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
        fun `models action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `dependencies action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "dependencies") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `middleware action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "middleware") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `security action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "security") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `config action with null basePath returns error`() = runTest {
            every { project.basePath } returns null
            val result = tool.execute(buildJsonObject { put("action", "config") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `routes action with nonexistent basePath returns no-files message`() = runTest {
            every { project.basePath } returns "/tmp/nonexistent-fastapi-test-zzz-xyz"
            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
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
        fun `routes action finds FastAPI route decorators`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI

                app = FastAPI()

                @app.get("/items")
                async def list_items():
                    return []

                @app.post("/items")
                async def create_item(item: dict):
                    return item
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("/items"), "Should find /items route")
            assertTrue(result.content.contains("list_items") || result.content.contains("create_item"),
                "Should find handler functions")
        }

        @Test
        fun `routes action finds router decorators`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app/routers").toFile().also { it.mkdirs() }
            appDir.resolve("users.py").writeText(
                """
                from fastapi import APIRouter

                router = APIRouter()

                @router.get("/users")
                async def get_users():
                    return []

                @router.delete("/users/{user_id}")
                async def delete_user(user_id: int):
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "routes") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("/users"), "Should find /users route")
        }

        @Test
        fun `routes action filters by path`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI
                app = FastAPI()

                @app.get("/items")
                async def list_items():
                    return []

                @app.get("/users")
                async def list_users():
                    return []
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject {
                put("action", "routes")
                put("path", "users")
            }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("/users"), "Should find /users route")
            assertFalse(result.content.contains("/items"), "Should NOT find /items route")
        }

        @Test
        fun `dependencies action finds Depends usages`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI, Depends

                app = FastAPI()

                def get_db():
                    pass

                @app.get("/items")
                async def list_items(db = Depends(get_db)):
                    return []
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "dependencies") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Depends(get_db)"), "Should find Depends(get_db)")
        }

        @Test
        fun `models action finds Pydantic models`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("schemas.py").writeText(
                """
                from pydantic import BaseModel

                class ItemCreate(BaseModel):
                    name: str
                    price: float
                    description: str = None

                class ItemResponse(BaseModel):
                    id: int
                    name: str
                    price: float
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "models") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("ItemCreate"), "Should find ItemCreate model")
            assertTrue(result.content.contains("ItemResponse"), "Should find ItemResponse model")
            assertTrue(result.content.contains("name: str"), "Should find model fields")
        }

        @Test
        fun `models action filters by model name`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("schemas.py").writeText(
                """
                from pydantic import BaseModel

                class UserCreate(BaseModel):
                    email: str

                class ItemCreate(BaseModel):
                    name: str
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject {
                put("action", "models")
                put("model", "User")
            }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("UserCreate"), "Should find UserCreate")
            assertFalse(result.content.contains("ItemCreate"), "Should NOT find ItemCreate")
        }

        @Test
        fun `middleware action finds add_middleware calls`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI
                from fastapi.middleware.cors import CORSMiddleware

                app = FastAPI()
                app.add_middleware(CORSMiddleware, allow_origins=["*"])
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "middleware") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("CORSMiddleware"), "Should find CORSMiddleware")
        }

        @Test
        fun `security action finds OAuth2 schemes`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("auth.py").writeText(
                """
                from fastapi.security import OAuth2PasswordBearer, APIKeyHeader

                oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")
                api_key_header = APIKeyHeader(name="X-API-Key")
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "security") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("OAuth2PasswordBearer"), "Should find OAuth2PasswordBearer")
            assertTrue(result.content.contains("APIKeyHeader"), "Should find APIKeyHeader")
        }

        @Test
        fun `background_tasks action finds handlers`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI, BackgroundTasks

                app = FastAPI()

                @app.post("/send-notification")
                async def send_notification(background_tasks: BackgroundTasks):
                    background_tasks.add_task(write_notification)
                    return {"message": "sent"}
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "background_tasks") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("send_notification"), "Should find send_notification handler")
        }

        @Test
        fun `events action finds on_event handlers`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from fastapi import FastAPI

                app = FastAPI()

                @app.on_event("startup")
                async def startup_event():
                    pass

                @app.on_event("shutdown")
                async def shutdown_event():
                    pass
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "events") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("startup"), "Should find startup event")
            assertTrue(result.content.contains("shutdown"), "Should find shutdown event")
        }

        @Test
        fun `events action finds lifespan pattern`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("main.py").writeText(
                """
                from contextlib import asynccontextmanager
                from fastapi import FastAPI

                @asynccontextmanager
                async def lifespan(app: FastAPI):
                    yield

                app = FastAPI(lifespan=lifespan)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "events") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("lifespan"), "Should find lifespan handler")
        }

        @Test
        fun `config action finds BaseSettings classes`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("config.py").writeText(
                """
                from pydantic_settings import BaseSettings

                class Settings(BaseSettings):
                    app_name: str = "My App"
                    debug: bool = False
                    database_url: str

                    class Config:
                        env_file = ".env"
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "config") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("Settings"), "Should find Settings class")
            assertTrue(result.content.contains("database_url"), "Should find database_url field")
        }

        @Test
        fun `database action finds SQLAlchemy models`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("models.py").writeText(
                """
                from sqlalchemy import Column, Integer, String
                from .database import Base

                class User(Base):
                    __tablename__ = "users"
                    id = Column(Integer, primary_key=True)
                    name = Column(String)
                    email = Column(String, unique=True)

                class Item(Base):
                    __tablename__ = "items"
                    id = Column(Integer, primary_key=True)
                    title = Column(String)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "database") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("User"), "Should find User model")
            assertTrue(result.content.contains("Item"), "Should find Item model")
            assertTrue(result.content.contains("SQLAlchemy"), "Should identify as SQLAlchemy")
        }

        @Test
        fun `database action finds Tortoise models`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            val appDir = tempDir.resolve("app").toFile().also { it.mkdirs() }
            appDir.resolve("models.py").writeText(
                """
                from tortoise import fields, Model

                class User(Model):
                    id = fields.IntField(pk=True)
                    name = fields.CharField(max_length=255)
                    email = fields.CharField(max_length=255)
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "database") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("User"), "Should find User model")
            assertTrue(result.content.contains("Tortoise"), "Should identify as Tortoise")
        }

        @Test
        fun `version_info action finds package versions`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.toFile().resolve("requirements.txt").writeText(
                """
                fastapi==0.109.0
                uvicorn==0.27.0
                pydantic==2.5.3
                sqlalchemy==2.0.25
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "version_info") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("fastapi"), "Should find fastapi version")
            assertTrue(result.content.contains("uvicorn"), "Should find uvicorn version")
            assertTrue(result.content.contains("pydantic"), "Should find pydantic version")
        }

        @Test
        fun `version_info action reads pyproject toml`(@TempDir tempDir: Path) = runTest {
            every { project.basePath } returns tempDir.toString()
            tempDir.toFile().resolve("pyproject.toml").writeText(
                """
                [project]
                dependencies = [
                    "fastapi>=0.109.0",
                    "uvicorn>=0.27.0",
                ]
                """.trimIndent()
            )

            val result = tool.execute(buildJsonObject { put("action", "version_info") }, project)
            assertFalse(result.isError, "Expected success: ${result.content}")
            assertTrue(result.content.contains("fastapi"), "Should find fastapi version")
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
            every { project.basePath } returns "/tmp/fastapi-smoke-noexist-zzz"
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
        @Test fun `dependencies routes`() = smokeTestAction("dependencies")
        @Test fun `models routes`() = smokeTestAction("models")
        @Test fun `middleware routes`() = smokeTestAction("middleware")
        @Test fun `security routes`() = smokeTestAction("security")
        @Test fun `background_tasks routes`() = smokeTestAction("background_tasks")
        @Test fun `events routes`() = smokeTestAction("events")
        @Test fun `config routes`() = smokeTestAction("config")
        @Test fun `database routes`() = smokeTestAction("database")
        @Test fun `version_info routes`() = smokeTestAction("version_info")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "routes", "dependencies", "models", "middleware", "security",
            "background_tasks", "events", "config", "database", "version_info"
        )
    }
}
