# Agentic Profiling Workstream (DRAFT)

> Status: **draft for discussion** · Owner: Roman Khazankin · Last updated: 2026-08-20

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

- Each defect is **toggle-able** (on/off, ideally with intensity) by the eval operator.
- Each defect's activation is **concealed from anyone inspecting the running app** — the
  investigating agent must not be able to discover *that a defect is armed* through the app's own
  surfaces (see §2.1). Toggling may require a pod restart; that is an acceptable price for
  concealment.
- Each defect has a **documented ground-truth root cause** (service, file, symbol, expected
  flamegraph signature).
- Defects run against a **steady baseline of realistic traffic** so profiles are non-trivial and
  the signal must be separated from normal work.
- Defects are spread across **multiple runtimes** (JVM, Go, .NET, Node) so we exercise
  language-specific profiling behavior and don't overfit the agent to one stack.
- The whole thing is **deployable to K8s and monitored by a Dynatrace tenant** with profiling
  enabled, and is stable enough to leave running for days.

### 2.1 Activation & concealment (why NOT feature flags)

**Principle: profiling defects must NOT be armed through EasyTrade's `feature-flag-service`.**

The whole point of the agentic eval is to score whether an agent can *diagnose a defect from
profiling signal*. If the agent can simply discover the defect is armed, the eval measures nothing.
EasyTrade's feature-flag mechanism is the opposite of concealed — a flag is advertised through
every app-facing surface:

- **REST API** — `GET /feature-flag-service/v1/flags?tag=problem_pattern` returns every problem
  pattern, its enabled state, name, and a plain-English description of what it does.
- **Swagger UI** — `…/feature-flag-service/swagger-ui/index.html` lists them interactively.
- **Frontend** — the app ships a `/feature-flags` page that renders each `problem_pattern` flag,
  its human description, and a ready-to-paste `curl` command to flip it.

So a flag named `memory_leak` with the description *"…growing heap monotonically…"* hands the agent
the answer key. Feature flags stay appropriate for the **functional** problem patterns
(`DbNotResponding`, `FactoryCrisis`, …) that are *meant* to be demoed and toggled from the UI —
they are just wrong for a **hidden ground-truth** eval.

**Concealed-activation contract for every profiling defect (UC1–UC11):**

1. **Activate via a private environment variable**, read by the service (typically once at
   startup), never through `feature-flag-service` / OpenFeature. Env vars are not exposed by any
   EasyTrade endpoint, Swagger, or the frontend.
2. **Use a neutral, non-descriptive variable name** that does not name the defect. It may surface
   in process properties on the monitored tenant, so it must not read as "leak/hotspot/etc." —
   e.g. UC2 uses `REQUEST_TRACE_RETENTION_ENABLED` (looks like a diagnostic-tracing toggle), not
   `ENABLE_MEMORY_LEAK`.
3. **Default to off** (absent/false ⇒ normal behaviour), so an un-armed deployment is clean.
4. **Arm only from the deployment layer** — the eval harness sets the env var in the K8s
   Deployment (or `compose.dev.yaml`) and restarts the pod. There is intentionally no runtime
   REST toggle; a restart to arm/disarm is acceptable.
5. **Source code is a permitted oracle, the running app is not.** The agent may be granted the
   repo to *propose a fix* — the defect living plainly in source is fine and intended. What must
   stay hidden is any signal, queryable from the *live app*, that a defect is currently armed.

Residual risk to keep in mind: an agent with cluster access (`kubectl get deploy -o yaml`) or a
tenant view that exposes captured environment variables could still read the arming variable. The
neutral naming (rule 2) mitigates this; if a stronger boundary is ever needed, bake the defect into
a dedicated image variant instead of an env var. Track this in §6.

## 3. Proposed profiling use-cases (generic, product-agnostic)

These are the eleven agentic profiling scenarios we consider most representative. For each we note
the **profiling signal** it exercises and the **agent task** we'd score.

### UC1 — CPU hotspot / inefficient algorithm ("on-CPU")
A single method or call path burns a disproportionate share of CPU (e.g. an O(n²) loop, regex
backtracking, unbounded serialization, crypto in a hot path).
- **Signal:** on-CPU sampling; a dominant frame in the flamegraph.
- **Agent task:** identify the offending service + method/stack, quantify its CPU share, and
  propose the fix. Ground truth = the injected hot method.

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

### UC7 — N+1 / chatty database access (off-CPU DB wait by call *count*)
The service is slow because it issues **many small queries** instead of one set-based query — the
classic N+1: fetch a list, then loop and run one query per row. Each query is fast; the aggregate
wait comes from the *number* of round-trips.
- **Signal:** off-CPU / DB-wait time dominated by a **high count** of short JDBC calls (not one long
  call); database service shows a spike in query volume with low per-query duration; the calling
  stack repeats the same statement in a loop.
