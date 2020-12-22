import re
import requests
import json
from time import sleep

from google.api_core.exceptions import FailedPrecondition
from google.cloud import storage

PIPELINE = "GDCWholeGenomeSomaticSingleSample"

OUTPUT_EXTENSIONS = {".bai"}


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


def make_output_json_blob(event: dict, bucket: storage.Bucket):
    # Returns a blob of `gs://{PIPELINE}/{WORKFLOW UUID}/output.json`
    return bucket.blob('/'.join([*(event['name'].split('/', 2)[:2]), 'output.json']))


def add_output_to_aggregate(event: dict, bucket: storage.Bucket):
    aggregation_blob = make_output_json_blob(event, bucket)
    output_path = f'gs://{bucket.name}/{event["name"]}'
    extension = event['name'].split(".")[-1]

    retries = len(OUTPUT_EXTENSIONS)
    while retries > 0:
        exists = aggregation_blob.exists()
        aggregation_json: dict = \
            json.loads(aggregation_blob.download_as_text()) if exists else {}
        if aggregation_json[extension] == output_path:
            print(f'Multiple messages were sent for {output_path}, exiting')
            return

        aggregation_json[extension] = output_path
        try:
            # Matching to our stored generation/metageneration avoids data
            # loss and permits automatic retries of connection failures
            return aggregation_blob.upload_from_string(
                json.dumps(aggregation_json),
                if_generation_match=
                (aggregation_blob.generation if exists else 0),
                if_metageneration_match=
                (aggregation_blob.metageneration if exists else None),
            )
        except FailedPrecondition:
            aggregation_blob.reload()
            retries -= 1

    raise Exception(f'{retries} remaining attempts to update '
                    f'{aggregation_blob.path} with {event["name"]}')


def update_clio(outputs: dict):
    """
    Actually does the talking to Clio.

    :param outputs: A dictionary where each entry of OUTPUT_EXTENSIONS is
    guaranteed to be a key with its value being a `gs://...` path to that
    file. The outputs are guaranteed to all be from the same workflow
    execution and in this bucket.

    :return: None
    """

    # We can really easily change the format of paths in the outputs dict
    # this function is given!
    # Check out output_path in add_output_to_aggregate

    # get_auth_headers gives us the proper authentication headers to
    # POST to Clio (assuming this cloud function's SA can access Clio)
    # (thanks Saman)

    # If we need to actually read the files for some reason, very easy
    # to pass the bucket into this function
    raise Exception('TODO')


def check_aggregate(event: dict, bucket: storage.Bucket):
    # Many messages notifying of updates to outputs.json will occur in quick
    # succession, so we wait 5 seconds to reasonably check if our message
    # has been superseded.
    sleep(5)
    aggregation_blob = bucket.blob(event['name'])
    try:
        aggregation_json: dict \
            = json.loads(aggregation_blob.download_as_text(
                if_generation_match=event['generation'],
                if_metageneration_match=event['metageneration'],
            ))
    except FailedPrecondition:
        print('Message out of date, exiting')
        return

    if aggregation_json.keys() != OUTPUT_EXTENSIONS:
        print(f'{aggregation_blob.path} still missing '
              f'{aggregation_json.keys() - OUTPUT_EXTENSIONS}, exiting')
        return

    return update_clio(aggregation_json)


# An association of str (to be used as a pattern) to some
# function(event: dict, bucket: storage.Bucket) to be called in case of a
# match. Searched in order; only the first match's function is called.
FILE_HANDLERS = {
    r'\.bai$': add_output_to_aggregate,
    r'output\.json$': check_aggregate,
}


def sg_clio(event, _):
    """Background Cloud Function to be triggered by Cloud Storage.
    Updates Clio with outputs of the Somatic Genomes workflow.

    Args:
        event (dict):  The dictionary with data specific to this type of event.
                       The `data` field contains a description of the event in
                       the Cloud Storage `object` format described here:
                       https://cloud.google.com/storage/docs/json_api/v1/objects#resource
        _ (google.cloud.functions.Context): Metadata of triggering event.
    """

    if not event['name'].startswith(PIPELINE):
        return

    for pattern, handler in FILE_HANDLERS:
        if re.search(pattern, event['name']) is not None:
            return handler(event, storage.Client().bucket(event['bucket']))
