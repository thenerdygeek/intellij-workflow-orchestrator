# Phase 5 — Manual Verification Checklist

Run in a `./gradlew runIde` sandbox. Each step targets a Task-4.6 breakpoint-type contract that the unit tests cannot exercise (requires a real JDI session and IntelliJ write-action machinery). Unit coverage in `DebugBreakpointsToolTest.kt` covers validation guards plus `list_breakpoints` type-differentiation via MockK; these happy-path add actions need the IDE.

## Preconditions
- Open a Java project with at least one class (e.g. `com.example.Foo` with a `doWork()` method and a `count` field).
- Agent tab open, chat session ready, Java plugin enabled (IntelliJ IDEA Ultimate or Community).

## Checklist

### 1. Line breakpoint (`add_breakpoint`)
- Agent: `debug_breakpoints` with `action=add_breakpoint`, `file=src/com/example/Foo.java`, `line=10`.
- Expected: IDE gutter shows red dot at line 10. `list_breakpoints` output contains `Foo.java:10 [enabled, suspend-all]`.

### 2. Method breakpoint (`method_breakpoint`)
- Agent: `debug_breakpoints` with `action=method_breakpoint`, `class_name=com.example.Foo`, `method_name=doWork`, `watch_entry=true`.
- Expected: IDE gutter shows method-diamond at the method declaration. `list_breakpoints` output contains `Method: com.example.Foo.doWork [entry, enabled]`.

### 3. Exception breakpoint (`exception_breakpoint`)
- Agent: `debug_breakpoints` with `action=exception_breakpoint`, `exception_class=java.lang.NullPointerException`, `caught=true`, `uncaught=false`.
- Expected: appears in Breakpoints tool window under "Java Exception Breakpoints". `list_breakpoints` output contains `Exception: java.lang.NullPointerException [caught, enabled]`.

### 4. Field watchpoint (`field_watchpoint`)
- Agent: `debug_breakpoints` with `action=field_watchpoint`, `class_name=com.example.Foo`, `field_name=count`, `watch_read=true`, `watch_write=true`.
- Expected: IDE gutter shows watch-eye icon at the field declaration. `list_breakpoints` output contains `Field: com.example.Foo.count [access, modification, enabled]`.

### 5. `list_breakpoints` aggregation
- After steps 1–4, call `debug_breakpoints` with `action=list_breakpoints`.
- Expected: header `Breakpoints (4):` followed by one line per type, each with its distinguishing prefix (`Foo.java:10`, `Method:`, `Exception:`, `Field:`). Type serialization is by string prefix, not by explicit `type` field.

## If any step fails
Report the failing step + IDE log output (`idea.log`) in the commit message. Scope-add a fix in a follow-up commit before merging.