- **Agent task:** recognize "death by a thousand queries" — attribute latency to call *frequency*
  rather than a single slow statement, locate the loop issuing per-row queries, and distinguish it
  from UC6 (one conditional slow call) and UC3 (a lock). Ground truth = the per-row query loop.
- **Grounded in:** `credit-card-order-service` `DatabaseHelper` already exposes both a set query
  (`getOrderStatusList`) and per-row queries (`getLastOrderStatus`, `getOrderCountForAccountId`);
  the env-gated defect replaces the set fetch with a per-row loop on `GET /v1/orders/{id}/status`.

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
  `AccountController` PUT logs `gson.toJson(accountDetails)`; the env-gated defect turns this into
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
  bodies; the env-gated defect re-serializes/re-parses the payload repeatedly (or pretty-prints a
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
  `ScheduledExecutorService` (size 1–2). The env-gated defect spawns a per-request thread that
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
  `HttpClient.send` results) can be replaced, under the env gate, by a spin-poll instead of a
  blocking call.

> **Cross-cutting scenario (stretch): release regression / profile diff.** Ship a "slow" build,
> let the agent compare before/after profiles to localize the regressed frame. This is arguably
> the highest-value agentic use-case but depends on us running two builds; kept as a stretch goal.

**Coverage rationale:** the eleven use-cases span the three profiling pillars and the "traps" where
symptoms look alike in metrics but differ in the profile:
- *on-CPU:* UC1 (business hotspot), UC5 (GC symptom), UC8 (logging), UC9 (serialization),
  UC11 (spin that only *looks* on-CPU).
- *allocation:* UC2 (retained leak), UC5 (churn), UC9 (marshalling allocation).
- *off-CPU / wait:* UC3 (lock), UC4 (pool queue), UC6 (conditional cache miss),
  UC7 (many small DB calls), UC10 (parked leaked threads).

The set is built around **disambiguation pairs** that force the agent to reason, not pattern-match:
UC2↔UC5 (leak vs. churn), UC4↔UC10 (pool saturation vs. thread leak), UC1↔UC11 (real compute vs.
spin-wait), UC3↔UC6↔UC7 (steady lock vs. intermittent tail vs. call-count wait), and
UC1↔UC8↔UC9 (business CPU vs. logging vs. serialization). UC6 additionally adds a
**probabilistic/tail-latency** dimension the always-on defects lack.

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
    frontend `/feature-flags` page, which reveals armed defects to the investigating agent. See
    §2.1 — profiling defects use concealed env-var activation instead. The flag framework remains
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
- **Arming is deployment-driven** — profiling defects flip via a concealed env var set on the
  Deployment/compose environment (§2.1), so the eval harness arms/disarms them between runs by
  patching env + restarting the pod. (Note: this is deliberately *not* the API-driven runtime
  toggle used by the demo-facing functional flags — concealment is worth the restart.)

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
- **Concealed activation needs no flag plumbing — this is a simplification.** Because profiling
  defects are armed by a private env var (§2.1) rather than the flag client, we do *not* need to
  copy OpenFeature providers into the target services. This removes what used to be the highest-
  friction item: Go services (`pricing-service`, `aggregator-service`) with **zero** flag plumbing,
  and busy Java services (`engine`, `accountservice`) that lacked the `JavaProvider`/
  `FeatureFlagClient` pair, can host a defect with just a `System.getenv`/`os.Getenv` read. (UC2 in
  `accountservice` was the first to switch: its OpenFeature client was removed in favour of the
  `REQUEST_TRACE_RETENTION_ENABLED` env var.)
- **Arming does not survive teardown, by design.** Env-var arming lives in the Deployment/compose
  spec, so it persists across pod *restarts* (unlike the old in-memory flag state) but is removed
  when the eval harness disarms. The harness owns the arm→restart→observe→disarm lifecycle.
- **No intensity control / ground-truth catalog yet.** We need a documented mapping
  `pattern → {activation env var, service, file, symbol, expected flamegraph signature, expected
  DQL}` to grade the agent objectively, plus an intensity knob (rate/size).
- **GC realism varies by runtime** — Go has no classic stop-the-world GC-pause story like the
  JVM/.NET; pick per-runtime defects that are idiomatic (e.g. GC pressure → JVM/.NET, not Go).

### 4.3 Complexity assessment
The app is **complex enough**: 19 services, four language runtimes, a message queue, a shared
database, and real multi-hop request flows under continuous synthetic load. That is more than
enough surface to host all eleven use-cases realistically and to make the agent's job non-trivial
(it must localize a defect within a real, noisy distributed system rather than a toy).

## 5. Proposed defect → service mapping (first cut)

