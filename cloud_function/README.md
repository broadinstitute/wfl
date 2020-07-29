Overview
--------
When the push to cloud service uploads arrays data to a GCS bucket, it will trigger execution of this cloud function.
The function checks the parent directory of the uploaded file `"gs://{bucket}/{chipwell_barcode}/{analysis_version}/"`
for a manifest file named `ptc.json`. If all of the files listed in the manifest have been uploaded, a request is sent 
to the WFL API to start processing this sample.


Deployment
---------
Run `bash deploy.sh $GCLOUD_PROJECT> $TRIGGER_BUCKET`


Testing
-------
1) Create a virtual python3 environment
2) Install requirements with `pip install -r dev-requirements.txt`
3) Run the unit tests:
```
WFL_URL="https://workflow-launcher.gotc-dev.broadinstitute.org"
WFL_URL=${WFL_URL} pytest tests/unit_tests.py
```