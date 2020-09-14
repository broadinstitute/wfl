# ExternalWholeGenomeReprocessing workload

An `ExternalWholeGenomeReprocessing` workload
requires the following inputs
for each workflow in the workload.

- input_cram
- sample_name
- base_file_name
- final_gvcf_base_name
- unmapped_bam_suffix

The `input_cram` is the path to a file
relative to the `input` URL prefix
in the `workload` definition above.

The `input_cram` contains the `sample_name`
but we break it out into a separate input here
to avoid parsing every (often large) CRAM file.

The `base_file_name` is used to name result files.
The workflow uses the `base_file_name`
together with one or more filetype suffixes
to name intermediate and output files.
It is usually just the leaf name of `input_cram`
with the `.cram` extension removed.

The `final_gvcf_base_name` is the root
of the leaf name
of the pathname of the final VCF.
The final VCF will have some variant
of a `.vcf` suffix
added by the workflow WDL.

It is common for `base_file_name`
and `final_gvcf_base_name`
to be identical to `sample_name`.
If no `base_file_name` is specified
for any workflow in a workload,
`base_file_name` defaults to `sample_name`.
Likewise,
if no `final_gvcf_base_name` is specified
for any workflow in a workload,
then `final_gvcf_base_name`
also defaults to `sample_name`.

It is used to recover the filename resulting
from re-aligning a reverted CRAM file.
The `unmapped_bam_suffix`
is almost always `.unmapped.bam`,
so that is its default value
unless it is specified.
