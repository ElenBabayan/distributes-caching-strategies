# Performance Evaluation of Distributed Caching Strategies: Impact on System Latency and Scalability

**Independent Study in Scalable Distributed Systems**
**Master of Computer Science**

---

## Abstract

Caching is a foundational technique in distributed systems engineering, yet the selection of an appropriate caching architecture is rarely straightforward. This paper presents a student-scale experimental evaluation of three caching topologies — local in-process caching (Caffeine), distributed caching (Redis), and multi-tier hybrid caching — combined with three write policies: cache-aside, write-through, and write-back. A Java benchmark harness was designed and implemented for this project, measuring p50/p95/p99 latency, throughput, and cache hit ratio across read-heavy (90R/10W), write-heavy (30R/70W), and mixed (50R/50W) workloads, as well as a horizontal scalability experiment from 1 to 32 replicas.

The experiments surface several results that were not obvious from prior literature or initial intuition. Write-through policy reduces read tail latency compared to cache-aside by maintaining a warmer cache, not increasing it. Cache-aside hit ratio falls from approximately 87% to 31% when write fraction increases from 10% to 70%, because continuous invalidations deplete the cache. Write-back policy's latency advantage diminishes under heavy write loads as the asynchronous flush queue saturates. And as replica count grows from 1 to 32, local cache hit ratio degrades from 75.5% to 49.4% while distributed cache hit ratio improves from 75.5% to 99.0% — both starting from the same initial point.

These results are specific to the described testbed: a single laptop, a local Redis instance, and a simulated backing store. Generalizations to production environments require caution, and the limitations of this setup are discussed in detail.

---

## 1. Introduction

Modern distributed applications routinely serve millions of requests per second with end-user experience tightly coupled to response latency. A 100ms increase in latency has been documented to reduce Amazon's revenue by 1% [Kohavi & Longbotham, 2007], and Google Search has documented a direct correlation between serving latency and user engagement [Brutlag, 2009]. Caching — storing computed or retrieved data at a layer closer to the consumer — is the most widely deployed technique for reducing latency and shielding storage backends from request amplification.

Despite its ubiquity, caching strategy selection remains largely ad hoc in practice. Engineering teams frequently begin with a simple local cache, encounter consistency problems as their service horizontally scales, migrate to a distributed cache cluster such as Redis or Memcached, and subsequently encounter elevated tail latency under write-heavy conditions. A principled understanding of how topology and write policy interact with workload characteristics could help practitioners avoid this reactive cycle.

The challenge is that caching architecture has two largely orthogonal degrees of freedom — topology (where data lives) and write policy (when data is synchronized) — and their interaction with workload characteristics is non-linear and not fully characterized in the existing literature. The systems literature focuses primarily on topology and consistency [Nishtala et al., 2013; Dean & Barroso, 2013], while write policy selection is treated as secondary in most published evaluations. This project attempts a controlled, if modest, empirical comparison of all nine topology × write-policy combinations under multiple workload profiles.

The central research question is: **How do different caching architectures affect system latency, cache hit ratio, and horizontal scalability under varying workloads?** The study is exploratory in nature — the goal is to characterize behavior and surface non-obvious interactions, not to make universal performance claims.

The remainder of this paper is organized as follows. Section 2 surveys related work. Section 3 defines the architectures and policies under study. Section 4 describes the implementation and experimental methodology. Section 5 presents the results. Section 6 provides comparative analysis. Section 7 discusses threats to validity. Section 8 derives practical guidelines. Section 9 concludes.

---

## 2. Background and Related Work

### 2.1 Caching in Distributed Systems

Caching has been studied extensively across CPU memory hierarchies, CDN edge delivery, and application-layer data stores. Belady's optimal replacement algorithm [Belady, 1966] established theoretical upper bounds on hit ratios under known access sequences. Practical eviction policies — LRU, LFU, ARC, and their variants — approximate optimal behavior under real-world patterns. Mattson et al.'s stack distance model [1970] provides a framework for understanding how cache size interacts with hit ratio for a given access distribution.

At the distributed systems layer, Nishtala et al.'s 2013 paper on Facebook's Memcached deployment is the most comprehensive public description of large-scale cache infrastructure [Nishtala et al., 2013]. Their work documents the evolution from a simple key-value cache to a regionally distributed system handling billions of requests per day, and is notable for showing that cache architecture must evolve with scale — a theme this project's scalability results echo, albeit at a much smaller scale.

### 2.2 Distributed Cache Systems

