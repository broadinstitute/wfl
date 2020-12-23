import os
import mock
import json
import pytest
from sg_clio import main


def test_make_output_json_blob():
    class FakeBucket:
        def __init__(self, name: str):
            self.name = name

        def blob(self, path: str):
            return self.name + '/' + path

    fake_blob = main.make_output_json_blob(
        {'name': f'{main.PIPELINE}/uuid/task/output.bam'},
        FakeBucket('bucket')
    )
    assert fake_blob == f'bucket/{main.PIPELINE}/uuid/output.json'