All defects are armed via a concealed env var (§2.1), so there is no flag-client "plumbing status"
to track — any service can host a defect with a single `getenv` read.

**Runtime decision: UC2–UC6 target Java services; UC1 stays .NET.** We concentrate the new defects
on the **JVM** to go deep on Java profiling (allocation, monitor/lock, thread-state, and GC signals
are all first-class there), keeping UC1 on .NET (`broker-service`) as the one cross-runtime
representative that reuses the existing `HighCpuUsage` template. This is a deliberate trade against
the original "spread across many runtimes" goal (§2) — Go/Node are dropped from the core set for
now; revisit if we want broader runtime coverage. The **specific Java host per UC is left open**
(TBD) — since env-var activation needs no per-service plumbing, host choice is a later, cheap
decision driven by which service gives the cleanest signal under load.

| Use-case | Runtime | Host | Status | Injection idea |
|---|---|---|---|---|
| UC1 CPU hotspot | .NET | `broker-service` (existing `HighCpuUsage`) | planned | Add a second env-gated hot path (regex backtracking / expensive serialization) beside the Collatz loop |
| UC2 Memory leak | Java | `accountservice` | ✅ implemented | `REQUEST_TRACE_RETENTION_ENABLED`-gated `static` collection that grows per `GET /account/{id}` and is never freed |
| UC3 Lock contention | Java | TBD | planned | env-gated coarse `synchronized`/`ReentrantLock` around a hot section → threads block, CPU idle |
| UC4 Thread-pool exhaustion | Java | TBD | planned | env-gated bounded `ExecutorService` (or constrained worker pool) that holds/leaks threads → requests queue |
| UC5 GC / alloc churn | Java | TBD | planned | env-gated high-allocation path emitting many short-lived objects → high alloc rate + GC pauses |
| UC6 Conditional cache-miss wait | Java | TBD | planned | env-gated cache with a tunable miss rate (~5%); missed requests fall back to a slow downstream/DB load → intermittent off-CPU/net-IO wait on the tail |
| UC7 N+1 / chatty DB | Java | `credit-card-order-service` (candidate) | planned | env-gated per-row query loop replacing `getOrderStatusList` on `GET /v1/orders/{id}/status` → many short JDBC calls |
| UC8 Logging overhead | Java | `accountservice`/`engine` (candidate) | planned | env-gated per-request INFO log that serializes a large object (`Gson#toJson`) and/or a synchronous appender → CPU in log frames + I/O |
| UC9 Serialization overhead | Java | `accountservice` (candidate) | planned | env-gated repeated (de)serialization of the payload (parse→transform→re-serialize / pretty-print) on `GET /account/{id}` → serializer CPU + alloc |
| UC10 Thread leak | Java | `contentcreator` (candidate) | planned | env-gated per-request `new Thread` that parks forever (never joined) → unbounded thread count + native-memory growth, heap flat |
| UC11 Busy-wait / spin-poll | Java | `engine` (candidate) | planned | env-gated tight `while (!done)` spin replacing a blocking `HttpClient.send`/await → 100% CPU that is really a wait |

**Notes driving these choices:**
- **JVM focus (UC2–UC11):** the JVM exposes rich, distinct profiling signals for every pillar
  (allocation, monitor-wait, thread-state, GC, off-CPU/net-IO, JDBC wait, spin), so a Java-heavy set
  exercises the profiling product thoroughly on one runtime.
- **UC7–UC11 are grounded in existing code** (see the "Grounded in" notes in §3): real JDBC helpers,
  per-request logging, `Gson` usage, `new Thread` spawning, and blocking downstream calls already
  exist, so each defect is a small, believable mutation of a real path rather than a synthetic add-on.
