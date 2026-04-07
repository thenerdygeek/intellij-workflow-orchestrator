package com.workflow.orchestrator.core.model

enum class ErrorType {
    AUTH_FAILED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    TIMEOUT,
    VALIDATION_ERROR,
    PARSE_ERROR,
    CONTEXT_LENGTH_EXCEEDED
}

sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data class Error(
        val type: ErrorType,
        val message: String,
        val cause: Throwable? = null,
        /**
         * Suggested retry delay in milliseconds, parsed from rate-limit response headers.
         * Ported from Cline's retry.ts: reads retry-after, x-ratelimit-reset, ratelimit-reset.
         * Null if no retry hint was available.
         */
        val retryAfterMs: Long? = null
    ) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (Error) -> R
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(this)
    }
}
