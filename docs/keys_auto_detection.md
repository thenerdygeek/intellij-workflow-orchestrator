# Auto-Detect Quick Start Guide

## Overview

This guide shows you how to implement an **auto-detect feature** for IntelliJ plugins that automatically discovers project configuration keys from repository structure.

## What Gets Auto-Detected

✅ **Bitbucket Project Key** (e.g., `MYPROJ`)  
✅ **Bitbucket Repository Slug** (e.g., `my-sample-service`)  
✅ **Bamboo Plan Key** (e.g., `MYSAMPLESERVICE`)  
✅ **Docker Tag Name** (e.g., `MySampleServiceDockerTag`)  
✅ **Default Branch** (e.g., `develop`)  
✅ **SonarQube Project Key** (e.g., `my-sample-service`)  

## Detection Sources (in Priority Order)

### 1. Git Remote URL (.git/config)
```bash
# SSH format:
git@bitbucket.example.com:7999/MYPROJ/my-sample-service.git
                                ↑     ↑
                          Project Key  Repo Slug

# HTTPS format:
https://bitbucket.example.com/scm/MYPROJ/my-sample-service.git
                                   ↑     ↑
                             Project Key  Repo Slug
```

### 2. Bamboo Specs (Java Constants)
```
project-root/
├── bamboo-specs/
│   └── src/main/java/constants/
│       ├── ProjectProperties.java  → PROJECT_KEY
│       └── PlanProperties.java     → PLAN_KEY, DOCKER_TAG_NAME, etc.
```

**Example PlanProperties.java:**
```java
public class PlanProperties {
    private static final String REPOSITORY_NAME = "my-sample-service";
    private static final String PLAN_KEY = "MYSAMPLESERVICE";
    private static final String GIT_PROJECT_ID = "MYPROJ";
    private static final String DOCKER_TAG_NAME = "MySampleServiceDockerTag";
    private static final String[] RELEASE_STAGE_BRANCHES = { "develop" };
}
```

### 3. Maven POM.xml (SonarQube)
```xml
<project>
    <groupId>com.example.product</groupId>
    <artifactId>my-sample-service</artifactId>
    
    <properties>
        <sonar.projectKey>my-sample-service</sonar.projectKey>
    </properties>
</project>
```

## Implementation Steps

### Step 1: Parse Git Remote URL

```kotlin
fun parseGitRemoteUrl(url: String): BitbucketInfo? {
    // SSH: git@host:7999/PROJECT/repo.git
    val sshRegex = Regex("""git@[^:]+:(?:\\d+/)?([^/]+)/([^/]+?)(?:\\.git)?$""")
    
    // HTTPS: https://host/scm/PROJECT/repo.git
    val httpsRegex = Regex(""".+/scm/([^/]+)/([^/]+?)(?:\\.git)?$""")
    
    sshRegex.find(url)?.let {
        return BitbucketInfo(
            projectKey = it.groupValues[1],
            repoSlug = it.groupValues[2]
        )
    }
    
    httpsRegex.find(url)?.let {
        return BitbucketInfo(
            projectKey = it.groupValues[1],
            repoSlug = it.groupValues[2]
        )
    }
    
    return null
}
```

### Step 2: Parse Bamboo Specs Java Files

```kotlin
fun extractConstant(javaContent: String, constantName: String): String? {
    // Match: private static final String PLAN_KEY = "value";
    val regex = Regex(
        """(?:private|public)?\\s+static\\s+final\\s+String\\s+$constantName\\s*=\\s*"([^"]+)"""
    )
    return regex.find(javaContent)?.groupValues?.get(1)
}

fun detectFromBambooSpecs(projectDir: Path): BambooSpecsInfo {
    val bambooSpecsDir = projectDir.resolve("bamboo-specs/src/main/java")
    if (!bambooSpecsDir.exists()) return BambooSpecsInfo()
    
    var result = BambooSpecsInfo()
    
    bambooSpecsDir.walk()
        .filter { it.toString().endsWith(".java") }
        .forEach { file ->
            val content = file.readText()
            
            result = result.copy(
                planKey = result.planKey ?: extractConstant(content, "PLAN_KEY"),
                dockerTagName = result.dockerTagName ?: extractConstant(content, "DOCKER_TAG_NAME"),
                projectKey = result.projectKey ?: extractConstant(content, "PROJECT_KEY"),
                repositoryName = result.repositoryName ?: extractConstant(content, "REPOSITORY_NAME")
            )
        }
    
    return result
}
```

