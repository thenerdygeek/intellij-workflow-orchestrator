package com.workflow.orchestrator.mockserver.bitbucket

object BitbucketDataFactory {

    const val DEFAULT_PROJECT = "PROJ"
    const val DEFAULT_REPO = "my-repo"
    const val DEFAULT_REPO2 = "other-repo"

    private val AUTHOR = MockBitbucketUser("mock.user", "Mock User", "mock.user@example.com")
    private val REVIEWER_1 = MockBitbucketUser("jane.smith", "Jane Smith", "jane.smith@example.com")
    private val REVIEWER_2 = MockBitbucketUser("bob.jones", "Bob Jones", "bob.jones@example.com")

    private const val SHA_MAIN = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
    private const val SHA_FEATURE_1 = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3"
    private const val SHA_FEATURE_2 = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
    private const val SHA_HOTFIX = "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5"
    private const val SHA_EXPERIMENT = "f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1"

    fun createDefaultState(): BitbucketState {
        val state = BitbucketState()
        createDefaultPrs().forEach { state.prs[it.id] = it }
        return state
    }

    fun createAllMergedState(): BitbucketState {
        val state = createDefaultState()
        state.prs.values.forEach { pr ->
            if (pr.state == "OPEN") {
                pr.state = "MERGED"
                pr.version++
                pr.updatedDate = System.currentTimeMillis()
            }
        }
        return state
    }

    fun createEmptyState(): BitbucketState = BitbucketState()

