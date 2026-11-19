import pytest
from scripts import validation


def test_existing_database_without_its_keys_is_never_rekeyed(monkeypatch,tmp_path):
    instance=tmp_path/'paired'
    instance.mkdir()
    database=instance/'validation.db'
    database.write_bytes(b'existing-database-must-not-be-touched')
    source=tmp_path/'source.env'
    source.write_text('',encoding='utf-8')
    monkeypatch.setattr(validation,'INSTANCE',instance)
    with pytest.raises(ValueError,match='original encryption keys'):
        validation.prepare(source,8120)
    assert database.read_bytes()==b'existing-database-must-not-be-touched'
    assert not (instance/'.env').exists()