Redis [Carlson, 2013] and Memcached [Fitzpatrick, 2004] are the dominant open-source distributed caching systems. Both rely on consistent hashing [Karger et al., 1997] to distribute keys across nodes with bounded key migration during topology changes. In-process JVM caching is most commonly implemented with Caffeine [Manes, 2015], which uses a Window-TinyLFU admission policy [Einziger et al., 2017]. Window-TinyLFU uses a frequency sketch to approximate optimal eviction decisions, reducing the lock contention that plagues global LRU implementations under high concurrency.

### 2.3 Tail Latency

Dean and Barroso [2013] document the "tail at scale" phenomenon: when a request fans out to multiple backend services, the probability that at least one exhibits high latency grows rapidly with fan-out depth. Caching reduces this fan-out, but as this project's write-heavy experiments show, cache misses under high write load can reintroduce tail latency through backing-store amplification.

### 2.4 Write Policies

Write-back, write-through, and cache-aside are well-established patterns in systems design, described in foundational treatments of database and storage systems [Mattson et al., 1970; Rabinovich & Spatscheck, 2002]. Write-through and cache-aside have been analyzed specifically in the context of database query caches [Altinel et al., 2003] and CDN origin synchronization [Rabinovich & Spatscheck, 2002]. This project extends prior work by measuring write policy effects on tail latency under concurrent JVM workloads and by observing write-back queue saturation as a specific failure mode under write-heavy conditions — an interaction that does not appear to be characterized in the literature reviewed for this project.

### 2.5 Multi-Tier Caching

Multi-tier caching — a local L1 backed by a shared distributed L2 — is deployed at scale by several large internet companies. Huang et al. [2013] analyze Facebook's photo caching hierarchy, showing that a local tier absorbs a substantial fraction of read traffic before it reaches the distributed tier. The primary challenge is maintaining consistency across tiers during writes, which requires an invalidation propagation mechanism not needed in single-tier designs.

---

## 3. Caching Architectures and Write Policies

### 3.1 Topology Definitions

**Local In-Process Cache.** Data is stored in the same process memory as the application, implemented here using Caffeine with Window-TinyLFU eviction. Lookups incur no network round-trip; latency is bounded by JVM heap access time. The cache is private to a single application instance, creating consistency hazards in multi-replica deployments.

**Distributed Cache Cluster.** A shared Redis instance deployed as a separate tier. Application instances share a unified cache namespace. Lookups incur a network round-trip. In this project, Redis runs on the same machine (localhost), so the measured network cost reflects loopback TCP overhead and Redis server processing, not real network latency. Results should be interpreted accordingly.

**Multi-Tier Hybrid.** A local Caffeine L1 backed by a distributed Redis L2. Read requests check L1 first, then L2, then the backing store, populating tiers on miss. Write invalidations propagate to L2 and evict from L1. This design aims to combine local access speed for hot keys with shared capacity and consistency from the distributed tier.

### 3.2 Write Policy Definitions

**Cache-Aside (Lazy Population).** The application manages cache state explicitly. On a write, the application updates the backing store and then invalidates the cache entry. This is conceptually simple but — as this project's write-heavy results demonstrate — continuous invalidation under high write rates can keep the cache chronically cold.

**Write-Through.** Every backing-store write is simultaneously written to the cache synchronously. This populates the cache with freshly written data. The surprising effect in this project's results is that write-through's cache population behavior improves hit ratios enough to lower read tail latency despite adding overhead to the write path.

**Write-Back (Write-Behind).** Writes are made to the cache first and acknowledged immediately; a background flush thread asynchronously drains a queue to the backing store. This minimizes write latency when the queue is not saturated, but introduces p99 spikes when the write rate exceeds the flush rate. The queue saturation effect was not anticipated at the start of this project and emerged from the write-heavy experiments.

---

## 4. Implementation and Experimental Methodology

### 4.1 What Was Built

This project required designing and implementing a benchmark harness from scratch. The main engineering work consisted of:

**Cache abstraction layer.** A `CacheLayer` interface was defined with `get`, `put`, `invalidate`, and hit/miss counters. Two implementations were written: `LocalCacheLayer` wrapping Caffeine, and `RedisCacheLayer` wrapping Jedis with a connection pool. This abstraction allowed the same benchmark runner to work across all topology configurations without duplication.

**Write policy executor.** A `CachePolicyExecutor` class implements all nine topology × write-policy combinations as two `switch` expressions — one for the read path (L1 check → L2 check → backing store) and one for the write path (varying by policy). Keeping both in one class made it easy to verify that the read path was identical across write policies, which is a correctness invariant the benchmark depends on.

**Write-back queue.** The `WriteBackQueue` class uses a `LinkedBlockingQueue` drained by a single daemon thread. A capacity cap (100,000 entries) prevents unbounded memory use; writes exceeding the cap are flushed synchronously, acting as a safety valve. This safety valve is what causes the p99 spikes observed under write-heavy conditions.

