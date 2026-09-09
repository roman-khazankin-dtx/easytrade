# EasyTrade Profiling Eval — Use Cases & Injected Defects (DRAFT)

> Status: **draft for discussion** · Owner: Roman Khazankin · Last updated: 2026-09-08

> **What this file is.** The **use-case catalog** for the agentic profiling eval: the profiling
> defects injected into this EasyTrade fork (UC1–UC12), which service hosts each, and the
> end-to-end ground truth for the ones already built. The defects live in *this* repo's service
> code; this doc documents them.
>
> **The strategy lives elsewhere.** Motivation, the always-on / no-activation philosophy, the
> EasyTrade-candidacy analysis, coverage rationale, open questions and next steps are in the
> **eval-runner** repo: `comet-agents-playground/docs/agentic-profiling-workstream.md`. The two
> files were one document and **share one section-numbering scheme** — this file holds **§3, §5,
> §7**; a `§`-reference to any section not here (e.g. §2.1 no-activation, §6 Q4 per-scenario
> isolation) points into that strategy doc.
>
> **Machine-readable ground truth** (authoritative for grading) is `evals/ground-truth.yaml` in the
> playground repo; the YAML blocks below are the human-readable source it syncs from.

> **Repo / contribution note:** the defects live on this **fork**
> (github.com/roman-khazankin-dtx/easytrade). We will **not** open pull requests to `origin` (the
> upstream Dynatrace EasyTrade). They are a private eval fixture, not meant to be upstreamed —
> commit and push to fork branches only. See strategy doc §6 Q5.

## 3. Proposed profiling use-cases (generic, product-agnostic)

These are the eleven agentic profiling defect scenarios (UC1–UC11) we consider most representative,
plus a draft flamegraph-navigation capability (UC12). For each we note the **profiling signal** it
exercises and the **agent task** we'd score.

### UC1 — CPU hotspot / inefficient algorithm ("on-CPU")
A single method or call path burns a disproportionate share of CPU (e.g. an O(n²) loop, regex
backtracking, unbounded serialization, crypto in a hot path).
- **Signal:** on-CPU sampling; a dominant frame in the flamegraph.
- **Agent task:** identify the offending service + method/stack, quantify its CPU share, and
  propose the fix. Ground truth = the injected hot method.
- **Two realizations (see §5):**
  - *(.NET, planned)* `broker-service` — a second always-on hot path beside the existing
    `HighCpuUsage` Collatz busy-loop. Keeps one non-JVM host in the mix.
  - *(Java, ✅ implemented)* `credit-card-order-service` — a **cache-defeat** variant that is
    deliberately harder than a bare busy-loop. An expensive `O(n²)` "orders overview"
    (`OrdersOverview#build`) is meant to be served from a cache, but the cache is invalidated on
    **every** status write (`OrderController`, `WorkScheduler`), so it is never warm and the
    rebuild runs on the request hot path. The agent must reason **two hops**: the CPU frame is
    real, but the root cause is not "optimize the loop" — it is "why does a cached result recompute
    every request?" (over-eager invalidation). Chosen over an N+1/chatty-DB shape on purpose: DB
    round-trips are already captured as **spans**, so a chatty-DB defect is diagnosable from tracing
    + database metrics **without a profiler**, whereas an in-process CPU hotspot shows up as a
    slow request with **no extra spans** and is only localizable from a CPU flamegraph — the signal
    this eval is meant to exercise. See §7.1.

### UC2 — Memory allocation hotspot / leak
Sustained, unbounded growth (e.g. an ever-growing cache/list) or high allocation churn from a
specific site driving RSS/heap up over time.
- **Signal:** allocation profiling + memory trend; growing retained set attributable to one stack.
- **Agent task:** distinguish leak vs. churn, name the allocating stack/type, correlate with the
  memory-growth trend, and locate the retaining structure.

### UC3 — Off-CPU / wait-time analysis (lock contention & blocking)
The service is slow while CPU is *idle*: threads blocked on a contended lock, a synchronized
section, or a slow downstream/DB call.
- **Signal:** off-CPU / wait profiling; latency high but CPU low; blocked-thread stacks.
- **Agent task:** recognize the "slow but not CPU-bound" pattern, identify the contended
  monitor / blocking call site, and separate lock-wait from I/O-wait.
- **Realization (Java, ✅ implemented):** `third-party-service` — a single global legacy
  mainframe-channel monitor (`LegacyMainframeBridge#transmit`, `synchronized`) that
  `ManufacturerController#issueCreditCard` must acquire before accepting each card order, held for
  an off-CPU interval (`MAINFRAME_TX_HOLD_MS`, default 150 ms). Under concurrent load, request
  threads pile up **BLOCKED** on the one monitor while CPU stays idle. Because `/v1/manufacturer`
  is normally driven by a *single* upstream scheduler thread (credit-card-order-service
  `WorkScheduler`), the contention only manifests under a concurrent driver — see §7.2.

