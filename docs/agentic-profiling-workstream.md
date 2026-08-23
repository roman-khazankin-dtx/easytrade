# Agentic Profiling Workstream (DRAFT)

> Status: **draft for discussion** · Owner: Roman Khazankin · Last updated: 2026-08-21

> **Repo / contribution note:** this workstream lives on the **fork**
> (github.com/roman-khazankin-dtx/easytrade). We will **not** open pull requests to `origin` (the
> upstream Dynatrace EasyTrade). The profiling defects are a private eval fixture and are not meant
> to be upstreamed into the public demo app — commit and push to fork branches only. See §6 Q5.

## 1. Motivation & Goal

We want a **single, always-on demo application** that:

1. Runs continuously and is monitored by Dynatrace (including **continuous / code-level profiling**).
2. Deliberately **exhibits profiling-relevant defects** — CPU hotspots, memory leaks, lock
   contention, thread-pool exhaustion, GC pressure, inefficient I/O — in a controllable way.
3. Serves as a **shared fixture for two workstreams at once**:
   - **Agentic eval** — a reproducible, ground-truth environment to score how well an AI agent
     investigates and diagnoses profiling problems against a live Dynatrace tenant.
   - **Profiling app validation** — a realistic, continuous exerciser of the profiling product
     itself (ingestion, symbolication, flamegraphs, service/process attribution, diffing).

The key property we need is **ground truth we control**: for every injected defect we know the
root-cause method / allocation site / lock, so we can objectively grade an agent's diagnosis and
confirm the profiling product surfaces the right frames.

## 2. What "good" looks like

