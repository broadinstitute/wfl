(ns wfl.references
  "Define shared static references.")

(defn reference_fasta
  "Default value for references.reference_fasta with PREFIX."
  [prefix]
  {:pre [(string? prefix) (seq prefix)]}
  {:ref_dict        (str prefix ".dict")
   :ref_fasta       (str prefix ".fasta")
   :ref_fasta_index (str prefix ".fasta.fai")
   :ref_ann         (str prefix ".fasta.64.ann")
   :ref_bwt         (str prefix ".fasta.64.bwt")
   :ref_pac         (str prefix ".fasta.64.pac")
   :ref_alt         (str prefix ".fasta.64.alt")
   :ref_amb         (str prefix ".fasta.64.amb")
   :ref_sa          (str prefix ".fasta.64.sa")})

(defn hg38-genome-references
  "HG38 reference files for genome reprocessing."
  [prefix]
  (let [hg38    "gs://gcp-public-data--broad-references/hg38/v0/"
        gold    "Mills_and_1000G_gold_standard.indels.hg38"
        hsa     "Homo_sapiens_assembly38"
        regions "_regions.hg38.interval_list"
        r_f_p   (fnil reference_fasta (str hg38 hsa))]
    {:haplotype_scatter_count     10
     :break_bands_at_multiples_of 100000
     :haplotype_database_file     (str hg38 hsa ".haplotype_database.txt")
     :dbsnp_vcf                   (str hg38 hsa  ".dbsnp138.vcf")
     :dbsnp_vcf_index             (str hg38 hsa  ".dbsnp138.vcf.idx")
     :evaluation_interval_list    (str hg38      "wgs_evaluation" regions)
     :known_indels_sites_indices [(str hg38 gold ".vcf.gz.tbi")
                                  (str hg38 hsa  ".known_indels.vcf.gz.tbi")]
     :known_indels_sites_vcfs    [(str hg38 gold ".vcf.gz")
                                  (str hg38 hsa  ".known_indels.vcf.gz")]
     :reference_fasta             (r_f_p prefix)}))

(def contamination-sites
  "Default contamination sites."
  (let [hg38 "gs://gcp-public-data--broad-references/hg38/v0/"
        dat  "contamination-resources/1000g/1000g.phase3.100k.b38.vcf.gz.dat"]
    {:contamination_sites_bed (str hg38 dat ".bed")
     :contamination_sites_mu  (str hg38 dat ".mu")
     :contamination_sites_ud  (str hg38 dat ".UD")}))

(def exome-scatter-settings
  "The default scatter_settings for exomes."
  {:break_bands_at_multiples_of  0
   :haplotype_scatter_count     50})

(def hg38-exome-references
  "HG38 reference files for exome reprocessing."
  (let [hg38    "gs://gcp-public-data--broad-references/hg38/v0/"
        gold    "Mills_and_1000G_gold_standard.indels.hg38"
        hsa     "Homo_sapiens_assembly38"
        regions "_regions.v1.interval_list"]
    {:haplotype_database_file     (str hg38 hsa ".haplotype_database.txt")
     :evaluation_interval_list    (str hg38      "exome_evaluation" regions)
     :dbsnp_vcf                   (str hg38 hsa  ".dbsnp138.vcf")
     :dbsnp_vcf_index             (str hg38 hsa  ".dbsnp138.vcf.idx")
     :known_indels_sites_indices [(str hg38 gold ".vcf.gz.tbi")
                                  (str hg38 hsa  ".known_indels.vcf.gz.tbi")]
     :known_indels_sites_vcfs    [(str hg38 gold ".vcf.gz")
                                  (str hg38 hsa  ".known_indels.vcf.gz")]
     :reference_fasta             (reference_fasta (str hg38 hsa))}))

(def hg19-arrays-references
  "HG19 reference files for arrays processing."
  (let [hg19 "gs://gcp-public-data--broad-references/hg19/v0/"]
    {:ref_fasta       (str hg19 "Homo_sapiens_assembly19.fasta")
     :ref_fasta_index (str hg19 "Homo_sapiens_assembly19.fasta.fai")
     :ref_dict        (str hg19 "Homo_sapiens_assembly19.dict")
     :dbSNP_vcf       (str hg19 "dbsnp_138.b37.vcf.gz")
     :dbSNP_vcf_index (str hg19 "dbsnp_138.b37.vcf.gz.tbi")}))
