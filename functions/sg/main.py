import os
import requests

WFL_URL = os.environ.get('WFL_URL')
CROMWELL_URL = os.environ.get('CROMWELL_URL')
WORKLOAD_PROJECT = os.environ.get('WORKLOAD_PROJECT')
OUTPUT_BUCKET = os.environ.get('OUTPUT_BUCKET')

assert WFL_URL is not None, 'WFL_URL is not set'
assert CROMWELL_URL is not None, 'CROMWELL_URL is not set'
assert WORKLOAD_PROJECT is not None, 'WORKLOAD_PROJECT is not set'
assert OUTPUT_BUCKET is not None, 'OUTPUT_BUCKET is not set'


def get_auth_headers():
    scopes = ','.join([
        'https://www.googleapis.com/auth/cloud-platform',
        'https://www.googleapis.com/auth/userinfo.email',
        'https://www.googleapis.com/auth/userinfo.profile'
    ])
    metadata_url = (
        'http://metadata.google.internal/computeMetadata/v1/'
        f'instance/service-accounts/default/token?scopes={scopes}'
    )
    metadata_headers = {'Metadata-Flavor': 'Google'}
    r = requests.get(metadata_url, headers=metadata_headers)
    r.raise_for_status()
    access_token = r.json()['access_token']
    headers = {
        'Authorization': 'Bearer {}'.format(access_token)
    }
    return headers


def make_payload(inputs):
    return {
        'cromwell': CROMWELL_URL,
        'output': OUTPUT_BUCKET,
        'pipeline': 'GDCWholeGenomeSomaticSingleSample',
        'project': WORKLOAD_PROJECT,
        'items': [
            {
                'inputs': inputs
            }
        ]
    }


def describe_workload(workload):
    workflows = [w["uuid"] for w in workload["workflows"]]
    print(f'Started workload {workload["uuid"]} with workflows {workflows}')
    return workflows


def post_payload(headers, payload):
    try:
        response = requests.post(
            url=f'{WFL_URL}/api/v1/exec',
            headers=headers,
            json=payload
        )
        response.raise_for_status()
        workload = response.json()
        return describe_workload(workload)
    except Exception as e:
        print(f'The failed payload: {payload}')
        try:
            # noinspection PyUnboundLocalVariable
            print(f'The failed response: {response.text}')
        except UnboundLocalError:
            print('No response text available')
        raise e


def submit_sg_workload(event, _):
    """Background Cloud Function to be triggered by Cloud Storage.

    Args:
        event (dict):  The dictionary with data specific to this type of event.
                       The `data` field contains a description of the event in
                       the Cloud Storage `object` format described here:
                       https://cloud.google.com/storage/docs/json_api/v1/objects#resource
        _ (google.cloud.functions.Context): Metadata of triggering event.
    """
    headers = get_auth_headers()

    input_file = f"gs://{event['bucket']}/{event['name']}"

    if input_file.endswith('.bam'):
        print(f'Submitting {input_file}')
        inputs = {'ubam': input_file}
        return post_payload(headers, make_payload(inputs))