**Zipfian key generator.** A `ZipfianGenerator` class precomputes the cumulative distribution function for a Zipfian distribution with configurable α and keyspace size, then uses binary search on a uniform random variate for O(log N) key generation. For α = 1.0 and N = 10,000 keys, the top 20% of keys account for approximately 80% of accesses.

**Benchmark runner.** The `BenchmarkRunner` class runs an 8-thread workload with a warmup phase (5,000 ops/thread, stats discarded) followed by a measurement phase (20,000 ops/thread). Latency is recorded into HDR histograms, which provide nanosecond-precision p50/p95/p99 values. Read and write latencies are tracked in separate histograms.

**Scalability experiment.** `ScalabilityExperiment` varies replica count from 1 to 32, with each replica simulated as a separate thread holding its own local cache. The total cache budget (3,000 keys) is divided across replicas for the local topology and shared for the distributed topology.

Two bugs were encountered and fixed during implementation. First, the HDR histogram's maximum trackable value was initially set to 1 second; a Hybrid × WriteThrough configuration under write-heavy load occasionally produced write latencies above 1 second due to thread scheduling delays, causing an `ArrayIndexOutOfBoundsException`. The fix was to extend the histogram range to 60 seconds. Second, in the scalability experiment, each replica's executor was closing the shared Redis connection pool when the replica finished, causing subsequent replicas to fail with pool-not-open errors. The fix was to make replica executors responsible only for their own local cache lifecycle, with the shared Redis pool managed by the outer experiment.

### 4.2 Hardware and Software Environment

All experiments were run on a MacBook Pro with an Apple M3 Pro chip (12 cores: 6 performance + 6 efficiency), 36 GB unified memory, running macOS. JVM: OpenJDK 21.0.7 (Homebrew build). Redis version: as available on localhost, DB index 2. No JVM tuning flags were set; the JVM ran with default GC (G1GC) settings.

Running benchmarks on a laptop introduces measurement noise from background processes, thermal throttling, and macOS scheduling. These are genuine limitations discussed in Section 7.

### 4.3 Workload Profiles

Three workload profiles are tested:

- **Read-Heavy (RH):** 90% reads, 10% writes.
- **Write-Heavy (WH):** 30% reads, 70% writes.
- **Mixed (MX):** 50% reads, 50% writes.

Key access follows a Zipfian distribution with α = 1.0 and a 10,000-key keyspace. Cache sizes: L1 holds 3,000 keys (30% of keyspace); L2 holds 6,000 keys (60%). Redis DB 2 is flushed between configuration runs to prevent cross-run contamination.

The backing store is an in-memory `ConcurrentHashMap` with injected exponential-distribution latency (mean 2ms reads, mean 4ms writes). This is a deliberate simplification — it does not model a real database — and its implications for result interpretation are addressed in Section 7.

### 4.4 Repeatability

Each of the 27 configurations was run twice independently. Table 0 shows p99 read latency and hit ratio from both runs for a representative subset of configurations, to give a sense of measurement stability.

**Table 0. Two-run comparison for selected configurations (Read-Heavy workload). All latency values in milliseconds.**

| Configuration | Run 1 r-p99 | Run 2 r-p99 | Δ | Run 1 hit | Run 2 hit | Δ |
|---|---|---|---|---|---|---|
| Local × CacheAside | 6.26 | 6.26 | 0.0% | 0.787 | 0.789 | 0.3% |
| Local × WriteThrough | 5.06 | 5.05 | 0.2% | 0.876 | 0.877 | 0.1% |
| Local × WriteBack | 5.12 | 6.01 | 17.4% | 0.876 | 0.876 | 0.0% |
| Distributed × WriteThrough | 1.44 | 1.44 | 0.0% | 0.982 | 0.982 | 0.0% |
| Distributed × WriteBack | 1.43 | 1.37 | 4.2% | 0.982 | 0.982 | 0.0% |

Hit ratios are highly stable across runs (maximum observed deviation: 0.3%). Read p99 latency is stable for all configurations except Local WriteBack, where run-to-run variance reaches 17%. This is attributable to JVM GC pause timing: G1GC pauses are infrequent stochastic events, and whether a GC pause happens to coincide with a p99 sample collection window varies between runs. All other p99 values vary by less than 5%. The final paper reports results from the second (complete) run.

The scalability experiment was run once. Hit ratio in that experiment should be more stable than p99 latency because it is computed over a larger sample (8,000 × N operations vs. 20,000 operations for the latency experiment at each scale point).

---

## 5. Experimental Results

