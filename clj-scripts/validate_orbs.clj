(ns validate-orbs
  (:require
    [compute.simple-shell.core :as bash]
    [ci.utils :as utils])
  (:import (java.io File)))

(defn orbs-in-dir-valid?
  [dir]
  (let [orb-files (utils/orb-files-in-dir dir)
        validate-results (map (fn [^File f]
                                (let [orb-path (.getPath f)]
                                  (assoc (bash/sh ["circleci" "orb" "validate" orb-path])
                                    :orb-path orb-path)))
                              orb-files)
        invalid-orbs (filter bash/failed? validate-results)]
    (if (empty? invalid-orbs)
      true
      (do
        (doseq [{::bash/keys [err]
                 :keys       [orb-path]} invalid-orbs]
          (println (str "Orb at " orb-path " is invalid.\n" err)))
        false))))

(defn -main
  [& args]
  (if (orbs-in-dir-valid? "src")
    (bash/exit! true)
    (bash/exit! false)))