### UC4 — Thread-pool / concurrency exhaustion
A bounded pool (HTTP worker pool, DB connection pool, executor) is saturated or threads leak,
so requests queue even though per-request work is cheap.
- **Signal:** thread-state profiling, thread counts, growing queue/wait time.
- **Agent task:** diagnose starvation vs. leak, identify the exhausted pool and the code holding
  its threads, and distinguish it from UC3 (a single hot lock) and UC1 (real CPU work).

### UC5 — GC pressure / runtime overhead
Excessive garbage collection (or GC-equivalent runtime overhead) steals CPU and adds latency
pauses, driven by a high-allocation code path.
- **Signal:** GC/runtime frames in the CPU profile, allocation rate, pause metrics.
- **Agent task:** attribute the GC/runtime cost to the allocating code path and separate
  "GC symptom" from "allocation root cause" (ties UC5 back to UC2).

### UC6 — Conditional / probabilistic off-CPU (selective cache miss)
Unlike UC3, the wait is **not** present on every request — it fires on only a *fraction* of
traffic. Concretely: a service caches a lookup, but for ~5% of requests the cache is deliberately
bypassed ("miss"), forcing a fallback load from a slow source (downstream/DB → network/I/O wait).
The other ~95% are served from cache and are fast. Averages look healthy; only the **tail**
(p95/p99) degrades.
- **Signal:** off-CPU / wait profiling on a *minority* of samples; a bimodal latency distribution
  (fast cache-hit path + slow cache-miss path); the wait localizes to the fallback load call
  (e.g. the outbound HTTP/DB call), not to CPU work.
- **Agent task:** notice that mean latency/CPU look fine while the tail is bad, recognize the
  **conditional** nature (a sampled subset of requests is slow), and attribute the slow path to the
  cache-miss fallback rather than concluding a uniform slowdown. This is deliberately harder than
  UC3: the signal is diluted by the fast majority, so the agent must reason about distributions and
  intermittency, not a single dominant frame. A **miss-rate knob** (intensity) lets us tune how
  hard the signal is to find.

### UC8 — Logging overhead on the hot path (on-CPU log frames + appender I/O)
CPU and latency are burned not on business logic but on **logging** — expensive message
construction (string concatenation, serializing an object to JSON per request) and/or synchronous
appender I/O — executed on every request.
- **Signal:** the profile shows a large share in logging-framework frames (SLF4J/Logback layout,
  encoder) and in the argument-building call (e.g. `Gson#toJson`) *above* the log call; if the
  appender is synchronous, some off-CPU I/O wait flushing to stdout/disk.
- **Agent task:** identify that the hotspot is *observability plumbing*, not the request's real
  work; separate message-construction CPU from appender I/O; recommend guarding/lowering the log
  level or making the appender async. Distinguish from UC1 (business CPU) and UC9 (serialization as
  the product, not as a log argument).
- **Grounded in:** `accountservice`/`engine` already `logger.info(...)` per request, and
  `AccountController` PUT logs `gson.toJson(accountDetails)`; the always-on defect turns this into
  per-request JSON-of-a-large-object logging at INFO.

### UC9 — Serialization / deserialization overhead (marshalling CPU + allocation)
A hot path spends its time in **(de)serialization** — reflective JSON marshalling of large or
repeatedly-processed payloads (parse → transform → re-serialize), rather than in domain logic.
- **Signal:** dominant frames inside the serializer (Gson/Jackson reflective read/write); elevated
  allocation from intermediate parse trees and buffers (a UC5-shaped alloc symptom whose *root
  cause* is marshalling); CPU scales with payload size, not request rate alone.
- **Agent task:** attribute the CPU+allocation to the serialization layer and the specific call
  site, not to business code or "the GC"; recommend streaming/partial parsing, reuse, or avoiding
  the round-trip. Distinguishes library-marshalling cost from an arbitrary hot loop (UC1) and from
  business-object churn (UC5).
- **Grounded in:** `accountservice` uses `Gson` to parse the `manager` response and serialize
  bodies; the always-on defect re-serializes/re-parses the payload repeatedly (or pretty-prints a
  large object) on `GET /account/{id}`.

### UC10 — Thread leak → native-memory growth (distinct from UC4 saturation)
Threads are **created and never terminated** (e.g. a `new Thread` or an unbounded executor per
request, each parking forever) so the thread count grows without bound — the leak analogue of UC2,
but for threads/native memory rather than heap.
- **Signal:** thread count climbs **monotonically**; thread-state profiling shows an ever-growing
  set of idle/parked/sleeping threads; RSS grows via thread stacks while **heap stays flat**;
  eventually `OutOfMemoryError: unable to create native thread`.
