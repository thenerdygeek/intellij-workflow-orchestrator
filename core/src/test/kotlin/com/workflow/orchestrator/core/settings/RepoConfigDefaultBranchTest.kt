package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepoConfigDefaultBranchTest {
    @Test fun `a new RepoConfig defaults to the neutral target branch`() {
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, RepoConfig().defaultTargetBranch)
    }

    @Test fun `an explicit per-repo target branch is preserved across a round-trip`() {
        val repo = RepoConfig().apply { defaultTargetBranch = "develop" }
        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(repo), RepoConfig::class.java,
        )
        // Explicit values materialize in XML and survive — existing repos are unaffected by the
        // default flip, which is why per-repo needs no migration.
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
