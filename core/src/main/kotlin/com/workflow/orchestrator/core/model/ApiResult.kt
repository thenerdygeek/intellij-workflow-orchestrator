package com.workflow.orchestrator.core.model

enum class ErrorType {
    AUTH_FAILED,
    /**
     * The server replied with HTML (typically a login redirect) where JSON was expected.
     * Distinct from [AUTH_FAILED] (HTTP 401) because the original request often returns
     * 200 OK with a `text/html` body when the auth cookie/PAT has expired and the server
     * silently redirects to the login page. Callers should surface this as
     * "your session expired, re-authenticate in Settings" rather than the generic 401 copy.
     *
     * Detected via [com.workflow.orchestrator.core.services.looksLikeAuthRedirect].
     */
    AUTH_REDIRECT,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    TIMEOUT,
    VALIDATION_ERROR,
    PARSE_ERROR,
    CONTEXT_LENGTH_EXCEEDED,
    /**
     * Optimistic-locking conflict: the resource changed between the GET-modify-PUT
     * cycle and the server rejected the update with 409. Used by Bitbucket PR
     * mutations after [com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient.modifyPullRequest]
     * exhausts its single retry. Callers should surface
     * "the PR was updated by someone else — refresh and try again".
     */
    STALE_VERSION
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
