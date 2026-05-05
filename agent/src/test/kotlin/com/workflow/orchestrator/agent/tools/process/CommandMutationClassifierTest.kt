package com.workflow.orchestrator.agent.tools.process

import com.workflow.orchestrator.agent.tools.process.CommandMutationClassifier.Mutation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandMutationClassifierTest {

    @Test
    fun `git stash pop is GitMutator`() {
        assertEquals(Mutation.GitMutator, CommandMutationClassifier.classify("git stash pop"))
    }

    @Test
    fun `git checkout file is GitMutator`() {
        assertEquals(Mutation.GitMutator, CommandMutationClassifier.classify("git checkout -- src/Foo.kt"))
    }

    @Test
    fun `git switch is GitMutator`() {
        assertEquals(Mutation.GitMutator, CommandMutationClassifier.classify("git switch main"))
    }

    @Test
    fun `git status is not classified`() {
        // Read-only git ops should fall through to Generic
        assertEquals(Mutation.Generic, CommandMutationClassifier.classify("git status"))
    }

    @Test
    fun `git log is not classified`() {
        assertEquals(Mutation.Generic, CommandMutationClassifier.classify("git log --oneline -n 5"))
    }

    @Test
    fun `mvn clean install is BuildClean (clean wins)`() {
        assertEquals(Mutation.BuildClean, CommandMutationClassifier.classify("mvn clean install"))
    }

    @Test
    fun `gradle clean is BuildClean`() {
        assertEquals(Mutation.BuildClean, CommandMutationClassifier.classify("./gradlew clean"))
    }

    @Test
    fun `npm ci is BuildClean`() {
        assertEquals(Mutation.BuildClean, CommandMutationClassifier.classify("npm ci"))
    }

    @Test
    fun `npm install is PackageInstall`() {
        assertEquals(Mutation.PackageInstall, CommandMutationClassifier.classify("npm install"))
    }

    @Test
    fun `yarn add is PackageInstall`() {
        assertEquals(Mutation.PackageInstall, CommandMutationClassifier.classify("yarn add lodash"))
    }

    @Test
    fun `mvn package without clean is BuildIncremental`() {
        assertEquals(Mutation.BuildIncremental, CommandMutationClassifier.classify("mvn package -DskipTests"))
    }

    @Test
    fun `gradle test is BuildIncremental`() {
        assertEquals(Mutation.BuildIncremental, CommandMutationClassifier.classify("gradle test"))
    }

    @Test
    fun `sed -i is FsMutator`() {
        assertEquals(Mutation.FsMutator, CommandMutationClassifier.classify("sed -i 's/foo/bar/g' file.txt"))
    }

    @Test
    fun `mv is FsMutator`() {
        assertEquals(Mutation.FsMutator, CommandMutationClassifier.classify("mv src/old.kt src/new.kt"))
    }

    @Test
    fun `rm is FsMutator`() {
        assertEquals(Mutation.FsMutator, CommandMutationClassifier.classify("rm -rf out/"))
    }

    @Test
    fun `chmod is FsMutator`() {
        assertEquals(Mutation.FsMutator, CommandMutationClassifier.classify("chmod +x script.sh"))
    }

    @Test
    fun `echo is Generic`() {
        assertEquals(Mutation.Generic, CommandMutationClassifier.classify("echo hello"))
    }

    @Test
    fun `ls is Generic`() {
        assertEquals(Mutation.Generic, CommandMutationClassifier.classify("ls -la"))
    }
}