### 5.1 Latency Under Read-Heavy Workload (90R/10W)

**Table 1. Latency under Read-Heavy workload (90R/10W). All values in milliseconds.**

| Architecture | Write Policy | r-p50 | r-p95 | r-p99 | w-p50 | w-p95 | w-p99 | Hit Ratio |
|---|---|---|---|---|---|---|---|---|
| Local | CacheAside | <0.001 | 2.888 | 6.263 | 2.765 | 13.222 | 20.021 | 0.789 |
| Local | WriteThrough | <0.001 | 1.795 | 5.050 | 2.783 | 13.763 | 20.693 | 0.877 |
| Local | WriteBack | <0.001 | 1.801 | 6.013 | <0.001 | 0.001 | 0.003 | 0.876 |
| Distributed | CacheAside | 0.117 | 2.081 | 5.333 | 2.900 | 13.459 | 20.136 | 0.876 |
| Distributed | WriteThrough | 0.069 | 0.173 | 1.442 | 2.957 | 13.836 | 20.152 | 0.982 |
| Distributed | WriteBack | 0.113 | 0.166 | 1.371 | 0.113 | 0.160 | 0.191 | 0.982 |
| Hybrid | CacheAside | <0.001 | 2.265 | 5.566 | 3.039 | 13.910 | 20.414 | 0.801 |
| Hybrid | WriteThrough | <0.001 | 0.298 | 1.692 | 3.037 | 13.918 | 20.627 | 0.889 |
| Hybrid | WriteBack | <0.001 | 0.117 | 1.265 | 0.094 | 0.195 | 0.302 | 0.888 |

Local and Hybrid configurations achieve sub-millisecond read p50 because cache hits require only a JVM heap lookup. Distributed CacheAside records 0.117ms — the Redis loopback round-trip cost. These numbers are not directly comparable to distributed systems running over real networks, where Redis p50 would typically be 0.5–2ms.

The most unexpected result in Table 1 is that Distributed WriteThrough records a *lower* read p99 (1.442ms) than Distributed CacheAside (5.333ms), despite imposing additional overhead on the write path. The mechanism is hit ratio: WriteThrough caches every written key immediately, producing a 98.2% hit rate vs. CacheAside's 87.6%. That 10.6 percentage-point difference reduces backing-store read frequency by roughly 85%, and since backing-store reads dominate p99 (mean 2ms exponential), the tail is correspondingly lower. This was not anticipated before running the experiments.

Local WriteBack achieves a write p99 of 0.003ms — the time for a single Caffeine `put()` call — because all backing-store writes are handled asynchronously.

**Finding 1:** Under read-heavy workloads, write-through and write-back policies achieve lower read p99 than cache-aside for Distributed and Hybrid topologies, because they maintain higher hit ratios (98% vs. 88%). The write-through "cost" is real on the write path but the tradeoff often favors read latency improvement.

### 5.2 Latency Under Write-Heavy Workload (30R/70W)

**Table 2. Latency under Write-Heavy workload (30R/70W). All values in milliseconds.**

| Architecture | Write Policy | r-p50 | r-p99 | w-p50 | w-p99 | Hit Ratio |
|---|---|---|---|---|---|---|
| Local | CacheAside | 0.637 | 9.208 | 2.775 | 20.054 | 0.314 |
| Local | WriteThrough | 0.001 | 5.046 | 2.789 | 20.414 | 0.883 |
| Local | WriteBack | 0.001 | 6.013 | 0.001 | 17.285 | 0.883 |
| Distributed | CacheAside | 1.120 | 9.560 | 3.058 | 20.414 | 0.314 |
| Distributed | WriteThrough | 0.202 | 1.700 | 3.045 | 20.365 | 0.983 |
| Distributed | WriteBack | 0.142 | 1.562 | 0.144 | 15.335 | 0.983 |
| Hybrid | CacheAside | 1.091 | 10.158 | 3.029 | 20.496 | 0.313 |
| Hybrid | WriteThrough | 0.001 | 1.820 | 3.105 | 20.660 | 0.894 |
| Hybrid | WriteBack | 0.001 | 1.451 | 0.153 | 15.442 | 0.894 |

The dominant result under write-heavy conditions is cache-aside's hit ratio collapse. All three topologies with cache-aside record a hit ratio around 0.31 — meaning roughly 69% of reads still go to the backing store, making the cache nearly useless for read acceleration. The mechanism is continuous invalidation: with 70% of operations being writes, cache entries are evicted nearly as fast as they are populated. Write-through avoids this by making each write also a cache population event.

Local CacheAside under write-heavy load records a read p50 of 0.637ms, compared to 0.001ms for Local WriteThrough — a 637× difference in median latency, caused entirely by the hit ratio gap (31% vs. 88%).

