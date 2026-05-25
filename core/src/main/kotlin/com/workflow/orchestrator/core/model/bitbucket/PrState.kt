package com.workflow.orchestrator.core.model.bitbucket

/**
 * Canonical Bitbucket pull-request state values.
 *
 * The Bitbucket REST API exposes the PR `state` field as one of these three
 * uppercase strings. Use these constants instead of inline string literals when
 * setting the list-state filter or comparing a PR's state.
 *
 * Note: the server may return mixed/lowercase casing in some payloads, so any
 * comparison against a PR's actual `state` should remain case-insensitive
 * (`equals(PrState.OPEN, ignoreCase = true)`).
 */
object PrState {
    const val OPEN = "OPEN"
    const val MERGED = "MERGED"
    const val DECLINED = "DECLINED"
}
