# Aborting a WFL Workload

Aborting a workload is done by aborting individual workflows directly with
Cromwell. 

???+ tip
    This only works with Cromwell! Don't try this script for things running in
    Terra, it won't work.

Here's a script that can help with that:

```bash
# Usage: bash abort.sh QUERY [WFL_URL] [THREADS]
#   QUERY is either like `project=PO-123` or `uuid=1a2b3c4d`
#   WFL_URL is optionally the WFL to abort workflows from
#       Default is the gotc-prod WFL
#   THREADS is optionally the number of threads to use to talk to Cromwell
#       Default is 2

WORKLOAD=$(curl -X GET "${2:-https://gotc-prod-wfl.gotc-prod.broadinstitute.org}/api/v1/workload?$1" \
        -H "Authorization: Bearer $(gcloud auth print-access-token)" \
        -H 'Content-Type: application/json' | jq)

CROMWELL=$(jq -r 'map(.executor) | .[0]' <<< "$WORKLOAD")

jq -r 'map(.workflows)
        | flatten
        | map(select(.status != "Failed" and .status != "Succeeded") | .uuid)
        | .[]' <<< "$WORKLOAD" \
    | xargs -I % -n 1 -P ${3:-2} curl -w "\n" -s -X POST "$CROMWELL/api/workflows/v1/%/abort" \
        -H "Authorization: Bearer $(gcloud auth print-access-token)" \
        -H "Content-Type: application/json"
```

The 'QUERY' part is like you'd pass to [retry.sh](./usage-retry).

You don't necessarily need to query WFL for the workload. As of this writing,
the response from `/start` or `/exec` includes the workflow UUIDs, so if you
stored that response in `WORKLOAD` then you could abort it without having
to query WFL (and trigger WFL's potentially lengthy update process).