    fun createHappyPathState(): BitbucketState {
        val state = BitbucketState()
        val pr = MockBitbucketPr(
            id = 1,
            title = "PROJ-200 Happy path feature",
            description = "A clean PR with all reviewers approved.",
            state = "OPEN",
            version = 1,
            fromRef = MockBitbucketRef(
                id = "refs/heads/feature/PROJ-200",
                displayId = "feature/PROJ-200",
                latestCommit = SHA_FEATURE_1,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            toRef = MockBitbucketRef(
                id = "refs/heads/main",
                displayId = "main",
                latestCommit = SHA_MAIN,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            author = AUTHOR,
            reviewers = mutableListOf(
                MockBitbucketReviewer(REVIEWER_1, approved = true, status = "APPROVED"),
            ),
            createdDate = 1750000000000L,
            updatedDate = 1750000000000L,
            comments = mutableListOf(),
            commits = listOf(
                MockBitbucketCommit(
                    id = SHA_FEATURE_1,
                    displayId = SHA_FEATURE_1.take(7),
                    message = "feat: happy path feature",
                    authorName = "mock.user",
                    authorTimestamp = 1749990000000L,
                ),
            ),
            changes = listOf(MockBitbucketChange("src/main/kotlin/Happy.kt", "ADD")),
            diff = "diff --git a/src/main/kotlin/Happy.kt b/src/main/kotlin/Happy.kt\n" +
                "new file mode 100644\n+class Happy\n",
        )
        state.prs[pr.id] = pr
        return state
    }

    private fun createDefaultPrs(): List<MockBitbucketPr> = listOf(
        // PR 1: OPEN — feature with 2 reviewers (1 approved)
        MockBitbucketPr(
            id = 1,
            title = "PROJ-101 Implement user authentication flow",
            description = "Adds JWT-based auth to the login endpoint. See PROJ-101 for requirements.",
            state = "OPEN",
            version = 3,
            fromRef = MockBitbucketRef(
                id = "refs/heads/feature/PROJ-101-auth",
                displayId = "feature/PROJ-101-auth",
                latestCommit = SHA_FEATURE_1,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            toRef = MockBitbucketRef(
                id = "refs/heads/main",
                displayId = "main",
                latestCommit = SHA_MAIN,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            author = AUTHOR,
            reviewers = mutableListOf(
                MockBitbucketReviewer(REVIEWER_1, approved = true, status = "APPROVED"),
                MockBitbucketReviewer(REVIEWER_2, approved = false, status = "UNAPPROVED"),
            ),
            createdDate = 1750000000000L,
            updatedDate = 1750100000000L,
            comments = mutableListOf(
                MockBitbucketComment(
                    id = 1L,
                    text = "LGTM overall, just one nit on the token expiry logic.",
                    authorName = REVIEWER_1.name,
                    authorDisplayName = REVIEWER_1.displayName,
                    createdDate = 1750050000000L,
                ),
                MockBitbucketComment(
                    id = 2L,
                    text = "Can you add a test for the refresh-token path?",
                    authorName = REVIEWER_2.name,
                    authorDisplayName = REVIEWER_2.displayName,
                    createdDate = 1750060000000L,
                ),
            ),
            commits = listOf(
                MockBitbucketCommit(
                    id = SHA_FEATURE_1,
                    displayId = SHA_FEATURE_1.take(7),
                    message = "feat(auth): add JWT token generation and validation",
                    authorName = AUTHOR.name,
                    authorTimestamp = 1749950000000L,
                ),
                MockBitbucketCommit(
                    id = "e1f2a3b4c5d6e1f2a3b4c5d6e1f2a3b4c5d6e1f2",
                    displayId = "e1f2a3b",
                    message = "test(auth): add unit tests for token refresh",
                    authorName = AUTHOR.name,
                    authorTimestamp = 1749960000000L,
                ),
            ),
            changes = listOf(
                MockBitbucketChange("src/main/kotlin/auth/JwtService.kt", "ADD"),
                MockBitbucketChange("src/main/kotlin/auth/AuthController.kt", "MODIFY"),
                MockBitbucketChange("src/test/kotlin/auth/JwtServiceTest.kt", "ADD"),
            ),
            diff = buildString {
                appendLine("diff --git a/src/main/kotlin/auth/JwtService.kt b/src/main/kotlin/auth/JwtService.kt")
                appendLine("new file mode 100644")
                appendLine("index 0000000..a1b2c3d")
                appendLine("--- /dev/null")
                appendLine("+++ b/src/main/kotlin/auth/JwtService.kt")
                appendLine("@@ -0,0 +1,8 @@")
                appendLine("+package auth")
                appendLine("+")
                appendLine("+class JwtService {")
                appendLine("+    fun generateToken(username: String): String {")
                appendLine("+        return \"mock-token-\$username\"")
                appendLine("+    }")
                appendLine("+}")
                appendLine("diff --git a/src/main/kotlin/auth/AuthController.kt b/src/main/kotlin/auth/AuthController.kt")
                appendLine("index b2c3d4e..c3d4e5f 100644")
                appendLine("--- a/src/main/kotlin/auth/AuthController.kt")
                appendLine("+++ b/src/main/kotlin/auth/AuthController.kt")
                appendLine("@@ -5,5 +5,6 @@ class AuthController(private val jwtService: JwtService) {")
                appendLine("     fun login(username: String, password: String): String {")
                appendLine("-        return \"not-implemented\"")
                appendLine("+        return jwtService.generateToken(username)")
                appendLine("     }")
            }
        ),

        // PR 2: OPEN — hotfix, one reviewer with NEEDS_WORK
        MockBitbucketPr(
            id = 2,
            title = "PROJ-102 Fix payment gateway timeout",
            description = "Bumps OkHttp connect timeout from 10 s to 30 s to handle slow payment providers.",
            state = "OPEN",
            version = 1,
            fromRef = MockBitbucketRef(
                id = "refs/heads/hotfix/PROJ-102-payment-timeout",
                displayId = "hotfix/PROJ-102-payment-timeout",
                latestCommit = SHA_HOTFIX,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            toRef = MockBitbucketRef(
                id = "refs/heads/main",
                displayId = "main",
                latestCommit = SHA_MAIN,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            author = AUTHOR,
            reviewers = mutableListOf(
                MockBitbucketReviewer(REVIEWER_1, approved = false, status = "NEEDS_WORK"),
            ),
            createdDate = 1750200000000L,
            updatedDate = 1750200000000L,
            comments = mutableListOf(
                MockBitbucketComment(
                    id = 3L,
                    text = "The timeout value is higher than our SLA. Please discuss with the team first.",
                    authorName = REVIEWER_1.name,
                    authorDisplayName = REVIEWER_1.displayName,
                    createdDate = 1750210000000L,
                ),
            ),
            commits = listOf(
                MockBitbucketCommit(
                    id = SHA_HOTFIX,
                    displayId = SHA_HOTFIX.take(7),
                    message = "fix(payment): increase OkHttp connect timeout to 30s",
                    authorName = AUTHOR.name,
                    authorTimestamp = 1750195000000L,
                ),
            ),
            changes = listOf(
                MockBitbucketChange("src/main/kotlin/payment/PaymentGatewayClient.kt", "MODIFY"),
            ),
            diff = buildString {
                appendLine("diff --git a/src/main/kotlin/payment/PaymentGatewayClient.kt b/src/main/kotlin/payment/PaymentGatewayClient.kt")
                appendLine("index d4e5f6a..e5f6a1b 100644")
                appendLine("--- a/src/main/kotlin/payment/PaymentGatewayClient.kt")
                appendLine("+++ b/src/main/kotlin/payment/PaymentGatewayClient.kt")
                appendLine("@@ -8,7 +8,7 @@ class PaymentGatewayClient {")
                appendLine("     private val client = OkHttpClient.Builder()")
                appendLine("-        .connectTimeout(10, TimeUnit.SECONDS)")
                appendLine("+        .connectTimeout(30, TimeUnit.SECONDS)")
                appendLine("         .build()")
            }
        ),

        // PR 3: MERGED — both reviewers approved
        MockBitbucketPr(
            id = 3,
            title = "PROJ-99 Update Gradle wrapper to 8.7",
            description = "Routine maintenance: bumps the Gradle wrapper from 8.5 to 8.7.",
            state = "MERGED",
            version = 5,
            fromRef = MockBitbucketRef(
                id = "refs/heads/chore/gradle-8.7",
                displayId = "chore/gradle-8.7",
                latestCommit = SHA_FEATURE_2,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            toRef = MockBitbucketRef(
                id = "refs/heads/main",
                displayId = "main",
                latestCommit = SHA_MAIN,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            author = AUTHOR,
            reviewers = mutableListOf(
                MockBitbucketReviewer(REVIEWER_1, approved = true, status = "APPROVED"),
                MockBitbucketReviewer(REVIEWER_2, approved = true, status = "APPROVED"),
            ),
            createdDate = 1749500000000L,
            updatedDate = 1749600000000L,
            comments = mutableListOf(),
            commits = listOf(
                MockBitbucketCommit(
                    id = SHA_FEATURE_2,
                    displayId = SHA_FEATURE_2.take(7),
                    message = "chore: bump Gradle wrapper to 8.7",
                    authorName = AUTHOR.name,
                    authorTimestamp = 1749490000000L,
                ),
            ),
            changes = listOf(
                MockBitbucketChange("gradle/wrapper/gradle-wrapper.properties", "MODIFY"),
            ),
            diff = buildString {
                appendLine("diff --git a/gradle/wrapper/gradle-wrapper.properties b/gradle/wrapper/gradle-wrapper.properties")
                appendLine("index a1b2c3d..b2c3d4e 100644")
                appendLine("--- a/gradle/wrapper/gradle-wrapper.properties")
                appendLine("+++ b/gradle/wrapper/gradle-wrapper.properties")
                appendLine("@@ -3,4 +3,4 @@")
                appendLine("-distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip")
                appendLine("+distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip")
            }
        ),

        // PR 4: DECLINED
        MockBitbucketPr(
            id = 4,
            title = "PROJ-98 Experimental: rewrite in Go",
            description = "Proof-of-concept rewrite. Declined by architecture review.",
            state = "DECLINED",
            version = 2,
            fromRef = MockBitbucketRef(
                id = "refs/heads/experiment/go-rewrite",
                displayId = "experiment/go-rewrite",
                latestCommit = SHA_EXPERIMENT,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            toRef = MockBitbucketRef(
                id = "refs/heads/main",
                displayId = "main",
                latestCommit = SHA_MAIN,
                repoSlug = DEFAULT_REPO,
                projectKey = DEFAULT_PROJECT,
            ),
            author = REVIEWER_2,
            reviewers = mutableListOf(
                MockBitbucketReviewer(AUTHOR, approved = false, status = "NEEDS_WORK"),
            ),
            createdDate = 1748000000000L,
            updatedDate = 1748100000000L,
            comments = mutableListOf(
                MockBitbucketComment(
                    id = 10L,
                    text = "This is not aligned with our technology strategy. Declining.",
                    authorName = AUTHOR.name,
                    authorDisplayName = AUTHOR.displayName,
                    createdDate = 1748090000000L,
                ),
            ),
            commits = listOf(
                MockBitbucketCommit(
                    id = SHA_EXPERIMENT,
                    displayId = SHA_EXPERIMENT.take(7),
                    message = "experiment: initial Go prototype",
                    authorName = REVIEWER_2.name,
                    authorTimestamp = 1747990000000L,
                ),
            ),
            changes = listOf(
                MockBitbucketChange("main.go", "ADD"),
            ),
            diff = buildString {
                appendLine("diff --git a/main.go b/main.go")
                appendLine("new file mode 100644")
                appendLine("--- /dev/null")
                appendLine("+++ b/main.go")
                appendLine("@@ -0,0 +1,3 @@")
                appendLine("+package main")
                appendLine("+")
                appendLine("+func main() {}")
            }
        ),
    )
}
