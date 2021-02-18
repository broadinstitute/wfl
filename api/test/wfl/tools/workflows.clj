(ns wfl.tools.workflows)

(def assemble-refbased-description
  "As described by womtool"
  {:meta    {:description "Reference-based microbial consensus calling. Aligns NGS reads to a singular reference genome, calls a new consensus sequence, and emits: new assembly, reads aligned to provided reference, reads aligned to new assembly, various figures of merit, plots, and QC metrics. The user may provide unaligned reads spread across multiple input files and this workflow will parallelize alignment per input file before merging results prior to consensus calling.",
             :author      "Broad Viral Genomics",
             :email       "viral-ngs@broadinstitute.org"},
   :name    "assemble_refbased",
   :inputs  [{:name            "align_to_ref.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "align_to_ref.machine_mem_gb",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "align_to_ref.sample_name",
              :valueType       {:typeName "Optional", :optionalType {:typeName "String"}},
              :typeDisplayName "String?",
              :optional        true,
              :default         nil}
             {:name            "align_to_self.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "align_to_self.machine_mem_gb",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "align_to_self.sample_name",
              :valueType       {:typeName "Optional", :optionalType {:typeName "String"}},
              :typeDisplayName "String?",
              :optional        true,
              :default         nil}
             {:name            "aligner",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"minimap2\""}
             {:name            "call_consensus.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-assemble:2.1.16.1\""}
             {:name            "call_consensus.machine_mem_gb",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "call_consensus.major_cutoff",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Float"}},
              :typeDisplayName "Float?",
              :optional        true,
              :default         "0.5"}
             {:name            "call_consensus.mark_duplicates",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Boolean"}},
              :typeDisplayName "Boolean?",
              :optional        true,
              :default         "false"}
             {:name            "ivar_trim.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"andersenlabapps/ivar:1.3.1\""}
             {:name            "ivar_trim.machine_mem_gb",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "ivar_trim.min_keep_length",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "ivar_trim.min_quality",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         "1"}
             {:name            "ivar_trim.sliding_window",
              :valueType       {:typeName "Optional", :optionalType {:typeName "Int"}},
              :typeDisplayName "Int?",
              :optional        true,
              :default         nil}
             {:name            "merge_align_to_ref.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "merge_align_to_ref.reheader_table",
              :valueType       {:typeName "Optional", :optionalType {:typeName "File"}},
              :typeDisplayName "File?",
              :optional        true,
              :default         nil}
             {:name            "merge_align_to_self.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "merge_align_to_self.reheader_table",
              :valueType       {:typeName "Optional", :optionalType {:typeName "File"}},
              :typeDisplayName "File?",
              :optional        true,
              :default         nil}
             {:name            "min_coverage",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int",
              :optional        true,
              :default         "3"}
             {:name            "novocraft_license",
              :valueType       {:typeName "Optional", :optionalType {:typeName "File"}},
              :typeDisplayName "File?",
              :optional        true,
              :default         nil}
             {:name            "plot_ref_coverage.bin_large_plots",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "plot_ref_coverage.binning_summary_statistic",
              :valueType       {:typeName "Optional", :optionalType {:typeName "String"}},
              :typeDisplayName "String?",
              :optional        true,
              :default         "\"max\""}
             {:name            "plot_ref_coverage.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "plot_ref_coverage.plot_only_non_duplicates",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "plot_ref_coverage.skip_mark_dupes",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "plot_self_coverage.bin_large_plots",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "plot_self_coverage.binning_summary_statistic",
              :valueType       {:typeName "Optional", :optionalType {:typeName "String"}},
              :typeDisplayName "String?",
              :optional        true,
              :default         "\"max\""}
             {:name            "plot_self_coverage.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "plot_self_coverage.plot_only_non_duplicates",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "plot_self_coverage.skip_mark_dupes",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "reads_unmapped_bams",
              :valueType       {:typeName "Array", :arrayType {:typeName "File"}, :nonEmpty true},
              :typeDisplayName "Array[File]+",
              :optional        false,
              :default         nil}
             {:name            "reference_fasta",
              :valueType       {:typeName "File"},
              :typeDisplayName "File",
              :optional        false,
              :default         nil}
             {:name            "run_discordance.docker",
              :valueType       {:typeName "String"},
              :typeDisplayName "String",
              :optional        true,
              :default         "\"quay.io/broadinstitute/viral-core:2.1.19\""}
             {:name            "sample_name",
              :valueType       {:typeName "Optional", :optionalType {:typeName "String"}},
              :typeDisplayName "String?",
              :optional        true,
              :default         nil}
             {:name            "skip_mark_dupes",
              :valueType       {:typeName "Boolean"},
              :typeDisplayName "Boolean",
              :optional        true,
              :default         "false"}
             {:name            "trim_coords_bed",
              :valueType       {:typeName "Optional", :optionalType {:typeName "File"}},
              :typeDisplayName "File?",
              :optional        true,
              :default         nil}],
   :outputs [{:name            "align_to_ref_merged_aligned_trimmed_only_bam",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_ref_merged_bases_aligned",
              :valueType       {:typeName "Float"},
              :typeDisplayName "Float"}
             {:name            "align_to_ref_merged_coverage_plot",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_ref_merged_coverage_tsv",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_ref_merged_read_pairs_aligned",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "align_to_ref_merged_reads_aligned",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "align_to_ref_per_input_aligned_flagstat",
              :valueType       {:typeName "Array", :arrayType {:typeName "File"}, :nonEmpty false},
              :typeDisplayName "Array[File]"}
             {:name            "align_to_ref_per_input_fastqc",
              :valueType       {:typeName "Array", :arrayType {:typeName "File"}, :nonEmpty false},
              :typeDisplayName "Array[File]"}
             {:name            "align_to_ref_per_input_reads_aligned",
              :valueType       {:typeName "Array", :arrayType {:typeName "Int"}, :nonEmpty false},
              :typeDisplayName "Array[Int]"}
             {:name            "align_to_ref_per_input_reads_provided",
              :valueType       {:typeName "Array", :arrayType {:typeName "Int"}, :nonEmpty false},
              :typeDisplayName "Array[Int]"}
             {:name            "align_to_ref_variants_vcf_gz",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_ref_viral_core_version",
              :valueType       {:typeName "String"},
              :typeDisplayName "String"}
             {:name            "align_to_self_merged_aligned_only_bam",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_self_merged_bases_aligned",
              :valueType       {:typeName "Float"},
              :typeDisplayName "Float"}
             {:name            "align_to_self_merged_coverage_plot",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_self_merged_coverage_tsv",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "align_to_self_merged_mean_coverage",
              :valueType       {:typeName "Float"},
              :typeDisplayName "Float"}
             {:name            "align_to_self_merged_read_pairs_aligned",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "align_to_self_merged_reads_aligned",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "assembly_fasta",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "assembly_length",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "assembly_length_unambiguous",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "assembly_mean_coverage",
              :valueType       {:typeName "Float"},
              :typeDisplayName "Float"}
             {:name            "dist_to_ref_indels",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "dist_to_ref_snps",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "ivar_version",
              :valueType       {:typeName "String"},
              :typeDisplayName "String"}
             {:name            "num_libraries",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "num_read_groups",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "reference_genome_length",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "replicate_concordant_sites",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "replicate_discordant_indels",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "replicate_discordant_snps",
              :valueType       {:typeName "Int"},
              :typeDisplayName "Int"}
             {:name            "replicate_discordant_vcf",
              :valueType       {:typeName "File"},
              :typeDisplayName "File"}
             {:name            "viral_assemble_version",
              :valueType       {:typeName "String"},
              :typeDisplayName "String"}]})

