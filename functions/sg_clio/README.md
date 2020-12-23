# sg_update_clio

A cloud function designed to inform Clio of new Somatic Genome workflow
completions.

## Deployment
Just like `sg_submit_workload`.

```bash
bash deploy.sh bucket-name-here
```

## Tests
Just like other cloud functions:

```bash
# From repo root
make functions/sg_clio TARGET=check
```

## Implementation
There's two distinct phases of this cloud function operating.

### Aggregating to a single call
`google.storage.object.finalize` events trigger cloud functions **at least
once** each time a file in the target bucket is created or modified.

Such an event makes no guarantee about any other file in the bucket, and outputs
from the WDL are copied in no particular order. Events may be delivered out of
order, simultaneously, or hours apart.

To work with Clio, we'd like the following to be guaranteed:
1. A single Python function call,
2. After all outputs are in place,
3. Given the paths of all outputs

> For AoU, we solved the last two points by having PTC upload an aggregate `ptc.
json` file. WFL's AoU module is resistant to the multiple WFL calls that result
from not addressing the first point.

Here, the cloud functions work together to construct an `output.json` file in
each workflow's folder. Edits to that file also trigger cloud function
invocations, and once the file has all outputs those subsequent invocations will
contact Clio.

![Normal Case Flow](./docs/SG%20Clio%20Update%20Function.svg)

> Writing output file paths into the scratch file is guarded by checking 
> [generation numbers](https://cloud.google.com/storage/docs/generations-preconditions#_Generations),
> so if an invocation loses the race to edit the file first, it will read it
> down and try again.
> 
> Number of retries is bounded to the number of file we expect the WDL to
> output, which is the same as the number of writes that will occur to the
> scratch file.

This accomplishes points two and three discussed above, where Clio is contacted 
with all outputs only once they are in place.

However, finalize events may be sent more than once. We have seen this happen
with AoU (see [GH-1084](https://broadinstitute.atlassian.net/browse/GH-1084) and
[GH-1071](https://broadinstitute.atlassian.net/browse/GH-1071) for bugs found
due to this behavior).

We'd like to avoid finding numerous bugs in Clio, so we use the bucket's state
to eliminate duplicate cloud function invocations.

![Duplicate Output Case Flow](./docs/SG%20Clio%20Update%20Function%20Duplicate%20Output.svg)

Above, an invocation looses the race to update the file but notices that another
invocation already did its job for it. It exits immediately without effect.

![Duplicate Scratch Case Flow](./docs/SG%20Clio%20Update%20Function%20Duplicate%20Scratch.svg)

Above, two invocations both read the scratch `output.json` file when it is ready
to be sent to Clio.

Before sending to Clio, each invocation will attempt to update the **metadata**
of the file, adding a custom key and value. This changes the
[metageneration number](https://cloud.google.com/storage/docs/generations-preconditions#_Generations) 
but not the generation number. When the "loosing" invocation attempts to add
the key, it finds its metageneration number out of date, and exits without
effect. 

Of course, if a duplicate invocation on the scratch file detects it is not
complete or detects that it has already been claimed by another invocation, it
exits without effect.

### Updating Clio
TODO...