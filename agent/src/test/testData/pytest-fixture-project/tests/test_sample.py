"""Pytest fixture project for Phase 4 native runner manual testing.

Usage: In a PyCharm runIde, open this project and invoke:
  python_runtime_exec run_tests  (happy path)
  python_runtime_exec run_tests class_name=tests/test_sample.py::test_raises_error  (collection error)
  python_runtime_exec run_tests class_name=tests/test_conftest.py::test_uses_broken_fixture  (fixture error)
"""

def test_passing(sample_value):
    assert sample_value == 42

def test_simple():
    assert 1 + 1 == 2

def test_another():
    assert "hello".upper() == "HELLO"