(def assemble-refbased-outputs
  {:num_read_groups                              2
   :align_to_ref_merged_coverage_plot            "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_ref_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.pdf"
   :align_to_ref_viral_core_version              "v2.1.19"
   :align_to_self_merged_coverage_plot           "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_self_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.pdf"
   :assembly_length_unambiguous                  29903
   :assembly_mean_coverage                       1064.1900143798282
   :align_to_self_merged_bases_aligned           31834594
   :align_to_ref_merged_bases_aligned            31822474
   :align_to_ref_per_input_aligned_flagstat      ["gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-0/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.all.bam.flagstat.txt"
                                                  "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-1/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L2.cleaned.all.bam.flagstat.txt"]
   :reference_genome_length                      29903
   :align_to_ref_per_input_reads_provided        [539844 555716]
   :align_to_ref_merged_coverage_tsv             "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_ref_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.txt"
   :assembly_length                              29903
   :replicate_discordant_indels                  0
   :align_to_ref_merged_aligned_trimmed_only_bam "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-merge_align_to_ref/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.align_to_ref.trimmed.bam"
   :align_to_ref_per_input_reads_aligned         [157936 157138]
   :replicate_discordant_vcf                     "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-run_discordance/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.discordant.vcf"
   :viral_assemble_version                       "v2.1.16"
   :dist_to_ref_snps                             19
   :align_to_self_merged_aligned_only_bam        "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-merge_align_to_self/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.merge_align_to_self.bam"
   :num_libraries                                1
   :align_to_self_merged_read_pairs_aligned      315194
   :align_to_ref_variants_vcf_gz                 "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-call_consensus/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.sites.vcf.gz"
   :align_to_ref_merged_read_pairs_aligned       315074
   :align_to_self_merged_coverage_tsv            "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-plot_self_coverage/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.coverage_plot.txt"
   :replicate_concordant_sites                   29857
   :align_to_self_merged_reads_aligned           315194
   :dist_to_ref_indels                           0
   :replicate_discordant_snps                    0
   :ivar_version                                 "iVar version 1.3"
   :assembly_fasta                               "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-call_consensus/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.fasta"
   :align_to_self_merged_mean_coverage           1064.595324883791
   :align_to_ref_merged_reads_aligned            315074
   :align_to_ref_per_input_fastqc                ["gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-0/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L1.cleaned.mapped_fastqc.html"
                                                  "gs://broad-gotc-dev-wfl-ptc-test-inputs/covid-19/assemble_refbased/call-align_to_ref/shard-1/MA_MGH_02760.lERCC-00134_RandomPrimer-SSIV_NexteraXT_RNA_0352325199_cDNA_0369438842_LIB_13215203_G2_HTHC3DRXX_L2.cleaned.mapped_fastqc.html"]})
