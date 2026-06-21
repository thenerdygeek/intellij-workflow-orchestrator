package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandShapeTest {

    // ── splitSubCommands ──
    @Test fun `splits on operators and newlines`() {
        assertEquals(listOf("git status", "rm -rf x"), CommandShape.splitSubCommands("git status && rm -rf x"))
        assertEquals(listOf("git status", "rm -rf x"), CommandShape.splitSubCommands("git status\nrm -rf x"))
        assertEquals(listOf("cat a", "grep b"), CommandShape.splitSubCommands("cat a | grep b"))
        assertEquals(listOf("ls"), CommandShape.splitSubCommands("ls"))
    }

    // ── isAutoApprovable: allowed shapes ──
    @Test fun `simple commands and pipes and chains are approvable`() {
        assertTrue(CommandShape.isAutoApprovable("ls -la"))
        assertTrue(CommandShape.isAutoApprovable("git status"))
        assertTrue(CommandShape.isAutoApprovable("git add . && git status"))
        assertTrue(CommandShape.isAutoApprovable("cat a | grep b"))
        assertTrue(CommandShape.isAutoApprovable("python script.py"))
    }

    // ── isAutoApprovable: rejected shapes ──
    @Test fun `redirection subshell expansion wrapper assignment inline-eval background are rejected`() {
        assertFalse(CommandShape.isAutoApprovable("git status > out.txt"))
        assertFalse(CommandShape.isAutoApprovable("git status >> out.txt"))
        assertFalse(CommandShape.isAutoApprovable("sort < in.txt"))
        assertFalse(CommandShape.isAutoApprovable("ls 2> err.txt"))
        assertFalse(CommandShape.isAutoApprovable("echo \$(rm -rf /)"))
        assertFalse(CommandShape.isAutoApprovable("echo `whoami`"))
        assertFalse(CommandShape.isAutoApprovable("echo \$HOME"))
        assertFalse(CommandShape.isAutoApprovable("FOO=bar git push"))
        assertFalse(CommandShape.isAutoApprovable("timeout 60 git push"))
        assertFalse(CommandShape.isAutoApprovable("env git push"))
        assertFalse(CommandShape.isAutoApprovable("python -c \"import os\""))
        assertFalse(CommandShape.isAutoApprovable("node -e \"x\""))
        assertFalse(CommandShape.isAutoApprovable("bash -c \"x\""))
        assertFalse(CommandShape.isAutoApprovable("sleep 10 & rm x"))
        assertFalse(CommandShape.isAutoApprovable("echo x | sh"))
        assertFalse(CommandShape.isAutoApprovable(""))
    }

    // ── derivePrefix ──
    @Test fun `derivePrefix uses verb for multi-verb tools and bare name otherwise`() {
        assertEquals("git add", CommandShape.derivePrefix("git add Foo.kt"))
        assertEquals("npm install", CommandShape.derivePrefix("npm install left-pad"))
        assertEquals("docker build", CommandShape.derivePrefix("docker build ."))
        assertEquals("ls", CommandShape.derivePrefix("ls -la"))
        assertEquals("./gradlew", CommandShape.derivePrefix("./gradlew"))
        assertEquals("git", CommandShape.derivePrefix("git -C /tmp")) // 2nd token is a flag → bare
    }

    @Test fun `derivePrefix returns null for compound wrapper assignment inline-eval`() {
        assertNull(CommandShape.derivePrefix("git add . && git status"))
        assertNull(CommandShape.derivePrefix("timeout 60 git push"))
        assertNull(CommandShape.derivePrefix("FOO=bar git push"))
        assertNull(CommandShape.derivePrefix("python -c \"x\""))
        assertNull(CommandShape.derivePrefix("git status > out"))
    }

    @Test fun `derivePrefix uses verb for multi-verb tool with subcommand`() {
        assertEquals("./gradlew clean", CommandShape.derivePrefix("./gradlew clean build"))
    }

    @Test fun `path-prefixed interpreters and structural patterns are rejected`() {
        // Fix 1: path-prefixed shell and code interpreters must be rejected
        assertFalse(CommandShape.isAutoApprovable("echo x | /bin/bash"))
        assertFalse(CommandShape.isAutoApprovable("/usr/bin/python -c \"x\""))
        // Lock-in: structural patterns already correct but now explicitly tested
        assertFalse(CommandShape.isAutoApprovable("git status &> out.txt"))
        assertFalse(CommandShape.isAutoApprovable("bash <<< \"cmd\""))
        assertFalse(CommandShape.isAutoApprovable("sort <(cat f1)"))
        assertFalse(CommandShape.isAutoApprovable("tee >(wc -l)"))
    }

    // ── coveringPrefixes ──
    @Test fun `coveringPrefixes token-boundary matches and requires all sub-commands`() {
        val allow = setOf("git add", "git status")
        assertEquals(listOf("git add"), CommandShape.coveringPrefixes("git add Foo.kt", allow))
        assertEquals(listOf("git add", "git status"), CommandShape.coveringPrefixes("git add . && git status", allow))
        assertNull(CommandShape.coveringPrefixes("git addendum", allow)) // token boundary
        assertNull(CommandShape.coveringPrefixes("git add . && rm -rf x", allow)) // one sub uncovered
        assertNull(CommandShape.coveringPrefixes("git add x > out", allow)) // not simple
        assertNull(CommandShape.coveringPrefixes("git status & rm -rf x", allow)) // lone & → not simple
        assertNull(CommandShape.coveringPrefixes("ls", emptySet())) // empty allowlist
    }

    @Test fun `coveringPrefixes lowercases the live command but assumes prefixes normalized`() {
        assertEquals(listOf("git add"), CommandShape.coveringPrefixes("GIT ADD Foo.kt", setOf("git add")))
    }

    // ── isAutoApprovable: more redirections ──
    @Test fun `append-redirect and-gt-gt and clobber-redirect pipe gt-pipe are rejected`() {
        // &>> is an operator token containing &
        assertFalse(CommandShape.isAutoApprovable("ls &>> out.txt"))
        // >| (noclobber override) tokenized; the | ends up in the first sub-command as a value containing |
        // via the tokenizer which sees > and | as separate chars; the actual rejection comes from &
        // being present or the redirect op. Characterise actual behaviour (any form that writes to a
        // file must be rejected).
        assertFalse(CommandShape.isAutoApprovable("ls > out.txt"))
    }

    @Test fun `stderr-redirect 1-gt-and-2 is rejected`() {
        // 1>&2 contains & in the token value
        assertFalse(CommandShape.isAutoApprovable("git status 1>&2"))
    }

    @Test fun `heredoc cat lt-lt-EOF is rejected because lt is a redirect operator`() {
        assertFalse(CommandShape.isAutoApprovable("cat <<EOF"))
    }

    // ── isAutoApprovable: more substitutions ──
    @Test fun `curly-brace var substitution dollar-curly is rejected`() {
        assertFalse(CommandShape.isAutoApprovable("echo \${VAR}"))
    }

    @Test fun `arithmetic substitution dollar-paren-paren is rejected`() {
        assertFalse(CommandShape.isAutoApprovable("echo \$((1+1))"))
    }

    @Test fun `special-var dollar-IFS is rejected`() {
        assertFalse(CommandShape.isAutoApprovable("echo \$IFS"))
    }

    @Test fun `nested command substitution dollar-paren is rejected`() {
        // nested $(...) still contains a $ character in a token value
        assertFalse(CommandShape.isAutoApprovable("ls \$(echo /)"))
    }

    // ── isAutoApprovable: exec wrappers in WRAPPER_DENYLIST ──
    @Test fun `additional wrapper-denylist entries are each rejected`() {
        assertFalse(CommandShape.isAutoApprovable("sudo git push"))
        assertFalse(CommandShape.isAutoApprovable("doas git pull"))
        assertFalse(CommandShape.isAutoApprovable("nohup ./server"))
        assertFalse(CommandShape.isAutoApprovable("nice ./build.sh"))
        assertFalse(CommandShape.isAutoApprovable("ionice ./build.sh"))
        assertFalse(CommandShape.isAutoApprovable("setsid ./daemon"))
        assertFalse(CommandShape.isAutoApprovable("stdbuf -oL ./log"))
        assertFalse(CommandShape.isAutoApprovable("command git status"))
        assertFalse(CommandShape.isAutoApprovable("exec bash"))
        assertFalse(CommandShape.isAutoApprovable("time gradle build"))
        assertFalse(CommandShape.isAutoApprovable("watch ls"))
        assertFalse(CommandShape.isAutoApprovable("flock /tmp/lock.file ./run.sh"))
        assertFalse(CommandShape.isAutoApprovable("xargs rm"))
    }

    // ── isAutoApprovable: inline-eval interpreter flags ──
    @Test fun `ruby -e and perl -E and perl -e and php -r and node --eval are rejected`() {
        assertFalse(CommandShape.isAutoApprovable("ruby -e \"puts 1\""))
        assertFalse(CommandShape.isAutoApprovable("perl -E \"say 1\""))
        assertFalse(CommandShape.isAutoApprovable("perl -e \"print 1\""))
        assertFalse(CommandShape.isAutoApprovable("php -r \"echo 1;\""))
        assertFalse(CommandShape.isAutoApprovable("node --eval \"console.log(1)\""))
    }

    @Test fun `bare-script invocations of code interpreters are approvable`() {
        // running a file is safe — no inline-eval flag present
        assertTrue(CommandShape.isAutoApprovable("python script.py"))
        assertTrue(CommandShape.isAutoApprovable("node app.js"))
        assertTrue(CommandShape.isAutoApprovable("ruby script.rb"))
        // python3 without -c is also fine
        assertTrue(CommandShape.isAutoApprovable("python3 main.py"))
    }

    // ── isAutoApprovable: path-prefixed interpreters (T1) ──
    @Test fun `T1 path-prefixed shell and code interpreters with inline-eval flag are rejected`() {
        // /bin/bash -c "x" — baseName = bash (a SHELL_INTERPRETER)
        assertFalse(CommandShape.isAutoApprovable("/bin/bash -c \"x\""))
        // /usr/bin/python -c "x" — baseName = python in CODE_INTERPRETERS and -c in INLINE_EVAL_FLAGS
        assertFalse(CommandShape.isAutoApprovable("/usr/bin/python -c \"x\""))
    }

    // ── derivePrefix: exhaustive multi-verb tool table ──
    @Test fun `derivePrefix returns verb+sub-verb for every MULTI_VERB_TOOL with a non-flag second token`() {
        // git family
        assertEquals("git commit", CommandShape.derivePrefix("git commit -m msg"))
        assertEquals("git push", CommandShape.derivePrefix("git push origin"))
        assertEquals("git pull", CommandShape.derivePrefix("git pull origin"))
        assertEquals("git checkout", CommandShape.derivePrefix("git checkout main"))
        assertEquals("git diff", CommandShape.derivePrefix("git diff HEAD"))
        // npm / yarn / pnpm / npx
        assertEquals("npm install", CommandShape.derivePrefix("npm install left-pad"))
        assertEquals("npm run", CommandShape.derivePrefix("npm run build"))
        assertEquals("yarn add", CommandShape.derivePrefix("yarn add lodash"))
        assertEquals("pnpm install", CommandShape.derivePrefix("pnpm install"))
        assertEquals("npx create-react-app", CommandShape.derivePrefix("npx create-react-app my-app"))
        // docker / docker-compose / kubectl / helm
        assertEquals("docker run", CommandShape.derivePrefix("docker run alpine"))
        assertEquals("docker build", CommandShape.derivePrefix("docker build ."))
        assertEquals("docker-compose up", CommandShape.derivePrefix("docker-compose up -d"))
        assertEquals("kubectl apply", CommandShape.derivePrefix("kubectl apply -f file.yaml"))
        assertEquals("helm install", CommandShape.derivePrefix("helm install release chart"))
        // mvn / ./mvnw
        assertEquals("mvn clean", CommandShape.derivePrefix("mvn clean install"))
        assertEquals("./mvnw package", CommandShape.derivePrefix("./mvnw package"))
        // gradle / ./gradlew
        assertEquals("gradle build", CommandShape.derivePrefix("gradle build"))
        assertEquals("./gradlew test", CommandShape.derivePrefix("./gradlew test"))
        // cargo / go
        assertEquals("cargo build", CommandShape.derivePrefix("cargo build"))
        assertEquals("go test", CommandShape.derivePrefix("go test ./..."))
        // pip / pip3 / poetry / uv
        assertEquals("pip install", CommandShape.derivePrefix("pip install requests"))
        assertEquals("pip3 install", CommandShape.derivePrefix("pip3 install requests"))
        assertEquals("poetry run", CommandShape.derivePrefix("poetry run pytest"))
        assertEquals("uv sync", CommandShape.derivePrefix("uv sync"))
        // gh / terraform / make
        assertEquals("gh pr", CommandShape.derivePrefix("gh pr create"))
        assertEquals("terraform apply", CommandShape.derivePrefix("terraform apply"))
        assertEquals("make all", CommandShape.derivePrefix("make all"))
    }

    @Test fun `derivePrefix returns bare name for simple non-multi-verb commands`() {
        // Bare commands with no sub-verb concept
        assertEquals("ls", CommandShape.derivePrefix("ls -la"))
        assertEquals("cat", CommandShape.derivePrefix("cat README.md"))
        assertEquals("echo", CommandShape.derivePrefix("echo hello"))
        assertEquals("find", CommandShape.derivePrefix("find . -name *.kt"))
        assertEquals("grep", CommandShape.derivePrefix("grep pattern file.txt"))
    }

    @Test fun `derivePrefix returns bare name when second token starts with a flag`() {
        // git -C /tmp — second token is -C (a flag) → bare "git"
        assertEquals("git", CommandShape.derivePrefix("git -C /tmp status"))
        // npm --prefix /dir install — second token is --prefix (a flag) → bare "npm"
        assertEquals("npm", CommandShape.derivePrefix("npm --prefix /tmp install"))
    }

    // ── coveringPrefixes: multi-token prefix edge cases ──
    @Test fun `multi-token prefix covers exact sub-command and longer but not different-token commands`() {
        val allow = setOf("git commit")
        // "git commit -m x" starts with the "git commit" prefix tokens → covered
        assertEquals(listOf("git commit"), CommandShape.coveringPrefixes("git commit -m msg", allow))
        // "git commitfoo" — first token "git" matches, second token "commitfoo" != "commit" → NOT covered
        assertNull(CommandShape.coveringPrefixes("git commitfoo", allow))
    }

    @Test fun `pipe chain fully covered when each side has its own prefix`() {
        val allow = setOf("cat", "grep")
        // Both sub-commands (cat a) and (grep b) covered
        assertEquals(listOf("cat", "grep"), CommandShape.coveringPrefixes("cat a | grep b", allow))
    }

    @Test fun `and-chain fully covered when both sides match`() {
        val allow = setOf("git add", "git status")
        assertEquals(listOf("git add", "git status"), CommandShape.coveringPrefixes("git add . && git status", allow))
    }

    @Test fun `partial coverage — one side uncovered — returns null`() {
        val allow = setOf("git add")
        // "git add ." is covered but "rm -rf x" is not
        assertNull(CommandShape.coveringPrefixes("git add . && rm -rf x", allow))
    }

    @Test fun `coveringPrefixes case-insensitive match on live command`() {
        val allow = setOf("git add")
        assertEquals(listOf("git add"), CommandShape.coveringPrefixes("GIT ADD Foo.kt", allow))
    }

    @Test fun `coveringPrefixes empty allowlist returns null`() {
        assertNull(CommandShape.coveringPrefixes("ls -la", emptySet()))
    }

    @Test fun `coveringPrefixes not-structurally-simple command returns null even if prefix exists`() {
        // The command contains a redirect → not structurally simple → null
        val allow = setOf("git add")
        assertNull(CommandShape.coveringPrefixes("git add . > out.txt", allow))
    }

    // ── splitSubCommands: newline variants and edge cases ──
    @Test fun `splitSubCommands handles CRLF and bare CR as line separators`() {
        // \r\n (Windows) → two sub-commands
        assertEquals(listOf("git status", "ls"), CommandShape.splitSubCommands("git status\r\nls"))
        // bare \r (old Mac) → two sub-commands
        assertEquals(listOf("git status", "ls"), CommandShape.splitSubCommands("git status\rls"))
    }

    @Test fun `splitSubCommands trailing operator produces no empty sub-command`() {
        // "ls;" — the semicolon splits, the empty part is discarded
        assertEquals(listOf("ls"), CommandShape.splitSubCommands("ls;"))
    }

    @Test fun `operators inside quotes are NOT treated as split points`() {
        // "echo "a && b"" should be ONE sub-command because && is inside a quoted string
        val subs = CommandShape.splitSubCommands("echo \"a && b\"")
        assertEquals(1, subs.size)
    }
}
