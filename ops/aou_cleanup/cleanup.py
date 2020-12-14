""" This script cleans up AoU input files by moving them from the input bucket to a "trash" bucket
that has a lifecycle policy for deletion. The following files are excluded from this operation:
1) Inputs for the latest analysis version of every chip well barcode
2) Inputs being used by an AoU workflow that is still running
3) Inputs that are listed in keep_files.json """

import argparse
from collections import defaultdict
import json
import requests
from google.cloud import storage
from google.oauth2 import service_account
import google.auth.transport.requests

CROMWELL_SCOPES = ['email', 'openid', 'profile']
STORAGE_SCOPES = ['https://www.googleapis.com/auth/devstorage.full_control',
                  'https://www.googleapis.com/auth/devstorage.read_only',
                  'https://www.googleapis.com/auth/devstorage.read_write']


def get_credentials(service_account_key_path, scopes):
    """Get Google OAuth Credentials given the path to a JSON service account key file"""
    credentials = service_account.Credentials.from_service_account_file(
        service_account_key_path, scopes=scopes
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
    latest_analysis_inputs = []
    for chip_name, chip_well_barcode in files.items():
        for barcode, versions in chip_well_barcode.items():
            latest_version = sorted(versions.keys(), key=lambda x: int(x))[-1]
            latest_analysis_inputs.extend(versions[latest_version])
    return latest_analysis_inputs

def check_active_workflows(cromwell_url, service_account_key_path):
    """Query Cromwell for submitted or running Arrays workflows."""
    query_url = f'{cromwell_url}/api/workflows/v1/query'
    params = [{"name": "Arrays"}, {"status": "Submitted"}, {"status": "Running"},
              {"additionalQueryResultFields": "labels"}]
    credentials = get_credentials(service_account_key_path, scopes=CROMWELL_SCOPES)
    headers = {'Authorization': f'Bearer {credentials.token}'}
    workflows = requests.post(query_url, headers=headers, json=params)
    return workflows.json().get('results')

def get_active_analysis_inputs(files, cromwell_url, service_account_key_path):
    """Get input files that are currently in use by an Arrays workflow in Cromwell."""
    active_files = []
    active_workflows = check_active_workflows(cromwell_url, service_account_key_path)
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

def get_files_to_keep(env):
    with open("keep_files.json") as f:
        files = json.load(f)
    return files.get(env, [])

def main(env, service_account_key_path, apply=False):
    if env == "prod":
        cromwell_url = "https://cromwell-aou.gotc-prod.broadinstitute.org"
        google_project = "broad-aou-storage"
        bucket_name = "broad-aou-arrays-input"
        cleanup_bucket = "broad-aou-arrays-trash"
    else:
        cromwell_url = "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org"
        google_project = "broad-gotc-dev-storage"
        bucket_name = "dev-aou-arrays-input"
        cleanup_bucket = "dev-aou-arrays-trash"

    credentials = get_credentials(service_account_key_path, scopes=STORAGE_SCOPES)
    client = storage.Client(project=google_project, credentials=credentials)
    # Only get files in one "environment" sub-directory because
    # dev and staging can include the same samples
    file_blobs = client.list_blobs(bucket_name, prefix=env)
    file_names = []
    files = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    for blob in file_blobs:
        mercury_env, chip_name, chip_well_barcode, analysis_version_number, file_name = blob.name.split("/", maxsplit=4)
        files[chip_name][chip_well_barcode][analysis_version_number].append(blob.name)
        file_names.append(blob.name)

    keep_files = get_files_to_keep(env)
    latest_analysis_files = get_latest_analysis_inputs(files)
    active_files = get_active_analysis_inputs(files, cromwell_url, service_account_key_path)
    keep_files.extend(latest_analysis_files)
    keep_files.extend(active_files)

    move_files = [f for f in file_names if f not in set(keep_files)]
    print(f"The following files will be moved to {cleanup_bucket} and deleted after 30 days:")
    for file in move_files:
        print(file)
        if apply:
            move_blob(client, bucket_name, file, cleanup_bucket)

    if active_files:
        print(f"The following files are currently in use by Cromwell and will NOT be deleted: {active_files}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Clean up outdated AOU input files.",
                                     usage="%(prog)s [-h] [ENV SERVICE_ACCOUNT_KEY_PATH] [...]")
    parser.add_argument("env",
                        metavar="env",
                        choices=["dev", "staging", "prod"],
                        help="Which environment's input files to clean up. Options: [%(choices)s]")
    parser.add_argument("service_account_key_path",
                        help="A service account with access to the buckets and to Cromwell. The staging 'env' shares"
                             "the same input bucket as dev and uses the dev service account.")
    parser.add_argument("--apply",
                        action="store_true",
                        help="Apply the changes.")
    args = parser.parse_args()
    main(args.env, args.service_account_key_path, args.apply)
