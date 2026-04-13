## Python Debugging

### Django Template Debugging
- Set breakpoints in template tags and filters
- Use `debug_inspect(action="evaluate", expression="request.user")` in view context
- Django middleware: step through MIDDLEWARE list in settings.py

### Common Exception Breakpoints
```
debug_breakpoints(action="exception_breakpoint", exception="ValueError")
debug_breakpoints(action="exception_breakpoint", exception="KeyError")
debug_breakpoints(action="exception_breakpoint", exception="AttributeError")
debug_breakpoints(action="exception_breakpoint", exception="django.core.exceptions.ValidationError")
```

### Remote Debugging
- PyCharm remote interpreter via SSH
- Docker Compose: `PYTHONDONTWRITEBYTECODE=1` + mapped volumes
- debugpy: `python -m debugpy --listen 0.0.0.0:5678 --wait-for-client manage.py runserver`
