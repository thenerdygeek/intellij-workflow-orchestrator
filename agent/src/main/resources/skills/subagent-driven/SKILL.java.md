## Java/Kotlin Verification

### Verification Commands
After each agent completes, verify with:
```
runtime_exec(action="run_tests", module=":module")     # Module-scoped tests
diagnostics(path="src/main/kotlin/...")                 # Compilation check
run_inspections(path="src/main/kotlin/...")             # Code quality
```

### Agent Selection
- **spring-boot-engineer** — for Spring Boot feature development
- **test-automator** — for JUnit 5 / MockK test generation
