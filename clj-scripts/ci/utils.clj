(ns ci.utils
  (:require
    [clojure.java.io :as io])
  (:import (java.io File)))

(defn orb-files-in-dir
  [dir]
  (filter (fn [^File f]
            (and (.isFile f)
                 (= "orb.yml" (.getName f))))
          (file-seq (io/file dir))))