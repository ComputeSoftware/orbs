(ns publish-orbs
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [compute.simple-shell.core :as bash]
    [ci.utils :as utils])
  (:import (java.io File)))

(defn parse-semver
  [semver-str]
  (let [[major minor patch] (str/split semver-str #"\." 3)]
    {:major major
     :minor minor
     :patch patch}))

(defn parse-orb-list-result
  [orb-list-result orb-ns]
  (when-not (str/blank? orb-list-result)
    (as-> orb-list-result $
          (str/split-lines $)
          (drop 2 $)
          (filter #(str/starts-with? % orb-ns) $)
          (map (fn [orb-name-version-str]
                 (let [[orb-name version] (str/split orb-name-version-str #" " 2)
                       version (str/replace version #"\(|\)" "")]
                   {:orb-name    orb-name
                    :orb-version (when (not= "Not published" version) version)})) $))))

(defn get-published-orb-versions
  "Returns a list of `:orb-name` and `:orb-version` for all the orbs in `orb-ns`."
  [orb-ns]
  (bash/alet [result (bash/sh ["circleci" "orb" "list" orb-ns])]
    (parse-orb-list-result (::bash/out result) orb-ns)))

;; take published orb source and compare it to current source. if changes then
;; publish a new version, otherwise skip

(defn get-local-orb-names
  "Returns a set of orb names that are in the `src` directory."
  []
  (into #{}
        (map (fn [^File f]
               (let [path-parts (str/split (.getParent f) #"\/")]
                 {:orb-name (str "compute/" (last path-parts))
                  :orb-path (.getPath f)})))
        (utils/orb-files-in-dir "src")))

(defn create-and-publish-new-orb!
  [orb-name orb-path]
  (bash/alet [_ (bash/sh ["circleci" "orb" "create" orb-name])
              _ (bash/sh ["circleci" "orb" "publish" orb-path (str orb-name "@0.0.0")]
                         {:stdio [nil "inherit"]})]))

(defn publish-existing-orb!
  [orb-name orb-path cur-orb-version]
  (let [existing-source (let [r (bash/sh ["circleci" "orb" "source" (str orb-name "@" cur-orb-version)])]
                          ;; this command may fail if the orb is created but it has
                          ;; not been published yet, so we wrap the command.
                          (when (bash/success? r)
                            (::bash/out r)))
        local-source (slurp orb-path)]
    ;; if the orb source is different then we publish. otherwise we skip publishing
    (if (not= (str/trim-newline existing-source) (str/trim-newline local-source))
      (bash/sh ["circleci" "orb" "publish" "increment" orb-path orb-name "patch"]
               {:stdio [nil "inherit"]})
      (println (str orb-name " orb source has not changed. Skipping publish for orb.")))))

(defn publish-local-orbs!
  []
  (let [local-orb-names (get-local-orb-names)
        versions (get-published-orb-versions "compute")
        get-existing-orb (fn [orb-name]
                           (first (filter #(= orb-name (:orb-name %)) versions)))
        publish-results (map (fn [{:keys [orb-name orb-path] :as orb}]
                               (assoc (if-let [existing-orb (get-existing-orb orb-name)]
                                        (publish-existing-orb! orb-name orb-path (:orb-version existing-orb))
                                        (create-and-publish-new-orb! orb-name orb-path))
                                 :orb orb)) local-orb-names)
        failed-publishes (filter bash/failed? publish-results)]
    (if (empty? failed-publishes)
      true
      (do
        (doseq [{:keys [orb] :as fail} failed-publishes]
          (println (str orb " failed to publish.\n" (::bash/err fail))))
        false))))

(defn -main
  [& args]
  (if (publish-local-orbs!)
    (bash/exit! true)
    (bash/exit! false)))