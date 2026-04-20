## Java/Spring Investigation Tools

### Spring Boot Diagnostics
```
spring(action="context")              # Bean graph — find wiring issues
endpoints(action="list")              # All HTTP endpoints (Ultimate/Pro/WebStorm)
spring(action="endpoints")            # Spring-only, used as fallback on IntelliJ Community
spring(action="boot_autoconfig")      # Auto-configuration decisions
spring(action="config_properties")    # Bound configuration
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
