package com.workflow.orchestrator.core.services

import okhttp3.Headers

/**
 * Pure HTTP response guards shared across every API client.
 *
 * Two helpers, both stateless and side-effect-free:
 *  - [isSuccess] — strict 200..299 success check. Replaces ad-hoc `200..399` ranges
 *    that quietly let auth-redirect responses (302 → /login) masquerade as success.
 *  - [looksLikeAuthRedirect] — pattern-matches a `text/html` response, which on
 *    Atlassian-stack servers (Bamboo, Bitbucket, Jira) means our PAT/cookie
 *    expired and the server silently swapped our JSON request for the login page.
 *    Callers should map this to [com.workflow.orchestrator.core.model.ErrorType.AUTH_REDIRECT]
 *    so the UI surfaces "your session expired, re-authenticate in Settings"
 *    instead of attempting to parse HTML as JSON.
 *
 * These helpers are deliberately top-level functions (not member methods) so any
 * caller — `:core`, `:jira`, `:bamboo`, `:pullrequest` — can use them without
 * pulling in a dependency on a specific client class.
 *
 * Foundation for the 2026-05-07 write-ops audit (PR 1). Consumers are introduced
 * by PRs 2 (Bamboo `queueBuild`), 3 (Bitbucket PR mutations), and 7 (Bamboo
 * `rerunFailedJobs`).
 */

/**
 * Returns true iff the HTTP status code is a real success (2xx).
 *
 * Use this in preference to `code in 200..399`. The 3xx range looks like success
 * to a redirect-following client but, in the Atlassian stack, a 302 to
 * `/login.jsp?permissionViolation=true` arrives at the OkHttp layer as a 2xx
 * response with `text/html` body — not a 3xx. Any *real* 3xx the client sees
 * here is therefore not a redirect that was followed; it's an unhandled redirect
 * (e.g., the request was made with `followRedirects=false`) and we should treat
 * it as an error.
 */
fun isSuccess(code: Int): Boolean = code in 200..299

/**
 * Returns true if the response Content-Type indicates HTML — strong signal that
 * the request hit a login redirect rather than the JSON API we expected.
 *
 * The check is permissive on case and on charset suffixes (e.g.,
 * `text/html;charset=UTF-8` matches). A blank or missing Content-Type returns
 * false: some endpoints (notably Jira `204 No Content`) return no body, no
 * Content-Type, and that's a normal success path.
 *
 * @param headers the response headers (from `okhttp3.Response.headers` or any
 *   compatible source). Using OkHttp's [Headers] type keeps the helper free of
 *   per-call allocations.
 */
fun looksLikeAuthRedirect(headers: Headers): Boolean {
    val contentType = headers["Content-Type"].orEmpty()
    if (contentType.isBlank()) return false
    return contentType.contains("text/html", ignoreCase = true)
}
