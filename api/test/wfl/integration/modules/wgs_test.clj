(ns wfl.integration.modules.wgs-test
  (:require [clojure.set :refer [rename-keys]]
            [clojure.test :refer [deftest testing is] :as clj-test]
            [wfl.service.cromwell :refer [wait-for-workflow-complete
                                          submit-workflows]]
            [wfl.tools.endpoints :as endpoints]
            [wfl.tools.fixtures :as fixtures]
            [wfl.tools.workloads :as workloads]
            [wfl.module.wgs :as wgs :refer [skip-workflow?]]
            [wfl.jdbc :as jdbc]
            [wfl.module.all :as all]
            [wfl.util :as util]
            [wfl.references :as references]
            [clojure.string :as str])
  (:import (java.util UUID)))

(clj-test/use-fixtures :once fixtures/temporary-postgresql-database)

(defn ^:private mock-submit-workflows [& _]
  [(UUID/randomUUID)])

(defn ^:private make-wgs-workload-request []
  (-> (UUID/randomUUID)
      workloads/wgs-workload-request
      (assoc :creator (:email @endpoints/userinfo))))

(defn ^:private strip-prefix
  [[k v]]
  [(keyword (util/unprefix (str k) ":ExternalWholeGenomeReprocessing.")) v])

(deftest test-create-with-common-reference-fasta-prefix
  (let [prefix "gs://fake-input-bucket/ref-fasta"]
    (letfn [(verify-reference-fasta [reference-fasta]
              (is (= reference-fasta (references/reference_fasta prefix))))
            (go! [inputs]
              (verify-reference-fasta
               (get-in inputs [:references :reference_fasta]))
              (is (empty? (-> inputs :references (dissoc :reference_fasta))))
              (is (util/absent? inputs :reference_fasta_prefix)))]
      (run! (comp go! :inputs)
            (-> (make-wgs-workload-request)
                (assoc-in [:common :inputs] {:reference_fasta_prefix prefix})
                workloads/create-workload!
                :workflows)))))

(deftest test-create-with-reference-fasta-prefix-override
  (let [prefix "gs://fake-input-bucket/ref-fasta"]
    (letfn [(verify-reference-fasta [reference-fasta]
              (is (= reference-fasta (references/reference_fasta prefix))))
            (go! [inputs]
              (verify-reference-fasta
               (get-in inputs [:references :reference_fasta]))
              (is (empty? (-> inputs :references (dissoc :reference_fasta))))
              (is (util/absent? inputs :reference_fasta_prefix)))]
      (run! (comp go! :inputs)
            (-> (make-wgs-workload-request)
                (assoc-in [:common :inputs] {:reference_fasta_prefix "gs://ignore/this/ref-fasta"})
                (update :items (partial map #(update % :inputs (fn [xs] (assoc xs :reference_fasta_prefix prefix)))))
                workloads/create-workload!
                :workflows)))))

(deftest test-start-wgs-workload!
  (with-redefs-fn {#'submit-workflows mock-submit-workflows}
    #(let [workload (-> (make-wgs-workload-request)
                        workloads/create-workload!
                        workloads/start-workload!)]
       (letfn [(check-nesting [workflow]
                 (is (:inputs workflow) "Inputs are under :inputs")
                 (is
                  (not-any? (partial contains? workflow) (keys workloads/wgs-inputs))
                  "Inputs are not at the top-level"))]
         (run! check-nesting (:workflows workload))))))

(defn ^:private old-create-wgs-workload! []
  (let [request (make-wgs-workload-request)]
    (jdbc/with-db-transaction [tx (fixtures/testing-db-config)]
      (let [[id table] (all/add-workload-table! tx wgs/workflow-wdl request)
            add-id (fn [m id] (assoc (:inputs m) :id id))]
        (jdbc/insert-multi! tx table (map add-id (:items request) (range)))
        (jdbc/update! tx :workload {:version "0.3.8"} ["id = ?" id])
        id))))

(deftest test-loading-old-wgs-workload
  (let [id       (old-create-wgs-workload!)
        workload (workloads/load-workload-for-id id)]
    (is (= id (:id workload)))
    (is (= wgs/pipeline (:pipeline workload)))))

