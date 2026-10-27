
ThermalBench: a thermal-aware sustained-load inference benchmark for Android. Call it something like ThermalBench or SustainedLM.

Why this one. The gap is real and documented. A March 2026 arXiv paper benchmarking a 4-bit Qwen 2.5 1.5B across mobile hardware found that thermal management supersedes peak compute as the primary constraint on mobile platforms — the iPhone 16 Pro loses nearly half its throughput within two iterations, and the S24 Ultra hits a hard OS-enforced GPU frequency floor that terminates inference entirely. The same paper notes that MLPerf Inference measures peak performance without tracking thermal throttling or sustained-workload behaviour. Existing Android LLM apps report load time, memory and thermal state as a snapshot; nobody has shipped the sustained degradation curve as tooling.
arxiv
arxiv

What it does:

Runs a fixed prompt workload for 50+ back-to-back iterations across CPU / GPU / NPU backends (LiteRT-LM, llama.cpp, MediaPipe)
Hooks PowerManager.getCurrentThermalStatus() and the vendor thermal HAL to log throttle state per iteration
Plots tokens/sec decay, time-to-first-token drift, frequency floors, battery drain, and the steady-state throughput floor
Exports CSV + a shareable device report; accumulates a community leaderboard of real devices
Kotlin, Jetpack Compose, Clean Architecture — so it also refreshes your app-layer credibility

============================

ThermalBench — Software Architecture

A thermal-aware sustained-load inference benchmark for Android. Kotlin · Clean Architecture · MVVM · Multi-module Gradle

1. What makes this architecture unusual

ThermalBench is a measuring instrument, not an app. Three constraints follow from that, and they drive nearly every decision below.

The observer effect is real and large. Your Compose UI, your live charts, your logging and your Room writes all consume CPU and generate heat on the same die you are measuring. A benchmark whose own instrumentation perturbs the thermal envelope produces numbers nobody should trust. The architecture must therefore support a fully headless execution path where no UI process exists — the app is a convenience wrapper around a harness that can run without it.

Preconditions are part of the measurement. A run starting from a warm device measures something different from a run starting cold. Device charging state, screen state, ambient temperature and prior workload all matter. These must be gated and recorded, not assumed.

Time must be monotonic. Every duration uses SystemClock.elapsedRealtimeNanos() or System.nanoTime(). Wall-clock time appears only once, as a single run start timestamp for labelling. NTP correction mid-run would silently corrupt a decay curve.

Everything else is ordinary Clean Architecture. These three are what separate a credible instrument from a phone benchmark app.

2. Module topology
thermalbench/
├── core/
│   ├── domain/              pure Kotlin. Entities, ports, use cases. No Android.
│   ├── analysis/            decay fitting, steady-state detection, statistics.
│   │                        Pure Kotlin so it runs on-device AND offline.
│   └── schema/              result document model + canonical serialization
│
├── data/
│   ├── backend-api/         InferenceBackend port + capability model
│   ├── backend-litert/      LiteRT / LiteRT-LM adapter
│   ├── backend-llamacpp/    llama.cpp JNI adapter (GGUF)
│   ├── backend-mediapipe/   MediaPipe Tasks GenAI adapter
│   ├── backend-executorch/  ExecuTorch .pte adapter (optional, phase 2)
│   ├── telemetry/           thermal, power, frequency, memory sources
│   ├── persistence/         Room, DataStore, artifact store
│   └── leaderboard/         opt-in submission client
│
├── harness/                 HEADLESS runner. Driven by am instrument / UiAutomator.
│                            Depends on core + data. Never on app.
│
├── app/                     Compose MVVM operator console (optional convenience)
│
├── tools/
│   ├── cli/                 JVM fleet orchestrator over ADB
│   └── analysis-cli/        offline re-analysis, cross-device comparison, plots
│
└── build-logic/             convention plugins, version catalog

Dependency rule: inward only. core/domain knows nothing but the Kotlin stdlib and coroutines.

The critical edge: harness must never depend on app. If it does, you have lost the headless path and with it the credibility of every number. Enforce this with a Gradle convention plugin that fails the build, not with review discipline.

Why core/analysis is pure Kotlin: the same decay-fitting code powers the live on-device chart and the offline cross-device report. Two implementations of the same math is how discrepancies get published.

3. Domain layer (core/domain)
3.1 The run specification
kotlin
/** Immutable, hashable. The hash is the run identity and appears in every result. */
data class BenchmarkSpec(
    val specId: SpecId,
    val model: ModelRef,
    val backend: BackendRef,
    val workload: Workload,
    val protocol: RunProtocol,
    val preconditions: PreconditionPolicy,
    val seed: Long,
    val toolVersion: SemVer,
)