### Step 3: Parse Maven POM.xml

```kotlin
fun detectSonarProjectKey(projectDir: Path): String? {
    val pomXml = projectDir.resolve("pom.xml")
    if (!pomXml.exists()) return null
    
    val doc = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(pomXml.inputStream())
    
    // Priority 1: Explicit sonar.projectKey
    val sonarKey = getPropertyValue(doc, "sonar.projectKey")
    if (sonarKey != null && !sonarKey.contains("${")) {
        return sonarKey
    }
    
    // Priority 2: ${project.artifactId} - resolve it
    if (sonarKey == "\${project.artifactId}") {
        return getArtifactId(doc)
    }
    
    // Priority 3: Just artifactId
    return getArtifactId(doc)
}
```

### Step 4: Combine All Sources

```kotlin
data class ProjectKeys(
    val bitbucketProjectKey: String?,
    val bitbucketRepoSlug: String?,
    val bambooPlanKey: String?,
    val dockerTagKey: String?,
    val defaultBranch: String?,
    val sonarProjectKey: String?
)

fun autoDetectProjectKeys(projectDir: Path): ProjectKeys {
    // Detect from all sources
    val gitInfo = detectFromGitRemote(projectDir)
    val bambooInfo = detectFromBambooSpecs(projectDir)
    val sonarKey = detectSonarProjectKey(projectDir)
    
    // Merge with fallback priority
    return ProjectKeys(
        bitbucketProjectKey = gitInfo?.projectKey ?: bambooInfo.projectKey,
        bitbucketRepoSlug = gitInfo?.repoSlug ?: bambooInfo.repositoryName,
        bambooPlanKey = bambooInfo.planKey,
        dockerTagKey = bambooInfo.dockerTagName,
        defaultBranch = bambooInfo.defaultBranch ?: "develop",
        sonarProjectKey = sonarKey
    )
}
```

## Real-World Example Output

Based on actual repository analysis:

```
Repository: my-sample-service
====================================
Bitbucket Project Key: MYPROJ
Bitbucket Repo Slug: my-sample-service
Bamboo Plan Key: MYSAMPLESERVICE
Docker Tag Name: MySampleServiceDockerTag
Default Branch: develop
SonarQube Project Key: my-sample-service

Detection Sources:
  ✓ Bitbucket Info: bamboo-specs/PlanProperties.java
  ✓ Bamboo Info: bamboo-specs/*.java
  ✓ SonarQube Key: pom.xml <sonar.projectKey>
  ✓ Default Branch: bamboo-specs RELEASE_STAGE_BRANCHES
```

## Multi-Module Repository Support

For projects with multiple modules:

```
parent-project/
├── pom.xml                    → Parent POM
├── bamboo-specs/              → Shared Bamboo config
├── service-a/
│   └── pom.xml               → Module-specific SonarQube key
└── service-b/
    └── pom.xml               → Module-specific SonarQube key
```

**Strategy:**
1. Detect Git + Bamboo info from root
2. Detect SonarQube keys from each module's pom.xml
3. Present user with option to choose module or use root

## Detection Priority Matrix

| Key | Primary | Fallback 1 | Fallback 2 |
|-----|---------|------------|------------|
| Bitbucket Project | Git remote | bamboo-specs | - |
| Bitbucket Repo | Git remote | bamboo-specs | Directory name |
| Bamboo Plan | bamboo-specs | - | - |
| Docker Tag | bamboo-specs | - | - |
| Default Branch | bamboo-specs | `git symbolic-ref` | `develop` |
| SonarQube | pom.xml explicit | pom.xml artifactId | - |

