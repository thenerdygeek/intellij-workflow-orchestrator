## Java/Spring Investigation Tools

### Spring Boot Diagnostics
```
spring(action="context")              # Bean graph — find wiring issues
spring(action="context", profile="dev") # Beans active under a specific profile
endpoints(action="list")              # All HTTP endpoints (Ultimate/Pro/WebStorm)
spring(action="endpoints")            # Spring-only, used as fallback on IntelliJ Community
spring(action="boot_autoconfig")      # Auto-configuration decisions
spring(action="config_properties")    # Bound configuration
spring(action="annotated_methods", annotation="@Transactional") # Find all transactional methods — trace transaction boundaries
spring(action="annotated_methods", annotation="@Scheduled")     # Find all scheduled tasks — spot overlapping or runaway jobs
spring(action="annotated_methods", annotation="@PreAuthorize")  # Find all authorization-guarded methods — verify coverage
```

### Common Java/Spring Failures
- **BeanCreationException** — check constructor args, missing @Component, circular deps
- **UnsatisfiedDependencyException** — missing bean, ambiguous qualifier
- **ClassNotFoundException** — dependency version mismatch, missing module
- **Testcontainers failures** — Docker not running, port conflicts, image pull timeout

### Build System Debugging
- Gradle debug: `./gradlew myTask -Dorg.gradle.debug=true --no-daemon`
- Maven debug: `mvnDebug clean test`
- Dependency conflicts: `build(action="gradle_dependencies")` or `build(action="maven_dependency_tree")`
