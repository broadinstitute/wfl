# Customizing Workflow Options

???+ tip
    This page covers customizing workflow _options_, which are different from the 
    _inputs_ passed to the WDL. Workflow options are interpreted directly by Cromwell,
    though WDLs can customize them too. For more information see 
    [Cromwell's documentation](https://cromwell.readthedocs.io/en/stable/wf_options/Overview/).
    
???+ tip
    Another important piece of context for this page is the difference between a workflow
    that actually gets run on Cromwell versus a workload (a WFL-managed set of individual
    workflows).
    
## Usage

!!! info "Summary"
    - Workflow options are an arbitrary JSON object stored in a key of `workflow_options`
    - Can be provided either per-workflow or for an entire workload (or both)
    - Optional when you make requests but will always be included in responses

Suppose the following valid workload request that you might `POST` to `/create` or `/exec`:
```json
{
  "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
  "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
  "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
  "pipeline": "ExternalWholeGenomeReprocessing",
  "project": "PO-1234",
  "items": [
    {
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample1234"
      }
    },
    {
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample5678"
      }
    }
  ]
}
```
You may optionally add arbitrary JSON objects as `workflow_options` either for individual
workflows, for the entire workload, or both:
```json
{
  "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org",
  "input": "gs://broad-gotc-dev-wfl-ptc-test-inputs/single_sample/plumbing/truth",
  "output": "gs://broad-gotc-dev-wfl-ptc-test-outputs/wgs-test-output/",
  "pipeline": "ExternalWholeGenomeReprocessing",
  "project": "PO-1234",
  "items": [
    {
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample1234"
      },
      "workflow_options": {
        "my_option": "something for sample 1234"      
      }
    },
    {
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample5678"
      },
      "workflow_options": {
        "my_option": "something different for sample 5678",
        "another_option": {"foo": "bar"}      
      }
    }
  ],
  "workflow_options": {
    "yet_another_option": "something for both of the samples"  
  }
}
```
To recap, in the above example the following workflow options will be set:
- "my_option" will have different strings for the different samples
- "another_option" will be an object just set for the latter sample
- "yet_another_option" will be the same string for all samples

In other words, WFL will recursively merge the options objects together to
resolve the options for individual workflows. You can see this in WFL's
response, which always includes the workflow options:
```json
{
  // Some fields omitted for brevity!
  "pipeline": "ExternalWholeGenomeReprocessing",
  "created": "2020-10-05T15:50:01Z",
  "workflows": [
    {
      "id": 1,
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample1234"
      },
      "workflow_options": {
        "my_option": "something for sample 1234",
        "yet_another_option": "something for both of the samples"     
      }
    },
    {
      "id": 2,
      "inputs": {
        "input_cram": "develop/20k/NA12878_PLUMBING.cram",
        "sample_name": "TestSample5678"
      },
      "workflow_options": {
        "my_option": "something different for sample 5678",
        "another_option": {"foo": "bar"},
        "yet_another_option": "something for both of the samples"     
      }
    }
  ],
  "workflow_options": {
    "yet_another_option": "something for both of the samples"  
  }
}
```

Note that the per-workflow options visible in the response reflect WFL's
merging of the per-workload options.

Another note is that WFL already has some default values it passes for
workflow options, and these defaults will also be visible in the response.
See below for more information.

## Behavior
The diagram below lists the different sources of options for a particular
workflow. Precedence is from the bottom up, so "higher" sources override
lower ones.

![](./assets/option-precedence.svg)

The green "sources" are where you may optionally provide configuration
via `workflow_options`, the white "sources" are where WFL may create and
supply options by default, and the gray "sources" are outside of WFL's
visibility but can still affect the result.

WFL supplies its own default options usually on a per-module basis
(meaning different pipelines that make use of different modules may
have different default options). As the diagram notes, this can
happen on an automatic per-workflow basis (like basing an option on
some WDL input) or over an entire workload (like basing options
on the Cromwell instance being used). Individual module documentation
can help provide more info, as will simply looking at WFL's response
from the `/create` or `/exec` endpoints, which includes those defaults.
    