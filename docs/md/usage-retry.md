# Retrying Failures via WFL

WFL remembers enough about submissions to let you quickly resubmit failed
workflows with the same inputs/options as they were originally submitted.

All you need is a query string like you'd pass to the `/workflows` endpoint,
either:

- `uuid=<UUID>` where `<UUID>` is the identifier of the specific workload you'd
like to retry failures from
    - Ex: `uuid=95d536c7-ce3e-4ffc-8c9c-2b9c710d625a`
- `project=<PROJECT>` where `<PROJECT>` is the value of the project field of the
workloads you'd like to retry
    - Ex: `project=PO-29619`

With the below script, WFL will find matching workloads and resubmit any unique
failures of individual workflows in a new workload (with the same parameters as
the originals).

Usage: `bash retry.sh QUERY`

Ex: `bash retry.sh project=PO-29619`

```bash
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

removeSucceeded() {
    # [[Workflow]] -> [Workflow]
    jq 'flatten
        | group_by( {inputs: .inputs, options: .options} )
        | map( select( all(.status=="Succeeded") )
             | .[0]
             | {inputs: .inputs, options: .options} )
        | del(.[][] | nulls)
        ' <<< "$1"
}

makeRetryRequest() {
    # [Workload], [Workflow] -> Request
    jq --argjson 'workflows' "$2" \
        '.[0]
         | { executor: .executor
           , input: .input
           , output: .output
           , pipeline: .pipeline
           , project: .project
           , items: $workflows
           }
        | del(.[] | nulls)
        ' <<< "$1"
}

mapjq () {
    jq -c '.[]' <<< "${2}" \
    | while read elem; do ${1} "${elem}"; done \
    | jq -s '[ .[] ]'
}

main() {
    # Query -> ()
    workloads=$(getWorkloads "${1}")
    workflows=$(mapjq getWorkflows "${workloads}")
    toSubmit=$(removeSucceeded "${workflows}")
    makeRetryRequest "${workloads[0]}" "${toSubmit}" > /tmp/retry.json

    curl -X POST "${WFL_URL}/api/v1/exec" \
         -H "${AUTH_HEADER}" \
         -H "Content-Type: application/json" \
         -d @/tmp/retry.json
}

main "$1"
```

## Tips

### Customizing Inputs/Options

If you want to inject a new input or option into all of the retried workflows,
you can do that with a `common` block. For example, replace this:

```
jq '{
    executor: .executor,
```

with this:

```
jq '{
    common: { inputs: { "WholeGenomeReprocessing.WholeGenomeGermlineSingleSample.BamToCram.ValidateCram.memory_multiplier": 2 } },
    executor: .executor,
```

That example uses WFL's arbitrary input feature to bump up the memory multiplier
for a particular WGS task.

- Nested inputs will have periods in them, you'll need to use quotes around it
- You can't override inputs or options that the workflows originally had (the
`common` block has lower precedence)
