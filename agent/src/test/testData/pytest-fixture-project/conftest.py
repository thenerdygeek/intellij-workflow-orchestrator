import pytest

@pytest.fixture
def sample_value():
    return 42

@pytest.fixture
def broken_fixture():
    raise RuntimeError("test setup failed: broken_fixture is intentionally broken")
