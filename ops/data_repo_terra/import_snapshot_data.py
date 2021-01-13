import argparse
import csv
import io
import requests
import uuid
import google.auth
import google.auth.transport.requests
from google.cloud import bigquery
from google.oauth2 import service_account


def get_service_account_credentials(service_account_path, scopes):
    credentials = service_account.Credentials.from_service_account_file(service_account_path, scopes=scopes)
    if not credentials.valid:
        credentials.refresh(google.auth.transport.requests.Request())
    return credentials

def get_snapshot_data(google_project, datarepo_snapshot, service_account_path):
    """Query the 'sample' table for a datarepo_snapshot. The service account must
    have at least data custodian permissions on the snapshot in order to
    run a query job."""
    scopes = ['https://www.googleapis.com/auth/bigquery']
    credentials = get_service_account_credentials(service_account_path, scopes)
    client = bigquery.Client(project=google_project, credentials=credentials)
    query = f'SELECT * FROM `{google_project}.{datarepo_snapshot}.sample`'
    query_job = client.query(query)
    return query_job.result()

def format_data_as_tsv(tsv_file, snapshot_rows, data_table):
    """ Write BigQuery results into a TSV file for importing to Terra.
    The first row header must follow the format 'entity:{data_table}_id'.
    For example, 'entity:sample_id' will upload the tsv data into a "sample" table in
    the workspace (or create one if it does not exist). If the table already contains
    a sample with that id, it will get overwritten."""
    entities = []
    with open(tsv_file, 'w') as f:
        headers = None
        writer = csv.writer(f, delimiter='\t')
        for row in snapshot_rows:
            if not headers:
                keys = list(row.keys())
                keys[0] = f'entity:{data_table}_id'
                writer.writerow(keys)
            writer.writerow(row.values())
            entities.append({'entity_name': data_table, "entity_id": row[0]})
    return entities

def upload_to_terra(terra_url, terra_workspace, tsv_file, service_account_path):
    """Upload a TSV file containing sample inputs to a terra workspace. The service
    account must have owner permissions on the workspace."""
    #TODO: Find out what the file size/upload limitations are
    import_url = f'{terra_url}/api/workspaces/{terra_workspace}/flexibleImportEntities'
    scopes = ['email', 'openid', 'profile']
    credentials = get_service_account_credentials(service_account_path, scopes)
    headers = {'Authorization': f'Bearer {credentials.token}'}
    with open (tsv_file, 'rb') as f:
        contents = f.read()
    response = requests.post(import_url,
                             headers=headers,
                             files={'entities': io.BytesIO(contents),
                                    'type': 'text/tab-separated-values'})
    return response

def main(datarepo_snapshot, terra_url, terra_workspace, terra_data_table, service_account_path):
    tsv_file = f'{uuid.uuid4()}_samples.tsv'
    snapshot_rows = get_snapshot_data('broad-jade-dev-data', datarepo_snapshot, service_account_path)
    entities = format_data_as_tsv(tsv_file, snapshot_rows, terra_data_table)
    upload_to_terra(terra_url, terra_workspace, tsv_file, service_account_path)
    print(f'The following samples have been uploaded to {terra_workspace}:')
    for sample in entities:
        print(sample)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Import Data Repo snapshot data into a Terra workspace.",
                                     usage="%(prog)s [-h] [DATAREPO_SNAPSHOT TERRA_URL TERRA_WORKSPACE SERVICE_ACCOUNT_PATH] [...]")
    parser.add_argument("--datarepo_snapshot",
                        default="zerosnapshot",
                        help="The name of the Data Repo snapshot to import.")
    parser.add_argument("--terra_url",
                        default="https://firecloud-orchestration.dsde-dev.broadinstitute.org",
                        help="The Terra API URL.")
    parser.add_argument("--terra_workspace",
                        default="general-dev-billing-account/hornet-test",
                        help="The Terra workspace where the data will be imported. Follows the format {workspaceNamespace}/{workspaceName}")
    parser.add_argument("--terra_data_table",
                        default="datarepo_row",
                        help="The Terra workspace data table that will contain the snapshot data.")
    parser.add_argument("service_account_path",
                        help="A service account with access to both the Data Repo snapshot and the Terra workspace.")
    args = parser.parse_args()
    main(args.datarepo_snapshot, args.terra_url, args.terra_workspace, args.terra_data_table, args.service_account_path)
