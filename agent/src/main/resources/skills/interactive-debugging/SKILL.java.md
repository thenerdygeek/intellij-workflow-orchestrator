## JVM Debugging

### Spring Boot Debugging
- CGLIB proxy step filters: `org.springframework.cglib.**`, `org.springframework.aop.**`
- `@Transactional` / `@Cacheable` proxied — step through to actual method
- Spring startup exceptions: `BeanCreationException`, `NoSuchBeanDefinitionException`, `UnsatisfiedDependencyException`
- Spring Security: `FilterChainProxy` step filters

### Exception Breakpoints
```
debug_breakpoints(action="exception_breakpoint", exception="java.lang.NullPointerException")
debug_breakpoints(action="exception_breakpoint", exception="java.lang.ClassCastException")
debug_breakpoints(action="exception_breakpoint", exception="org.springframework.beans.factory.BeanCreationException")
```

### Remote Debugging
- Java 11+: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`
- Java 8: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005`
- Testcontainers: `JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`
