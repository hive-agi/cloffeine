(ns cloffeine.mutation-test
  "Mutation testing for the caffeine-facing code in `cloffeine.common`.

  Each mutant is a deliberate defect injected into the source at load time (the
  file on disk is never modified). A mutant is *killed* when at least one of its
  `:killed-by` tests fails while the mutant is loaded; a surviving mutant marks
  behaviour no test constrains. The suite asserts a mutation score of 100%."
  (:require [cloffeine.core-test]
            [cloffeine.property-test]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def mutants
  "Injected defects, each with the tests expected to detect it."
  [{:id :maximum-size-ignored
    :find "(:maximumSize settings) (.maximumSize (long (:maximumSize settings)))"
    :replace "(:maximumSize settings) (identity)"
    :killed-by ['cloffeine.property-test/maximum-size-bounds-the-cache]}

   {:id :maximum-size-narrowed-to-int
    :find "(.maximumSize (long (:maximumSize settings)))"
    :replace "(.maximumSize (int (:maximumSize settings)))"
    :killed-by ['cloffeine.property-test/maximum-size-accepts-values-beyond-int-range]}

   {:id :maximum-weight-ignored
    :find "(:maximumWeight settings) (.maximumWeight (long (:maximumWeight settings)))"
    :replace "(:maximumWeight settings) (identity)"
    :killed-by ['cloffeine.property-test/maximum-weight-bounds-the-total-weight]}

   {:id :weigher-guard-disabled
    :find "(not (:maximumWeight settings))"
    :replace "(not true)"
    :killed-by ['cloffeine.property-test/weigher-without-maximum-weight-is-rejected]}

   {:id :refresh-after-write-ignored
    :find "(:refreshAfterWrite settings) (refresh-after-write (:refreshAfterWrite settings) timeUnit)"
    :replace "(:refreshAfterWrite settings) (identity)"
    :killed-by ['cloffeine.core-test/auto-refresh]}

   {:id :expire-after-write-becomes-access
    :find "(:expireAfterWrite settings) (expire-after-write (:expireAfterWrite settings) timeUnit)"
    :replace "(:expireAfterWrite settings) (expire-after-access (:expireAfterWrite settings) timeUnit)"
    :killed-by ['cloffeine.property-test/expire-after-write-ignores-reads]}

   {:id :millisecond-unit-becomes-seconds
    :find ":ms TimeUnit/MILLISECONDS"
    :replace ":ms TimeUnit/SECONDS"
    :killed-by ['cloffeine.property-test/millisecond-time-unit-is-honoured]}

   {:id :hit-count-reads-miss-count
    :find "(.hitCount cs)"
    :replace "(.missCount cs)"
    :killed-by ['cloffeine.property-test/stats-account-for-every-request]}])

(defn- source []
  (slurp (io/resource "cloffeine/common.clj")))

(defn- mutate
  "Returns the source of `cloffeine.common` with `mutant` applied. Throws when the
  target text is absent or ambiguous, so a stale mutant cannot silently pass."
  [src {:keys [id find replace]}]
  (let [occurrences (count (re-seq (re-pattern (java.util.regex.Pattern/quote find)) src))]
    (when-not (= 1 occurrences)
      (throw (ex-info "mutation target is not unique in the source"
                      {:mutant id :occurrences occurrences :find find})))
    (str/replace src find replace)))

(defn- failure-count
  "Runs `test-syms` with reporting silenced and returns the number of failures
  and errors."
  [test-syms]
  (let [failures (atom 0)]
    (binding [t/report (fn [m]
                         (when (#{:fail :error} (:type m))
                           (swap! failures inc)))]
      (t/test-vars (map (fn [s]
                          (or (resolve s)
                              (throw (ex-info "unknown test var" {:sym s}))))
                        test-syms)))
    @failures))

(defn- with-mutant
  "Loads `mutant` into the running image, calls `f`, and restores the original
  namespace afterwards."
  [mutant f]
  (let [mutated (mutate (source) mutant)]
    (try
      ;; a mutant breaks the builder's type inference, so it would flood the
      ;; report with reflection warnings the shipped source does not produce.
      (binding [*ns* *ns*
                *warn-on-reflection* false]
        (load-string mutated))
      (f)
      (finally
        (require 'cloffeine.common :reload)))))

(deftest baseline-is-green
  (testing "every test used as a mutation detector passes on unmutated source"
    (doseq [sym (distinct (mapcat :killed-by mutants))]
      (is (zero? (failure-count [sym])) (str sym " must pass before it can kill")))))

(deftest every-mutant-is-killed
  (let [results (doall
                  (for [{:keys [id killed-by] :as mutant} mutants]
                    (let [failures (with-mutant mutant #(failure-count killed-by))]
                      (testing (str "mutant " id)
                        (is (pos? failures)
                            (str "mutant survived: " id
                                 " — no test in " (vec killed-by) " detected it")))
                      [id (pos? failures)])))
        killed (count (filter second results))]
    (println (format "mutation score: %d/%d killed (%.0f%%)"
                     killed (count results)
                     (double (* 100 (/ killed (count results))))))
    (is (= (count mutants) killed))))