- **Agent task:** distinguish a *thread leak* (unbounded, monotonic count) from *pool saturation*
  (UC4 — bounded count, full queue) and from a *heap* leak (UC2 — RSS growth is off-heap here).
  Locate the code creating the never-joined threads. This pairs with UC4 the way UC2 pairs with UC5.
- **Grounded in:** `contentcreator` already spawns `new Thread(...)`; card services use bounded
  `ScheduledExecutorService` (size 1–2). The always-on defect spawns a per-request thread that
  blocks forever instead of reusing a pool.
- **Realization (Java, ✅ implemented):** `contentcreator` — each steady-state one-minute pricing
  cycle hands every generated candle to a new `AsyncPricingWriter#submit` thread that is meant to
  flush and exit but instead **parks forever** (`awaitFlushSignal` → `LockSupport.park`). No thread
  is joined or pooled, so the live thread count climbs monotonically
  (`PRICING_WRITER_THREADS_PER_CYCLE`, default one per instrument ≈ 15/min) and native memory grows
  while the **heap stays flat**; the JVM runs `-XX:+ExitOnOutOfMemoryError` so the pod OOM-restarts
  into a sawtooth. `contentcreator` is a background loop, so the leak is **load-independent** (no
  driver needed). The startup back-fill deliberately does not leak. See §7.3.

### UC11 — Busy-wait / spin-poll (CPU that is really a wait)
The service pegs a core in a tight polling loop (`while (!done) { … }` with no block/sleep) while
*waiting* for a condition or a downstream result — high CPU that accomplishes no useful work. It
looks like UC1 in metrics but the correct diagnosis is the opposite: it should be blocking, not
computing.
- **Signal:** an on-CPU hotspot at 100% in a small spin frame, but the surrounding logic is a wait
  (the loop body just re-checks a flag / re-polls); no business throughput corresponds to the CPU;
  wall-clock latency tracks the awaited event, not the CPU work.
- **Agent task:** recognize the **wait-masquerading-as-CPU** trap — a dominant CPU frame that is
  *not* a real hotspot to optimize but a spin that should block/await. This is the sharpest
  disambiguation against UC1 (genuine compute) and complements UC3/UC6 (honest off-CPU waits).
- **Grounded in:** `engine`'s scheduler loop and any request path that awaits a downstream (the
  `HttpClient.send` results) can be replaced, always-on, by a spin-poll instead of a
  blocking call.

### UC12 — Flamegraph navigation / frame localization (DRAFT)
> Status: **draft for discussion.** Unlike UC1–UC11, this is **not a defect-diagnosis** scenario —
> there is no injected root cause to name. It scores a more primitive **capability**: can the agent
> (driving `dtctl`) *navigate the call tree itself* to locate a concrete frame that is **buried deep**
> in the samples? It doubles as a **profiling-product validation** case (does the flamegraph surface,
> subtree traversal, and reverse-caller lookup return the right frames?). It likely needs **no new
> defect** — any existing UC deployment (or a plain easytrade service with several endpoints and deep
> stacks) already has a rich enough tree to exercise.

The motivating shape: a service exposes **multiple endpoints**, and deep inside **one** of them sits
a frame we care about. The flamegraph is wide (many endpoints) and tall (deep stacks), so the target
frame is a small box far from the root. We score whether the agent can find it and reason about its
neighbourhood rather than eyeballing a picture.

- **Signal:** on-CPU (or off-CPU) sampling with **deep, multi-endpoint call trees**; the frame of
  interest is neither a dominant hotspot nor near a root — it is localized by *structure* (which
  entrypoint it descends from, its parents/children), not by raw sample share.
- **Agent task — three variants of increasing difficulty:**
  - **(a) Known name.** We give the agent the fully-qualified frame (`Class#method`); it must locate
    every occurrence in the tree, report where it sits (under which endpoint(s) / call path), and
    quantify its share. Tests exact-match search + path reporting.
  - **(b) Known entry, unknown target.** We give only a **starting point** — an endpoint or a partial
    stacktrace — and a rough direction ("a couple of levels down from here"). The agent must **walk
    the subtree** below that anchor, enumerate the children a level or two down, and surface the
    frame(s) of interest without being told their names. Tests subtree traversal / drill-down, not
    keyword lookup.
  - **(c) Reverse lookup (callers).** Given a method, find **who calls it** — its callers (the
    inverted/reverse view), across all endpoints, with each caller's contribution. Tests the
    callers-of / reverse-flamegraph capability, distinct from the top-down descent of (a)/(b).