The second finding is that write-back p99 under write-heavy conditions is 15–17ms, not the near-zero values seen under read-heavy conditions. The write-back flush queue saturates when 8 threads generate writes faster than the single flush thread can drain them. Once the 100,000-entry queue fills, overflow writes are handled synchronously, introducing backing-store latency into the p99. This is an inherent architectural constraint of single-threaded write-back flushing, not a bug — but it means write-back's advantage is workload-dependent in a way that may not be obvious from its design description alone.

**Finding 2:** Cache-aside hit ratio collapses from ~88% to ~31% when write fraction increases from 10% to 70%, because continuous invalidations keep the cache cold regardless of topology. Write-through avoids this collapse by populating the cache on writes.

**Finding 3:** Write-back p99 under write-heavy conditions (15–17ms) is comparable to write-through, not the sub-millisecond values observed under read-heavy conditions. The asynchronous flush queue saturates when write throughput exceeds the flush thread's drain rate.

### 5.3 Mixed Workload (50R/50W)

**Table 3. Latency under Mixed workload (50R/50W). All values in milliseconds.**

| Architecture | Write Policy | r-p50 | r-p99 | w-p50 | w-p99 | Hit Ratio | TPS |
|---|---|---|---|---|---|---|---|
| Local | CacheAside | 0.047 | 8.765 | 2.783 | 20.136 | 0.490 | 2,966 |
| Local | WriteThrough | 0.001 | 5.292 | 2.951 | 21.348 | 0.883 | 3,352 |
| Local | WriteBack | 0.001 | 5.014 | 0.001 | 0.002 | 0.883 | 55,404 |
| Distributed | CacheAside | 0.449 | 9.241 | 3.052 | 20.365 | 0.497 | 2,630 |
| Distributed | WriteThrough | 0.157 | 1.901 | 2.994 | 20.529 | 0.982 | 3,333 |
| Distributed | WriteBack | 0.124 | 1.485 | 0.124 | 0.271 | 0.982 | 52,466 |
| Hybrid | CacheAside | 0.311 | 9.298 | 3.043 | 20.496 | 0.493 | 2,705 |
| Hybrid | WriteThrough | 0.001 | 1.747 | 3.090 | 20.480 | 0.894 | 3,405 |
| Hybrid | WriteBack | 0.001 | 1.297 | 0.122 | 0.195 | 0.894 | 85,819 |

The throughput numbers under the mixed workload illustrate the write-back advantage most clearly. Hybrid WriteBack achieves 85,819 ops/sec compared to 3,405 ops/sec for Hybrid WriteThrough. This 25× gap arises because write-back removes the backing store from the write critical path: a write completes after a Caffeine put (sub-millisecond) and a Redis set (~0.1ms) rather than a backing-store write (~4ms mean). With 50% of operations being writes, this reduction has a compounding effect on throughput. Note that the backing-store latency in this experiment (2–4ms) is simulated, so the actual throughput multiple in production would depend on real backing-store latency.

Hybrid WriteBack also records the lowest read p99 of any configuration across any workload in this study (1.265ms for read-heavy, 1.297ms for mixed), because the L1 cache absorbs hot reads at local speed while the warm L2 serves cold-key fallthrough without triggering backing-store reads.

**Finding 4:** Write-back achieves 25× higher throughput than write-through under the mixed workload (85,819 vs. 3,405 ops/sec for Hybrid) by decoupling writes from backing-store I/O. This advantage holds only when write rate does not saturate the flush queue.

### 5.4 Horizontal Scalability

**Table 4. Hit ratio and throughput vs. replica count. Read-heavy workload, cache-aside policy, fixed total cache budget (3,000 keys).**

| | N=1 | N=2 | N=4 | N=8 | N=16 | N=32 |
|---|---|---|---|---|---|---|
| **Local hit ratio** | 0.755 | 0.723 | 0.673 | 0.621 | 0.566 | 0.494 |
| **Distributed hit ratio** | 0.755 | 0.822 | 0.880 | 0.931 | 0.968 | 0.990 |
| **Hybrid hit ratio** | 0.755 | 0.768 | 0.819 | 0.886 | 0.937 | 0.969 |
| Local TPS | 1,456 | 2,628 | 4,667 | 8,029 | 14,400 | 25,154 |
| Distributed TPS | 941 | 2,440 | 7,666 | 22,123 | 37,545 | 47,642 |
| Hybrid TPS | 1,156 | 2,880 | 7,679 | 24,577 | 61,212 | 74,974 |

All three topologies start from the same hit ratio (0.755) at N=1. As replicas scale, the behaviors diverge:

