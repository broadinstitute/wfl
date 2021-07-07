(ns wfl.service.dockstore
  "Analyze data in Terra using the Firecloud/Terra API."
  (:require [clj-http.client   :as http]
            [clojure.string    :as str]
            [wfl.environment   :as env]
            [wfl.util          :as util]))

(defn ^:private dockstore-url [& parts]
  (let [url (util/de-slashify (env/getenv "WFL_DOCKSTORE_URL"))]
    (str/join "/" (cons url parts))))

(defn ga4gh-tool-descriptor [id version type]
  (-> (dockstore-url "api/api/ga4gh/v2/tools" id "versions" version type "descriptor")
      http/get
      (util/response-body-json)))
