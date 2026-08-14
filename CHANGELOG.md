#### VERSION 1.1.0
* Caffeine 3.2.4 (from 3.0.2)
* `:maximumWeight` and `:scheduler` builder settings
* `:expireAfterAccess`, `:expireAfterWrite` and `:refreshAfterWrite` also accept a
`java.time.Duration`
* `:maximumSize` is no longer narrowed to an int, so bounds above `Integer/MAX_VALUE` work
* `:weigher` without `:maximumWeight` is rejected up front — caffeine ignores a weigher
without a weight bound
* Property-based tests (`test.check`) and a mutation-testing suite
* A `deps.edn` for tools.deps users, alongside the existing `project.clj`

**Behaviour change inherited from caffeine 3.1.0** ("return the new value if computed by
the caller", ben-manes/caffeine#688, #699): once `refreshAfterWrite` has elapsed, a read
may return the *refreshed* value instead of the stale one. Up to caffeine 3.0.x the stale
value was always returned until the refresh completed. A read still never blocks on the
reload, and a failing reload still keeps the previous value.

#### VERSION 1.0.0
* Caffeine 3.0.2
* Commitment on backwards compatibility
* Add async `loading-cache/refresh-all`

#### VERSION 0.1.9
* Clarify docstrings
* Differentiate the construction of an AsyncLoadingCache that uses a CacheLoader
vs. an AsyncCacheLoader

#### VERSION 0.1.8
* Bump caffeine to 2.8.8

#### VERSION 0.1.7
* Bump caffeine to 2.8.7

#### VERSION 0.1.5
* Bump caffeine to 2.8.4

#### VERSION 0.1.4
* Bump caffeine to 2.8.2
