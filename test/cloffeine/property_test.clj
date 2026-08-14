(ns cloffeine.property-test
  "Property-based tests: they state invariants of the cloffeine wrapper that must
  hold for any generated key/value/operation sequence, rather than for one example."
  (:require [cloffeine.cache :as cache]
            [cloffeine.common :as common]
            [cloffeine.loading-cache :as loading-cache]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [clojure.lang ExceptionInfo]
           [com.github.benmanes.caffeine.cache Ticker]
           [com.google.common.testing FakeTicker]
           [java.time Duration]
           [java.util.concurrent TimeUnit]))

(def ^:private gen-key
  (gen/one-of [gen/small-integer gen/keyword gen/string-alphanumeric]))

(def ^:private gen-val
  (gen/one-of [gen/small-integer gen/string-alphanumeric gen/boolean
               (gen/vector gen/small-integer 0 5)]))

(def ^:private gen-entries
  (gen/map gen-key gen-val {:max-elements 20}))

(defn- reify-ticker [^FakeTicker ticker]
  (reify Ticker (read [_this] (.read ticker))))

(defn- as-clj-map [cache]
  (into {} (cache/as-map cache)))

;; ---------------------------------------------------------------------------
;; Manual cache: the cache is a map with eviction

(defspec put-then-get-if-present-returns-the-value 200
  (prop/for-all [entries gen-entries]
    (let [c (cache/make-cache)]
      (doseq [[k v] entries]
        (cache/put! c k v))
      (every? (fn [[k v]] (= v (cache/get-if-present c k))) entries))))

(defspec absent-keys-are-nil 200
  (prop/for-all [entries gen-entries
                 k gen-key]
    (let [c (cache/make-cache)]
      (doseq [[ek ev] entries]
        (cache/put! c ek ev))
      (cache/invalidate! c k)
      (nil? (cache/get-if-present c k)))))

(defspec as-map-mirrors-a-clojure-map-model 200
  (prop/for-all [ops (gen/vector
                       (gen/one-of
                         [(gen/tuple (gen/return :put) gen-key gen-val)
                          (gen/tuple (gen/return :invalidate) gen-key gen-val)
                          (gen/tuple (gen/return :invalidate-all) gen-key gen-val)])
                       0 40)]
    (let [c (cache/make-cache)
          model (reduce (fn [m [op k v]]
                          (case op
                            :put            (assoc m k v)
                            :invalidate     (dissoc m k)
                            :invalidate-all {}))
                        {}
                        ops)]
      (doseq [[op k v] ops]
        (case op
          :put            (cache/put! c k v)
          :invalidate     (cache/invalidate! c k)
          :invalidate-all (cache/invalidate-all! c)))
      (cache/cleanup c)
      (= model (as-clj-map c)))))

(defspec estimated-size-matches-distinct-keys 100
  (prop/for-all [entries gen-entries]
    (let [c (cache/make-cache)]
      (doseq [[k v] entries]
        (cache/put! c k v))
      (cache/cleanup c)
      (= (count entries) (cache/estimated-size c)))))

;; ---------------------------------------------------------------------------
;; Loading cache: a loader turns the cache into a memoized function

