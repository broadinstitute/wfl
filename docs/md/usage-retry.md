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
WORKLOAD=$(curl -X GET "https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/workload?$1" \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' | jq)

jq '{
    executor: .[0].executor,
    output: .[0].output,
    input: .[0].input,
    pipeline: .[0].pipeline,
    project: .[0].project,
    items: .[].workflows
        | group_by({inputs: .inputs, options: .options}) 
        | map(select(all(.status=="Failed")) | .[0]
            | {inputs: .inputs, options: .options})
        | del(.[][] | nulls)
} | del(.[] | nulls)' <<< "$WORKLOAD" > retry.json

curl -X POST "https://gotc-prod-wfl.gotc-prod.broadinstitute.org/api/v1/exec" \
    -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H 'Content-Type: application/json' \
    -d "@retry.json"
```

## Tips

### Customizing Inputs/Options

If you want to inject a new input or option into all of the retried workflows,
you can do that with a `common` block. For example, replace this:

```
jq '{
    executor: .[0].executor,
```

with this:

```
jq '{
    common: { inputs: { "WholeGenomeReprocessing.WholeGenomeGermlineSingleSample.BamToCram.ValidateCram.memory_multiplier": 2 } },
    executor: .[0].executor,
```

That example uses WFL's arbitrary input feature to bump up the memory multiplier
for a particular WGS task.

- Nested inputs will have periods in them, you'll need to use quotes around it
- You can't override inputs or options that the workflows originally had (the
`common` block has lower precedence)


### "Simpler" Retries

The script tries to be a bit intelligent about not making multiple resubmissions
for the same sets of inputs/options. If you don't want that logic for whatever
reason, replace this:

```
    items: .[].workflows
        | group_by({inputs: .inputs, options: .options}) 
        | map(select(all(.status=="Failed")) | .[0]
            | {inputs: .inputs, options: .options})
        | del(.[][] | nulls)
```

with this:

```
    items: .[].workflows
        | map(select(.status=="Failed")
            | {inputs: .inputs, options: .options})
        | del(.[][] | nulls)
```
