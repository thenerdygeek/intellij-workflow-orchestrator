## Python Testing

### Test Framework
Use pytest with fixtures:

```python
import pytest
from myapp.services import UserService

@pytest.fixture
def user_service(db_session):
    return UserService(db_session)

def test_creates_user_with_valid_input(user_service, valid_user_data):
    user = user_service.create_user(valid_user_data)
    assert user.email == valid_user_data["email"]
    assert user.id is not None

class TestUserService:
    def test_raises_on_duplicate_email(self, user_service, existing_user):
        with pytest.raises(ValueError, match="already exists"):
            user_service.create_user({"email": existing_user.email})
```

### Build Commands
- Run tests: `pytest tests/ -v`
- Run single test: `pytest tests/test_users.py::TestUserService::test_creates_user -v`
- Run with coverage: `pytest --cov=myapp --cov-report=term-missing`
- Run with markers: `pytest -m "not slow"`
