package com.workflow.orchestrator.core.model

import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CiModelsTest {

    @Test fun `PlanData maps to PipelineData with project fields renamed to group`() {
        val plan = PlanData(
            key = "MY-PROJ-AUTOTESTS",
            name = "Auto Tests",
            shortName = "Auto",
            projectKey = "MY-PROJ",
            projectName = "My Project",
            enabled = false,
        )

        val pipeline = plan.toPipelineData()

        assertEquals(
            PipelineData(
                key = "MY-PROJ-AUTOTESTS",
                name = "Auto Tests",
                shortName = "Auto",
                groupKey = "MY-PROJ",
                groupName = "My Project",
                enabled = false,
            ),
            pipeline,
        )
    }

    @Test fun `ProjectData maps to CiGroupData one to one`() {
        val project = ProjectData(key = "MY-PROJ", name = "My Project", description = "desc")

        assertEquals(
            CiGroupData(key = "MY-PROJ", name = "My Project", description = "desc"),
            project.toCiGroupData(),
        )
    }
}
