"""
Support Arbitrary WDL is a challenging task, it requires:

1. Support inputs orchestration of any (or a large definitive set of) WDLs.
2. Support OTTER (Add, Update, Delete, Retract) workloads of any
   (or a large definitive set of) WDLs.
3. Well-designed Database schemas.
4. Standard way to track/query jobs (on-going, done, failed, etc.)
...

This script only tries to explore how could we do `1.` better.
"""
import WDL
from WDL.Type import String


ARRAY_WDL = "warp-Arrays_v2.0/pipelines/broad/arrays/single_sample/Arrays.wdl"

loaded_wdl      = WDL.load(uri=ARRAY_WDL)
inputs          = loaded_wdl.workflow.inputs
required_inputs = [i.name for i in loaded_wdl.workflow.required_inputs]
optional_inputs = [i.name for i in inputs if i.type.optional]

"""
optional = [
    'gender_cluster_file',
    'zcall_thresholds_file',
    'fingerprint_genotypes_vcf_file',
    'fingerprint_genotypes_vcf_index_file',
    'subsampled_metrics_interval_list',
    'contamination_controls_vcf',
    'control_sample_vcf_file',
    'control_sample_vcf_index_file',
    'control_sample_intervals_file',
    'control_sample_name'
]
required = [
    'sample_alias',
    'sample_lsid',
    'analysis_version_number',
    'call_rate_threshold',
    'reported_gender',
    'chip_well_barcode',
    'red_idat_cloud_path',
    'green_idat_cloud_path',
    'ref_fasta',
    'ref_fasta_index',
    'ref_dict',
    'dbSNP_vcf',
    'dbSNP_vcf_index',
    'params_file',
    'bead_pool_manifest_file',
    'extended_chip_manifest_file',
    'cluster_file',
    'haplotype_database_file',
    'variant_rsids_file',
    'disk_size',
    'preemptible_tries',
    'environment',
    'vault_token_path'
]
"""

# this is module-specific
# could have multiple set of defaults
# that maps to different pipeline versions
# -> rationale:
# 1. WFL doesn't need to care
# or distinguish between fingerprinting or reference
defaults = {
  "ref_dict": "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.dict",
  "ref_fasta_index": "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.fasta.fai",
  "dbSNP_vcf": "gs://gcp-public-data--broad-references/hg19/v0/dbsnp_138.b37.vcf.gz",
  "dbSNP_vcf_index": "gs://gcp-public-data--broad-references/hg19/v0/dbsnp_138.b37.vcf.gz.tbi",
  "ref_fasta": "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.fasta",
  "variant_rsids_file": "gs://broad-references-private/hg19/v0/Homo_sapiens_assembly19.haplotype_database.snps.list",
  "haplotype_database_file": "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.haplotype_database.txt",
  "fingerprint_genotypes_vcf_file": None,
  "fingerprint_genotypes_vcf_index_file": None,
  "disk_size": 100,
  "subsampled_metrics_interval_list": None,
  "preemptible_tries": 3,
  "contamination_controls_vcf": None,
}

# this is environment-specific
per_envs = {
    "vault_token_path": "",
    "environment": "dev"
}

# this is per-sample, and dominates in the
# merge chain (always predominate among all inputs)
# -> rationale:
# 1. garbage in garbage out, even if the garbage overrides
# the good defaults, since WFL should always respect the user
# inputs, unless it breaks rules defined by the WDL file
# >> A WFL must obey the orders given it by human beings except
# where such orders would conflict with the First Law
per_sample = {
    "analysis_version_number": "",
    "bead_pool_manifest_file": "",
    "call_rate_threshold": "",
    "chip_well_barcode": "",
    "cluster_file": "",
    "extended_chip_manifest_file": "",
    "green_idat_cloud_path": "",
    "params_file": "",
    "red_idat_cloud_path": "",
    "reported_gender": "",
    "sample_alias": "",
    "sample_lsid": "",
    "Arrays.IlluminaGenotypingArray.ref_fasta": "CUSTOM IlluminaGenotypingArray.ref_fasta"
}

composed_inputs = {}
composed_inputs.update(defaults)
composed_inputs.update(per_envs)
composed_inputs.update(per_sample)

# other validation rules, even including nested task
# parsing and validation can happen here,
# this can be done via:
# 1. miniWDL
# 2. WOMTOOL
# 3. Cromwell API endpoint
# the rule is to bake as few domain knowledge as we can
# into the WFL modules, for better scalability
for i in required_inputs:
    assert i in composed_inputs, f"{i} is not provided!"


sr = set(required_inputs)
so = set(optional_inputs)
for i in composed_inputs:
    if i not in sr:
        if not i in so:
            print(f"{i} is provided, but not required even as optional inputs!")
