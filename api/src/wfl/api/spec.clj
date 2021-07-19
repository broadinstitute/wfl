(ns wfl.api.spec
  "Define specs used in routes"
  (:require [clojure.spec.alpha   :as s]
            [clojure.string       :as str]
            [wfl.util             :as util]
            [wfl.module.covid     :as covid]
            [wfl.module.batch     :as batch]
            [wfl.module.aou       :as aou]
            [wfl.module.copyfile  :as copyfile]
            [wfl.module.sg        :as sg]
            [wfl.module.xx        :as xx]
            [wfl.module.wgs       :as wgs]
            [wfl.source           :as source]
            [wfl.module.all       :as all]))

(s/def ::workload-query (s/and (s/keys :opt-un [::all/uuid ::all/project])
                               #(not (and (:uuid %) (:project %)))))

(s/def ::workflow-query (s/keys :opt-un [::all/status]))

(s/def :version/built     util/datetime-string?)
(s/def :version/commit    (s/and string? (comp not str/blank?)))
(s/def :version/committed util/datetime-string?)
(s/def :version/user      (s/and string? (comp not str/blank?)))

(s/def ::version-response (s/keys :req-un [:version/built
                                           :version/commit
                                           :version/committed
                                           :version/user
                                           ::all/version]))

(s/def ::items (s/* ::workload-inputs))
(s/def ::workload-inputs (s/keys :req-un [::inputs]
                                 :opt-un [::all/options]))
(s/def ::inputs (s/or :aou      ::aou/workflow-inputs
                      :copyfile ::copyfile/workflow-inputs
                      :wgs      ::wgs/workflow-inputs
                      :xx       ::xx/workflow-inputs
                      :sg       ::sg/workflow-inputs
                      :other    map?))

(s/def ::workflow  (s/keys :req-un [::inputs]
                           :opt-un [::all/status ::all/updated ::all/uuid ::all/options]))

(s/def ::workflows (s/* ::workflow))

(s/def ::workload-request (s/or :batch :batch/workload-request
                                :covid ::covid/workload-request))

(s/def ::workload-response (s/or :batch ::batch/workload-response
                                 :covid ::covid/workload-response))

(s/def ::workload-responses (s/* ::workload-response))
