# Agentic Profiling Workstream (DRAFT)

> Status: **draft for discussion** · Owner: Roman Khazankin · Last updated: 2026-08-13

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

- Each defect is **toggle-able** (on/off, ideally with intensity) without redeploying.
- Each defect has a **documented ground-truth root cause** (service, file, symbol, expected
  flamegraph signature).
- Defects run against a **steady baseline of realistic traffic** so profiles are non-trivial and
  the signal must be separated from normal work.
- Defects are spread across **multiple runtimes** (JVM, Go, .NET, Node) so we exercise
  language-specific profiling behavior and don't overfit the agent to one stack.
- The whole thing is **deployable to K8s and monitored by a Dynatrace tenant** with profiling
  enabled, and is stable enough to leave running for days.

## 3. Proposed profiling use-cases (generic, product-agnostic)

These are the five agentic profiling scenarios we consider most representative. For each we note
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

> **Cross-cutting scenario (stretch): release regression / profile diff.** Ship a "slow" build,
> let the agent compare before/after profiles to localize the regressed frame. This is arguably
> the highest-value agentic use-case but depends on us running two builds; kept as a stretch goal.

**Coverage rationale:** UC1/UC5 stress *on-CPU* attribution, UC2 stresses *allocation*
profiling, UC3/UC4 stress *off-CPU / wait* profiling — together they cover the three pillars of a
profiling product and force the agent to *disambiguate* symptoms that look similar in metrics but
differ in the profile.

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
- **A clean, extensible fault-injection framework:**
  - **`feature-flag-service`** (Java) holds all flags as an **in-memory Spring bean map**
    (`FeatureFlagConfig.java#flagRegistry()`), toggled over REST (`GET/PUT /v1/flags/{id}`).
    Consumers read flags via the **OpenFeature SDK** with per-stack providers (.NET
    `PluginManager` w/ 60s TTL cache; Java `JavaProvider`/`FeatureFlagClient`; Node
    `EasyTradeProvider`). Adding a flag = one edit to `FeatureFlagConfig.java` +
    `application.properties`, plus a per-service constant.
  - **`problem-operator`** (Go) is a pluggable k8s operator: on a 5s ticker it reconciles flags
    against Deployment specs. Adding an operator-driven behavior = a new package in
    `controllers/` + one `RegisterController(...)` line.
- **`HighCpuUsage` is already a profiling-shaped template.** In `broker-service` (.NET),
  `HighCpuUsageMiddleware` spins N `Task.Run` workers running a tight Collatz busy-loop per
  request, deliberately marked `[MethodImpl(NoInlining)]` **so it shows up in profiler call
  trees**. The operator separately applies a `300m` CPU limit on K8s to force throttling. This is
  the exact shape we generalize for UC1 and is the lowest-friction host for new patterns.
- **Meaningful call graphs** — trades flow through proxy → multiple services → MSSQL and a
  RabbitMQ queue (`pricing-service` → `calculationservice`). Real cross-service work means the
  profiler has non-trivial stacks to attribute, and off-CPU/DB-wait scenarios are natural.
