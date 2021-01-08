import mock
from google.cloud import storage, exceptions
from aou import main


bucket_name = "test_bucket"
file_name = "dev/chip_name/chipwell_barcode/analysis_version/arrays/metadata/file.txt"
event_data = {'bucket': bucket_name, 'name': file_name}


def test_get_manifest_path_from_uploaded_file_with_environment_prefix():
    uploaded_file = "dev/chip_name/chipwell_barcode/analysis_version/arrays/metadata/file.txt"
    manifest_file = "dev/chip_name/chipwell_barcode/analysis_version/ptc.json"
    result = main.get_manifest_path(uploaded_file)
    assert result == manifest_file

def test_get_manifest_path_from_uploaded_file():
    uploaded_file = "chip_name/chipwell_barcode/analysis_version/arrays/metadata/file.txt"
    manifest_file = "chip_name/chipwell_barcode/analysis_version/ptc.json"
    result = main.get_manifest_path(uploaded_file)
    assert result == manifest_file

@mock.patch("aou.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("aou.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Blob, 'download_as_string')
@mock.patch("aou.main.get_auth_headers")
def test_manifest_file_not_uploaded(mock_headers, mock_download, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    mock_download.side_effect = exceptions.NotFound('Error')
    main.submit_aou_workload(event_data, None)
    assert not mock_get_workload.called
    assert not mock_update_workload.called

@mock.patch("aou.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("aou.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Bucket, 'get_blob')
@mock.patch.object(storage.Blob, 'download_as_string')
@mock.patch("aou.main.get_auth_headers")
def test_input_file_not_uploaded(mock_headers, mock_download, mock_get_blob, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    mock_download.return_value = '{"notifications": [{"file": "gs://test_bucket/file.txt", "environment": "dev"}]}'
    mock_get_blob.return_value = None
    main.submit_aou_workload(event_data, None)
    assert not mock_get_workload.called
    assert not mock_update_workload.called

@mock.patch("aou.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("aou.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Bucket, 'get_blob')
@mock.patch.object(storage.Blob, 'download_as_string')
@mock.patch("aou.main.get_auth_headers")
def test_wfl_called_when_sample_upload_completes(mock_headers, mock_download, mock_get_blob, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    mock_download.return_value = '{"executor": "http://cromwell.broadinstitute.org", ' \
                                 '"sample_alias": "test_sample", ' \
                                 '"notifications": [{"file": "gs://test_bucket/file.txt", "environment": "dev"}]}'
    mock_get_blob.return_value = "blob"
    main.submit_aou_workload(event_data, None)
    assert mock_get_workload.called
    assert mock_update_workload.called
