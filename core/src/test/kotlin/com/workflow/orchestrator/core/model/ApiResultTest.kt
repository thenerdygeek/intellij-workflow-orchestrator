package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiResultTest {

    @Test
    fun `Success maps value`() {
        val result: ApiResult<Int> = ApiResult.Success(42)
        val mapped = result.map { it * 2 }
        assertEquals(ApiResult.Success(84), mapped)
    }

    @Test
    fun `Error propagates through map`() {
        val result: ApiResult<Int> = ApiResult.Error(ErrorType.NETWORK_ERROR, "timeout")
        val mapped = result.map { it * 2 }
        assertTrue(mapped is ApiResult.Error)
        assertEquals("timeout", (mapped as ApiResult.Error).message)
    }

    @Test
    fun `fold returns success value or error handler`() {
        val success: ApiResult<String> = ApiResult.Success("hello")
        val error: ApiResult<String> = ApiResult.Error(ErrorType.AUTH_FAILED, "bad token")

        assertEquals("hello", success.fold(onSuccess = { it }, onError = { "error" }))
        assertEquals("error", error.fold(onSuccess = { it }, onError = { "error" }))
    }

    @Test
    fun `getOrNull returns value on success, null on error`() {
        assertEquals("data", ApiResult.Success("data").getOrNull())
        assertNull(ApiResult.Error(ErrorType.NOT_FOUND, "gone").getOrNull())
    }

    @Test
    fun `isSuccess and isError`() {
        assertTrue(ApiResult.Success("ok").isSuccess)
        assertFalse(ApiResult.Success("ok").isError)
        assertTrue(ApiResult.Error(ErrorType.TIMEOUT, "slow").isError)
        assertFalse(ApiResult.Error(ErrorType.TIMEOUT, "slow").isSuccess)
    }
}
