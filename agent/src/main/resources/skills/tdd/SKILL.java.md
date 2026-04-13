## Java/Kotlin Testing

### Test Framework
Use JUnit 5 with MockK for Kotlin tests:

```kotlin
@ExtendWith(MockKExtension::class)
class UserServiceTest {
    @MockK private lateinit var repository: UserRepository
    @InjectMockKs private lateinit var service: UserService

    @Test
    fun `creates user with valid input`() {
        every { repository.save(any()) } returns mockUser
        val result = service.createUser(validInput)
        assertEquals(mockUser, result)
        verify(exactly = 1) { repository.save(any()) }
    }
}
```

### Build Commands
- Run tests: `./gradlew :module:test`
- Run single test: `./gradlew :module:test --tests "ClassName.methodName"`
- Run with coverage: `./gradlew :module:test jacocoTestReport`

### Spring Boot Testing
- `@SpringBootTest` — full application context
- `@WebMvcTest` — MVC layer only
- `@DataJpaTest` — JPA repository layer
- `@WithMockUser` — Spring Security mock
