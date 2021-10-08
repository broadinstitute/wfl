# Workflow Launcher Monitoring
Logs from stdout and stderr are sent to Google Logging (Stackdriver) where they can be queried. With the logs, metrics can be created to see developments from those logs over time. From those metrics, we can create alerts that are sent to notification channels of our choosing (slack, email, sms, pubsub, etc.).

To create a metric via command line:
```
gcloud auth login
gcloud config set project PROJECT_ID
gcloud beta logging metrics create MY-METRIC-NAME --description="description goes here" --filter="filter goes here"
```

The log entries for WFL should be located under a container name of `workflow-launcher-api` so logging queries to find said logs should contain `resource.labels.container_name="workflow-launcher-api"`. To look for log severities of error and above, include `severity>=ERROR` in the metric filter as well. You can exclude specific items in the query with the `NOT` keyword (ex: `NOT "INFO: "` excludes messages that contain `"INFO: "`)

An example query for all wfl errors of severity ERROR and above:
```
resource.labels.container_name="workflow-launcher-api"
severity>=ERROR
```

To create an alert via command line:
```
gcloud auth login
gcloud config set project PROJECT_ID
gcloud alpha monitoring policies create --policy-from-file="path/to/file"
```

Example policies can be found here: https://cloud.google.com/monitoring/alerts/policies-in-json

When a metric goes over the threshold set by the policy, an alert is sent via the notification channels provided in the configuration. An incident is created in google cloud monitoring under alerts. These incidents will resolve themselves once the time series shows the metric condition of the alert going back under the configured threshold.
