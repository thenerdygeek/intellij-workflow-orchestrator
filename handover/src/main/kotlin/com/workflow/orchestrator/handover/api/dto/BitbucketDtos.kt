package com.workflow.orchestrator.handover.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class BitbucketPrResponse(
    val id: Int,
    val title: String,
    val state: String,
    val links: BitbucketLinks
)

@Serializable
data class BitbucketLinks(
    val self: List<BitbucketLink>
)

@Serializable
data class BitbucketLink(
    val href: String
)

@Serializable
data class BitbucketPrListResponse(
    val size: Int,
    val values: List<BitbucketPrResponse>
)

/** Request body for PR creation — not deserialized, only serialized. */
@Serializable
data class BitbucketPrRequest(
    val title: String,
    val description: String,
    val fromRef: BitbucketRef,
    val toRef: BitbucketRef
)

@Serializable
data class BitbucketRef(
    val id: String
)