data class RunProtocol(
    val warmupIterations: Int,        // discarded from analysis, recorded anyway
    val measuredIterations: Int,      // >= 50 for the sustained-load thesis
    val interIterationDelay: Duration, // zero for back-to-back; nonzero to model duty cycle
    val abortOnCriticalThermal: Boolean,
    val maxRunDuration: Duration,
)

data class Workload(
    val promptTokens: Int,            // fixed prompt, fixed length
    val maxOutputTokens: Int,
    val promptSha256: Sha256,         // the exact prompt is part of the identity
    val stopCondition: StopCondition, // token count only — never EOS, which varies by model
)

StopCondition deserves a comment. If one backend stops on EOS and another on token count, your tokens-per-second figures are not comparable. Fix output length across all backends and record it.

3.2 Preconditions — the gate
kotlin
data class PreconditionPolicy(
    val requiredThermalStatus: ThermalStatus,   // typically NONE
    val minThermalHeadroom: Float?,             // API 30+, 0.0..1.0
    val requireUnplugged: Boolean,              // charging generates heat — default true
    val minBatteryPercent: Int,                 // default 50; low battery triggers its own throttling
    val screenPolicy: ScreenPolicy,             // ON_DIMMED | OFF — must be fixed, not incidental
    val stabilizationWindow: Duration,          // how long conditions must hold before starting
    val maxWaitForPreconditions: Duration,
)

sealed interface PreconditionState {
    data class Waiting(
        val unmet: List<UnmetCondition>,
        val elapsedInWindow: Duration,
        val estimatedReady: Duration?,
    ) : PreconditionState
    data object Satisfied : PreconditionState
    data class TimedOut(val unmet: List<UnmetCondition>) : PreconditionState
}

A run that starts without satisfying its policy is not a failed run — it is an invalid run, and the result document must say so. Never silently proceed.

3.3 Samples and timelines
kotlin
data class IterationSample(
    val index: Int,
    val phase: Phase,                          // WARMUP | MEASURED
    val startElapsedNanos: Long,               // monotonic
    val timeToFirstTokenMicros: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val outputTokenCount: Int,
    val thermalStatusAtStart: ThermalStatus,
    val thermalStatusAtEnd: ThermalStatus,
    val thermalHeadroomAtStart: Float?,
    val batteryTemperatureDeciC: Int?,
    val batteryPercent: Int,
    val cpuFrequenciesKhz: List<Long>?,        // null where unreadable — never fabricate
    val peakRssBytes: Long,
    val throttleObserved: Boolean,
)

data class ThermalTimeline(
    val samples: List<ThermalSample>,          // sampled independently of iterations
    val transitions: List<ThermalTransition>,  // event-driven, from the status listener
)

Two independent thermal streams matter: polled samples give you a regular time series for plotting, while listener transitions give you exact throttle-onset timestamps that polling would blur.

3.4 The analysis product
kotlin
data class DegradationProfile(
    val peakDecodeTokensPerSecond: Double,      // best sustained window, not a single outlier
    val steadyStateTokensPerSecond: Double?,    // null if never stabilized within the run
    val retentionRatio: Double?,                // steadyState / peak — the headline number
    val timeToFirstThrottle: Duration?,
    val iterationsToFirstThrottle: Int?,
    val decayModel: DecayFit?,                  // exponential fit + goodness
    val ttftDriftMicrosPerIteration: Double,
    val throttleEvents: List<ThrottleEvent>,
    val terminated: TerminationReason,          // COMPLETED | THERMAL_CRITICAL | TIMEOUT | ERROR
    val validity: ValidityVerdict,              // VALID | PRECONDITIONS_UNMET | INSUFFICIENT_DATA
)

data class DecayFit(
    val asymptote: Double,
    val amplitude: Double,
    val timeConstantSeconds: Double,
    val rSquared: Double,
)

retentionRatio is the number your paper is about. Design the report around it.

3.5 Ports
kotlin
interface InferenceBackend {
    val id: BackendId
    suspend fun capabilities(): BackendCapabilities
    suspend fun load(model: ModelRef, delegate: Delegate): LoadedModel
    suspend fun generate(model: LoadedModel, workload: Workload): GenerationResult
    suspend fun unload(model: LoadedModel)
}

interface ThermalTelemetrySource {
    fun poll(interval: Duration): Flow<ThermalSample>
    fun transitions(): Flow<ThermalTransition>
    suspend fun headroom(forecastSeconds: Int): Float?
}