(deftest test-exec-with-input_bam
  (letfn [(go! [workflow]
            (is (:uuid workflow))
            (is (:status workflow))
            (is (:updated workflow)))
          (use-input_bam [item]
            (update item :inputs
                    #(-> %
                         (dissoc :input_cram)
                         (assoc :input_bam "gs://inputs/fake.bam"))))
          (verify-use_input_bam! [inputs labels]
            (is (contains? inputs :input_bam))
            (is (util/absent? inputs :input_cram))
            (is (contains? labels :workload)))
          (verify-inputs [env _ _ inputs options labels]
            (map
             (fn [inputs]
               (verify-use_input_bam! (into {} inputs) labels)
               [env _ _ inputs options labels])
             inputs))]
    (with-redefs-fn
      {#'submit-workflows
       (comp mock-submit-workflows verify-inputs)}
      #(-> (make-wgs-workload-request)
           (update :items (comp vector use-input_bam first))
           (workloads/execute-workload!)
           (as-> workload
                 (is (:started workload))
             (run! go! (:workflows workload)))))))

(deftest test-submitted-workflow-inputs
  (letfn [(prefixed? [prefix key] (str/starts-with? (str key) (str prefix)))
          (verify-workflow-inputs [inputs]
            (is (:supports_common_inputs inputs))
            (is (:supports_inputs inputs))
            (is (:overwritten inputs))
            (is (not-empty (-> inputs :references (dissoc :reference_fasta)))))
          (verify-submitted-inputs [_ _ _ inputs _ _]
            (map
             (fn [in]
               (is (every? #(prefixed? :ExternalWholeGenomeReprocessing %) (keys in)))
               (verify-workflow-inputs (into {} (map strip-prefix in)))
               (UUID/randomUUID))
             inputs))]
    (with-redefs-fn {#'submit-workflows verify-submitted-inputs
                     #'skip-workflow? (constantly nil)}
      (fn []
        (->
         (make-wgs-workload-request)
         (assoc-in [:common :inputs]
                   {:supports_common_inputs true :overwritten false})
         (update :items
                 (partial map
                          #(update % :inputs
                                   (fn [xs] (merge xs {:supports_inputs true :overwritten true})))))
         workloads/execute-workload!)))))

(deftest test-only-bam-or-cram
  (letfn [(verify-workflow-inputs [inputs]
            (is (some inputs [:input_bam :input_cram]))
            (run! #(is (% inputs)) [:base_file_name
                                    :sample_name
                                    :unmapped_bam_suffix
                                    :final_gvcf_base_name
                                    :destination_cloud_path]))
          (verify-submitted-inputs [_ _ _ inputs _ _]
            (verify-workflow-inputs (into {} (map strip-prefix inputs)))
            (UUID/randomUUID))
          (test-with-input [key value]
            (let [request (-> (make-wgs-workload-request)
                              (assoc :items [{:inputs {key value}}]))]
              (testing (str "default inputs when given only " key)
                (with-redefs-fn {#'submit-workflow verify-submitted-inputs
                                 #'skip-workflow? (constantly false)}
                  #(workloads/execute-workload! request)))))]
    (test-with-input :input_bam (:input_cram workloads/wgs-inputs))
    (test-with-input :input_cram (:input_cram workloads/wgs-inputs))))

(deftest test-workflow-options
  (letfn [(verify-workflow-options [options]
            (is (:supports_common_options options))
            (is (:supports_options options))
            (is (:overwritten options)))
          (verify-submitted-options [env _ _ inputs options _]
            (let [defaults (util/make-options env)]
              (verify-workflow-options options)
              (is (= defaults (select-keys options (keys defaults))))
              (map (fn [_] (UUID/randomUUID)) inputs)))]
    (with-redefs-fn {#'submit-workflows verify-submitted-options
                     #'skip-workflow? (constantly nil)}
      (fn []
        (->
         (make-wgs-workload-request)
         (assoc-in [:common :options]
                   {:supports_common_options true :overwritten false})
         (update :items
                 (partial map
                          #(assoc % :options {:supports_options true :overwritten true})))
         workloads/execute-workload!
         :workflows
         (->> (map (comp verify-workflow-options :options))))))))

(deftest test-empty-workflow-options
  (letfn [(go! [workflow] (is (util/absent? workflow :options)))]
    (run! go! (-> (make-wgs-workload-request)
                  (assoc-in [:common :options] {})
                  (update :items (partial map #(assoc % :options {})))
                  workloads/create-workload!
                  :workflows))))
