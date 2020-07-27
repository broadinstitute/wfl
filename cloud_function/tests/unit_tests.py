import mock
from unittest.mock import MagicMock
from google.cloud import storage, exceptions
from cloud_function import main


bucket_name = "test_bucket"
file_name = "chipwell_barcode/analysis_version/arrays/metadata/file.txt"
event_data = {'bucket': bucket_name, 'name': file_name}


def test_get_manifest_path_from_uploaded_file():
    uploaded_file = "chipwell_barcode/analysis_version/arrays/metadata/file.txt"
    manifest_file = "chipwell_barcode/analysis_version/ptc.json"
    result = main.get_manifest_path(uploaded_file)
    assert result == manifest_file

@mock.patch("cloud_function.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("cloud_function.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Blob, 'download_as_string')
def test_manifest_file_not_uploaded(mock_download, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    client.token = MagicMock(return_value="token")
    mock_download.side_effect = exceptions.NotFound('Error')
    main.submit_aou_workload(event_data, None)
    assert not mock_get_workload.called
    assert not mock_update_workload.called

@mock.patch("cloud_function.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("cloud_function.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Bucket, 'get_blob')
@mock.patch.object(storage.Blob, 'download_as_string')
def test_input_file_not_uploaded(mock_download, mock_get_blob, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    client.token = MagicMock(return_value="token")
    mock_download.return_value = '{"notifications": [{"file": "gs://test_bucket/file.txt"}]}'
    mock_get_blob.return_value = None
    main.submit_aou_workload(event_data, None)
    assert not mock_get_workload.called
    assert not mock_update_workload.called

@mock.patch("cloud_function.main.update_workload", return_value=["workflow_uuid"])
@mock.patch("cloud_function.main.get_or_create_workload", return_value="workload_uuid")
@mock.patch.object(storage.Bucket, 'get_blob')
@mock.patch.object(storage.Blob, 'download_as_string')
def test_wfl_called_when_sample_upload_completes(mock_download, mock_get_blob, mock_get_workload, mock_update_workload):
    client = mock.create_autospec(storage.Client())
    client.token = MagicMock(return_value="token")
    mock_download.return_value = '{"cromwell": "http://cromwell.broadinstitute.org", ' \
                                 '"sample_alias": "test_sample", ' \
                                 '"notifications": [{"file": "gs://test_bucket/file.txt"}]}'
    mock_get_blob.return_value = "blob"
    main.submit_aou_workload(event_data, None)
    assert mock_get_workload.called
    assert mock_update_workload.called