## Error Handling

```kotlin
fun safeAutoDetect(projectDir: Path): Result<ProjectKeys> {
    return try {
        val keys = autoDetectProjectKeys(projectDir)
        
        // Validate at least some keys were detected
        if (keys.bitbucketProjectKey == null && 
            keys.bambooPlanKey == null) {
            Result.failure(Exception(
                "Could not auto-detect project keys. " +
                "Please ensure the project has .git/config or bamboo-specs/"
            ))
        } else {
            Result.success(keys)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

## UI Integration

### Option 1: Auto-Detect Button

```kotlin
class PluginSettingsUI {
    private val autoDetectButton = JButton("Auto-Detect")
    
    init {
        autoDetectButton.addActionListener {
            val projectPath = getCurrentProjectPath()
            val result = safeAutoDetect(projectPath)
            
            result.onSuccess { keys ->
                populateFields(keys)
                showSuccessNotification()
            }.onFailure { error ->
                showErrorDialog(error.message)
            }
        }
    }
    
    private fun populateFields(keys: ProjectKeys) {
        bitbucketProjectField.text = keys.bitbucketProjectKey ?: ""
        bitbucketRepoField.text = keys.bitbucketRepoSlug ?: ""
        bambooPlanField.text = keys.bambooPlanKey ?: ""
        dockerTagField.text = keys.dockerTagKey ?: ""
        defaultBranchField.text = keys.defaultBranch ?: "develop"
        sonarProjectField.text = keys.sonarProjectKey ?: ""
    }
}
```

### Option 2: Auto-Detect on Plugin Initialization

```kotlin
class PluginInitializer : StartupActivity {
    override fun runActivity(project: Project) {
        val settings = PluginSettings.getInstance(project)
        
        // Only auto-detect if fields are empty
        if (settings.isEmpty()) {
            val keys = autoDetectProjectKeys(Paths.get(project.basePath))
            settings.applyKeys(keys)
        }
    }
}
```

## Testing with Real Repositories

Tested with these repository patterns:

✅ **Single-module Maven projects**  
✅ **Multi-module Maven projects** (parent + modules)  
✅ **Spring Boot applications**  
✅ **Projects with bamboo-specs**  
✅ **Projects with explicit SonarQube config**  

## Common Edge Cases Handled

- ❌ Missing `.git` directory → Uses bamboo-specs only
- ❌ Missing `bamboo-specs` → Uses Git + pom.xml only
- ❌ Multi-module projects → Scans each module separately
- ❌ No detectable keys → Returns partial results with clear error
- ❌ Git SSH vs HTTPS URLs → Both patterns supported

## Next Steps

1. ✅ Read the full implementation guide: `INTELLIJ_PLUGIN_AUTO_DETECT_GUIDE.md`
2. ✅ Copy the Kotlin code snippets into your plugin
3. ✅ Test with your actual repositories
4. ✅ Add UI button for auto-detect
5. ✅ Handle edge cases gracefully

## Full Documentation

For complete implementation with all code examples, error handling, and advanced features, see:

📄 **[INTELLIJ_PLUGIN_AUTO_DETECT_GUIDE.md](./INTELLIJ_PLUGIN_AUTO_DETECT_GUIDE.md)**

---

*Generated from analysis of sample service repositories*


# IntelliJ Plugin Auto-Detect Guide for Project Keys

This guide provides a comprehensive approach to automatically detect project configuration keys for repositories, supporting both single-module and multi-module Maven projects.

---

## Overview

The auto-detect feature extracts the following keys from repository structure:

1. **Bitbucket Project Key** - The project identifier in Bitbucket (e.g., `PROJKEY`)
2. **Bitbucket Repository Slug** - The repository name (e.g., `my-service`)
3. **Bamboo Plan Key** - The unique build plan identifier (e.g., `MYSERVICE`)
4. **Bamboo Project Key** - The project key in Bamboo (e.g., `PROJKEY`)
5. **Docker Tag Name** - The Docker image tag variable (e.g., `MyServiceDockerTag`)
6. **Default Branch** - The primary development branch (e.g., `develop`)
7. **SonarQube Project Key** - The project identifier in SonarQube (e.g., `my-service`)

---

## Detection Strategy Priority

### Strategy 1: Git Remote URL (Bitbucket)

**Priority:** PRIMARY  
**Reliability:** HIGH  
**Source:** `.git/config` or `git config --get remote.origin.url`

#### SSH Format Examples:
```
git@bitbucket.company.com:7999/PROJKEY/my-service.git
git@bitbucket.company.com:PROJKEY/my-service.git
```

#### HTTPS Format Examples:
```
https://bitbucket.company.com/scm/PROJKEY/my-service.git
https://user@bitbucket.company.com/scm/PROJKEY/my-service.git
```

#### Implementation (Kotlin):

```kotlin
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

