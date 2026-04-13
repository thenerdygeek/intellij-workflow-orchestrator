## Java/Kotlin Build Commands

### Gradle
- Build: `./gradlew :module:build`
- Test: `./gradlew :module:test`
- Single test: `./gradlew :module:test --tests "com.example.MyTest"`
- Dependencies: `./gradlew :module:dependencies`
- Clean: `./gradlew clean`

### Maven
- Build: `mvn clean compile`
- Test: `mvn test -pl module`
- Single test: `mvn test -pl module -Dtest=MyTest`
- Dependencies: `mvn dependency:tree`
