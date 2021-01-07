import os
import mock
import json
import pytest
from sg import main


def test_filter_for_input_paths():
    # How files look in production: https://broadinstitute.slack.com/archives/C01G46T8HPC/p1608226117024900
    input_file = 'gs://broad-gotc-prod-storage/pipeline/foo/bar/unmapped/baz.bam'
    # Filtering need not handle extensions, make_inputs handles that
    garbage_input_file = 'gs://broad-gotc-prod-storage/pipeline/foo/bar/unmapped/baz.bam.metrics.something'
    # Files not for us may be uploaded to production bucket
    other_file = 'gs://broad-gotc-prod-storage/blah.txt'

    assert main.filter_for_input_paths(None, input_file) == input_file
    assert main.filter_for_input_paths(None, garbage_input_file) == garbage_input_file
    assert main.filter_for_input_paths(None, other_file) == other_file

    pattern = '^gs://broad-gotc-prod-storage/pipeline/[^/]+/[^/]+/unmapped/'
    assert main.filter_for_input_paths(pattern, input_file) == input_file
    assert main.filter_for_input_paths(pattern, garbage_input_file) == garbage_input_file
    assert main.filter_for_input_paths(pattern, other_file) is None


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
        {'bucket': 'fake-bucket', 'name': 'something.bam'},
        None
    ) == ['uuid1']

    mock_get_auth_headers.return_value = {}
    with pytest.raises(Exception) as excinfo:
        main.submit_sg_workload(
            {'bucket': 'fake-bucket', 'name': 'something.bam'},
            None
        )
    assert '401' in str(excinfo.value)
