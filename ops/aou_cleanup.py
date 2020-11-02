# Get list of files at the input_bucket/prefix path (e.g. dev-aou-arrays-input/HumanExome-12v1-1_A)
# Remove latest analysis versions
# Remove files being used by any running aou workflows
# Move remaining files to a "trash" bucket that has a lifecycle policy for deletion

import argparse
from collections import defaultdict
import requests
from google.cloud import storage
from google.oauth2 import service_account
import google.auth.transport.requests

def get_bearer_token(service_account_key):
    """Get Google OAuth Credentials given the path to a JSON service account key file"""
    scopes = ['email', 'openid', 'profile']
    credentials = service_account.Credentials.from_service_account_file(
        service_account_key, scopes=scopes
    )
    if not credentials.valid:
        credentials.refresh(google.auth.transport.requests.Request())
    return credentials

def move_blob(client, bucket_name, blob_name, destination_bucket_name):
    """Copy a blob from bucket_name to destination_bucket_name, and delete it from bucket_name.
    https://cloud.google.com/storage/docs/copying-renaming-moving-objects#copy"""
    source_bucket = client.bucket(bucket_name)
    source_blob = source_bucket.blob(blob_name)
    destination_bucket = client.bucket(destination_bucket_name)
    blob_copy = source_bucket.copy_blob(source_blob, destination_bucket)
    source_blob.delete()

def get_latest_analysis_inputs(files):
    """Get input files for the latest analysis version number of each chip_well_barcode."""
    lastest_analysis_inputs = []
    for chip_name, chip_well_barcode in files.items():
        for barcode, versions in chip_well_barcode.items():
            latest_version = sorted(versions.keys(), key=lambda x: int(x))[-1]
            lastest_analysis_inputs.extend(versions[latest_version])
    return lastest_analysis_inputs

def check_active_workflows(cromwell_url, service_account_key):
    """Query Cromwell for submitted or running Arrays workflows."""
    query_url = f'{cromwell_url}/api/workflows/v1/query?name=Arrays&status=Submitted&status=Running&additionalQueryResultFields=labels'
    credentials = get_bearer_token(service_account_key)
    headers = {'Authorization': f'Bearer {credentials.token}'}
    workflows = requests.get(query_url, headers=headers)
    return workflows.json().get('results')

def get_active_analysis_inputs(files, cromwell_url, service_account_key="/etc/gotc/dev/wfl-non-prod.json"):
    """Get input files that are currently in use by an Arrays workflow in Cromwell."""
    active_files = []
    active_workflows = check_active_workflows(cromwell_url, service_account_key)
    for wf in active_workflows:
        labels = wf.get('labels')
        if labels:
            wf_chip_well_barcode = labels.get('chip_well_barcode')
            wf_analysis_version_number = labels.get('analysis_version_number')
            for chip_name, chip_well_barcode in files.items():
                if chip_well_barcode[wf_chip_well_barcode][wf_analysis_version_number]:
                  _files = chip_well_barcode[wf_chip_well_barcode][wf_analysis_version_number]
                  active_files.extend(_files)
    return active_files

def main(env, service_account_key_path=None, prefix=None, dry_run=True):
    if env == "prod":
        cromwell_url = "https://cromwell-aou.gotc-prod.broadinstitute.org"
        bucket_name = "broad-aou-arrays-input"
        cleanup_bucket = "aou-arrays-trash"
    else:
        cromwell_url = "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
        bucket_name = "dev-aou-arrays-input"
        cleanup_bucket = "dev-aou-arrays-trash"

    #credentials = get_bearer_token(service_account_key_path)
    client = storage.Client()
    file_blobs = client.list_blobs(bucket_name, prefix=prefix)
    file_names = []
    files = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    for blob in file_blobs:
        chip_name, chip_well_barcode, analysis_version_number, file_name = blob.name.split("/", maxsplit=3)
        files[chip_name][chip_well_barcode][analysis_version_number].append(blob.name)
        file_names.append(blob.name)

    keep_files = get_latest_analysis_inputs(files)
    active_files = get_active_analysis_inputs(files, cromwell_url, "/etc/gotc/dev/wfl-non-prod.json")
    keep_files.extend(active_files)

    move_files = [f for f in file_names if f not in set(keep_files)]
    print(f"The following files will be moved to {cleanup_bucket} and deleted after 30 days:")
    for file in move_files:
        print(file)
        if not dry_run:
            move_blob(client, bucket_name, file, cleanup_bucket)

    print(f"The following files are currently in use and will NOT be deleted: {active_files}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", default="dev")
    # parser.add_argument("--prefix") #e.g. "HumanExome-12v1-1_A"
    # parser.add_argument("--service_account_key_path")
    parser.add_argument("--dry_run", default=True)
    args = parser.parse_args()
    main(args.env)
