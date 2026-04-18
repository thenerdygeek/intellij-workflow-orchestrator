def test_uses_broken_fixture(broken_fixture):
    """This test will fail with a fixture setup error."""
    pass

def test_collection_error():
    # This test has a syntax error to trigger collection failure
    assert True