interface PowerTelemetrySource { fun stream(interval: Duration): Flow<PowerSample> }
interface FrequencySource { suspend fun readCpuFrequenciesKhz(): List<Long>? }
interface DeviceProfiler { suspend fun profile(): DeviceDescriptor }
interface MonotonicClock { fun nanos(): Long }

interface ResultRepository {
    suspend fun save(result: BenchmarkResult): ResultId
    fun observeAll(): Flow<List<ResultSummary>>
    suspend fun load(id: ResultId): BenchmarkResult?
    suspend fun import(document: String): ResultId
}

interface LeaderboardClient {
    suspend fun submit(result: BenchmarkResult, consent: SubmissionConsent): SubmissionReceipt
    suspend fun fetch(filter: LeaderboardFilter): List<LeaderboardEntry>
}
3.6 Use cases
Use case	Responsibility
ValidateSpecUseCase	Reject specs that can't produce comparable results before burning an hour
AwaitPreconditionsUseCase	Emit Flow<PreconditionState> until satisfied or timed out
RunSustainedBenchmarkUseCase	Warmup → N measured iterations → emit Flow<RunProgress>
AnalyzeDegradationUseCase	Samples → DegradationProfile
CompareBackendsUseCase	Multiple results on one device → backend comparison
CompareDevicesUseCase	Same spec across devices → the cross-device table
ExportResultUseCase	Canonical JSON + CSV
SubmitToLeaderboardUseCase	Consent-gated, anonymized submission
ImportResultUseCase	Ingest someone else's result document
4. Execution engine
4.1 Headless harness (harness)

The primary path. An Android instrumentation test that takes the spec as instrumentation arguments, runs, and writes a result document to external files.

adb shell am instrument -w \
  -e specPath /sdcard/specs/gemma3n-q4-gpu.json \
  -e outputDir /sdcard/thermalbench \
  com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner

Properties this buys you: no UI process, no rendering load, scriptable across a fleet from tools/cli, and reproducible by anyone who reads your paper.

4.2 In-app runner (app)

For interactive use. A long-running foreground service — not a plain coroutine — because runs last tens of minutes and must survive Doze and screen-off.

Foreground service type specialUse, with justification in the manifest.
Explicit wake-lock policy, acquired and released in finally, documented in the result because it affects power draw.
Samples flushed to Room in batched transactions every N iterations. A crash at iteration 47 must not lose 46 iterations.
The service holds only a SpecId; it re-resolves state from persistence, so restart is idempotent.

Do not use WorkManager here. WorkManager may defer or reschedule work based on system conditions, which is precisely the confound you're trying to measure. Use a foreground service you control.

4.3 Sampling loop

Telemetry sampling runs on its own dispatcher and its own coroutine, independent of the inference loop, so a long generation call never starves the thermal time series. Both write to a bounded channel consumed by a single collector that owns ordering.

5. Presentation layer (MVVM + Compose)
5.1 State contract
kotlin
sealed interface BenchUiState {
    data object Idle : BenchUiState
    data class Composing(val form: SpecForm, val errors: List<FieldError>) : BenchUiState
    data class Preconditioning(
        val unmet: List<UnmetCondition>,
        val currentThermal: ThermalStatus,
        val headroom: Float?,
        val elapsedInWindow: Duration,
    ) : BenchUiState
    data class Warmup(val iteration: Int, val total: Int) : BenchUiState
    data class Running(
        val iteration: Int,
        val total: Int,
        val liveDecodeTps: Double,
        val decaySeries: List<Point>,
        val thermalSeries: List<Point>,
        val throttled: Boolean,
    ) : BenchUiState
    data class Complete(val resultId: ResultId, val profile: DegradationProfile) : BenchUiState
    data class Aborted(val reason: TerminationReason) : BenchUiState
}
kotlin
@HiltViewModel
class BenchViewModel @Inject constructor(
    private val validateSpec: ValidateSpecUseCase,
    private val awaitPreconditions: AwaitPreconditionsUseCase,
    private val analyze: AnalyzeDegradationUseCase,
    private val runController: RunController,   // binds to the foreground service
) : ViewModel() {
    val state: StateFlow<BenchUiState>
    fun onIntent(intent: BenchIntent)
}

ViewModels hold no domain logic and no Context. Composables receive state plus (BenchIntent) -> Unit — never the ViewModel — so Paparazzi screenshot tests work.

