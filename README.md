# Cloffeine 

Simple clojure wrapper over [`Caffeine`](https://github.com/ben-manes/caffeine).

> **hive-agi fork.** Published as `io.github.hive-agi/cloffeine` so hive projects can
> use caffeine 3.2.4 while [AppsFlyer/cloffeine#16](https://github.com/AppsFlyer/cloffeine/pull/16)
> is pending upstream. Namespaces are unchanged (`cloffeine.*`), so it is a drop-in
> replacement for `com.appsflyer/cloffeine`. Once the PR lands, switch back to upstream.
>
> ```clojure
> ;; deps.edn
> :mvn/repos {"hive-gitea" {:url "https://gitea.hive-mcp.com/api/packages/hive-agi/maven"}}
> :deps {io.github.hive-agi/cloffeine {:mvn/version "1.1.0"}}
> ```
>
> Releases are cut locally: `clojure -T:build bump :level :patch` then `clojure -T:build deploy`.

[![Clojars Project](https://img.shields.io/clojars/v/com.appsflyer/cloffeine.svg)](https://clojars.org/com.appsflyer/cloffeine)

[![Coverage Status](https://coveralls.io/repos/github/AppsFlyer/cloffeine/badge.svg?branch=master)](https://coveralls.io/github/AppsFlyer/cloffeine?branch=master)

[![cljdoc badge](https://cljdoc.org/badge/com.appsflyer/cloffeine)](https://cljdoc.org/d/com.appsflyer/cloffeine/CURRENT)

## Installing
Add `[com.appsflyer/cloffeine "1.1.0"]` to your `project.clj` under `:dependencies`,
or `com.appsflyer/cloffeine {:mvn/version "1.1.0"}` to your `deps.edn`.

## [Checkout the docs](https://appsflyer.github.io/cloffeine/index.html)

## Stability
* This project is used in production already and is deemed stable.
* Since 1.0.0 the project will change the major semver iff Caffeine does so (currently at 3.x).


## Testing

```bash
lein test          # or: clojure -X:test
```

The suite has three parts: example-based tests (`cloffeine.core-test`),
property-based tests over generated keys, values and operation sequences
(`cloffeine.property-test`), and a mutation-testing suite
(`cloffeine.mutation-test`) that injects defects into `cloffeine.common` at load
time and asserts each one is detected by the tests.

## Usage

### Manual loading

```clojure
(require '[cloffeine.cache :as cache])
(require '[clojure.test :refer [is]])

(def cache (cache/make-cache))
(cache/put! cache :key :v)
(is (= :v (cache/get cache :key name)))
(cache/invalidate! cache :key)
(is (= "key" (cache/get cache :key name)))
```

### Automatic loading

```clojure
(require '[cloffeine.loading-cache :as loading-cache])
(require '[cloffeine.common :as common])

(def loads (atom 0))
(def cl (common/reify-cache-loader (fn [k]
                                      (swap! loads inc)
                                      (name k))))
(def lcache (loading-cache/make-cache cl))
(loading-cache/put! lcache :key :v)
(is (= :v (loading-cache/get lcache :key)))
(is (= 0 @loads))
(loading-cache/invalidate! lcache :key)
(is (= "key" (loading-cache/get lcache :key)))
(is (= 1 @loads))
(is (= "key" (loading-cache/get lcache :key name)))
(is (= 1 @loads))
(is (= "key" (cache/get lcache :key name)))
(is (= 1 @loads))
(cache/invalidate! lcache :key)
(is (= "key" (cache/get lcache :key name)))
(is (= 1 @loads))
```

#### Refreshing

Since caffeine 3.1.0, a read that finds a `:refreshAfterWrite` deadline elapsed may
return the refreshed value rather than the stale one — the reload is computed by the
caller when possible. The read never blocks on the reload, and a reload that throws
(or a rejected promise, for the async loaders) leaves the previous value in place.

```clojure
(def lcache (loading-cache/make-cache cl {:refreshAfterWrite 10 :timeUnit :s}))
;; equivalently, with a java.time.Duration
(def lcache (loading-cache/make-cache cl {:refreshAfterWrite (java.time.Duration/ofSeconds 10)}))
```

### Bounding a cache

```clojure
;; by entry count
(cache/make-cache {:maximumSize 10000})

;; by weight — :weigher requires :maximumWeight
(def weigher (common/reify-weigher (fn [_this _k v] (count v))))
(cache/make-cache {:maximumWeight 1000 :weigher weigher})
```

### Async cache

```clojure
(require '[cloffeine.async-cache :as async-cache])
(require '[promesa.core :as p])

(def acache (async-cache/make-cache))
(async-cache/put! acache :key (p/resolved :v))
(is (= :v @(async-cache/get acache :key name)))
(async-cache/invalidate! acache :key)
(is (= "key" @(async-cache/get acache :key name)))
```

### Async with automatic loading:

```clojure
(require '[cloffeine.async-loading-cache :as async-loading-cache])

(def alcache (async-loading-cache/make-cache (common/reify-cache-loader name)))
(async-loading-cache/put! alcache :key (p/resolved :v))
(is (= :v @(async-loading-cache/get alcache :key name)))
(async-loading-cache/invalidate! alcache :key)
(is (= "key" @(async-loading-cache/get alcache :key name)))
```
