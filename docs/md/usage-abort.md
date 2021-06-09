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

# Usage: bash abort.sh QUERY [WFL_URL]
#   QUERY is either like `project=PO-123` or `uuid=1a2b3c4d`
#   WFL_URL is the WFL instance to abort workflows from [default: gotc-prod]

WFL_URL="${2:-https://gotc-prod-wfl.gotc-prod.broadinstitute.org}"
AUTH_HEADER="Authorization: Bearer $(gcloud auth print-access-token)"

getWorkloads () {
    # Query -> [Workload]
    curl -s -X GET "${WFL_URL}/api/v1/workload?$1" \
         -H "${AUTH_HEADER}" \
         | jq
}

getWorkflows() {
    # Workload -> [Workflow]
    uuid=$(jq -r .uuid <<< "$1")
    curl -s -X GET "${WFL_URL}/api/v1/workload/${uuid}/workflows" \
         -H "${AUTH_HEADER}" \
         | jq
}

mapjq () {
    jq -c '.[]' <<< "${2}" \
    | while read elem; do ${1} "${elem}"; done \
    | jq '[ .[] ]'
}

main() {
    # Query -> ()
    workloads=$(getWorkloads "${1}")
    cromwell=$(jq -r 'map(.executor) | .[0]' <<< "$WORKLOAD")

    mapjq getWorkflows "${workloads}"
        | jq -s 'flatten
                | map(select(.status != "Failed" and .status != "Succeeded") | .uuid)
                | .[]' \
        | xargs -I % -n 1 -P ${3:-2} curl -w "\n" -s -X POST "$CROMWELL/api/workflows/v1/%/abort" \
                -H "${AUTH_HEADER}" \
                -H "Content-Type: application/json"
}

main "$1"
```

The 'QUERY' part is like you'd pass to [retry.sh](./usage-retry).

You don't necessarily need to query WFL for the workload. As of this writing,
the response from `/start` or `/exec` includes the workflow UUIDs, so if you
stored that response in `WORKLOAD` then you could abort it without having
to query WFL (and trigger WFL's potentially lengthy update process).

