(ns wfl.service.http-utils
  (:require [clj-http.client :as http]
            [wfl.once :as once]))

(defn post-multipart
  "Assemble PARTS into a multipart HTML body and post it to specified URL."
  [url parts]
  (letfn [(make-part [[k v]] {:name (name k) :content v})]
    (http/post url {:headers   (once/get-auth-header)
                    :multipart (map make-part parts)})))