5.2 Screens
Screen	Purpose
Spec Composer	Pick model, backend, delegate, protocol; validate before commit
Precondition Gate	Live view of what's blocking the run and estimated readiness
Live Run	Decay chart, thermal overlay, throttle markers, abort control
Result Detail	DegradationProfile, timeline, validity verdict, export
Compare	Backends on this device, or this device against imported results
Leaderboard	Community results, with explicit consent flow before any upload
5.3 Live charting caveat

The live chart is the single largest source of observer effect in the app path. Throttle chart recomposition to roughly 1 Hz, keep the series bounded via a ring buffer, and add a "minimal UI" toggle that suppresses charts entirely. Then run one comparison of charted vs headless on the same device and publish the delta. That measurement is itself a small, credible result and it inoculates you against the obvious reviewer objection.

6. Platform telemetry — what actually works

This is where most benchmark apps quietly fail. Realistic expectations:

Source	API	Availability
Thermal status	PowerManager.getCurrentThermalStatus(), addThermalStatusListener()	API 29+, universally available. Your primary signal.
Thermal headroom	PowerManager.getThermalHeadroom(forecastSeconds)	API 30+. Continuous 0..1 value — far richer than the coarse status enum. Some OEMs return NaN; handle it.
Battery temperature	BatteryManager / sticky ACTION_BATTERY_CHANGED, EXTRA_TEMPERATURE	Universal but coarse (deci-°C) and lags die temperature by minutes. Useful as corroboration, not as the primary signal.
Charging state	BatteryManager.EXTRA_PLUGGED	Universal. Gate on it — charging is a heat source.
CPU frequency	/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq	Vendor-dependent. Often readable, increasingly restricted. Must degrade to null, never to a guess.
Detailed hardware temps	HardwarePropertiesManager	Requires device owner or system app. Effectively unavailable to a third-party app — don't design around it.
Memory	Debug.MemoryInfo, ActivityManager.getMemoryInfo()	Universal.
Traces	androidx.tracing + Perfetto	Universal; gives you phase boundaries inside inference.

Design the FrequencySource port to return List<Long>? and record the unavailability in the result. A result that honestly says "CPU frequency unreadable on this device" is more credible than one that quietly omits the field.

7. Third-party libraries
Core / language
Library	Coordinates	Purpose
Coroutines	org.jetbrains.kotlinx:kotlinx-coroutines-core	Concurrency; Flow for telemetry streams
kotlinx.serialization	org.jetbrains.kotlinx:kotlinx-serialization-json	Result documents (with a canonical encoder)
kotlinx-datetime	org.jetbrains.kotlinx:kotlinx-datetime	The single UTC run timestamp
Okio	com.squareup.okio:okio	Streaming SHA-256 over multi-GB model files without OOM
DI
Library	Coordinates	Purpose
Hilt	com.google.dagger:hilt-android	Android graph (app + harness)
Dagger	com.google.dagger:dagger	Plain Dagger for JVM CLI modules — Hilt is Android-only
KSP	com.google.devtools.ksp	Annotation processing; skip kapt
Android UI
Library	Purpose
androidx.compose:compose-bom + Material3	UI
androidx.navigation:navigation-compose	Navigation
androidx.lifecycle:lifecycle-viewmodel-compose	ViewModel wiring
androidx.lifecycle:lifecycle-runtime-compose	collectAsStateWithLifecycle
Vico com.patrykandpatrick.vico:compose-m3	Compose-native charts. Lighter than MPAndroidChart, and lightness matters here
Persistence
Library	Purpose
androidx.room:room-ktx + room-compiler (KSP)	Iteration samples, thermal timelines, run history
androidx.datastore:datastore-preferences	Settings, consent flags

No WorkManager — see §4.2. Deferrable scheduling is a confound, not a convenience.

