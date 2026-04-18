## Python Build Commands

### pytest
- All tests: `pytest tests/ -v`
- Single file: `pytest tests/test_users.py -v`
- Single test: `pytest tests/test_users.py::test_create_user -v`
- With coverage: `pytest --cov=myapp --cov-report=term-missing`

### Package Management
- pip: `pip install -e ".[dev]"`, `pip freeze > requirements.txt`
- Poetry: `poetry install`, `poetry add package`, `poetry run pytest`
- uv: `uv pip install -e ".[dev]"`, `uv run pytest`

### Django
- Migrations: `python manage.py makemigrations && python manage.py migrate`
- Check: `python manage.py check --deploy`
