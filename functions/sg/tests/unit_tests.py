import os
import mock
import json
import pytest
from sg import main


def test_make_payload():
    inputs = {'foo': 'bar'}
    assert main.make_payload(inputs) == {
        'cromwell': os.environ.get('CROMWELL_URL'),
        'output': os.environ.get('OUTPUT_BUCKET'),
        'pipeline': 'GDCWholeGenomeSomaticSingleSample',
        'project': os.environ.get('WORKLOAD_PROJECT'),
        'items': [
            {
                'inputs': inputs
            }
        ]
    }


def test_describe_workload():
    workload_one = {
        'uuid': 'foo',
        'workflows': [
            {'uuid': 'bar'}
        ]
    }
    assert main.describe_workload(workload_one) == ['bar']

    workload_one = {
        'uuid': 'foo',
        'workflows': [
            {'uuid': 'bar'},
            {'uuid': 'baz'}
        ]
    }
    assert main.describe_workload(workload_one) == ['bar', 'baz']


def mocked_requests_post(*args, **kwargs):
    class MockResponse:
        def __init__(self, text, status_code):
            self.text = text
            self.status_code = status_code

        def raise_for_status(self):
            if self.status_code >= 400:
                raise Exception(self.status_code)

        def json(self):
            return json.loads(self.text)

    if 'authorization' not in {k.lower() for k in kwargs['headers']}:
        return MockResponse('<span>Not authorized</span>', 401)
    if (kwargs['url'] or "").endswith('/api/v1/exec'):
        return MockResponse(json.dumps(
            {'uuid': 'foo', 'workflows': [{'uuid': 'uuid1'}]}
        ), 200)
    return MockResponse('<span>Not found</span>', 404)


@mock.patch('sg.main.get_auth_headers')
@mock.patch('requests.post', side_effect=mocked_requests_post)
def test_main(mock_post, mock_get_auth_headers):
    mock_get_auth_headers.return_value = {'Authorization': 'Bearer abcd'}
    assert main.submit_sg_workload(
        {'bucket': 'fake-bucket', 'name': 'something.cram'},
        None
    ) == ['uuid1']

    mock_get_auth_headers.return_value = {}
    with pytest.raises(Exception) as excinfo:
        main.submit_sg_workload(
            {'bucket': 'fake-bucket', 'name': 'something.cram'},
            None
        )
    assert '401' in str(excinfo.value)