(defspec loading-cache-memoizes-the-loader 100
  (prop/for-all [ks (gen/vector gen-key 0 20)]
    (let [calls (atom [])
          cl (common/reify-cache-loader (fn [k] (swap! calls conj k) [:loaded k]))
          c (loading-cache/make-cache cl)
          results (mapv #(loading-cache/get c %) ks)]
      (and (= (mapv (fn [k] [:loaded k]) ks) results)
           ;; the loader runs exactly once per distinct key
           (= (count (distinct ks)) (count @calls))))))

;; ---------------------------------------------------------------------------
;; Bounded caches

(defspec maximum-size-bounds-the-cache 100
  (prop/for-all [max-size (gen/choose 1 20)
                 entries (gen/map gen-key gen-val {:max-elements 40})]
    (let [c (cache/make-cache {:maximumSize max-size})]
      (doseq [[k v] entries]
        (cache/put! c k v))
      (cache/cleanup c)
      (<= (cache/estimated-size c) max-size))))

(defspec maximum-size-accepts-values-beyond-int-range 50
  ;; regression: the builder used to narrow :maximumSize with `int`, which threw
  ;; for any bound above Integer/MAX_VALUE.
  (prop/for-all [max-size (gen/choose (inc (long Integer/MAX_VALUE)) Long/MAX_VALUE)
                 entries gen-entries]
    (let [c (cache/make-cache {:maximumSize max-size})]
      (doseq [[k v] entries]
        (cache/put! c k v))
      (cache/cleanup c)
      (= (count entries) (cache/estimated-size c)))))

(defspec maximum-weight-bounds-the-total-weight 100
  (prop/for-all [max-weight (gen/choose 10 100)
                 entries (gen/map gen-key (gen/choose 1 10) {:max-elements 40})]
    ;; the weigh-fn returns a long here: the wrapper is responsible for the
    ;; coercion Weigher#weigh requires.
    (let [weigher (common/reify-weigher (fn [_this _k v] v))
          c (cache/make-cache {:maximumWeight max-weight
                               :weigher weigher})]
      (doseq [[k v] entries]
        (cache/put! c k v))
      (cache/cleanup c)
      (<= (reduce + 0 (vals (as-clj-map c))) max-weight))))

;; ---------------------------------------------------------------------------
;; Builder configuration

(deftest weigher-without-maximum-weight-is-rejected
  (testing "caffeine only honours a weigher together with a maximum weight"
    (is (thrown-with-msg? ExceptionInfo #":weigher requires :maximumWeight"
          (cache/make-cache {:weigher (common/reify-weigher (fn [_ _ _] (int 1)))})))))

(deftest record-stats-and-stats-counter-supplier-are-exclusive
  (is (thrown-with-msg? ExceptionInfo #"mutually exclusive"
        (cache/make-cache {:recordStats true
                           :statsCounterSupplier (constantly nil)}))))

(defspec every-time-unit-builds-a-cache 50
  (prop/for-all [unit (gen/elements [:ms :us :s :m :h :d])
                 n (gen/choose 1 1000)]
    ;; a FakeTicker that never advances keeps this independent of wall-clock
    ;; time, which would otherwise expire the sub-millisecond units.
    (let [c (cache/make-cache {:expireAfterWrite n
                               :timeUnit unit
                               :ticker (reify-ticker (FakeTicker.))})]
      (cache/put! c :k :v)
      (= :v (cache/get-if-present c :k)))))

(deftest expire-after-write-ignores-reads
  (testing "reads do not extend an expireAfterWrite deadline"
    (let [ticker (FakeTicker.)
          c (cache/make-cache {:expireAfterWrite 10
                               :timeUnit :s
                               :ticker (reify-ticker ticker)})]
      (cache/put! c :k :v)
      (dotimes [_ 5]
        (.advance ticker 3 TimeUnit/SECONDS)
        (cache/get-if-present c :k))
      (cache/cleanup c)
      (is (nil? (cache/get-if-present c :k))))))

(deftest millisecond-time-unit-is-honoured
  (let [ticker (FakeTicker.)
        c (cache/make-cache {:expireAfterWrite 100
                             :timeUnit :ms
                             :ticker (reify-ticker ticker)})]
    (cache/put! c :k :v)
    (.advance ticker 50 TimeUnit/MILLISECONDS)
    (is (= :v (cache/get-if-present c :k)))
    (.advance ticker 100 TimeUnit/MILLISECONDS)
    (cache/cleanup c)
    (is (nil? (cache/get-if-present c :k)))))

(defspec duration-and-time-unit-expire-identically 50
  (prop/for-all [seconds (gen/choose 1 60)
                 advance-by (gen/choose 1 120)]
    (let [ticker (FakeTicker.)
          with-unit (cache/make-cache {:expireAfterWrite seconds
                                       :timeUnit :s
                                       :ticker (reify-ticker ticker)})
          with-duration (cache/make-cache {:expireAfterWrite (Duration/ofSeconds seconds)
                                           :ticker (reify-ticker ticker)})]
      (cache/put! with-unit :k :v)
      (cache/put! with-duration :k :v)
      (.advance ticker advance-by TimeUnit/SECONDS)
      (cache/cleanup with-unit)
      (cache/cleanup with-duration)
      (= (cache/get-if-present with-unit :k)
         (cache/get-if-present with-duration :k)))))

;; ---------------------------------------------------------------------------
;; Statistics

(defspec stats-account-for-every-request 100
  (prop/for-all [present (gen/vector gen-key 1 10)
                 lookups (gen/vector gen-key 0 30)]
    (let [c (cache/make-cache {:recordStats true})
          present? (set present)]
      (doseq [k present]
        (cache/put! c k :v))
      (doseq [k lookups]
        (cache/get-if-present c k))
      (let [{:keys [hitCount missCount requestCount hitRate missRate]} (common/stats c)
            expected-hits (count (filter present? lookups))]
        (and (= (count lookups) requestCount)
             (= requestCount (+ hitCount missCount))
             (= expected-hits hitCount)
             (<= 0.0 hitRate 1.0)
             (<= 0.0 missRate 1.0))))))
