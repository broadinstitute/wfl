import os
import re
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


def test_make_inputs():
    input_bam = "foo.bam"
    assert main.make_inputs(input_bam) == {'ubam': input_bam}

    input_txt = "foo.txt"
    assert main.make_inputs(input_txt) is None


def test_make_payload():
    inputs = {'foo': 'bar'}
    assert main.make_payload(inputs) == {
        'cromwell': os.environ.get('CROMWELL_URL'),
        'output': os.environ.get('OUTPUT_BUCKET'),
        'pipeline': 'GDCWholeGenomeSomaticSingleSample',
        'project': os.environ.get('WFL_ENVIRONMENT'),
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
