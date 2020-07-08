(ns zero.references
  "Define shared static references.")

(def reference_fasta
  "Default value for references.reference_fastas."
  (let [prefix "gs://gcp-public-data--broad-references/hg38/v0/Homo_sapiens_assembly38"
        prefix (partial str prefix)]
    {:ref_dict        (prefix ".dict")
     :ref_fasta       (prefix ".fasta")
     :ref_fasta_index (prefix ".fasta.fai")
     :ref_ann         (prefix ".fasta.64.ann")
     :ref_bwt         (prefix ".fasta.64.bwt")
     :ref_pac         (prefix ".fasta.64.pac")
     :ref_alt         (prefix ".fasta.64.alt")
     :ref_amb         (prefix ".fasta.64.amb")
     :ref_sa          (prefix ".fasta.64.sa")}))

(def hg38-genome-references
  "HG38 reference files for genome reprocessing."
  (let [gold    "Mills_and_1000G_gold_standard.indels.hg38"
        hsa     "Homo_sapiens_assembly38"
        regions "_regions.hg38.interval_list"
        hg      (partial str "gs://gcp-public-data--broad-references/hg38/v0/")]
    {:haplotype_scatter_count     10
     :break_bands_at_multiples_of 100000
     :haplotype_database_file     (hg hsa ".haplotype_database.txt")
     :dbsnp_vcf                   (hg hsa  ".dbsnp138.vcf")
     :dbsnp_vcf_index             (hg hsa  ".dbsnp138.vcf.idx")
     :evaluation_interval_list    (hg      "wgs_evaluation" regions)
     :known_indels_sites_indices [(hg gold ".vcf.gz.tbi")
                                  (hg hsa  ".known_indels.vcf.gz.tbi")]
     :known_indels_sites_vcfs    [(hg gold ".vcf.gz")
                                  (hg hsa  ".known_indels.vcf.gz")]
     :reference_fasta             reference_fasta}))

(def hg38-exome-references
  "HG38 reference files for exome reprocessing."
  (let [hsa     "Homo_sapiens_assembly38"
        gold    "Mills_and_1000G_gold_standard.indels.hg38"
        regions "_regions.v1.interval_list"
        private "gs://broad-references-private/"
        hd      ".haplotype_database.txt"
        hg      (partial str "gs://gcp-public-data--broad-references/hg38/v0/")]
    {:break_bands_at_multiples_of 0
     :haplotype_scatter_count     50
     :haplotype_database_file     (str private "hg38/v0/" hsa hd)
     :evaluation_interval_list    (hg      "exome_evaluation" regions)
     :dbsnp_vcf                   (hg hsa  ".dbsnp138.vcf")
     :dbsnp_vcf_index             (hg hsa  ".dbsnp138.vcf.idx")
     :known_indels_sites_indices [(hg gold ".vcf.gz.tbi")
                                  (hg hsa  ".known_indels.vcf.gz.tbi")]
     :known_indels_sites_vcfs    [(hg gold ".vcf.gz")
                                  (hg hsa  ".known_indels.vcf.gz")]
     :reference_fasta             reference_fasta}))

(def hg19-arrays-references
  "HG19 reference files for arrays processing."
  {:ref_fasta                   "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.fasta"
   :ref_fasta_index             "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.fasta.fai"
   :ref_dict                    "gs://gcp-public-data--broad-references/hg19/v0/Homo_sapiens_assembly19.dict"
   :dbSNP_vcf                   "gs://gcp-public-data--broad-references/hg19/v0/dbsnp_138.b37.vcf.gz"
   :dbSNP_vcf_index             "gs://gcp-public-data--broad-references/hg19/v0/dbsnp_138.b37.vcf.gz.tbi"}
  )