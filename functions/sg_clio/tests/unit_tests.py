import mock
import json
from random import randrange

from google.api_core.exceptions import FailedPrecondition
from sg_clio import main


class FakeBucket:
    def __init__(
            self,
            name='fake-bucket',
            blobs_exist=True,
            blob_contents='',
            blob_generation=randrange(100),
            blob_metageneration=randrange(100),
            blob_metadata={}
    ):
        self.name = name
        self.blobs_exist = blobs_exist
        self.blob_contents = blob_contents
        self.blob_generation = blob_generation
        self.blob_metageneration = blob_metageneration
        self.blob_metadata = blob_metadata

    def blob(self, path: str):
        return FakeBlob(self, path, self.blob_metadata)


class FakeBlob:
    def __init__(self, bucket: FakeBucket, path: str, metadata: dict):
        self.bucket = bucket
        self.path = path
        self.metadata = metadata
        self.generation = self.bucket.blob_generation if self.bucket.blobs_exist else 0
        self.metageneration = self.bucket.blob_metageneration if self.bucket.blobs_exist else None
        self.reload()

    def exists(self):
        return self.bucket.blobs_exist

    def reload(self):
        self.generation = self.bucket.blob_generation if self.bucket.blobs_exist else 0
        self.metageneration = self.bucket.blob_metageneration if self.bucket.blobs_exist else None

    def __if_match(self, if_generation_match, if_metageneration_match):
        if ((if_generation_match is not None and
             if_generation_match != self.generation) or
                (if_metageneration_match is not None and
                 if_metageneration_match != self.metageneration)):
            raise FailedPrecondition('Failed match')

    def download_as_text(
            self,
            if_generation_match=None,
            if_metageneration_match=None
    ):
        self.__if_match(if_generation_match, if_metageneration_match)
        return self.bucket.blob_contents

    def upload_from_string(
            self,
            contents: str,
            if_generation_match=None,
            if_metageneration_match=None
    ):
        self.__if_match(if_generation_match, if_metageneration_match)
        self.bucket.blobs_exist = True
        self.bucket.blob_contents = contents
        self.bucket.blob_generation = randrange(100)
        self.bucket.blob_metageneration = randrange(100)

    def patch(
            self,
            if_generation_match=None,
            if_metageneration_match=None
    ):
        self.__if_match(if_generation_match, if_metageneration_match)
        self.bucket.blob_metageneration = randrange(100)


def test_make_output_json_blob():
    fake_blob = main.make_output_json_blob(
        {'name': f'{main.PIPELINE}/uuid/task/out.bam'},
        FakeBucket()
    )
    assert fake_blob.path == f'{main.PIPELINE}/uuid/output.json'


def test_add_output_to_aggregate():
    # Simulate making the aggregate when it doesn't exist
    fake_bucket = FakeBucket(blobs_exist=False)
    main.add_output_to_aggregate(
        {'name': f'{main.PIPELINE}/uuid/task/out.bam'},
        fake_bucket
    )
    assert json.loads(fake_bucket.blob_contents) == {
        '.bam': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bam'
    }

    generation = fake_bucket.blob_generation
    metageneration = fake_bucket.blob_metageneration

    # Simulate a duplicate message arriving: generation and metageneration
    # numbers don't change since file is not mutated
    main.add_output_to_aggregate(
        {'name': f'{main.PIPELINE}/uuid/task/out.bam',
         'generation': generation,
         'metageneration': metageneration},
        fake_bucket
    )
    assert json.loads(fake_bucket.blob_contents) == {
        '.bam': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bam'
    }
    assert generation == fake_bucket.blob_generation
    assert metageneration == fake_bucket.blob_metageneration

    # Simulate normally updating the aggregate
    main.add_output_to_aggregate(
        {'name': f'{main.PIPELINE}/uuid/task/out.bai',
         'generation': generation,
         'metageneration': metageneration},
        fake_bucket
    )
    assert json.loads(fake_bucket.blob_contents) == {
        '.bam': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bam',
        '.bai': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bai'
    }

    # Simulate an older message appearing with out-of-date generation
    # and metageneration numbers (same case as another invocation
    # winning the race to edit the file first)
    main.add_output_to_aggregate(
        {'name': f'{main.PIPELINE}/uuid/task/out.md_metrics',
         'generation': generation,
         'metageneration': metageneration},
        fake_bucket
    )
    assert json.loads(fake_bucket.blob_contents) == {
        '.bam': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bam',
        '.bai': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.bai',
        '.md_metrics': f'gs://{fake_bucket.name}/{main.PIPELINE}/uuid/task/out.md_metrics'
    }


@mock.patch('sg_clio.main.post_outputs_to_clio')
def test_check_aggregate(mock_post_outputs_to_clio):
    mock_post_outputs_to_clio.return_value = None

    # Simulate an out-of-date message
    main.check_aggregate(
        {'name': f'{main.PIPELINE}/uuid/output.json',
         'generation': -1,
         'metageneration': -1},
        FakeBucket(),
        disable_sleep=True
    )
    assert mock_post_outputs_to_clio.call_count == 0

    # Simulate a message with an incomplete aggregate
    fake_bucket = FakeBucket(blob_contents='{}')
    main.check_aggregate(
        {'name': f'{main.PIPELINE}/uuid/output.json',
         'generation': fake_bucket.blob_generation,
         'metageneration': fake_bucket.blob_metageneration},
        fake_bucket,
        disable_sleep=True
    )
    assert mock_post_outputs_to_clio.call_count == 0

    # Simulate a complete aggregate
    generation = fake_bucket.blob_generation
    metageneration = fake_bucket.blob_metageneration
    fake_bucket.blob_contents = json.dumps(
        {ext: f'gs://{fake_bucket.name}/foo' for ext in main.OUTPUT_EXTENSIONS}
    )
    main.check_aggregate(
        {'name': f'{main.PIPELINE}/uuid/output.json',
         'generation': generation,
         'metageneration': metageneration},
        fake_bucket,
        disable_sleep=True
    )
    assert mock_post_outputs_to_clio.call_count == 1

    # Simulate a plain but complete duplicate
    main.check_aggregate(
        {'name': f'{main.PIPELINE}/uuid/output.json',
         'generation': generation,
         'metageneration': metageneration},
        fake_bucket,
        disable_sleep=True
    )
    assert mock_post_outputs_to_clio.call_count == 1

    # Simulate a fully valid message after we've sent to Clio,
    # metadata check avoids duplicate update
    main.check_aggregate(
        {'name': f'{main.PIPELINE}/uuid/output.json',
         'generation': fake_bucket.blob_generation,
         'metageneration': fake_bucket.blob_metageneration},
        fake_bucket,
        disable_sleep=True
    )
    assert mock_post_outputs_to_clio.call_count == 1