**Local** hit ratio degrades monotonically to 0.494 at N=32 (−34.5%). With a fixed total cache budget, each replica receives 3,000/N capacity. At N=32, each replica holds only 94 keys. With random request routing, the hot working set (~2,000 keys for Zipf α=1.0) does not fit in any single replica's cache. Note that key-affine load balancing would substantially change this result — assigning each key to a designated replica preserves hit ratio regardless of N. Random routing is the worst case.

**Distributed** hit ratio improves to 0.990 at N=32 (+31.1%). With a shared namespace, additional replicas contribute write traffic that populates the shared cache faster. At high replica counts, the shared Redis instance holds a warm representation of the full working set, and nearly every read is served from cache. The single-machine Redis instance in this experiment would eventually become a throughput bottleneck as replicas scale further, but within the 32-replica range tested, it does not appear to be the limiting factor (TPS is 47,642 at N=32 with 4ms backing-store writes).

**Hybrid** tracks Distributed at scale because the shared L2 progressively dominates as L1 capacity per replica shrinks.

**Finding 5:** Local and distributed topologies diverge in hit ratio from an identical starting point under random routing with fixed cache budget. At N=32, local hit ratio falls to 49% while distributed rises to 99%. This is likely the most operationally significant finding in this study — it suggests that local caching requires either key-affine routing or a transition to distributed caching as fleet size grows.

---

## 6. Comparative Analysis

### 6.1 Write Policy Effect Is Larger Than Topology Effect Under Write-Heavy Workloads

The data suggests that write policy choice has a larger effect on hit ratio than topology choice when write fraction is high. Under write-heavy conditions, all topologies with cache-aside record approximately the same hit ratio (0.31–0.31), and all topologies with write-through record approximately the same hit ratio (0.88–0.98). The topology choice affects the *magnitude* of read latency (local sub-ms vs. distributed ~0.2ms median under write-through), but write policy determines whether the cache is useful at all.

This was the most consequential pattern in the results, and it was not anticipated from the initial framing of the project. The original hypothesis was that topology would be the dominant factor; write policy was treated as a secondary parameter.

### 6.2 Write-Through's Read Latency Benefit

A recurring pattern in the data is that write-through achieves lower read p99 than cache-aside despite adding overhead to writes. For Distributed topology, the read p99 improvement is 5.33ms → 1.44ms (−73%) while write p50 only increases by ~0.05ms. The underlying reason is that write-through's cache population behavior raises hit ratio from 87.6% to 98.2%, reducing the frequency of slow backing-store reads that drive p99.

This result challenges the common framing of write-through as a latency cost. Whether this generalizes depends on the ratio of write-path overhead to backing-store read latency. If backing-store latency is much higher relative to cache write latency (as it is in production systems with remote databases), the read latency benefit would be larger, not smaller, than what this experiment shows.

### 6.3 Write-Back: Conditional on Write Rate

Write-back's latency advantage is workload-dependent in a way that matters for architecture selection. Local WriteBack write p99: 0.003ms (read-heavy) vs. 17.3ms (write-heavy). The saturation threshold depends on the flush thread's drain rate relative to write arrival rate. In this implementation, the single-threaded flush is the limiting factor. A multi-threaded or batched flush implementation might raise this threshold, but would introduce its own complexity.

Operationally, flush queue depth is the leading indicator for write-back saturation — a growing queue signals that the system is approaching the write-back p99 degradation regime before it appears in latency histograms.

---

## 7. Threats to Validity

This section describes the limitations of the experimental setup that should be considered when interpreting the results.

**Localhost Redis, not real network.** The distributed cache in this experiment runs on the same machine as the application, connected over the loopback interface. Real network round-trips to a co-located Redis instance in a datacenter are typically 0.5–2ms. The p50 Redis read latency in this experiment (0.11–0.12ms) is approximately 4–18× lower than production. This means the absolute latency numbers for all Distributed and Hybrid configurations are not directly comparable to production systems. The relative orderings and hit ratio numbers, which are independent of network latency, are more likely to generalize.

**Simulated backing store.** The backing store is an in-memory `ConcurrentHashMap` with injected artificial latency, not a real database. The latency distribution (exponential, mean 2ms reads / 4ms writes) was chosen to be plausible for a co-located database, but the backing store's concurrency model (a Java ConcurrentHashMap with no lock contention at the storage layer) does not model a real database's behavior under contention. Cache misses may be less expensive in this experiment than in production if the real backing store exhibits queuing under load.

