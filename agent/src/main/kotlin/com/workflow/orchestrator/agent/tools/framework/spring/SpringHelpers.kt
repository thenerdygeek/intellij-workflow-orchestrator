package com.workflow.orchestrator.agent.tools.framework.spring

/**
 * Shared constants used by multiple Spring action handlers.
 *
 * Note: `detectStereotype` is shared between context and bean_graph actions
 * and is declared `internal` in [SpringContextAction.kt] for that purpose.
 */

internal val SPRING_ENDPOINT_MAPPING_ANNOTATIONS = mapOf(
    "org.springframework.web.bind.annotation.RequestMapping" to null,
    "org.springframework.web.bind.annotation.GetMapping" to "GET",
    "org.springframework.web.bind.annotation.PostMapping" to "POST",
    "org.springframework.web.bind.annotation.PutMapping" to "PUT",
    "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
    "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
)

internal val SPRING_CONTROLLER_ANNOTATIONS = listOf(
    "org.springframework.web.bind.annotation.RestController",
    "org.springframework.stereotype.Controller"
)