- **Already Dynatrace-native** — K8s + Helm deployment, Monaco configs, documented DQL workflow.
- **Toggling is API-driven** — flags flip via REST (and the frontend), so an eval harness can
  arm/disarm defects programmatically between runs.

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
- **Go application services have no runtime flag plumbing.** `pricing-service` and
  `aggregator-service` contain **zero** feature-flag references — only `problem-operator` reads
  flags. Any in-process Go defect (e.g. UC5 alloc/GC churn) requires first copying a flag client
  (the operator's `featureflag` GET-`/v1/flags/{name}` client is a ready template). **Highest
  friction stack.**
- **Java flag client is not universal.** Only `third-party-service` and
  `credit-card-order-service` have `JavaProvider`/`FeatureFlagClient` wired. The *busy* Java
  services under load (`engine`, `accountservice`) would need the client copied in. So there's a
  tension: existing-plumbing Java services are **low-traffic**; high-traffic Java services need
  **plumbing added**.
- **Flag state is in-memory and resets on pod restart** (no persistence). The eval harness must
  **re-arm defects after any restart**, and the operator-applied CPU limit is the only thing that
  survives via k8s spec. (Could be turned into a feature — chaos — or we add persistence.)
- **No intensity control / ground-truth catalog yet.** We need a documented mapping
  `pattern → {flag id, service, file, symbol, expected flamegraph signature, expected DQL}` to
  grade the agent objectively, plus an intensity knob (rate/size).
- **GC realism varies by runtime** — Go has no classic stop-the-world GC-pause story like the
  JVM/.NET; pick per-runtime defects that are idiomatic (e.g. GC pressure → JVM/.NET, not Go).

### 4.3 Complexity assessment
The app is **complex enough**: 19 services, four language runtimes, a message queue, a shared
database, and real multi-hop request flows under continuous synthetic load. That is more than
enough surface to host all five use-cases realistically and to make the agent's job non-trivial
(it must localize a defect within a real, noisy distributed system rather than a toy).

## 5. Proposed defect → service mapping (first cut)

| Use-case | Candidate service | Runtime | Plumbing status | Injection idea |
|---|---|---|---|---|
| UC1 CPU hotspot | `broker-service` (generalize existing `HighCpuUsage`) | .NET | ✅ exists | Add a second hot path (e.g. regex backtracking / expensive serialization) beside the Collatz loop |
| UC2 Memory leak | `credit-card-order-service` or `third-party-service` | Java | ✅ client wired | Flag-gated `static` collection that grows per request and is never freed |
| UC3 Lock contention | `broker-service` | .NET | ✅ exists | Flag-gated coarse `lock`/`SemaphoreSlim(1)` around a hot section → threads block, CPU idle |
| UC4 Event-loop / pool starvation | `offerservice` | Node | ✅ provider wired | Flag-gated synchronous CPU block in middleware → event loop stalls, requests queue |
| UC5 GC / alloc churn | `broker-service` (.NET) **or** `pricing-service` (Go) | .NET / Go | .NET ✅ / **Go ✗ needs client** | High-allocation churn path; GC-pause story is idiomatic on .NET/JVM, alloc-rate on Go |

**Notes driving these choices:**
- `broker-service` (.NET) is the **lowest-friction, busiest** host — it already has the
  `ProblemPatterns/` folder, `PluginManager`, and a profiler-visible template. Concentrating early
  prototypes here de-risks the end-to-end loop; we spread across runtimes once the loop is proven.
- The two Java card services have the flag client but see **low traffic** (credit-card orders are
  a *rare* loadgen visit, ~every 30 min). For a strong memory-growth trend we may need to **raise
  the card-order frequency in `loadgen`** or add the flag client to a busy Java service
  (`engine`/`accountservice`).
- Go (`pricing-service`/`aggregator-service`) is deferred until a flag client is added; both have
  natural hot paths (RabbitMQ publish loop; periodic tickers) once plumbed.

## 6. Open questions

1. **Which Dynatrace tenant(s)** host this, and is continuous profiling enabled there today?
2. **Eval harness** — does agentic-eval already have a runner we plug into, or do we build the
   arm-defect → wait → query-tenant → grade loop from scratch?
3. **Ground-truth grading** — score on "named the right service", "named the right method", or a
   rubric? Who owns the answer key?
4. **One app instance or per-scenario instances?** Isolated instances give cleaner signal;
   one shared instance is cheaper but noisier.
5. **Do we upstream these patterns** into the public EasyTrade, or keep a profiling-specific fork?
6. **UC2 target service** — use a card service (existing flag client, low traffic) and bump
   loadgen frequency, or add the flag client to a busy Java service (`engine`/`accountservice`)
   for a stronger memory-growth signal?

## 7. Prototype plan — UC2 memory leak, end-to-end (agreed first step)

Goal: prove the whole loop on **one** defect before scaling — inject → observe in the profiling
product on a monitored tenant → capture ground truth → (later) grade an agent.

**Target service — decision needed (see §6 Q6).** Two viable Java hosts:
- `credit-card-order-service` / `third-party-service` — flag client already wired, but low traffic
  (may need a `loadgen` frequency bump for a visible trend).
- a busy Java service (`engine`/`accountservice`) — strong signal, but needs the
  `JavaProvider`/`FeatureFlagClient` pair copied in first.

**Implementation sketch (whichever Java host):**
1. Register a new flag `memory_leak` in `feature-flag-service` — `FeatureFlagConfig.java`
   (`flagRegistry()`, tag `problem_pattern`) + `application.properties`
   (`ENABLE_MEMORY_LEAK:false`).
2. In the host service, add a flag-gated leak: a `static`/singleton collection (e.g.
   `List<byte[]>` or a `Map` keyed per request) that appends a chunk on each relevant request and
   is **never cleared** while the flag is on. Give the allocating method a distinct,
   non-inlined name so it's unambiguous in the flamegraph (mirror `HighCpuUsage`'s
   `[MethodImpl(NoInlining)]` intent).
3. Verify locally via `compose.dev.yaml`: flip the flag, watch heap/RSS climb; flip off → confirm
   it plateaus (leak vs. churn distinction is part of the ground truth).
4. Deploy to a monitored tenant (Helm) with profiling enabled; confirm allocation profiling
   attributes the growth to our method and the memory trend correlates.
5. Author the **ground-truth catalog entry** (see below) and the DQL that should surface it.

**Ground-truth catalog entry (format to standardize):**
```yaml
- id: UC2-memory-leak
  flag: memory_leak
  service: <host-service>
  runtime: java
  root_cause:
    file: <path>
    symbol: <Class#method>
    mechanism: unbounded static collection, never freed while flag on
  expected_signal:
    profiling: allocation hotspot at <symbol>; retained set grows with request count
    metric: heap/RSS monotonic increase; plateaus when flag off
  expected_dql: <query>
  agent_answer_key:
    service: <host-service>
    method: <Class#method>
    classification: leak (not churn)
```

## 8. Next steps (post-prototype)

1. **Decide UC2 target service** (§6 Q6) and build it end-to-end per §7.
2. Confirm the tenant has continuous profiling enabled; wire the arm→wait→query→grade harness.
3. Standardize the ground-truth catalog; backfill for UC1 (already implemented).
4. Roll out UC3–UC5 one per runtime; add the Go flag client when we reach UC5-on-Go.
5. Decide upstream-vs-fork for the public EasyTrade (§6 Q5).
