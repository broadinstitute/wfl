import pytest
import os
from sg import main

def test_make_inputs():
    input_bam = "foo.bam"
    assert main.make_inputs(input_bam) == {'ubam': input_bam}

    input_cram = "foo.cram"
    with pytest.raises(Exception) as excinfo:
        main.make_inputs(input_cram)
    assert f'Saw CRAM {input_cram}' in str(excinfo.value)

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