- **Why it matters:** the other UCs assume the agent can already read a flamegraph; this one
  isolates and grades that assumption. It also stresses the **`dtctl` profiling surface**
  (`dtctl exec profile`) directly — frame search, subtree extraction, and caller inversion are the
  primitives every other UC's investigation is built on, so a gap here caps performance everywhere.
- **Open questions (draft):** which deployment hosts it (reuse an existing UC vs. a clean
  multi-endpoint service); how ground truth is expressed (an exact frame + expected call path for
  (a)/(c); an expected subtree/frame-set for (b)); and whether grading is exact-match or partial on
  the reported path.

> **Cross-cutting scenario (stretch): release regression / profile diff.** Ship a "slow" build,
> let the agent compare before/after profiles to localize the regressed frame. This is arguably
> the highest-value agentic use-case but depends on us running two builds; kept as a stretch goal.

> **Removed: UC7 (N+1 / chatty DB).** Originally listed as an off-CPU DB-wait scenario, it was
> dropped because it is a **poor profiling use-case**: every DB round-trip is captured as a
> distributed-tracing span, so an N+1 is diagnosable directly from a trace waterfall and
> database-service metrics (query count spikes, per-query duration flat) **without ever opening a
> profiler**. The credit-card-order-service host we had earmarked for it now hosts the Java UC1
> **cache-defeat CPU hotspot** instead (§3 UC1, §5, §7.1), which produces an in-process CPU signal
> that *only* a profiler can localize. UC7's id is retired; UC8–UC11 keep their numbers.

> The **coverage rationale** and **disambiguation pairs** that explain *why this set* was chosen
> live in the strategy doc (playground), since they argue the design rather than document a defect.

## 5. Proposed defect → service mapping (first cut)

All defects are always-on with no activation mechanism (§2.1), so there is no flag-client "plumbing status"
to track — any service can host a defect with a single `getenv` read.

**Runtime decision: UC2–UC6 target Java services; UC1 stays .NET.** We concentrate the new defects
on the **JVM** to go deep on Java profiling (allocation, monitor/lock, thread-state, and GC signals
are all first-class there), keeping UC1 on .NET (`broker-service`) as the one cross-runtime
representative that reuses the existing `HighCpuUsage` template. This is a deliberate trade against
the original "spread across many runtimes" goal (§2) — Go/Node are dropped from the core set for
now; revisit if we want broader runtime coverage. The **specific Java host per UC is left open**
(TBD) — since always-on activation needs no per-service plumbing, host choice is a later, cheap
decision driven by which service gives the cleanest signal under load.

| Use-case | Runtime | Host | Status | Injection idea |
|---|---|---|---|---|
| UC1 CPU hotspot (.NET) | .NET | `broker-service` (existing `HighCpuUsage`) | planned | Add a second always-on hot path (regex backtracking / expensive serialization) beside the Collatz loop |
| UC1 CPU hotspot (Java, cache-defeat) | Java | `credit-card-order-service` | ✅ implemented | Expensive `O(n²)` `OrdersOverview#build` meant to be cached, but invalidated on every status write (`OrderController`, `WorkScheduler`) → never warm → rebuild runs on every `GET /v1/orders/{id}/status`. **Always-on (no env gate)** — see §7.1 |
| UC2 Memory leak | Java | `accountservice` | ✅ implemented | always-on `static` collection (`AccountControllerV2`) that grows 256 KB per `GET /accounts/{id}` and is never freed; JVM `-XX:+ExitOnOutOfMemoryError` restarts the pod on OOM |
| UC3 Lock contention | Java | `third-party-service` | ✅ implemented | always-on single global `synchronized` monitor (`LegacyMainframeBridge#transmit`) acquired per card order (`issueCreditCard`), held off-CPU (`MAINFRAME_TX_HOLD_MS`) → concurrent request threads block, CPU idle. Needs a concurrent driver to manifest (§7.2) |
| UC4 Thread-pool exhaustion | Java | TBD | planned | always-on bounded `ExecutorService` (or constrained worker pool) that holds/leaks threads → requests queue |
| UC5 GC / alloc churn | Java | TBD | planned | always-on high-allocation path emitting many short-lived objects → high alloc rate + GC pauses |
| UC6 Conditional cache-miss wait | Java | TBD | planned | always-on cache with a tunable miss rate (~5%); missed requests fall back to a slow downstream/DB load → intermittent off-CPU/net-IO wait on the tail |
| UC8 Logging overhead | Java | `accountservice`/`engine` (candidate) | planned | always-on per-request INFO log that serializes a large object (`Gson#toJson`) and/or a synchronous appender → CPU in log frames + I/O |
| UC9 Serialization overhead | Java | `accountservice` (candidate) | planned | always-on repeated (de)serialization of the payload (parse→transform→re-serialize / pretty-print) on `GET /account/{id}` → serializer CPU + alloc |
| UC10 Thread leak | Java | `contentcreator` | ✅ implemented | always-on per-cycle `new Thread` (`AsyncPricingWriter#submit`) that parks forever (never joined/pooled) → unbounded thread count + native-memory growth, heap flat; `-XX:+ExitOnOutOfMemoryError` → sawtooth restart. Load-independent background loop (§7.3) |
| UC11 Busy-wait / spin-poll | Java | `engine` (candidate) | planned | always-on tight `while (!done)` spin replacing a blocking `HttpClient.send`/await → 100% CPU that is really a wait |

