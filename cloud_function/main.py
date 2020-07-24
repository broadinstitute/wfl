import os
import json
import requests
from google.cloud import storage
from google.cloud import exceptions

project = os.environ.get("GCP_PROJECT", "broad-gotc-dev")
wfl_url = os.environ.get("WFL_URL", "https://workflow-launcher.gotc-dev.broadinstitute.org")
default_credentials = os.environ.get("DEFAULT_CREDENTIALS", False)


def get_test_credentials():
    import google.auth
    import google.auth.transport.requests

    scopes = ["https://www.googleapis.com/auth/cloud-platform",
              "https://www.googleapis.com/auth/userinfo.email",
              "https://www.googleapis.com/auth/userinfo.profile"]
    credentials, gcp_project = google.auth.default(scopes=scopes)
    if not credentials.valid:
        credentials.refresh(google.auth.transport.requests.Request())
    return credentials

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
    parts = object_name.split("/")[:-1]
    parts.append("ptc.json")
    return "/".join(parts)

def get_or_create_workload(headers, cromwell_url):
    payload = {
        "creator": "wfl-non-prod@broad-gotc-dev.iam.gserviceaccount.com",
        "cromwell": cromwell_url,
        "input": "aou-inputs-placeholder",
        "output": "aou-ouputs-placeholder",
        "pipeline": "AllOfUsArrays",
        "project": "gotc-dev",
        "items": [{}]
    }
    response = requests.post(
        url=f"{wfl_url}/api/v1/exec",
        headers=headers,
        json=payload
    )
    return response.json().get('uuid')

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

    if default_credentials:
        headers = get_auth_headers()
        client = storage.Client()
    else:
        credentials = get_test_credentials()
        headers = {
            'Authorization': 'Bearer {}'.format(credentials.token)
        }
        client = storage.Client(credentials=credentials, project=project)

    # Get sample manifest/metadata file
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
    input_files = set([f for f in input_values if str(f).startswith("gs://")])

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
        sample_alias = notification.get('sample_alias')
        print(f"Sample upload completed for {sample_alias}")
        cromwell_url = input_data.get('cromwell')
        workload_uuid = get_or_create_workload(headers, cromwell_url)
        input_data['uuid'] = workload_uuid
        print(f"Updating workload {workload_uuid}")
        response = requests.post(
            url=f"{wfl_url}/api/v1/append_to_aou",
            headers=headers,
            json=input_data
        )
        workflows = response.json()
        workflow_ids = [each['uuid'] for each in workflows]
        print(f"Started cromwell workflows: {workflow_ids}")