- **Host = TBD, on purpose:** concealed env-var activation removes the per-service plumbing cost,
  so we pick each host later based on signal quality. Practical hints for when we do: the always-on
  concurrency/wait defects (UC3/UC4/UC6) want a **busy, request-driven** Java service
  (`accountservice` is the obvious candidate); UC5's steady alloc churn suits a **background-loop**
  service (`engine`'s scheduler) that generates signal without needing request traffic; the
  low-traffic card services (`credit-card-order-service`, `third-party-service`) are weak hosts for
  load-dependent defects unless we bump their `loadgen` frequency.
- **UC2 landed in `accountservice`** (a busy Java service under continuous load) for a strong
  memory-growth signal — the reference implementation for the concealed-activation pattern.
- **UC1 stays .NET** on `broker-service`, which already has a profiler-visible template
  (`HighCpuUsage`); it keeps at least one non-JVM host in the mix.

## 6. Open questions

1. **Which Dynatrace tenant(s)** host this, and is continuous profiling enabled there today?
2. **Eval harness** — does agentic-eval already have a runner we plug into, or do we build the
   arm-defect → wait → query-tenant → grade loop from scratch?
3. **Ground-truth grading** — score on "named the right service", "named the right method", or a
   rubric? Who owns the answer key?
4. **One app instance or per-scenario instances?** Isolated instances give cleaner signal;
   one shared instance is cheaper but noisier.
5. **Do we upstream these patterns** into the public EasyTrade, or keep a profiling-specific fork?
   (Concealed env-var defects don't belong in the public demo; this pushes toward a fork or a
   build-flag-gated variant.)
6. ~~**UC2 target service**~~ **Resolved:** UC2 is implemented in `accountservice` with concealed
   env-var activation (§2.1, §5). The old flag-client-plumbing trade-off no longer applies.
7. **Is env-var concealment strong enough** for the eval's threat model, or do we need to prevent
   an agent with cluster/tenant access from reading the arming variable (dedicated image variant,
   built-in defect)? See the residual-risk note in §2.1.

## 7. Prototype — UC2 memory leak, end-to-end (implemented)

Goal: prove the whole loop on **one** defect before scaling — inject → observe in the profiling
product on a monitored tenant → capture ground truth → (later) grade an agent.

**Target service: `accountservice`** (busy Java service under continuous load). Chosen for a strong
memory-growth signal; env-var activation meant no flag client or loadgen changes were needed.

**What was built:**
1. **Concealed activation, not a feature flag.** The leak is gated by the private env var
   `REQUEST_TRACE_RETENTION_ENABLED` (default off), read once at startup in
   `AccountController`. There is deliberately **no** `feature-flag-service` flag, so nothing about
   the defect is discoverable via REST/Swagger/frontend (§2.1). The earlier `memory_leak` flag and
   the OpenFeature client wiring were removed from `accountservice` and `feature-flag-service`.
2. **The leak.** On each `GET /account/{id}`, when armed, `accumulateRequestTrace()` appends a
   4 KB `byte[]` to a `static` `HEAP_RETAINER` list that is **never cleared** — monotonic heap
   growth. The method is distinctly named so the allocation profiler shows the frame unambiguously
   (mirrors `HighCpuUsage`'s `[MethodImpl(NoInlining)]` intent).
3. **Arm/verify locally** via `compose.dev.yaml`: set `REQUEST_TRACE_RETENTION_ENABLED: "true"` on
   the `accountservice` block (commented example already present) and restart the pod; watch
   heap/RSS climb under load; disarm (remove the env var + restart) → confirm it plateaus (the
   leak-vs-churn distinction is part of the ground truth).
4. Deploy to a monitored tenant (Helm) with profiling enabled; confirm allocation profiling
   attributes the growth to `accumulateRequestTrace` and the memory trend correlates.
5. The **ground-truth catalog entry** is below.

**Ground-truth catalog entry (standardized format — note `activation`, not `flag`):**
```yaml
- id: UC2-memory-leak
  activation:
    mechanism: env-var            # concealed; NOT a feature-flag-service flag
    var: REQUEST_TRACE_RETENTION_ENABLED
    armed_when: "true"            # absent/false => normal behaviour
    toggle: set on Deployment/compose env + restart pod
  service: accountservice
  runtime: java
  root_cause:
    file: src/accountservice/src/main/java/com/dynatrace/easytrade/accountservice/AccountController.java
    symbol: AccountController#accumulateRequestTrace
    trigger: GET /account/{id}
    mechanism: unbounded static List<byte[]> (HEAP_RETAINER), 4 KB/request, never freed while armed
  expected_signal:
    profiling: allocation hotspot at AccountController#accumulateRequestTrace; retained set grows with request count
    metric: heap/RSS monotonic increase; plateaus when disarmed
  expected_dql: <query>
  agent_answer_key:
    service: accountservice
    method: AccountController#accumulateRequestTrace
    classification: leak (not churn)
```

## 8. Next steps (post-prototype)

1. ✅ UC2 built end-to-end in `accountservice` with concealed activation (§7).
2. Confirm the tenant has continuous profiling enabled; wire the arm→restart→wait→query→grade
   harness (arming = env-var patch + pod restart, per §2.1).
3. Standardize the ground-truth catalog; backfill for UC1 (already implemented) — and migrate UC1
   off its `high_cpu_usage` flag to concealed activation so it isn't self-disclosing either.
4. Roll out UC3–UC11 on **Java services** (§5), each with concealed env-var activation. Sequence by
   disambiguation value: build the always-on wait/CPU defects first (UC3, UC7, UC11), then the
   leak/churn pairs (UC5, UC10), then the intensity-tunable ones (UC6 miss-rate, UC8/UC9). Give the
   probabilistic defects a knob so we can dial signal difficulty.
5. Decide upstream-vs-fork for the public EasyTrade (§6 Q5) — concealed defects argue for a fork
   or a build-gated variant.