**Notes driving these choices:**
- **JVM focus (UC2–UC11):** the JVM exposes rich, distinct profiling signals for every pillar
  (allocation, monitor-wait, thread-state, GC, off-CPU/net-IO, JDBC wait, spin), so a Java-heavy set
  exercises the profiling product thoroughly on one runtime.
- **UC8–UC11 are grounded in existing code** (see the "Grounded in" notes in §3): real
  per-request logging, `Gson` usage, `new Thread` spawning, and blocking downstream calls already
  exist, so each defect is a small, believable mutation of a real path rather than a synthetic add-on.
- **Host = TBD, on purpose:** always-on activation removes the per-service plumbing cost, so we pick
  each host later based on signal quality. Practical hints for when we do: the request-driven
  concurrency/wait defects (UC3/UC4/UC6) want a **busy, request-driven** Java service
  (`accountservice` is the obvious candidate); UC5's steady alloc churn suits a **background-loop**
  service (`engine`'s scheduler) that generates signal without needing request traffic; the
  low-traffic card services (`credit-card-order-service`, `third-party-service`) are weak hosts for
  load-dependent defects unless we drive the right endpoint hard (see UC1's loadgen changes, §7.1).
- **UC3 host resolved: `third-party-service` (+ concurrent driver).** The two busy, request-driven
  Java services (`accountservice`, `credit-card-order-service`) were already taken by UC2/UC1, and
  the invariant is **one defect per service** (all defects are always-on in shared images, so a
  scenario deployment scopes to a service, and two defects on one service would break per-service
  grading). Among the free Java services none does heavy *synchronous request* work — so UC3 landed
  on `third-party-service`'s real `issueCreditCard` endpoint and the required **concurrency is
  supplied by a dedicated driver** (`deploy/uc3-load/`), exactly as UC1 needed `uc1-load`. The lock
  holder is off-CPU (a bounded sleep = the mainframe channel's round-trip cycle), so the graded signal is
  request threads **BLOCKED on the monitor**, not CPU. See §7.2.
- **UC10 host resolved: `contentcreator`.** A background per-minute loop, so the thread leak is
  **load-independent** and monotonic (the safest signal against the load-dependency failure mode
  that bit UC1/UC2). Note the design's "per-request thread" framing became "per-cycle thread" here
  because `contentcreator` serves no requests — the leak semantics (never-joined, never-pooled,
  parks forever) are identical. See §7.3.
- **UC2 landed in `accountservice`** (a busy Java service under continuous load) for a strong
  memory-growth signal — the reference implementation for the always-on / flag-free pattern. Note
  the leak must sit on a **trafficked** endpoint (`AccountControllerV2` / `/accounts/{id}`), not the
  dead `/account/{id}` — see the §7 post-mortem.
- **UC1 now has two hosts.** The .NET `broker-service` variant (planned) reuses the existing
  profiler-visible `HighCpuUsage` template and keeps one non-JVM host in the mix. The Java
  `credit-card-order-service` variant (✅ implemented, §7.1) is the **cache-defeat CPU hotspot** that
  replaced the earlier UC7 idea on this host — it makes the CPU-hotspot task harder (real hot frame,
  but the root cause is a defeated cache) and gives an in-process signal that, unlike a chatty-DB
  N+1, cannot be diagnosed from traces alone.

## 7. Prototype — UC2 memory leak, end-to-end (implemented)

Goal: prove the whole loop on **one** defect before scaling — inject → observe in the profiling
product on a monitored tenant → capture ground truth → (later) grade an agent.

**Target service: `accountservice`** (busy Java service under continuous load). Chosen for a strong
memory-growth signal; always-on activation means no flag client, env var, or loadgen changes.

**What was built:**
1. **No activation — always-on (§2.1).** The leak is baked into the service's default behaviour:
   no `feature-flag-service` flag and no env var. The earlier `memory_leak` flag + OpenFeature
   wiring, and then the interim `REQUEST_TRACE_RETENTION_ENABLED` env var, were both removed.
2. **The leak.** On each `GET /accounts/{id}`, `accumulateRequestTrace()` appends a 256 KB `byte[]`
   to a `static` `HEAP_RETAINER` list that is **never cleared** — monotonic heap growth. The method
   is distinctly named so the allocation profiler shows the frame unambiguously. The pod runs with
   `-XX:+ExitOnOutOfMemoryError` (compose + Helm) so it terminates on OOM and Kubernetes restarts
   it, giving a clean sawtooth rather than a hung pod.
3. **It lives on the endpoint that actually gets traffic.** The leak sits in **`AccountControllerV2`**
   (`/accounts/{id}`), which is what the frontend and `broker-service` call. An earlier version put
   it in `AccountController` (`/account/{id}`, singular) — a **dead endpoint with no callers** — so
   `accumulateRequestTrace` never ran. Combined with the env var being unset in Helm, the leak sat
   flat for days on the tenant before this was caught (verified via `dtctl`: working-set memory
   ~222 MB start vs. ~221 MB three days later). Both bugs are fixed.
4. **Verify:** deploy (Helm) with profiling enabled and continuous loadgen traffic; confirm heap/RSS
   climbs monotonically, allocation profiling attributes the growth to `accumulateRequestTrace`, and
   the pod OOM-restarts into a sawtooth.
5. The **ground-truth catalog entry** is below.

**Ground-truth catalog entry (standardized format — `activation: none`):**
```yaml
- id: UC2-memory-leak
  activation:
    mechanism: none               # always-on default behaviour (no flag, no env var)
  service: accountservice
  runtime: java
  root_cause:
    file: src/accountservice/src/main/java/com/dynatrace/easytrade/accountservice/AccountControllerV2.java
    symbol: AccountControllerV2#accumulateRequestTrace
    trigger: GET /accounts/{id}
    mechanism: unbounded static List<byte[]> (HEAP_RETAINER), 256 KB/request, never freed; JVM runs -XX:+ExitOnOutOfMemoryError so the pod restarts on OOM
  expected_signal:
    profiling: allocation hotspot at AccountControllerV2#accumulateRequestTrace; retained set grows with request count
    metric: heap/RSS monotonic increase; sawtooth as the pod OOM-restarts
  expected_dql: <query>
  agent_answer_key:
    service: accountservice
    method: AccountControllerV2#accumulateRequestTrace
    classification: leak (not churn)
```

## 7.1 UC1 (Java) — cache-defeat CPU hotspot, end-to-end (implemented)

Second defect built end-to-end, on `credit-card-order-service`. This is the Java realization of
UC1 (§3) and the replacement for the retired UC7 idea on this host.

**The scenario.** A system-wide "orders overview" (`OrdersOverview#build`) cross-references every
order against every status row — a naive `O(orders × statuses)` scan. It is loaded with **two
bounded set queries** (`getAllOrders`, `getAllOrderStatuses`) — deliberately **not** an N+1 — so the
cost is **in-process CPU**, not DB round-trips. It is cached in `OrderOverviewService` and is meant
to be rebuilt rarely.

**The bug (root cause).** The cache is invalidated on **every** status write — in
`OrderController` (order create, status update) and, critically, in `WorkScheduler`, which advances
orders on every tick. Because writes are continuous, the cache is essentially never warm, so the
expensive rebuild runs on the request hot path: every `GET /v1/orders/{accountId}/status` consults
the overview and pays the full `O(n²)` cost.

**Why this is a *profiling* defect (vs. UC7 / chatty DB).** The recompute does only two DB queries,
so a trace waterfall shows a slow request with **no extra spans** and DB metrics stay flat; the time
is burned inside `OrdersOverview#build`. The only way to localize it is the **CPU flamegraph**. A
chatty-DB N+1, by contrast, would be obvious from tracing + database-service metrics without a
profiler — which is exactly why UC7 was dropped.

**Two-hop agent task.** (1) Find the dominant CPU frame (`OrdersOverview#build`). (2) Realize the
correct fix is *not* "optimize the loop" but "stop recomputing a cacheable result every request" —
the over-eager `OrderOverviewService#invalidate()` on every write. This disambiguates a genuine
hotspot-to-optimize (bare UC1) from a hotspot that should not be running at all.

**Making it manifest (load requirements).** The defect only produces CPU signal if two conditions
hold — both were initially missing, so the first deployment sat idle:
1. **The endpoint must be driven.** `getOverview()` runs only from `getStatusHistory`
   (`GET /v1/orders/{accountId}/status`). loadgen's `order_credit_card` visit was *rare* and only
   ordered/revoked a card — it never viewed the status timeline, so the endpoint saw ~zero traffic.
   Fix: `order_credit_card` is now a **regular, weighted** visit (`ORDER_CREDIT_CARD_WEIGHT`,
   default 5) that, after ordering, re-visits the credit-card page (which auto-redirects to the
   status timeline for an in-progress order) `CREDIT_CARD_STATUS_VIEWS` times (default 5).
2. **The dataset must be large.** `CreditCardOrders`/`CreditCardOrderStatus` seed empty and orders
   are ~1/account (capped, deleted on revoke), so `O(n²)` was microseconds. Fix:
   `src/db/sql-scripts/sql-seed-creditcard-history.sql` seeds a stable history (default 3000 orders
   × 5 statuses = 15000 rows → ~45M iterations/recompute) on **dedicated synthetic accounts**
   (`Origin = 'SEED_CCORDER'`) that loadgen never logs into, so the seed can't be eroded by revokes.
   Knobs: `@synthAccounts`, `@ordersPerAccount`.

Neither lever alone is enough: driving the endpoint over an empty DB stays cheap; a big DB never
recomputed stays idle. Together (with the cache defeated on every write) every `/status` call pays
the full `O(n²)` → a sustained CPU hotspot. Dial CPU via the seed size and the two loadgen knobs.

> **Flag-free by design (and why that's fine here).** This defect ships as **default behaviour**
> with no activation env var — there is nothing to "arm," which also means there is nothing about it
> to *discover* through the app's surfaces (the concealment goal of §2.1 is met trivially). The one
> property we give up — a clean, un-armed baseline from the *same* image — does not matter under our
> operating model: **scenarios do not overlap.** Each UC runs on its own isolated deployment (one
> scenario per instance, §6 Q4), so this service's always-on CPU hotspot never contaminates another
> scenario's profiles, and grading the agent needs only the live app + the answer key below, not an
> A/B against a baseline. If we ever move to a **shared instance hosting several UCs at once**, this
> defect would need an env gate to be isolatable — but that is explicitly out of scope; we keep it
> simple and flag-free.

**Ground-truth catalog entry:**
```yaml
- id: UC1-java-cache-defeat-cpu-hotspot
  activation:
    mechanism: none               # always-on default behaviour (see §2.1 deviation above)
    var: null
    armed_when: always
    toggle: n/a (baked into the service)
  service: credit-card-order-service
  runtime: java
  root_cause:
    file: src/credit-card-order-service/src/main/java/com/dynatrace/easytrade/creditcardorderservice/OrderOverviewService.java
    symbol: OrdersOverview#build          # the O(n^2) CPU hotspot frame
    true_cause: OrderOverviewService#invalidate over-called on every status write (OrderController, WorkScheduler)
    trigger: GET /v1/orders/{accountId}/status
    mechanism: cacheable O(orders*statuses) overview recomputed every request because the cache is never warm
  expected_signal:
    profiling: on-CPU hotspot at OrdersOverview#build; frame share grows as the DB accumulates orders
    tracing: slow request with NO extra DB spans (only 2 set queries); distinguishes this from an N+1
    metric: service CPU up while DB per-query duration / call count stay flat
  expected_dql: <query>
  agent_answer_key:
    service: credit-card-order-service
    method: OrdersOverview#build
    classification: CPU hotspot whose root cause is a defeated cache (over-eager invalidation), not the loop itself
```

## 7.2 UC3 (Java) — lock contention, end-to-end (implemented)

Third defect built end-to-end, on `third-party-service`. The Java realization of UC3 (§3).

**The scenario.** Card issuance must hand every order to a single legacy mainframe channel that can
transmit only one order at a time. It is modelled as one global monitor — `LegacyMainframeBridge#transmit`,
a `synchronized` method on a singleton bean — that `ManufacturerController#issueCreditCard` must
acquire before accepting each order. The critical section is held for `MAINFRAME_TX_HOLD_MS`
(default 150 ms) of **off-CPU** time (a bounded sleep = the mainframe's fixed round-trip cycle).

**The bug (root cause).** Serializing *all* card issuance through one coarse monitor. Under
concurrent orders, only one thread holds the line while the rest sit **BLOCKED** on the monitor —
latency climbs, CPU stays idle. The fix is a finer-grained / per-resource lock or an async queue,
not "more CPU".

**Why this is a *profiling* defect.** The slow requests do no CPU work and make no downstream call
while blocked, so a CPU flamegraph is flat and a trace shows a slow span with nothing inside it. The
only way to localize it is **off-CPU / lock (monitor) analysis**, which attributes the wait to the
`transmit` monitor with threads in the BLOCKED state — the exact signal that distinguishes UC3
(lock-wait) from UC1 (genuine compute) and from a per-request I/O wait.

**Making it manifest (load requirement).** Like UC1, the defect is invisible without the right load
— but here the missing ingredient is **concurrency**, not data volume. `/v1/manufacturer` is
normally called by a *single* upstream thread (credit-card-order-service `WorkScheduler` loops over
orders sequentially), so nothing ever contends. `comet-agents-playground/deploy/uc3-load/` runs a
small curl Deployment that POSTs card orders **in parallel** (`PAR`, default 20) → ~`PAR-1` threads
BLOCKED on the monitor at all times. Accepted throughput is self-limited by the lock hold
(~`1000/MAINFRAME_TX_HOLD_MS`/s), so the enqueued work drains harmlessly on the normal
`ManufactureScheduler` cadence and is not a memory signal. Dial contention via `PAR` (how many block)
and `MAINFRAME_TX_HOLD_MS` (how long they wait).

**Ground-truth catalog entry:**
```yaml
- id: UC3-lock-contention
  activation:
    mechanism: none               # always-on default behaviour (see §2.1)
  service: third-party-service
  runtime: java
  root_cause:
    file: src/third-party-service/src/main/java/com/dynatrace/easytrade/thirdpartyservice/LegacyMainframeBridge.java
    symbol: LegacyMainframeBridge#transmit        # the contended monitor
    entry: ManufacturerController#issueCreditCard     # acquires it per request
    trigger: POST /v1/manufacturer
    mechanism: single global synchronized monitor held ~150 ms off-CPU per card; concurrent requests block on it (needs deploy/uc3-load for concurrency)
  expected_signal:
    profiling: off-CPU / lock analysis shows request threads BLOCKED entering the transmit monitor; NOT a CPU hotspot
    tracing: POST /v1/manufacturer response time high, dominated by wait, ~zero self-CPU
    metric: third-party-service response time up while service CPU stays low
  expected_dql: <query>
  agent_answer_key:
    service: third-party-service
    method: LegacyMainframeBridge#transmit
    classification: lock-contention
```

## 7.3 UC10 (Java) — thread leak, end-to-end (implemented)

Fourth defect built end-to-end, on `contentcreator`. The Java realization of UC10 (§3), and the
thread/native-memory analogue of the UC2 heap leak.

**The scenario.** Every steady-state one-minute pricing cycle generates a candle per instrument and
"hands each off" to a background writer thread (`AsyncPricingWriter#submit`) that is supposed to
flush it asynchronously and then exit.

**The bug (root cause).** The writer never receives its flush signal — it parks forever
(`awaitFlushSignal` → `LockSupport.park`) instead of returning. A **new** thread is created every
cycle and **none are ever joined or reused** (no pool), so the live thread count climbs
**monotonically** (`PRICING_WRITER_THREADS_PER_CYCLE`, default one per instrument ≈ 15/min). The fix
is a bounded pool / actually completing the task, not more memory.

**Why this is a *profiling* defect, and how it differs from its neighbours.** The distinguishing
signal is **thread-state**: an ever-growing set of parked threads at one frame
(`AsyncPricingWriter#awaitFlushSignal`), with **native memory (thread stacks) climbing while the
Java heap stays flat**. That flat heap is exactly what separates UC10 from the UC2 **heap** leak; the
*unbounded, monotonic* count is what separates it from UC4 **pool saturation** (bounded count, full
queue). Eventually `OutOfMemoryError: unable to create native thread` (or a cgroup OOM-kill from
RSS); the JVM runs `-XX:+ExitOnOutOfMemoryError` so the pod restarts into a sawtooth like UC2.

**Making it manifest (no driver needed).** `contentcreator` is a background loop with no HTTP
surface, so the leak is **load-independent** — it climbs on the fixed one-minute cadence regardless
of frontend traffic. The startup back-fill path deliberately does **not** leak, so the growth is a
clean steady climb from process start. Intensity is tuned at the source via
`PRICING_WRITER_THREADS_PER_CYCLE`.

**Ground-truth catalog entry:**
```yaml
- id: UC10-thread-leak
  activation:
    mechanism: none               # always-on default behaviour (see §2.1)
  service: contentcreator
  runtime: java
  root_cause:
    file: src/contentcreator/src/main/java/com/dynatrace/easytrade/contentcreator/AsyncPricingWriter.java
    symbol: AsyncPricingWriter#submit                 # creates the never-joined threads
    parked_frame: AsyncPricingWriter#awaitFlushSignal  # where leaked threads park forever
    trigger: steady-state one-minute pricing cycle (ContentCreator#spawnAsyncPricingWriters)
    mechanism: per-cycle new Thread that parks forever (LockSupport.park), never joined/pooled → monotonic thread + native-memory growth
  expected_signal:
    profiling: thread-state profiling shows an ever-growing set of parked threads at AsyncPricingWriter#awaitFlushSignal
    metric: JVM thread count and RSS/native memory climb monotonically while JVM HEAP stays FLAT; sawtooth as the pod OOM-restarts
  expected_dql: <query>
  agent_answer_key:
    service: contentcreator
    method: AsyncPricingWriter#submit
    classification: thread-leak
```
