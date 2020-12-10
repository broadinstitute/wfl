Overview
--------
TBA

Deployment
---------
Run `bash deploy.sh $GCLOUD_PROJECT> $TRIGGER_BUCKET`


Testing
-------
1) Create a virtual python3 environment
2) Install requirements with `pip install -r dev-requirements.txt`
3) Run the unit tests:
```bash
$ pytest tests/unit_tests.py
```