**Single machine, macOS scheduling.** macOS is not a real-time OS. Background processes and OS scheduling can introduce latency outliers, particularly in p99 measurements. The write-back p99 variance between runs (17% for Local WriteBack) is partly attributable to this. Hardware thermal throttling on a laptop under sustained multi-threaded load may also affect later benchmark runs compared to earlier ones, though the ordering of configurations was not randomized in this study.

**Single workload distribution.** All experiments use Zipfian α = 1.0. Real workloads may have different access skew. A flatter distribution (smaller α) would produce lower hit ratios across all configurations; a more skewed distribution (larger α) would produce higher hit ratios. The qualitative comparisons between policies should hold across different α values, but the specific hit ratio numbers are specific to α = 1.0.

**No repeated trials / no confidence intervals.** Each of the 27 configurations was run twice; Table 0 reports the run-to-run comparison, showing stable results except for write-back p99 under read-heavy conditions. Formal confidence intervals were not computed. For p50 and hit ratio, the stability across two runs suggests the estimates are reliable. For p99, particularly for Local WriteBack, the reader should treat reported values as approximations with roughly ±15–20% uncertainty.

**No Redis Cluster, no failure recovery.** The experiment uses a single Redis instance, not a Redis Cluster. Consistent hashing and key migration behavior under cluster resizing are not measured. Node failure and recovery are not tested. The scalability experiment's "32 replicas" simulates fleet-level scaling of the application tier, not cache-tier scaling.

**Write-back queue implementation.** The write-back flush thread is single-threaded. A production write-back implementation might use batched writes, multiple flush threads, or adaptive flushing. The saturation behavior observed under write-heavy conditions reflects this specific implementation, and a more sophisticated implementation might raise the saturation threshold.

---

## 8. Practical Selection Guidelines

With the limitations above in mind, the experimental results suggest the following guidelines. These are patterns observed in a controlled benchmark; production deployments should validate them with workload-specific measurements.

### 8.1 Workload-Based Recommendations

**Read-Heavy (<20% writes), Small Deployment (<4 replicas):**
Local write-through is a reasonable starting point. This project's results show 0.877 hit ratio with sub-millisecond read median latency and write latency dominated by backing-store speed (~2.78ms). Cache-aside is viable but produces lower hit ratios under this configuration (0.789). The 0.088 hit ratio difference between the two policies would be more pronounced on a slower backing store.

**Read-Heavy, Large Deployment (>8 replicas, random routing):**
The scalability results suggest that local caching hit ratio degrades significantly past 8 replicas under random routing. Distributed or hybrid with write-through or write-back is preferable. If key-affine routing is implemented at the load balancer, local caching can maintain hit ratio at scale — that case was not tested here.

**Write-Heavy (>50% writes):**
Avoid cache-aside. The hit ratio collapse from ~88% to ~31% observed in this experiment makes cache-aside functionally equivalent to no cache under heavy write workloads. Write-through maintains a useful cache at the cost of write latency. Write-back maintains useful cache hit ratios but provides unreliable write latency when writes are sustained at high rates.

**High Throughput, Mixed Workload:**
Write-back with hybrid or distributed topology produced the highest throughput in these experiments (85,819 TPS for Hybrid WriteBack under mixed workload). This advantage depends on the flush queue not saturating. The 50% write rate in the mixed workload was below the saturation threshold in this experiment; the 70% write rate in the write-heavy workload was above it.

### 8.2 Decision Summary

| Workload | Scale | Recommendation | Write Policy | Caveat |
|---|---|---|---|---|
| Read-heavy | Small | Local | WriteThrough | Test hit ratio, not just latency |
| Read-heavy | Large (random routing) | Distributed or Hybrid | WriteThrough / WriteBack | Key-affine routing changes this |
| Write-heavy | Any | Distributed | WriteThrough | Avoid CacheAside |
| Mixed, high TPS | Any | Hybrid or Distributed | WriteBack | Monitor flush queue depth |
| Durability-critical | Any | Any topology | WriteThrough | WriteBack durability gap is real |

---

## 9. Conclusion

This project implemented a Java-based benchmark harness to compare nine caching configurations (three topologies × three write policies) across three workload profiles, and conducted a horizontal scalability experiment from 1 to 32 replicas. The main engineering artifacts are the cache abstraction layer, write policy executor, write-back queue, Zipfian key generator, HDR histogram-based measurement, and benchmark runner — approximately 900 lines of Java.

The most important findings from the experiments:

1. **Write policy choice matters as much or more than topology choice** under write-heavy conditions. Cache-aside hit ratio collapses from ~88% to ~31% at 70% write rate, because continuous invalidations deplete the cache. Write-through avoids this regardless of topology.

2. **Write-through reduces read tail latency** by maintaining a warmer cache, even though it adds overhead to the write path. This was the most unexpected result in the project.