Instrumentation & measurement
Library	Purpose
androidx.benchmark:benchmark-macro-junit4	Borrow its warmup and outlier methodology even where you don't use the harness directly
androidx.tracing:tracing-perfetto	Phase markers inside prefill/decode
androidx.test:runner + androidx.test.uiautomator	The headless harness entry point
Perfetto trace processor (JVM)	Offline trace analysis in tools/analysis-cli
Inference backends
Library	Coordinates	Purpose
LiteRT	com.google.ai.edge.litert:litert	Google runtime
LiteRT-LM	com.google.ai.edge.litert:litert-lm	LLM path (Gemma family)
MediaPipe Tasks GenAI	com.google.mediapipe:tasks-genai	Second Google-stack path
llama.cpp	JNI wrapper built via CMake/NDK	GGUF — the format Google won't ship. Your differentiator
ExecuTorch	org.pytorch:executorch-android	.pte runtime (phase 2)
ONNX Runtime Mobile	com.microsoft.onnxruntime:onnxruntime-android	Optional; strong presence in automotive
Play AI Delivery	com.google.android.play:ai-delivery	Delivering large model artifacts to test devices
Analysis & statistics (core/analysis, pure Kotlin)
Library	Coordinates	Purpose
Apache Commons Math3	org.apache.commons:commons-math3	LevenbergMarquardtOptimizer for exponential decay fitting; percentiles; changepoint statistics
Multik	org.jetbrains.kotlinx:multik-core	Kotlin ndarrays where vectorized work is cleaner than loops
Kotlin DataFrame	org.jetbrains.kotlinx:dataframe	Cross-device aggregation in tools/analysis-cli
kotlin-csv	com.github.doyaaaaaken:kotlin-csv-jvm	CSV export — the format reviewers will actually open
Reporting & export
Library	Coordinates	Purpose
Apache PDFBox	org.apache.pdfbox:pdfbox	Device report PDFs. Apache 2.0
XChart	org.knowm.xchart:xchart	Publication-quality charts rendered offline for the PDF and the paper
kotlinx-html	org.jetbrains.kotlinx:kotlinx-html-jvm	Shareable HTML device reports
networknt json-schema-validator	com.networknt:json-schema-validator	Validate imported result documents against the published schema

Licensing: avoid iText 7 despite your prior experience with it — it's AGPL, which propagates to adopters. PDFBox under Apache 2.0 keeps the door open for the automotive and medical-device teams you want reading this.

Leaderboard / networking
Library	Coordinates	Purpose
Ktor Client	io.ktor:ktor-client-okhttp + ktor-client-content-negotiation	Submission and fetch
OkHttp	com.squareup.okhttp3:okhttp	Engine; certificate pinning if you host the leaderboard
CLI / fleet orchestration
Library	Coordinates	Purpose
Clikt	com.github.ajalt.clikt:clikt	Kotlin CLI framework
adam	com.malinskiy.adam:adam	Coroutines ADB client — drive a device fleet without shelling out to adb
Mordant	com.github.ajalt.mordant:mordant	Terminal tables and progress
Testing
Library	Coordinates	Purpose
JUnit 5	org.junit.jupiter:junit-jupiter	JVM tests
Kotest	io.kotest:kotest-property	Property-test canonical serialization and decay-fit invariants
MockK	io.mockk:mockk	Kotlin-idiomatic mocking of suspend ports
Turbine	app.cash.turbine:turbine	Flow assertions for telemetry and precondition streams
Robolectric	org.robolectric:robolectric	Android unit tests
Truth	com.google.truth:truth	Readable assertions
Paparazzi	app.cash.paparazzi	Compose screenshot tests without a device
Kover	org.jetbrains.kotlinx:kover	Coverage (Kotlin-native successor to Jacoco)
Build & quality
Tool	Purpose
Gradle Version Catalogs (libs.versions.toml)	Single source of dependency truth
Convention plugins in build-logic	Mechanically enforce harness ↛ app
Detekt + Spotless/ktlint	Static analysis, formatting
binary-compatibility-validator	The result schema is a public contract
Dokka	API docs
Reproducible build flags	A reproducibility tool should build reproducibly
8. Build order
core/domain + core/schema with canonical serialization and property tests. No UI, no device.
data/telemetry thermal only — status, listener, headroom. Verify against a known-hot workload.
One backend (backend-litert).
harness headless runner producing a result JSON. The project is useful at this point.
core/analysis — decay fit, steady-state detection, retention ratio.
tools/analysis-cli cross-device comparison on 3–4 devices. Stop here and write the preprint.
app Compose console, with the charted-vs-headless delta measured and published.
Second and third backends, then tools/cli fleet orchestration.
Leaderboard, last — it needs traffic to mean anything, and traffic follows the paper.

Steps 1–6 are the publishable core and should be reachable in roughly three months of evenings. Everything after is expansion once you know whether anyone cares.

9. Growing into EdgeAttest

If you later expand this into the conformance toolkit, the seams are already here:

ThermalBench	Becomes
BenchmarkSpec	RunManifest — add signing, no structural change
BenchmarkResult	one section of EvidenceBundle
DegradationProfile	the "sustained performance envelope" evidence category
ResultRepository	EvidenceRepository with signature verification added
core/schema canonical serialization	reused verbatim — it's already the hard part
—	new: attestation, core/clauses, VerifyBundleUseCase

Which is the argument for doing the canonical serialization properly now even though ThermalBench alone doesn't strictly require it. It's the one piece that's expensive to retrofit and free to get right up front.
