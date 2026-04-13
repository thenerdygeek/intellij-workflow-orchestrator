## Python Verification

### Verification Commands
After each agent completes, verify with:
```
run_command(command="pytest tests/ -v --tb=short")      # Test suite
diagnostics(path="src/...")                              # Type/syntax check
run_inspections(path="src/...")                          # Code quality
```

### Agent Selection
- **python-engineer** — for Django/FastAPI/Flask development
- **test-automator** — for pytest test generation