3. **Write-back saturation is a real failure mode.** Write-back p99 under write-heavy conditions (15–17ms) is similar to write-through p99, because the flush queue fills and overflow writes are handled synchronously. This was not anticipated from the write-back design description and only became apparent from the write-heavy experiments.

4. **Local and distributed cache scalability diverge from an identical starting point.** At N=32 with random routing and fixed cache budget, local hit ratio falls to 49% while distributed rises to 99%. This is the clearest practical argument for a distributed or hybrid cache in large deployments with random routing.

These results come with significant caveats (localhost Redis, simulated backing store, single-machine benchmarking, no confidence intervals), which are detailed in Section 7. They should be treated as directional findings from a controlled student-scale experiment, not production-validated performance claims.

The most valuable lesson from building this project is that the interaction between write policy and workload read/write ratio is underappreciated in standard descriptions of caching architectures. Both cache-aside and write-through are often presented as equivalent read-path strategies, differing only in write overhead. The experimental results show they are not equivalent — their write policies have substantial and somewhat counter-intuitive effects on read latency through the mechanism of hit ratio.

---

## References

Altinel, M., Bornhövd, C., Krishnamurthy, S., Mohan, C., Pirahesh, H., & Reinwald, B. (2003). Cache tables: Paving the way for an adaptive database cache. *Proceedings of the 29th International Conference on Very Large Data Bases (VLDB).* https://www.vldb.org/conf/2003/papers/S22P01.pdf

Atikoglu, B., Xu, Y., Frachtenberg, E., Jiang, S., & Paleczny, M. (2012). Workload analysis of a large-scale key-value store. *ACM SIGMETRICS Performance Evaluation Review, 40*(1), 53–64. https://dl.acm.org/doi/10.1145/2254756.2254766

Belady, L. A. (1966). A study of replacement algorithms for a virtual-storage computer. *IBM Systems Journal, 5*(2), 78–101. https://dl.acm.org/doi/10.1147/sj.52.0078

Brutlag, J. (2009). Speed matters for Google web search. *Google Technical Report.* https://research.google/blog/speed-matters/

Carlson, J. L. (2013). *Redis in Action.* Manning Publications. https://www.manning.com/books/redis-in-action

Dean, J., & Barroso, L. A. (2013). The tail at scale. *Communications of the ACM, 56*(2), 74–80. https://dl.acm.org/doi/10.1145/2408776.2408794

Einziger, G., Friedman, R., & Manes, B. (2017). TinyLFU: A highly efficient cache admission policy. *ACM Transactions on Storage, 13*(4), Article 35. https://dl.acm.org/doi/10.1145/3149371

Fitzpatrick, B. (2004). Distributed caching with Memcached. *Linux Journal, 2004*(124). https://www.linuxjournal.com/article/7451

Huang, Q., Birman, K., van Renesse, R., Lloyd, W., Kumar, S., & Li, H. C. (2013). An analysis of Facebook photo caching. *Proceedings of the 24th ACM Symposium on Operating Systems Principles (SOSP '13).* https://dl.acm.org/doi/10.1145/2517349.2522722

Karger, D., Lehman, E., Leighton, T., Panigrahy, R., Levine, M., & Lewin, D. (1997). Consistent hashing and random trees. *Proceedings of the 29th Annual ACM Symposium on Theory of Computing (STOC '97).* https://dl.acm.org/doi/10.1145/258533.258660

Kohavi, R., & Longbotham, R. (2007). Online experiments: Lessons learned. *IEEE Computer, 40*(9), 103–105. https://ieeexplore.ieee.org/document/4302627

Manes, B. (2015). Caffeine: A high-performance caching library for Java. *GitHub Repository.* https://github.com/ben-manes/caffeine

Mattson, R. L., Gecsei, J., Slutz, D. R., & Traiger, I. L. (1970). Evaluation techniques for storage hierarchies. *IBM Systems Journal, 9*(2), 78–117. https://ieeexplore.ieee.org/document/5388318

Nishtala, R., Fugal, H., Grimm, S., Kwiatkowski, M., Lee, H., Li, H. C., McElroy, R., Paleczny, M., Peek, D., Saab, P., Stafford, D., Tung, T., & Venkataraman, V. (2013). Scaling Memcache at Facebook. *Proceedings of the 10th USENIX Symposium on Networked Systems Design and Implementation (NSDI '13).* https://www.usenix.org/conference/nsdi13/technical-sessions/presentation/nishtala

Rabinovich, M., & Spatscheck, O. (2002). *Web Caching and Replication.* Addison-Wesley Professional. https://www.oreilly.com/library/view/web-caching-and/0201615703/
