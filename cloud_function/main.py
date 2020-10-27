import os
import json
import requests
from google.cloud import storage
from google.cloud import exceptions


WFL_URL = os.environ.get("WFL_URL")
CROMWELL_URL = os.environ.get("CROMWELL_URL")
WFL_ENVIRONMENT = os.environ.get("WFL_ENVIRONMENT")
OUTPUT_BUCKET = os.environ.get("OUTPUT_BUCKET")

assert WFL_URL is not None, "WFL_URL is not set"
assert CROMWELL_URL is not None, "CROMWELL_URL is not set"
assert WFL_ENVIRONMENT is not None, "WFL_ENVIRONMENT is not set"
assert OUTPUT_BUCKET is not None, "OUTPUT_BUCKET is not set"

def get_auth_headers():
    scopes_list = ["https://www.googleapis.com/auth/cloud-platform",
                   "https://www.googleapis.com/auth/userinfo.email",
                   "https://www.googleapis.com/auth/userinfo.profile"]
    scopes = ",".join(scopes_list)
    metadata_url = f'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token?scopes={scopes}'
    metadata_headers = {'Metadata-Flavor': 'Google'}
    r = requests.get(metadata_url, headers=metadata_headers)
    r.raise_for_status()
    access_token = r.json()['access_token']
    headers = {
        'Authorization': 'Bearer {}'.format(access_token)
    }
    return headers

def get_manifest_path(object_name):
    path = object_name.strip("/")
    chip_name, chip_well_barcode, analysis_version, file_name = path.split("/", maxsplit=3)
    return "/".join([chip_name, chip_well_barcode, analysis_version, "ptc.json"])

def get_or_create_workload(headers):
    payload = {
        "cromwell": CROMWELL_URL,
        "output": OUTPUT_BUCKET,
        "pipeline": "AllOfUsArrays",
        "project": WFL_ENVIRONMENT
    }
    response = requests.post(
        url=f"{WFL_URL}/api/v1/exec",
        headers=headers,
        json=payload
    )
    return response.json().get('uuid')

def update_workload(headers, workload_uuid, input_data):
    input_data['uuid'] = workload_uuid
    print(f"Updating workload {workload_uuid}")
    response = requests.post(
        url=f"{WFL_URL}/api/v1/append_to_aou",
        headers=headers,
        json=input_data
    )
    try:
        response.raise_for_status()
    except requests.HTTPError as e:
        print(f"The failed request: {response.request}")
        print(f"The failed response: {response.text}")
        raise e
    workflows = response.json()
    return [each['uuid'] for each in workflows]

def submit_aou_workload(event, context):
    """Background Cloud Function to be triggered by Cloud Storage.
    Args:
         event (dict):  The dictionary with data specific to this type of
         event. The `data` field contains the PubsubMessage message. The
         `attributes` field will contain custom attributes if there are any.
         context (google.cloud.functions.Context): The Cloud Functions event
         metadata. The `event_id` field contains the Pub/Sub message ID. The
         `timestamp` field contains the publish time.
    """
    headers = get_auth_headers()

    # Get sample manifest/metadata file
    client = storage.Client()
    bucket = client.bucket(event['bucket'])
    manifest_path = get_manifest_path(event['name'])
    manifest_blob = bucket.blob(manifest_path)

    try:
        manifest_file_content = manifest_blob.download_as_string()
    except exceptions.NotFound as e:
        print(f'File not found: {manifest_path}')
        return

    # Parse manifest file to get input file paths
    input_data = json.loads(manifest_file_content)
    notification = input_data['notifications'][0]
    input_values = notification.values()
    input_files = set([f for f in input_values if str(f).startswith(f"gs://{bucket.name}")])

    # Check if the input files have been uploaded
    upload_complete = True
    for gs_url in input_files:
        file_blob = storage.Blob.from_string(gs_url)
        file_metadata = bucket.get_blob(file_blob.name)
        if not file_metadata:
            upload_complete = False
            print(f"File not found: {gs_url}")
            return

    if upload_complete:
        chip_well_barcode = notification.get('chip_well_barcode')
        analysis_version = notification.get('analysis_version_number')
        print(f"Completed sample upload for {chip_well_barcode}-{analysis_version}")
        workload_uuid = get_or_create_workload(headers)
        print(f"Updating workload: {workload_uuid}")
        workflow_ids = update_workload(headers, workload_uuid, input_data)
        print(f"Started cromwell workflows: {workflow_ids} for {chip_well_barcode}-{analysis_version}")