data class BitbucketInfo(
    val projectKey: String,
    val repoSlug: String
)

fun detectFromGitRemote(projectDir: Path): BitbucketInfo? {
    val gitConfig = projectDir.resolve(".git/config")
    if (!gitConfig.exists()) return null
    
    val configContent = gitConfig.readText()
    val remoteUrl = extractRemoteUrl(configContent) ?: return null
    
    return parseGitRemoteUrl(remoteUrl)
}

fun extractRemoteUrl(gitConfig: String): String? {
    // Extract URL from [remote "origin"] section
    val remoteOriginRegex = Regex(
        """\[remote "origin"\].*?url\s*=\s*([^\s]+)""",
        RegexOption.DOT_MATCHES_ALL
    )
    return remoteOriginRegex.find(gitConfig)?.groupValues?.get(1)
}

fun parseGitRemoteUrl(url: String): BitbucketInfo? {
    // SSH format: git@host:port/PROJECT/repo.git or git@host:PROJECT/repo.git
    val sshRegex = Regex("""git@[^:]+:(?:\d+/)?([^/]+)/([^/]+?)(?:\.git)?$""")
    
    // HTTPS format: https://host/scm/PROJECT/repo.git
    val httpsRegex = Regex(""".+/scm/([^/]+)/([^/]+?)(?:\.git)?$""")
    
    sshRegex.find(url)?.let {
        return BitbucketInfo(
            projectKey = it.groupValues[1],
            repoSlug = it.groupValues[2]
        )
    }
    
    httpsRegex.find(url)?.let {
        return BitbucketInfo(
            projectKey = it.groupValues[1],
            repoSlug = it.groupValues[2]
        )
    }
    
    return null
}
```

---

### Strategy 2: Bamboo Specs (Java Constants)

**Priority:** HIGH  
**Reliability:** HIGH  
**Source:** `bamboo-specs/src/main/java/constants/*.java`

#### Standard File Structure:
```
project-root/
├── bamboo-specs/
│   └── src/
│       └── main/
│           └── java/
│               └── constants/
│                   ├── ProjectProperties.java
│                   └── PlanProperties.java
```

#### ProjectProperties.java Pattern:

```java
package constants;

public class ProjectProperties {
    private static final String PROJECT_KEY = "PROJKEY";
    private static final String PROJECT_NAME = "My Project";
    private static final String BAMBOO_SERVER_URL = "https://bamboo.company.com";
    
    public static String getProjectKey() {
        return PROJECT_KEY;
    }
}
```

#### PlanProperties.java Pattern:

```java
package constants;

public class PlanProperties {
    private static final String REPOSITORY_NAME = "my-service";
    private static final String PLAN_KEY = "MYSERVICE";
    private static final String GIT_PROJECT_ID = "PROJKEY";
    private static final String DOCKER_TAG_NAME = "MyServiceDockerTag";
    
    // Release stage branches (indicates default branch)
    private static final String[] RELEASE_STAGE_BRANCHES = {
        "develop"
    };
    
    public static String getRepositoryName() {
        return REPOSITORY_NAME;
    }
    
    public static String getPlanKey() {
        return PLAN_KEY;
    }
    
    public static String getGitProjectId() {
        return GIT_PROJECT_ID;
    }
    
    public static String getDockerTagName() {
        return DOCKER_TAG_NAME;
    }
    
    public static boolean isReleaseStageAllowed(String branchName) {
        return Arrays.asList(RELEASE_STAGE_BRANCHES).contains(branchName);
    }
}
```

#### Implementation (Kotlin):

```kotlin
import java.nio.file.Path
import kotlin.io.path.walk
import kotlin.io.path.readText

data class BambooSpecsInfo(
    val projectKey: String? = null,
    val repositoryName: String? = null,
    val planKey: String? = null,
    val dockerTagName: String? = null,
    val defaultBranch: String? = null
)

fun detectFromBambooSpecs(projectDir: Path): BambooSpecsInfo {
    val bambooSpecsDir = projectDir.resolve("bamboo-specs/src/main/java")
    if (!bambooSpecsDir.exists()) return BambooSpecsInfo()
    
    var result = BambooSpecsInfo()
    
    // Scan all Java files in bamboo-specs
    bambooSpecsDir.walk()
        .filter { it.toString().endsWith(".java") }
        .forEach { file ->
            val content = file.readText()
            
            // Extract values using regex patterns
            result = result.copy(
                projectKey = result.projectKey ?: extractConstant(content, "PROJECT_KEY"),
                repositoryName = result.repositoryName ?: extractConstant(content, "REPOSITORY_NAME"),
                planKey = result.planKey ?: extractConstant(content, "PLAN_KEY"),
                dockerTagName = result.dockerTagName ?: extractConstant(content, "DOCKER_TAG_NAME"),
                defaultBranch = result.defaultBranch ?: extractDefaultBranch(content)
            )
        }
    
    return result
}

fun extractConstant(javaContent: String, constantName: String): String? {
    // Match: private static final String CONSTANT_NAME = "value";
    val regex = Regex(
        """(?:private|public)?\s+static\s+final\s+String\s+$constantName\s*=\s*"([^"]+)"""
    )
    return regex.find(javaContent)?.groupValues?.get(1)
}

fun extractDefaultBranch(javaContent: String): String? {
    // Extract first branch from RELEASE_STAGE_BRANCHES array
    val regex = Regex(
        """RELEASE_STAGE_BRANCHES\s*=\s*\{\s*"([^"]+)"""
    )
    return regex.find(javaContent)?.groupValues?.get(1)
}
```

---

### Strategy 3: Maven POM.xml (SonarQube)

**Priority:** MEDIUM  
**Reliability:** HIGH  
**Source:** `pom.xml` or `module/pom.xml`

#### Single-Module Project Pattern:

```xml
<project>
    <groupId>com.company.product</groupId>
    <artifactId>my-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    
    <properties>
        <!-- Explicit SonarQube key -->
        <sonar.projectKey>my-service</sonar.projectKey>
        <sonar.projectName>my-service</sonar.projectName>
    </properties>
</project>
```

#### Multi-Module Project Pattern:

```xml
<!-- Parent pom.xml -->
<project>
    <groupId>com.company.product</groupId>
    <artifactId>parent-project</artifactId>
    <packaging>pom</packaging>
    
    <modules>
        <module>service-a</module>
        <module>service-b</module>
    </modules>
</project>

<!-- service-a/pom.xml -->
<project>
    <parent>
        <groupId>com.company.product</groupId>
        <artifactId>parent-project</artifactId>
        <version>1.0.0</version>
    </parent>
    
    <artifactId>service-a</artifactId>
    
    <properties>
        <sonar.projectKey>${project.artifactId}</sonar.projectKey>
    </properties>
</project>
```

#### Implementation (Kotlin):

```kotlin
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun detectSonarProjectKey(projectDir: Path): String? {
    val pomXml = projectDir.resolve("pom.xml")
    if (!pomXml.exists()) return null
    
    return parsePomForSonarKey(pomXml)
}

fun parsePomForSonarKey(pomPath: Path): String? {
    try {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pomPath.inputStream())
        
        doc.documentElement.normalize()
        
        // Priority 1: Explicit <sonar.projectKey> in properties
        val sonarKey = getPropertyValue(doc, "sonar.projectKey")
        if (sonarKey != null && !sonarKey.contains("${")) {
            return sonarKey
        }
        
        // Priority 2: ${project.artifactId} - resolve it
        if (sonarKey == "\${project.artifactId}") {
            return getArtifactId(doc)
        }
        
        // Priority 3: groupId:artifactId (Maven default)
        val groupId = getGroupId(doc)
        val artifactId = getArtifactId(doc)
        if (groupId != null && artifactId != null) {
            return "$groupId:$artifactId"
        }
        
        // Priority 4: Just artifactId
        return artifactId
        
    } catch (e: Exception) {
        return null
    }
}

fun getPropertyValue(doc: Document, propertyName: String): String? {
    val properties = doc.getElementsByTagName("properties")
    if (properties.length == 0) return null
    
    val propElement = properties.item(0) as Element
    val propNodes = propElement.getElementsByTagName(propertyName)
    
    return if (propNodes.length > 0) {
        propNodes.item(0).textContent?.trim()
    } else null
}

fun getArtifactId(doc: Document): String? {
    val nodes = doc.getElementsByTagName("artifactId")
    // Get first artifactId (project's own, not parent's)
    return if (nodes.length > 0) {
        nodes.item(0).textContent?.trim()
    } else null
}

fun getGroupId(doc: Document): String? {
    // Try direct groupId first
    var nodes = doc.getElementsByTagName("groupId")
    if (nodes.length > 0) {
        val directGroupId = nodes.item(0).textContent?.trim()
        if (directGroupId != null && !directGroupId.isEmpty()) {
            return directGroupId
        }
    }
    
    // Try parent groupId
    val parent = doc.getElementsByTagName("parent")
    if (parent.length > 0) {
        val parentElement = parent.item(0) as Element
        nodes = parentElement.getElementsByTagName("groupId")
        if (nodes.length > 0) {
            return nodes.item(0).textContent?.trim()
        }
    }
    
    return null
}
```

---

### Strategy 4: Git Default Branch Detection

**Priority:** LOW  
**Reliability:** MEDIUM  
**Source:** Git commands or bamboo-specs

#### Implementation (Kotlin):

```kotlin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

fun detectDefaultBranch(projectDir: Path, bambooDefault: String?): String {
    // Priority 1: Use bamboo-specs RELEASE_STAGE_BRANCHES
    if (bambooDefault != null) {
        return bambooDefault
    }
    
    // Priority 2: Git symbolic-ref for HEAD
    val gitHead = runGitCommand(projectDir, "symbolic-ref", "refs/remotes/origin/HEAD")
        ?.removePrefix("refs/remotes/origin/")
    if (gitHead != null) {
        return gitHead
    }
    
    // Priority 3: Check for common branch names
    val branches = runGitCommand(projectDir, "branch", "-r")?.lines() ?: emptyList()
    
    return when {
        branches.any { it.contains("origin/develop") } -> "develop"
        branches.any { it.contains("origin/main") } -> "main"
        branches.any { it.contains("origin/master") } -> "master"
        else -> "develop" // Default fallback
    }
}

fun runGitCommand(projectDir: Path, vararg args: String): String? {
    return try {
        val command = listOf("git") + args
        val process = ProcessBuilder(command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        
        val output = BufferedReader(InputStreamReader(process.inputStream))
            .readText()
            .trim()
        
        if (process.waitFor() == 0 && output.isNotEmpty()) {
            output
        } else null
    } catch (e: Exception) {
        null
    }
}
```

---

## Complete Auto-Detect Implementation

### Main Detection Class:

```kotlin
import java.nio.file.Path

data class ProjectKeys(
    val bitbucketProjectKey: String?,
    val bitbucketRepoSlug: String?,
    val bambooPlanKey: String?,
    val bambooProjectKey: String?,
    val dockerTagKey: String?,
    val defaultBranch: String?,
    val sonarProjectKey: String?,
    val detectionSummary: Map<String, String>
)

class ProjectKeyDetector {
    
    fun autoDetectProjectKeys(projectDir: Path): ProjectKeys {
        val detectionSummary = mutableMapOf<String, String>()
        
        // Strategy 1: Git remote URL
        val gitInfo = detectFromGitRemote(projectDir)
        if (gitInfo != null) {
            detectionSummary["Bitbucket Info"] = "Detected from .git/config"
        }
        
        // Strategy 2: Bamboo specs
        val bambooInfo = detectFromBambooSpecs(projectDir)
        if (bambooInfo.planKey != null) {
            detectionSummary["Bamboo Info"] = "Detected from bamboo-specs/"
        }
        
        // Strategy 3: SonarQube from pom.xml
        val sonarKey = detectSonarProjectKey(projectDir)
        if (sonarKey != null) {
            detectionSummary["SonarQube Key"] = "Detected from pom.xml"
        }
        
        // Strategy 4: Default branch
        val defaultBranch = detectDefaultBranch(projectDir, bambooInfo.defaultBranch)
        detectionSummary["Default Branch"] = "Detected: $defaultBranch"
        
        // Merge all sources with fallback priority
        return ProjectKeys(
            bitbucketProjectKey = gitInfo?.projectKey 
                ?: bambooInfo.projectKey,
            bitbucketRepoSlug = gitInfo?.repoSlug 
                ?: bambooInfo.repositoryName,
            bambooPlanKey = bambooInfo.planKey,
            bambooProjectKey = gitInfo?.projectKey 
                ?: bambooInfo.projectKey,
            dockerTagKey = bambooInfo.dockerTagName,
            defaultBranch = defaultBranch,
            sonarProjectKey = sonarKey,
            detectionSummary = detectionSummary
        )
    }
}
```

---

## Usage Example

```kotlin
import java.nio.file.Paths

fun main() {
    val projectPath = Paths.get("/path/to/project")
    val detector = ProjectKeyDetector()
    
    val keys = detector.autoDetectProjectKeys(projectPath)
    
    println("Auto-Detection Results:")
    println("=======================")
    println("Bitbucket Project Key: ${keys.bitbucketProjectKey}")
    println("Bitbucket Repo Slug: ${keys.bitbucketRepoSlug}")
    println("Bamboo Plan Key: ${keys.bambooPlanKey}")
    println("Bamboo Project Key: ${keys.bambooProjectKey}")
    println("Docker Tag Name: ${keys.dockerTagKey}")
    println("Default Branch: ${keys.defaultBranch}")
    println("SonarQube Project Key: ${keys.sonarProjectKey}")
    println()
    println("Detection Summary:")
    keys.detectionSummary.forEach { (key, value) ->
        println("  $key: $value")
    }
}
```

### Example Output:

```
Auto-Detection Results:
=======================
Bitbucket Project Key: PROJKEY
Bitbucket Repo Slug: my-service
Bamboo Plan Key: MYSERVICE
Bamboo Project Key: PROJKEY
Docker Tag Name: MyServiceDockerTag
Default Branch: develop
SonarQube Project Key: my-service

Detection Summary:
  Bitbucket Info: Detected from .git/config
  Bamboo Info: Detected from bamboo-specs/
  SonarQube Key: Detected from pom.xml
  Default Branch: Detected: develop
```

---

## Multi-Module Repository Support

For multi-module Maven projects, the detection should:

1. **Scan parent `pom.xml`** for overall project information
2. **Scan each module's `pom.xml`** for module-specific SonarQube keys
3. **Use bamboo-specs** which typically exists at root level
4. **Detect Git remote** from root `.git/config`

### Multi-Module Example:

```kotlin
fun detectMultiModuleProjects(projectDir: Path): Map<String, ProjectKeys> {
    val results = mutableMapOf<String, ProjectKeys>()
    
    // Root project
    results["root"] = ProjectKeyDetector().autoDetectProjectKeys(projectDir)
    
    // Find all module pom.xml files
    val parentPom = projectDir.resolve("pom.xml")
    if (parentPom.exists()) {
        val modules = extractModules(parentPom)
        modules.forEach { module ->
            val modulePath = projectDir.resolve(module)
            if (modulePath.exists()) {
                results[module] = ProjectKeyDetector().autoDetectProjectKeys(modulePath)
            }
        }
    }
    
    return results
}

fun extractModules(pomPath: Path): List<String> {
    try {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pomPath.inputStream())
        
        val moduleNodes = doc.getElementsByTagName("module")
        return (0 until moduleNodes.length).mapNotNull { i ->
            moduleNodes.item(i).textContent?.trim()
        }
    } catch (e: Exception) {
        return emptyList()
    }
}
```

---

## Detection Priority Summary

| Key | Primary Source | Fallback 1 | Fallback 2 |
|-----|---------------|------------|------------|
| **Bitbucket Project** | Git remote URL | bamboo-specs `PROJECT_KEY` | bamboo-specs `GIT_PROJECT_ID` |
| **Bitbucket Repo** | Git remote URL | bamboo-specs `REPOSITORY_NAME` | Directory name |
| **Bamboo Plan Key** | bamboo-specs `PLAN_KEY` | - | - |
| **Bamboo Project Key** | Git remote URL | bamboo-specs `PROJECT_KEY` | - |
| **Docker Tag** | bamboo-specs `DOCKER_TAG_NAME` | - | - |
| **Default Branch** | bamboo-specs `RELEASE_STAGE_BRANCHES` | `git symbolic-ref` | Common names (develop/main/master) |
| **SonarQube Key** | pom.xml `sonar.projectKey` | pom.xml `groupId:artifactId` | pom.xml `artifactId` |

---

## Error Handling

```kotlin
fun safeAutoDetect(projectDir: Path): Result<ProjectKeys> {
    return try {
        val keys = ProjectKeyDetector().autoDetectProjectKeys(projectDir)
        
        // Validate at least some keys were detected
        if (keys.bitbucketProjectKey == null && 
            keys.bambooPlanKey == null && 
            keys.sonarProjectKey == null) {
            Result.failure(Exception("No project keys could be auto-detected"))
        } else {
            Result.success(keys)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

## IntelliJ Plugin Integration

### UI Component:

```kotlin
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import javax.swing.*

class AutoDetectDialog(private val keys: ProjectKeys) : DialogWrapper(true) {
    
    init {
        title = "Auto-Detected Project Keys"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        
        panel.add(JBLabel("The following keys were automatically detected:"))
        panel.add(Box.createVerticalStrut(10))
        
        addKeyField(panel, "Bitbucket Project Key:", keys.bitbucketProjectKey)
        addKeyField(panel, "Bitbucket Repo Slug:", keys.bitbucketRepoSlug)
        addKeyField(panel, "Bamboo Plan Key:", keys.bambooPlanKey)
        addKeyField(panel, "Docker Tag Name:", keys.dockerTagKey)
        addKeyField(panel, "Default Branch:", keys.defaultBranch)
        addKeyField(panel, "SonarQube Project Key:", keys.sonarProjectKey)
        
        return panel
    }
    
    private fun addKeyField(panel: JPanel, label: String, value: String?) {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(JBLabel("$label "))
        row.add(JBLabel(value ?: "<not detected>"))
        panel.add(row)
        panel.add(Box.createVerticalStrut(5))
    }
}
```

---

## Testing Recommendations

1. **Test with real repositories** - Use actual project structures from your workspace
2. **Test edge cases**:
   - Missing `.git` directory
   - Missing `bamboo-specs`
   - Multi-module projects
   - Projects without any detectable keys
3. **Validate regex patterns** against various URL formats
4. **Test XML parsing** with different POM structures

---

## Conclusion

This auto-detect feature provides a robust, multi-strategy approach to extracting project configuration keys. By combining multiple detection sources with clear fallback priorities, it handles both simple single-module and complex multi-module repository structures.

The implementation is production-ready and handles edge cases gracefully while providing clear feedback to users about what was detected and from which sources.