- Each defect is **always-on** (baked into the service's default behaviour) with **no activation
  flag or env var** — there is nothing to arm and nothing to discover from the running app (see
  §2.1). Intensity is tuned at the source/dataset level (e.g. UC1's seeded history), not a runtime
  toggle.
- Each defect stays **undiscoverable from the running app** — because there is no activation signal
  at all, the agent must localize it from *profiling signal*, not from a flag or env var (see §2.1).
- Each defect has a **documented ground-truth root cause** (service, file, symbol, expected
  flamegraph signature).
- Defects run against a **steady baseline of realistic traffic** so profiles are non-trivial and
  the signal must be separated from normal work.
- Defects are spread across **multiple runtimes** (JVM, Go, .NET, Node) so we exercise
  language-specific profiling behavior and don't overfit the agent to one stack.
- The whole thing is **deployable to K8s and monitored by a Dynatrace tenant** with profiling
  enabled, and is stable enough to leave running for days.

### 2.1 Activation: none (always-on, flag-free, env-free)

**Principle: profiling defects carry NO activation mechanism — no `feature-flag-service` flag and
no environment variable. Each defect is baked into the service's default behaviour and is always
on.**

The whole point of the agentic eval is to score whether an agent can *diagnose a defect from
profiling signal*. If the agent can discover *that a defect is armed* from the running app, the eval
measures nothing. We considered two activation mechanisms and rejected both:

- **Feature flags — rejected.** EasyTrade's `feature-flag-service` advertises every flag through the
  REST API (`GET /feature-flag-service/v1/flags?tag=problem_pattern`), the Swagger UI, and the
  frontend `/feature-flags` page (name, description, ready-to-paste `curl`). A flag named
  `memory_leak` described as *"…growing heap monotonically…"* hands the agent the answer key.
  Feature flags stay appropriate for the **functional** problem patterns (`DbNotResponding`,
  `FactoryCrisis`, …) that are *meant* to be demoed from the UI — they are wrong for a hidden
  ground-truth eval.
- **Env-var activation — also dropped.** An earlier version armed defects via a neutrally-named
  private env var (e.g. UC2's `REQUEST_TRACE_RETENTION_ENABLED`). It removed the flag-plumbing cost,
  but still leaves an arming signal an agent with cluster access (`kubectl get deploy -o yaml`) or a
  tenant view of captured process env vars could read — and it is one more thing to get wrong (UC2
  in fact shipped with the env var *unset* in Helm, so it never leaked; see §7). We dropped it.

**No-activation contract for every profiling defect (UC1–UC11):**

1. **Always-on.** The defect is default behaviour in the service code. There is no flag, no env var,
   no runtime toggle — nothing to arm and therefore nothing to discover from the live app.
2. **Scenarios do not overlap.** Each UC runs on its own isolated deployment (one scenario per
   instance, §6 Q4), so an always-on defect never contaminates another scenario's profiles. This is
   what removes the need for a clean, un-armed baseline from the same image.
3. **Source code is a permitted oracle, the running app is not.** The agent may be granted the repo
   to *propose a fix* — the defect living plainly in source is fine and intended. What the eval
   tests is whether the agent localizes it from *profiling signal*, not from a flag or env var.

Trade-off we accept: the same image cannot serve a clean baseline, and the defect is present in
every deployment of that service on the fork. Both are fine under per-scenario isolation (§6 Q4). If
we ever need several UCs live on one shared instance, we would reintroduce a concealed toggle then —
out of scope for now.

## 3. Proposed profiling use-cases (generic, product-agnostic)

These are the eleven agentic profiling scenarios we consider most representative. For each we note
the **profiling signal** it exercises and the **agent task** we'd score.

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

**Coverage rationale:** the use-cases span the three profiling pillars and the "traps" where
symptoms look alike in metrics but differ in the profile:
- *on-CPU:* UC1 (business hotspot; incl. the cache-defeat variant), UC5 (GC symptom), UC8
  (logging), UC9 (serialization), UC11 (spin that only *looks* on-CPU).
- *allocation:* UC2 (retained leak), UC5 (churn), UC9 (marshalling allocation).
- *off-CPU / wait:* UC3 (lock), UC4 (pool queue), UC6 (conditional cache miss),
  UC10 (parked leaked threads).

The set is built around **disambiguation pairs** that force the agent to reason, not pattern-match:
UC2↔UC5 (leak vs. churn), UC4↔UC10 (pool saturation vs. thread leak), UC1↔UC11 (real compute vs.
spin-wait), UC3↔UC6 (steady lock vs. intermittent tail), and
UC1↔UC8↔UC9 (business CPU vs. logging vs. serialization). The Java UC1 adds a further twist —
a *real* CPU hotspot whose true root cause is a **defeated cache**, not the hot loop itself. UC6
additionally adds a **probabilistic/tail-latency** dimension the always-on defects lack.

## 4. Is EasyTrade a good candidate to extend?

**Preliminary verdict: yes — strong fit, with gaps to fill.** EasyTrade already gives us most of
the scaffolding; what's missing is profiling-specific fault injection across runtimes.

### 4.1 What EasyTrade already provides (pros)
- **Multi-runtime by design** — 19 services across **Java 21 / Spring Boot, Go, .NET 8,
  Node/TS**, plus a C++ calc service. Directly satisfies the "multiple runtimes" requirement so we
  can exercise per-language profiling.
- **Realistic, continuous traffic** — a dedicated **`loadgen`** service (Puppeteer/Chrome) replays
  real browser journeys (deposit + buy/sell, long positions, credit-card orders) continuously,
  with an **NYSE time-of-day load curve** (heavier during simulated market hours, `0.7×`
  off-hours). Exactly the steady, non-trivial baseline we need — and the market-hours curve gives
  us natural load variation to profile against.
- **A clean, extensible fault-injection framework** (for the *functional* patterns — but see the
  concealment caveat below and §2.1):
  - **`feature-flag-service`** (Java) holds all flags as an **in-memory Spring bean map**
    (`FeatureFlagConfig.java#flagRegistry()`), toggled over REST (`GET/PUT /v1/flags/{id}`).
    Consumers read flags via the **OpenFeature SDK** with per-stack providers (.NET
    `PluginManager` w/ 60s TTL cache; Java `JavaProvider`/`FeatureFlagClient`; Node
    `EasyTradeProvider`). Adding a flag = one edit to `FeatureFlagConfig.java` +
    `application.properties`, plus a per-service constant.
    **⚠️ Not usable for profiling defects:** flags are advertised via REST, Swagger, and the
    frontend `/feature-flags` page, which would reveal a defect to the investigating agent. See
    §2.1 — profiling defects are always-on with no activation at all. The flag framework remains
    the right tool for the demo-facing functional patterns.
  - **`problem-operator`** (Go) is a pluggable k8s operator: on a 5s ticker it reconciles flags
    against Deployment specs. Adding an operator-driven behavior = a new package in
    `controllers/` + one `RegisterController(...)` line. (It reads the same flags, so it inherits
    the same visibility caveat; it can still apply *k8s-spec* side effects like the CPU limit.)
- **`HighCpuUsage` is already a profiling-shaped template.** In `broker-service` (.NET),
  `HighCpuUsageMiddleware` spins N `Task.Run` workers running a tight Collatz busy-loop per
  request, deliberately marked `[MethodImpl(NoInlining)]` **so it shows up in profiler call
  trees**. The operator separately applies a `300m` CPU limit on K8s to force throttling. This is
  the exact shape we generalize for UC1 and is the lowest-friction host for new patterns.
- **Meaningful call graphs** — trades flow through proxy → multiple services → MSSQL and a
  RabbitMQ queue (`pricing-service` → `calculationservice`). Real cross-service work means the
  profiler has non-trivial stacks to attribute, and off-CPU/DB-wait scenarios are natural.
- **Already Dynatrace-native** — K8s + Helm deployment, Monaco configs, documented DQL workflow.
- **No arming step at all** — profiling defects are always-on default behaviour (§2.1). There is no
  flag and no env var to set; deploying the service *is* arming it. Per-scenario isolation (§6 Q4)
  keeps that from contaminating other scenarios.

### 4.2 Gaps / what we'd need to add
- **Only 1 of 4 existing patterns is truly profiling-shaped**, and two are *misleadingly named*:
  - `HighCpuUsage` — ✅ real CPU busy-loop (our UC1 template).
  - `DbNotResponding` — ❌ **not** a DB-latency fault; it corrupts a trade row (`Id = -1`) so
    creation errors out. Pure logic fault.
  - `FactoryCrisis` — ❌ **not** a memory leak despite the name; it forces every card to
    `MANUFACTURE_ERROR`. Functional fault.
  - `ErgoAggregatorSlowdown` — latency injection (`await delay(2000)` in `offerservice`) +
    Go backoff. Availability/latency fault, no CPU/mem cost.
  → We must **build new patterns for UC2–UC5** (leak, contention, pool exhaustion, GC pressure);
  none exist today.
- **Always-on activation needs no plumbing — this is a simplification.** Because profiling defects
  are baked into the service's default behaviour (§2.1), we need neither OpenFeature providers nor
  an env-var read. Any service — Go (`pricing-service`, `aggregator-service`) with zero flag
  plumbing, or busy Java services (`engine`, `accountservice`) — can host a defect as a plain code
  change. (UC2 went through an env-var stage and then dropped it entirely.)
- **No intensity control / ground-truth catalog yet.** We need a documented mapping
  `pattern → {service, file, symbol, expected flamegraph signature, expected DQL}` to grade the
  agent objectively, plus a source/dataset-level intensity knob (rate/size).
- **GC realism varies by runtime** — Go has no classic stop-the-world GC-pause story like the
  JVM/.NET; pick per-runtime defects that are idiomatic (e.g. GC pressure → JVM/.NET, not Go).

### 4.3 Complexity assessment
The app is **complex enough**: 19 services, four language runtimes, a message queue, a shared
database, and real multi-hop request flows under continuous synthetic load. That is more than
enough surface to host all eleven use-cases realistically and to make the agent's job non-trivial
(it must localize a defect within a real, noisy distributed system rather than a toy).

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
| UC3 Lock contention | Java | TBD | planned | always-on coarse `synchronized`/`ReentrantLock` around a hot section → threads block, CPU idle |
| UC4 Thread-pool exhaustion | Java | TBD | planned | always-on bounded `ExecutorService` (or constrained worker pool) that holds/leaks threads → requests queue |
| UC5 GC / alloc churn | Java | TBD | planned | always-on high-allocation path emitting many short-lived objects → high alloc rate + GC pauses |
| UC6 Conditional cache-miss wait | Java | TBD | planned | always-on cache with a tunable miss rate (~5%); missed requests fall back to a slow downstream/DB load → intermittent off-CPU/net-IO wait on the tail |
| UC8 Logging overhead | Java | `accountservice`/`engine` (candidate) | planned | always-on per-request INFO log that serializes a large object (`Gson#toJson`) and/or a synchronous appender → CPU in log frames + I/O |
| UC9 Serialization overhead | Java | `accountservice` (candidate) | planned | always-on repeated (de)serialization of the payload (parse→transform→re-serialize / pretty-print) on `GET /account/{id}` → serializer CPU + alloc |
| UC10 Thread leak | Java | `contentcreator` (candidate) | planned | always-on per-request `new Thread` that parks forever (never joined) → unbounded thread count + native-memory growth, heap flat |
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

## 6. Open questions

1. **Which Dynatrace tenant(s)** host this, and is continuous profiling enabled there today?
2. **Eval harness** — does agentic-eval already have a runner we plug into, or do we build the
   deploy → wait → query-tenant → grade loop from scratch? (No arm/disarm step — defects are
   always-on, §2.1.)
3. **Ground-truth grading** — score on "named the right service", "named the right method", or a
   rubric? Who owns the answer key?
4. ~~**One app instance or per-scenario instances?**~~ **Resolved: per-scenario, non-overlapping.**
   Each UC runs on its own isolated deployment so scenarios never contaminate each other's profiles.
   This is what lets defects stay **flag-free / always-on** (no arm/disarm lifecycle needed on a
   shared instance) — see the §7.1 note. Revisit only if we ever need several UCs live at once on one
   instance.
5. ~~**Do we upstream these patterns** into the public EasyTrade?~~ **Resolved: no.** We keep a
   profiling-specific **fork** and do **not** open PRs to `origin` — these eval defects don't
   belong in the public demo app (see the repo/contribution note at the top).
6. ~~**UC2 target service**~~ **Resolved:** UC2 is implemented in `accountservice`, always-on
   (§2.1, §5, §7), on the trafficked `AccountControllerV2` / `/accounts/{id}` endpoint.
7. ~~**Is env-var concealment strong enough** for the eval's threat model?~~ **Resolved:**
   activation was dropped entirely (§2.1) — defects are always-on, so there is no arming variable to
   read. The remaining exposure is that the defect lives in source, which is a permitted oracle.

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

## 8. Next steps (post-prototype)

1. ✅ UC2 built end-to-end in `accountservice`, always-on (§7). Post-mortem: the leak must live on a
   trafficked endpoint (`AccountControllerV2` / `/accounts/{id}`), not the dead `/account/{id}`.
2. ✅ UC1 (Java) cache-defeat CPU hotspot built end-to-end in `credit-card-order-service` (§7.1),
   always-on. Note the load requirements: the seeded dataset and the loadgen status-view traffic are
   what make it burn CPU (see §7.1).
3. Confirm the tenant has continuous profiling enabled; wire the deploy→wait→query→grade harness (no
   arm/disarm step — defects are always-on, §2.1).
4. Standardize the ground-truth catalog; backfill for the .NET UC1 (existing `HighCpuUsage`) — and
   migrate it off its `high_cpu_usage` flag so it isn't self-disclosing either. The Java UC1 (§7.1)
   is already always-on.
5. Roll out UC3–UC6, UC8–UC11 on **Java services** (§5), all **always-on** (§2.1) under per-scenario
   isolation (§6 Q4). Sequence by disambiguation value: wait/CPU defects first (UC3, UC11), then the
   leak/churn pairs (UC5, UC10), then the intensity-tunable ones (UC6 miss-rate, UC8/UC9) — tune
   intensity at the source/dataset level, not a runtime toggle.
6. ✅ Upstream-vs-fork decided (§6 Q5): stay on the **fork**, no PRs to `origin`.
