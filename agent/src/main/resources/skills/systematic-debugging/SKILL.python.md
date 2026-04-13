## Python Investigation Tools

### Django/FastAPI Diagnostics
- Django: `python manage.py check --deploy` for production readiness
- Django: `python manage.py showmigrations` for migration status
- FastAPI: Check OpenAPI docs at `/docs` endpoint

### Common Python Failures
- **ImportError/ModuleNotFoundError** — virtual env not activated, missing dependency
- **Django ORM errors** — check migrations, model field types, database schema
- **FastAPI validation errors** — Pydantic model mismatches, request body schema
- **Circular imports** — restructure imports, use TYPE_CHECKING guard

### Build System Debugging
- Dependency conflicts: `pip list --outdated`, `poetry show --tree`
- Virtual env issues: `python -c "import sys; print(sys.prefix)"`
- Package resolution: `pip install --dry-run package_name`
