"""Prose for the ThermalBench developer manual, second edition of the wording.

Same chapters, tables, code and figures as `manual_content.py`; only the prose
is rewritten. Build with:

    THERMALBENCH_CONTENT=manual_content_02 python build_manual.py
"""

TITLE = "ThermalBench"
SUBTITLE = "Developer Manual — a thermal-aware sustained-load inference benchmark for Android"
VERSION = "0.1.0"
OUTPUT_NAME = "ThermalBench-Developer-Manual_02.pdf"
RUNNING_HEAD = "ThermalBench — Developer Manual"
ACCENT_HEX = "#00629E"

COVER_LEAD = (
    "How ThermalBench is put together, why it measures the way it does, and how to "
    "run it day to day. Written for an engineer who wants the reasoning behind each "
    "decision, not only the commands.")

COVER_META = [
    ["Audience", "Engineers who build on, run, or review ThermalBench"],
    ["Source", "GUIDE.md and the code it describes"],
]

DOCUMENT = [
    # ------------------------------------------------------------------ 1
    {
        "title": "Why this tool exists",
        "blocks": [
            ("lead",
             "Every mobile inference benchmark reports peak throughput. A phone stops "
             "delivering that number after about ninety seconds. ThermalBench measures what "
             "happens next: how throughput decays under sustained load, when the operating "
             "system starts to throttle, and how much of the peak the device can actually "
             "hold."),

            ("p",
             "The gap is documented. In March 2026 an arXiv paper benchmarked a 4-bit Qwen 2.5 "
             "1.5B across mobile hardware and found that thermal management, not peak compute, "
             "is the main constraint on phones. The iPhone 16 Pro lost nearly half its "
             "throughput within two iterations. The Galaxy S24 Ultra hit a GPU frequency floor "
             "set by the OS, and inference simply stopped. The same paper points out that "
             "MLPerf Inference measures peak performance without tracking thermal throttling "
             "or how a device behaves under a sustained workload."),

            ("p",
             "Android LLM apps today report load time, memory use and a snapshot of the "
             "thermal state. That is one frame from a film about a car climbing a long hill. "
             "Nobody has shipped the whole film, the sustained degradation curve, as a tool. "
             "ThermalBench produces that curve."),

            ("h2", "The headline number"),
            ("p",
             "The <b>retention ratio</b> is steady-state throughput divided by peak throughput. "
             "It answers the question a buyer, an app developer and a reviewer all share: of "
             "the performance this device advertises, how much survives a real workload? Every "
             "design decision in this manual exists to make that one number defensible."),

            ("note",
             "<b>What this tool is.</b> A measuring instrument that happens to have an app "
             "attached, not an app that happens to measure. When the two goals conflict, "
             "measurement integrity wins, and the code says so at every point where that "
             "happens."),
        ],
    },

    # ------------------------------------------------------------------ 2
    {
        "title": "Three constraints that shape everything",
        "blocks": [
            ("p",
             "Most of this codebase is ordinary Clean Architecture. Three constraints explain "
             "the rest. They are what separate a credible instrument from a phone benchmark "
             "app, and every odd-looking decision later in this manual traces back to one of "
             "them."),

            ("h2", "1. The observer effect is real and large"),
            ("p",
             "A Compose UI, live charts, logging and database writes all burn CPU and make heat "
             "on the same die you are measuring. It is like taking a patient's temperature with "
             "a thermometer you just warmed in your hand. If a benchmark's own instrumentation "
             "shifts the thermal envelope, nobody should trust its numbers."),
            ("p",
             "So the main execution path is <b>headless</b>: an instrumentation harness with no "
             "UI process at all. The Compose app is a convenience wrapper around a harness that "
             "runs fine without it. The result document records which path produced the "
             "numbers and whether live charts were on, so the two never get compared by "
             "accident."),

            ("h2", "2. Preconditions are part of the measurement"),
            ("p",
             "A run that starts on a warm device measures something different from one that "
             "starts cold. Charging state, screen state, battery level and prior workload all "
             "change the answer. The tool checks them before a run starts and records them in "
             "the result."),
            ("warn",
             "A run that starts without meeting its policy is not a slightly worse run. It is "
             "an <b>invalid</b> run, and the result document says so with "
             "<font face='Courier'>PRECONDITIONS_UNMET</font>. The tool never carries on "
             "quietly, and it never adjusts a number to compensate."),

            ("h2", "3. Time must be monotonic"),
            ("p",
             "Every duration comes from "
             "<font face='Courier'>SystemClock.elapsedRealtimeNanos()</font>. Wall-clock time "
             "appears exactly once per run, as a label on the document. Here is why. Suppose "
             "the phone corrects its clock from the network in the middle of a forty-minute "
             "run. The decay curve now contains a jump that no thermal event caused, and "
             "nothing in the output would tell you it happened."),
            ("p",
             "<font face='Courier'>elapsedRealtimeNanos</font> wins over "
             "<font face='Courier'>System.nanoTime()</font> on purpose: it keeps counting "
             "through deep sleep. That matters for runs that last tens of minutes with the "
             "screen off."),
        ],
    },

    # ------------------------------------------------------------------ 3
    {
        "title": "Module topology",
        "blocks": [
            ("p",
             "Sixteen Gradle modules. Dependencies point inward only, and a convention plugin "
             "enforces the direction, so nobody has to catch a stray arrow in review."),

            ("code",
             "core/domain          pure Kotlin. Entities, ports, use cases. No Android.\n"
             "core/analysis        decay fitting, steady-state detection, statistics.\n"
             "core/schema          result document model + canonical serialization.\n"
             "\n"
             "data/backend-api     backend registry, model files, generation timing\n"
             "data/backend-litert       LiteRT-LM adapter (the reference backend)\n"
             "data/backend-llamacpp     llama.cpp JNI adapter (GGUF)\n"
             "data/backend-mediapipe    MediaPipe Tasks GenAI adapter\n"
             "data/backend-executorch   ExecuTorch adapter (phase 2)\n"
             "data/telemetry       thermal, power, frequency, memory sources\n"
             "data/persistence     Room, DataStore, canonical artifact store\n"
             "data/leaderboard     opt-in, consent-gated submission client\n"
             "data/engine          composition root shared by harness and app\n"
             "\n"
             "harness              HEADLESS runner (am instrument). Never sees :app.\n"
             "app                  Compose MVVM operator console (optional)\n"
             "\n"
             "tools/cli            JVM fleet orchestrator over ADB\n"
             "tools/analysis-cli   offline re-analysis, comparison, plots\n"
             "build-logic          convention plugins"),

            ("h2", "The critical edge"),
            ("p",
             "<font face='Courier'>harness</font> must never depend on "
             "<font face='Courier'>app</font>. Break that rule and you lose the headless path, "
             "and with it the credibility of every number the tool produces. "
             "<font face='Courier'>DependencyRulesPlugin</font> enforces this mechanically and "
             "fails the build at configuration time."),
            ("p", "Add that dependency and you get exactly this, and no APK:"),
            ("code",
             "Dependency rule violation — arrows must point inward only:\n"
             "  :harness (implementation) -> :app"),
            ("p", "The full rule set:"),
            ("table", [
                ["Layer", "May depend on"],
                ["core/…", "core/… only"],
                ["data/…", "core/…, and data/backend-api"],
                ["data/engine", "core/…, and any data/… module"],
                ["harness", "core/…, data/…  —  never app"],
                ["app", "core/…, data/…, harness"],
                ["tools/…", "core/…, tools/…  (JVM only, never Android)"],
            ], [30, 70]),

            ("h2", "Why data/engine exists"),
            ("p",
             "Start with the puzzle. The harness and the app both need the same run engine, "
             "built from the same backend registry, telemetry sources and repositories. Neither "
             "can depend on the other. The harness must never see the app, and both are Android "
             "application modules, which Gradle cannot make depend on each other anyway."),
            ("p",
             "That leaves one answer. The dependency-injection wiring lives in a single library "
             "that both consume. It is the only module allowed to reach sideways across the "
             "data layer, and the convention plugin permits that for this module alone. The "
             "alternative, copying the wiring into two application modules, is exactly the kind "
             "of drift the plugin exists to prevent."),

            ("h2", "Why core/analysis is pure Kotlin"),
            ("p",
             "The same decay-fitting code drives the live chart on the device and the offline "
             "cross-device report. Two implementations of the same statistics is how a "
             "discrepancy ends up in a published paper. There is one. "
             "<font face='Courier'>tools/analysis-cli</font> exists partly to prove it, by "
             "re-deriving on a workstation the numbers a phone reported."),
        ],
    },

    # ------------------------------------------------------------------ 4
    {
        "title": "How a run actually works",
        "blocks": [
            ("p",
             "A run moves through five phases. Learn them and you can find your way around "
             "almost any part of the codebase."),

            ("steps", [
                "<b>Validate the spec.</b> <font face='Courier'>ValidateSpecUseCase</font> "
                "checks the spec against the resolved backend's capabilities and rejects "
                "anything that cannot produce comparable results, before any device time is "
                "spent.",

                "<b>Wait at the gate.</b> <font face='Courier'>AwaitPreconditionsUseCase</font> "
                "emits a stream of states until every condition has held continuously for the "
                "stabilization window, or until the maximum wait runs out.",

                "<b>Load the model.</b> The backend adapter checks the file against the digest "
                "in the spec, then loads it and records the load time.",

                "<b>Warm up, then measure.</b> Warmup iterations are left out of the analysis "
                "but still written to the document. Measured iterations follow back-to-back by "
                "default.",

                "<b>Analyze and write.</b> The samples become a "
                "<font face='Courier'>DegradationProfile</font>, and the whole run becomes a "
                "canonical JSON document plus a CSV sidecar.",
            ]),

            ("h2", "The sampling loop"),
            ("p",
             "Telemetry sampling runs on its own thread and its own coroutine, fully "
             "independent of the inference loop. If the two shared a dispatcher, a long "
             "generation call could delay a thermal sample and leave a gap in exactly the "
             "series you are trying to measure."),
            ("p",
             "Both write into one bounded channel, read by a single collector that owns "
             "ordering and assembles the result. That collector is the only place run state "
             "changes. No lock, and no interleaving to reason about."),

            ("code",
             "  +- inference thread ---+\n"
             "  | prefill -> decode xN |--+\n"
             "  +----------------------+  |    +-----------------+\n"
             "                            +--->| bounded channel |---> single collector\n"
             "  +- telemetry thread ---+  |    +-----------------+     owns ordering,\n"
             "  | poll / listen / pwr  |--+                            assembles result\n"
             "  +----------------------+"),
            ("caption",
             "Two producers, one consumer. Neither producer can starve the other, and only the "
             "consumer writes run state."),

            ("h2", "Two independent thermal streams"),
            ("p",
             "Polled samples give a regular time series you can plot. Listener transitions give "
             "exact throttle-onset timestamps that polling would blur, the way a photo taken "
             "once a minute can miss the moment a light switches on. The tool records both. For "
             "onset timing the analysis prefers listener events, and falls back to iteration "
             "boundaries only when the platform delivered none."),

            ("h2", "What one iteration actually runs"),
            ("p",
             "People often ask whether the stress test is token analysis or token generation. "
             "It's generation, and nothing else. Each iteration is one call to "
             "<font face='Courier'>InferenceBackend.generate(model, workload, seed)</font>: the "
             "model reads a fixed prompt and writes a fixed number of tokens. There's no "
             "classification, no scoring, no embedding, and the generated text is never "
             "evaluated. It's kept in <font face='Courier'>GenerationResult.text</font> for "
             "debugging and for checking the stop condition, and it never reaches the result "
             "document."),
            ("p",
             "The prompt is <font face='Courier'>Fixtures.REFERENCE_PROMPT</font>, shared by the "
             "app's spec composer, the CLI and the tests:"),
            ("code",
             "You are a careful technical writer. In plain English, explain how a mobile\n"
             "phone manages heat while running a demanding application for a long time.\n"
             "Cover the roles of the battery, the system-on-chip, the operating system's\n"
             "thermal policy, and the trade-off between performance and temperature.\n"
             "Write in complete sentences, avoid lists, and keep a steady, measured tone\n"
             "throughout the whole explanation without stopping early."),
            ("p",
             "<font face='Courier'>Fixtures.referenceWorkload()</font> wraps it in a "
             "<font face='Courier'>Workload</font> with <font face='Courier'>promptTokens = "
             "128</font> (a declared figure; llama.cpp reports the real count from its own "
             "tokenizer), the prompt's SHA-256, and <font face='Courier'>StopCondition."
             "TokenCount</font> set to the output length: "
             "<font face='Courier'>REFERENCE_OUTPUT_TOKENS = 128</font> by default. The runs "
             "charted in chapter 9 used 256."),

            ("h3", "Inside the call"),
            ("steps", [
                "<b>Fresh context.</b> Every adapter starts from an empty KV cache: LiteRT-LM "
                "and MediaPipe open a new session, llama.cpp calls "
                "<font face='Courier'>resetContext</font>. Without this, iteration N would "
                "attend over everything iterations 1 to N-1 wrote, and would be measuring a "
                "longer, slower sequence than iteration 1.",

                "<b>Prefill.</b> The whole prompt goes through the model in one batched pass "
                "(<font face='Courier'>runPrefill</font> on LiteRT-LM, "
                "<font face='Courier'>addQueryChunk</font> on MediaPipe, "
                "<font face='Courier'>prefill</font> on llama.cpp). It ends when the first "
                "output token appears.",

                "<b>Decode.</b> Autoregressive generation, one token per forward pass, each "
                "pass reading every weight in the model once. This loop runs for most of the "
                "wall-clock time and makes most of the heat. LiteRT-LM and llama.cpp call "
                "<font face='Courier'>runDecode</font> / <font face='Courier'>decodeToken</font> "
                "exactly N times. MediaPipe streams through "
                "<font face='Courier'>generateResponseAsync</font>, and the adapter cancels it "
                "at the Nth token so it can't run on into the next iteration.",

                "<b>Stop at N.</b> Never on end-of-sequence. On MediaPipe, a generation that "
                "ends before its Nth token is reported as a failure, not recorded as a slow "
                "iteration.",
            ]),

            ("p",
             "Sampling is greedy everywhere the adapters set it: top-k 1 at temperature 0 on "
             "LiteRT-LM and MediaPipe, <font face='Courier'>llama_sampler_init_greedy()</font> "
             "on llama.cpp. Sampling noise would show up as throughput noise. The seed "
             "(<font face='Courier'>spec.seed + index</font>) is passed through and recorded "
             "anyway, even where greedy decoding ignores it."),

            ("h3", "How the timings fall out of it"),
            ("p",
             "Every adapter times through the shared <font face='Courier'>GenerationTimer</font>, "
             "so the figures mean the same thing on every backend:"),
            ("table", [
                ["Field", "Computed as"],
                ["timeToFirstTokenMicros", "First token observed minus the call into the runtime. Prefill plus the first decode step."],
                ["prefillTokensPerSecond", "promptTokenCount ÷ time to first token."],
                ["decodeTokensPerSecond", "(outputTokenCount − 1) ÷ (last token − first token). The figure every chart plots."],
            ], [32, 68], [0]),
            ("note",
             "To get a feel for the load: the default protocol is 3 warmup plus 50 measured "
             "iterations. At 256 output tokens, that's 13,568 tokens written back to back with "
             "no pause, and for Gemma 3 1B each one is a full pass over a billion parameters."),
        ],
    },

    # ------------------------------------------------------------------ 5
    {
        "title": "The spec: what makes runs comparable",
        "blocks": [
            ("p",
             "A <font face='Courier'>BenchmarkSpec</font> is the complete, immutable "
             "description of a run. If something could change the numbers, it belongs in the "
             "spec."),

            ("p",
             "Its identity is the SHA-256 of its own canonical serialization. Two people who "
             "write the same spec get the same identifier, so results are comparable by "
             "construction, not by convention. The model path and the run label stay out of "
             "that hash: they differ between devices without changing what is measured."),

            ("table", [
                ["Field", "Purpose"],
                ["model", "Name, format, SHA-256 digest, size. The digest is the identity; the path is a hint."],
                ["backend", "Which adapter, which delegate (CPU / GPU / NPU), and any backend-specific options."],
                ["workload", "Fixed prompt, its hash, prompt length, output length, and the stop condition."],
                ["protocol", "Warmup and measured iteration counts, inter-iteration delay, abort policy, time limit."],
                ["preconditions", "The gate the device must pass before the run may start."],
                ["seed", "Recorded even where a backend decodes greedily and ignores it."],
                ["toolVersion", "The version of ThermalBench that produced the run."],
            ], [22, 78]),

            ("h2", "The stop condition is a token count, never end-of-sequence"),
            ("warn",
             "If one backend stops on end-of-sequence and another at a fixed token count, their "
             "tokens-per-second figures cannot be compared. It is like timing two runners when "
             "one of them gets to pick where the finish line is. So output length is fixed "
             "across all backends and recorded. If a spec's backend cannot honour a token-count "
             "stop, the validator <b>rejects</b> the spec before any device time is spent."),

            ("h2", "What the validator catches"),
            ("bullets", [
                "A stop count that disagrees with the declared output length.",
                "A backend that is not registered, or is unavailable on this device, with the reason attached.",
                "A model format the chosen backend cannot load.",
                "A delegate the chosen backend does not offer.",
                "A backend that cannot enforce a token-count stop, and so cannot produce comparable rates.",
                "A stabilization window longer than the maximum wait, which could never be satisfied.",
            ]),
            ("p",
             "Too few measured iterations gets a warning, not a rejection. A short smoke run is "
             "a reasonable thing to want, so the validator lets it through and the analysis "
             "marks the result <font face='Courier'>INSUFFICIENT_DATA</font>."),

            ("h2", "Where model files have to live"),
            ("p",
             "ThermalBench ships no models. It measures whatever you point it at, which is why "
             "the model's digest is part of the run identity."),
            ("warn",
             "This one catches people out. Both the app and the harness target a scoped-storage "
             "SDK, so neither can open an arbitrary path under "
             "<font face='Courier'>/sdcard</font>, however you write the manifest. Models, "
             "specs and results all live in the relevant app's own external files directory. "
             "That directory needs no permission, and adb can still reach it."),
            ("table", [
                ["Consumer", "Directory"],
                ["Headless harness", "/sdcard/Android/data/com.thermalbench.harness/files/models"],
                ["App, debug build", "/sdcard/Android/data/com.thermalbench.debug/files/models"],
            ], [26, 74], (1,)),
            ("p",
             "The app lists whatever is in that directory. Tap a file and the app sets the "
             "format from its extension, so you never type it. The extension is the only thing "
             "that identifies a format here, so a file with an unrecognised one is listed as "
             "ignored rather than guessed at. To place a model with the fleet tool, run:"),
            ("code", "thermalbench push-model --model gemma-3n-e2b-it-q4.litertlm"),

            ("h2", "Generating a spec"),
            ("code",
             "./gradlew :tools:cli:installDist\n"
             "./tools/cli/build/install/thermalbench/bin/thermalbench spec > my-spec.json"),
            ("p",
             "The template arrives with a fixed reference prompt, its hash already computed and "
             "the spec identity already derived. Edit the model path and the backend. Then, to "
             "re-derive the identity, load the spec and save it again through the codec."),
        ],
    },

    # ------------------------------------------------------------------ 6
    {
        "title": "The precondition gate",
        "blocks": [
            ("p",
             "The gate is what makes two runs comparable in the first place. It is a policy "
             "plus a stabilization window, and it emits a live stream of states so you can see "
             "what is holding a run back."),

            ("table", [
                ["Condition", "Default", "Why"],
                ["Thermal status", "NONE", "A warm start measures a different thing than a cold one."],
                ["Thermal headroom", "≥ 0.5 margin", "Continuous 0..1 signal, far richer than the coarse status enum."],
                ["Unplugged", "required", "Charging is a heat source in its own right."],
                ["Battery", "≥ 50%", "A low battery triggers its own independent throttling."],
                ["Screen", "OFF", "Must be fixed rather than incidental; the panel is a heat source. "
                 "The app can instead hold it on at minimum brightness (ON_DIMMED)."],
                ["Stabilization", "30 s", "Conditions must hold continuously, not merely be true once."],
                ["Maximum wait", "15 min", "Beyond this the gate times out rather than waiting forever."],
            ], [24, 20, 56]),

            ("note",
             "<b>The window restarts on any failure.</b> A device that holds for twenty "
             "seconds, charges briefly, then holds again is not stable. Treat it as stable and "
             "you put a warm start into a run that claims to be cold."),

            ("h2", "Following the cool-down with the screen off"),
            ("p",
             "There is a catch. The default policy wants the screen off, and if you turn the "
             "screen on to check progress, you fail the screen condition and restart the "
             "window. So in the app, the runner service reports the cool-down in two ways that "
             "need no screen:"),
            ("bullets", [
                "<b>Notification.</b> The run's ongoing notification shows the margin against "
                "its target, for example <font face='Courier'>Cooling: margin 0.27 of 0.50"
                "</font>, with a progress bar toward the target. You can see it on the lock "
                "screen.",
                "<b>Spoken cues.</b> While the thermal conditions are unmet, the phone reads "
                "the margin aloud every 20 seconds: <i>\"Current thermal margin is 0.27.\"</i> "
                "If only the thermal status is blocking, it says the status instead. Two "
                "one-off cues mark progress: <i>\"halfway cooled\"</i>, spoken only when the "
                "device started at least partly warm, and <i>\"cool enough\"</i>, which also "
                "says what is still blocking, or roughly when the run will start.",
            ]),
            ("p",
             "Every margin you see or hear goes through one formatter, "
             "<font face='Courier'>formatThermalMargin</font>. It shows two decimals and "
             "rounds down, so it never claims a threshold the device has not reached: 0.499 "
             "reads 0.49, not 0.50. While the margin is moving, the voice can still lag the "
             "screen by up to 20 seconds."),
            ("p",
             "Speech plays as navigation guidance, like a satnav prompt. That means it plays "
             "with the ringer on silent and follows the media volume. The speech engine starts "
             "only when there is something to say and shuts down before the first iteration, "
             "so none of it is resident while the device is being measured. The run itself is "
             "silent, apart from a closing <i>\"run finished\"</i> with the retention "
             "percentage, or a notice that it stopped with an error. A device with no speech "
             "engine simply stays quiet; the run does not fail."),
            ("p",
             "Every spoken sentence also goes to logcat, so you can check afterwards what the "
             "phone said:"),
            ("code", "adb logcat -s ThermalBench | grep said"),
            ("p",
             "The headless harness does not speak. A computer drives it, and that computer "
             "already sees the same state as log lines."),

            ("h2", "Overriding the gate"),
            ("p",
             "Sometimes you have to run on a device that cannot meet the policy. For that, the "
             "harness accepts an explicit override:"),
            ("code", "-e ignorePreconditions true"),
            ("p",
             "The run goes ahead, and the result is permanently marked "
             "<font face='Courier'>PRECONDITIONS_UNMET</font>, with the specific unmet "
             "conditions listed in the document. The leaderboard refuses such a result, the "
             "comparison tables leave it out by default, and the app shows a red banner. The "
             "override changes what the tool does. It never changes what the tool reports."),

            ("h2", "Handling unavailable signals"),
            ("p",
             "Some vendors return NaN for thermal headroom. The tool turns that into "
             "<font face='Courier'>null</font>, skips the headroom check, and records the "
             "absence. Consider the other two options. Treat a missing reading as a failure, "
             "and the tool becomes unusable on those devices. Treat it as a pass, and the tool "
             "tells a quiet lie. Recording the gap is the only choice left that is both usable "
             "and honest."),
        ],
    },

    # ------------------------------------------------------------------ 7
    {
        "title": "Backends",
        "blocks": [
            ("p",
             "Every inference runtime sits behind the "
             "<font face='Courier'>InferenceBackend</font> port. Adapters live in "
             "<font face='Courier'>data/backend-*</font>, and dependency injection assembles "
             "them into a registry, so adding a module adds a backend."),

            ("table", [
                ["Backend", "Format", "Delegates", "Notes"],
                ["litert-lm", ".litertlm", "CPU, GPU, NPU",
                 "The reference path. Its session API separates prefill from decode. NPU needs the "
                 "vendor's libraries packaged; see below."],
                ["mediapipe-genai", ".task", "CPU, GPU",
                 "Also stops on end-of-sequence, so the window closes at the Nth token."],
                ["llamacpp", "GGUF", "CPU, GPU",
                 "The format the Google stack will not ship. Requires the NDK. GPU needs the "
                 "Vulkan build and a GPU Vulkan can drive."],
                ["qualcomm-genie", "Genie bundle", "CPU, NPU",
                 "Qualcomm's own runtime (QAIRT). The compute unit is the bundle's. Needs the SDK."],
                ["executorch", ".pte", "—",
                 "Phase 2. Registered, reports itself unavailable."],
            ], [20, 14, 20, 46], (0, 1)),

            ("h2", "Why LiteRT-LM is the reference"),
            ("p",
             "Its session API exposes <font face='Courier'>runPrefill</font> and "
             "<font face='Courier'>runDecode</font> separately, and "
             "<font face='Courier'>runDecode</font> produces exactly one token per call. That "
             "gives you an unambiguous time-to-first-token, a real prefill rate, and a decode "
             "rate measured over a token count the spec fixes, not one that depends on when the "
             "model decides to emit end-of-sequence. Nothing has to be inferred."),
            ("p",
             "Each iteration gets a fresh session. Reuse one and iteration two inherits "
             "iteration one's key-value cache, so it measures a longer sequence: a different "
             "workload wearing the same label."),

            ("h2", "How MediaPipe is handled honestly"),
            ("p",
             "MediaPipe stops on end-of-sequence as well as on its token budget, so you cannot "
             "tell it to produce exactly N tokens. Left alone, that would quietly make its "
             "numbers incomparable. Instead, the adapter timestamps every streamed token and "
             "closes the measurement window at the Nth one. Anything the runtime emits after "
             "that falls outside the window. If a generation stops early, it is reported with "
             "the count it actually reached, and the analysis records it as a shorter "
             "iteration, not a faster one."),

            ("h2", "Unavailable backends stay visible"),
            ("note",
             "A backend that cannot run on a device is still registered and still shows up in "
             "the list, with the reason attached: a missing native library, an unsupported "
             "delegate, a phase-2 adapter. <b>A gap in coverage is a finding, not something "
             "to hide from a comparison table.</b> The same contract covers a device with no "
             "NPU and an adapter nobody has written yet."),

            ("h2", "Building the llama.cpp backend"),
            ("p",
             "The native half needs the NDK, CMake and a llama.cpp checkout. It is off by "
             "default, so the project still builds on a machine that has none of them. Without "
             "it, the Kotlin adapter still compiles and reports the backend unavailable."),
            ("code",
             "git clone --depth 1 --branch b11879 \\\n"
             "  https://github.com/ggml-org/llama.cpp ~/src/llama.cpp"),
            ("p",
             "Then turn it on in your user-level <font face='Courier'>~/.gradle/gradle.properties"
             "</font>. That file overrides the project one, so your checkout path never lands in "
             "the repository. Use forward slashes. A properties file treats a backslash as an "
             "escape, and a Windows path written the usual way will not mean what you think:"),
            ("code",
             "thermalbench.nativeLlama=true\n"
             "thermalbench.llamaCppDir=C:/Users/you/src/llama.cpp"),
            ("p",
             "The pinned release, <font face='Courier'>thermalbench.llamaCppTag</font>, stays "
             "in the project's own <font face='Courier'>gradle.properties</font>."),
            ("p",
             "Point it at a directory that is not a llama.cpp checkout and the build fails at "
             "configuration time with a message that says so, not with a CMake error three "
             "layers down."),
            ("h2", "Why the checkout is pinned"),
            ("p",
             "llama.cpp has no stable API and no stable performance. Functions get renamed "
             "between releases, and kernel and threading changes move tokens per second on the "
             "same device. Now follow the chain. A run's spec does not capture the runtime "
             "version. So if someone upgrades llama.cpp without recording it, the speed change "
             "lands in the results, and a comparison presents it as a difference between "
             "devices. That is exactly the kind of hidden confound the rest of this manual "
             "exists to rule out."),
            ("p",
             "So the project names one release, <font face='Courier'>thermalbench.llamaCppTag"
             "</font>, and the native build does two things with it:"),
            ("bullets", [
                "It stamps the checkout's actual version, from <font face='Courier'>git "
                "describe --tags --always --dirty</font>, into the library. "
                "<font face='Courier'>runtimeVersion()</font> returns it, so every result "
                "document names the llama.cpp release that produced it. A locally modified "
                "checkout reads <font face='Courier'>b11879-dirty</font>, so a modified "
                "runtime cannot pass itself off as the release.",
                "It warns when the checkout is not the pinned tag. The build still completes, "
                "so you can try a newer release, and the stamp records that you did.",
            ]),
            ("p",
             "An upgrade takes two changes, made together: check out the new tag, and move the "
             "pinned tag property to match. If <font face='Courier'>llamacpp_jni.cpp</font> "
             "stops compiling afterwards, the API moved; compare the calls against the new "
             "<font face='Courier'>include/llama.h</font>."),
            ("warn",
             "Results from different llama.cpp versions are not comparable, even for the same "
             "spec on the same device. Before you put two llamacpp runs in one table, check the "
             "runtime version in each document."),
            ("h2", "Always an optimised build"),
            ("p",
             "The native code is built with <font face='Courier'>CMAKE_BUILD_TYPE=Release"
             "</font> for every variant, debug included. Otherwise AGP compiles it as Debug, and "
             "an unoptimised llama.cpp is roughly an order of magnitude slower. A benchmark "
             "built that way measures its own compiler flags, not the device. Worse, it still "
             "draws a plausible-looking decay curve, so nothing looks wrong."),
            ("h2", "GPU offload with Vulkan"),
            ("p",
             "A llama.cpp build without a GPU backend offers only the CPU delegate. Ask it to "
             "offload layers and it accepts the request and silently ignores it, so a run "
             "labelled GPU really runs on the CPU. GPU offload comes from ggml's Vulkan backend. "
             "It is off by default because it needs more on the build machine:"),
            ("bullets", [
                "<b>From the NDK:</b> the C Vulkan headers, the Vulkan loader and the "
                "<font face='Courier'>glslc</font> shader compiler. Nothing to install.",
                "<b>Two Khronos header checkouts:</b> Vulkan-Hpp (C++) and SPIR-V, which the "
                "NDK does not ship. Pin both to one Vulkan SDK release.",
                "<b>A compiler for the build machine itself.</b> llama.cpp compiles a small "
                "shader generator that runs during the build. On Windows that means Visual "
                "Studio Build Tools with the C++ workload; the build finds it with "
                "<font face='Courier'>vswhere</font>, so you don't need a developer shell. On "
                "Linux and macOS, a gcc or clang on PATH is enough.",
            ]),
            ("code",
             "git clone --depth 1 --branch vulkan-sdk-1.4.363.0 \\\n"
             "  https://github.com/KhronosGroup/Vulkan-Headers ~/src/Vulkan-Headers\n"
             "git clone --depth 1 --branch vulkan-sdk-1.4.363.0 \\\n"
             "  https://github.com/KhronosGroup/SPIRV-Headers ~/src/SPIRV-Headers"),
            ("p",
             "Then add three lines next to the llama.cpp ones in "
             "<font face='Courier'>~/.gradle/gradle.properties</font>:"),
            ("code",
             "thermalbench.llamaVulkan=true\n"
             "thermalbench.vulkanHeadersDir=C:/Users/you/src/Vulkan-Headers\n"
             "thermalbench.spirvHeadersDir=C:/Users/you/src/SPIRV-Headers"),
            ("p",
             "Expect the first Vulkan build to be slow. It compiles several hundred shaders and "
             "takes noticeably longer than a CPU-only build; later builds are incremental. The "
             "APK gains <font face='Courier'>libggml-vulkan.so</font>, about 20 MB. If your "
             "machine has its own host toolchain, pass it as the CMake argument "
             "<font face='Courier'>THERMALBENCH_HOST_TOOLCHAIN</font>."),
            ("p",
             "Headers newer than the NDK's are safe on older devices. ggml loads every Vulkan "
             "entry point at run time, so the device's own driver decides what is available."),
            ("h3", "GPU is offered only when a GPU is found"),
            ("p",
             "On a device whose driver exposes no usable GPU, a Vulkan build would fall back to "
             "the CPU without telling anyone. So at startup the adapter asks ggml for a GPU "
             "device, and offers the GPU delegate only if one exists. Phone GPUs share system "
             "memory, so ggml lists them as integrated (IGPU); both kinds count. The decision "
             "is logged:"),
            ("code",
             "adb logcat -s ThermalBenchLlama\n"
             "  GPU device: Vulkan0 (Mali-G78)\n"
             "  load_tensors: offloaded 25/25 layers to GPU"),
            ("p",
             "llama.cpp's own log goes to logcat under the same tag. Its load-time lines are "
             "the record of which device a run used and how many layers it offloaded. On a new "
             "device, read them before you trust a GPU result."),
            ("h3", "What to expect"),
            ("p",
             "GPU is not automatically faster. On a Pixel 6 (Mali-G78), qwen2.5-0.5B Q4_K_M "
             "decoded at about 16 tokens per second on the GPU against 8 on the CPU. It also "
             "processed the prompt about four times more slowly, so each iteration took twice "
             "as long. Vulkan is the portable path. Qualcomm Adreno GPUs also have a dedicated "
             "OpenCL backend in llama.cpp, which this build does not yet include."),
            ("warn",
             "A GPU run and a CPU run of the same model have different spec identities, because "
             "the delegate is part of the spec. Compare them as two specs on one device, not as "
             "two runs of one spec."),
            ("h2", "GPU offload with OpenCL (Adreno)"),
            ("p",
             "llama.cpp also has an OpenCL backend written for Qualcomm Adreno GPUs. On the "
             "build machine it needs only the Khronos OpenCL headers, plus Python 3, which "
             "embeds the kernels. The OpenCL library itself belongs to the GPU vendor and lives "
             "on the device:"),
            ("code",
             "git clone --depth 1 --branch v2026.05.29 \\\n"
             "  https://github.com/KhronosGroup/OpenCL-Headers ~/src/OpenCL-Headers\n"
             "\n"
             "# ~/.gradle/gradle.properties\n"
             "thermalbench.llamaOpenCl=true\n"
             "thermalbench.openClHeadersDir=C:/Users/you/src/OpenCL-Headers"),
            ("p",
             "Linking needs a <font face='Courier'>libOpenCL.so</font>, so the build generates a "
             "stand-in with every function the pinned <font face='Courier'>cl.h</font> declares. "
             "The app and harness keep it out of their APKs, because a packaged copy would "
             "shadow the vendor's library. The manifest declares "
             "<font face='Courier'>libOpenCL.so</font> with "
             "<font face='Courier'>uses-native-library</font>, which Android 12 and later "
             "require before an app may load a vendor library."),
            ("p",
             "Each ggml backend is built as its own library and loaded by name at startup. A "
             "backend that cannot load is skipped and logged, so a phone without vendor OpenCL, "
             "such as a Pixel, still runs llama.cpp on the CPU and Vulkan. On a Snapdragon, "
             "OpenCL and Vulkan both reach the same Adreno. The composer then offers a choice "
             "of GPU API, recorded in the spec as the <font face='Courier'>gpuApi</font> "
             "option, and a GPU run is pinned to exactly one device. A spec that names no API "
             "gets OpenCL. Either way, the result's <font face='Courier'>computeDevice</font> "
             "records the device that ran."),

            ("h2", "NPU on Snapdragon (LiteRT-LM)"),
            ("p",
             "LiteRT-LM can run a model on the Qualcomm Hexagon NPU, but its Maven package "
             "contains neither of the two pieces that needs: a LiteRT dispatch library, and "
             "Qualcomm's QAIRT runtime libraries. Start an NPU engine without them and it "
             "aborts the whole process natively. So the adapter offers NPU only when a "
             "<font face='Courier'>libLiteRtDispatch_*.so</font> is actually packaged."),
            ("steps", [
                "Download the Qualcomm AI Runtime (QAIRT) SDK, version 2.42, from Qualcomm's "
                "software centre. You need a Qualcomm account and must accept their licence, "
                "which is why the build cannot fetch it for you.",
                "Build LiteRT's Qualcomm dispatch library with Bazel and NDK r28b or newer "
                "(Linux or WSL): <font face='Courier'>bazel build --config=android_arm64 "
                "@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so</font>. It must come "
                "from the LiteRT release that matches the LiteRT-LM version in the version "
                "catalog. A mismatched pair fails at engine creation.",
                "Collect these in one folder under <font face='Courier'>arm64-v8a/</font>: the "
                "dispatch library; <font face='Courier'>libQnnHtp.so</font>, "
                "<font face='Courier'>libQnnSystem.so</font> and "
                "<font face='Courier'>libQnnHtpPrepare.so</font> from "
                "<font face='Courier'>lib/aarch64-android/</font>; and the Hexagon stub and "
                "skel for the target chip, <font face='Courier'>libQnnHtpV75Stub.so</font> and "
                "<font face='Courier'>libQnnHtpV75Skel.so</font> for the Snapdragon 8 Gen 3 "
                "(SM8650).",
                "Point <font face='Courier'>thermalbench.npuLibsDir</font> at the folder in "
                "<font face='Courier'>~/.gradle/gradle.properties</font>, then rebuild.",
            ]),
            ("p",
             "With the property set, the libraries are packaged and the APK extracts its native "
             "libraries to disk, because the QNN runtime loads its DSP-side skel by directory "
             "path. Before creating the engine, the adapter points "
             "<font face='Courier'>ADSP_LIBRARY_PATH</font> at that directory. The manifest "
             "declares the vendor FastRPC library, <font face='Courier'>libcdsprpc.so</font>, "
             "which the runtime uses to reach the DSP. Without the property nothing changes: "
             "the APK carries no vendor code and NPU is not offered."),
            ("p",
             "NPU models are compiled per chip. The catalog lists Gemma 3 1B for the SM8650 "
             "(<font face='Courier'>Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm</font>), gated "
             "behind Google's Gemma licence like the other Gemma files. Google publishes NPU "
             "builds for SM8550, SM8650, SM8750 and SM8850 only. Load a file compiled for "
             "another chip and it fails."),
            ("warn",
             "Older Snapdragons cannot run LLMs on the NPU this way. The OnePlus 8's Snapdragon "
             "865 (SM8250) has no published NPU models, so there the honest result is CPU "
             "against GPU."),
            ("h2", "Qualcomm Genie (QAIRT)"),
            ("p",
             "Genie is Qualcomm's own LLM runtime, part of the Qualcomm AI Runtime (QAIRT) SDK. "
             "It runs a model on the Hexagon NPU (the QnnHtp backend) or on Qualcomm's CPU path "
             "(QnnGenAiTransformer), and on a Snapdragon it is the most direct route to the "
             "NPU. You download the SDK from Qualcomm under their licence; it is never part of "
             "this repository. Point the build at an unpacked copy:"),
            ("code",
             "# ~/.gradle/gradle.properties\n"
             "thermalbench.qairtDir=C:/PROJECTS/Android/2.44.0.260225\n"
             "thermalbench.qairtHexagon=v75        # v73, v75, v79: one per NPU generation"),
            ("p",
             "The build then compiles a small JNI adapter against the Genie headers and packages "
             "only what running a prepared model needs: <font face='Courier'>libGenie.so</font>, "
             "the QNN system, HTP and GenAiTransformer libraries, and a stub and DSP-side skel "
             "for each listed Hexagon version. The 85 MB "
             "<font face='Courier'>libQnnHtpPrepare.so</font> stays out. It compiles graphs on "
             "the device, and Genie bundles arrive already compiled. The adapter opens "
             "<font face='Courier'>libGenie.so</font> at run time instead of linking it, so a "
             "build without the SDK, or a phone it cannot load on, reports the backend "
             "unavailable with the reason."),
            ("p",
             "The licence lets the libraries ship inside an application, which is what an APK "
             "built this way is. It does not let you distribute them on their own, so never "
             "commit them. No account is involved at run time."),
            ("h3", "Model bundles"),
            ("p",
             "A Genie model is a folder: <font face='Courier'>genie_config.json</font>, a "
             "tokenizer and the model files. Push the folder into the models directory. The "
             "library lists it by folder name, and a spec's model path is its config. Genie "
             "resolves relative paths against the working directory, so before loading, the "
             "adapter makes every path in the config absolute. It also makes sampling greedy "
             "with the run's seed and caps generation at the spec's token count."),
            ("p",
             "A bundle's compute unit is fixed when the bundle is built. The adapter reads it "
             "from the config (QnnHtp is NPU, QnnGenAiTransformer is CPU) and preselects it. If "
             "a spec asks a bundle for a different unit, the load is refused; the run never "
             "quietly moves somewhere else."),
            ("bullets", [
                "<b>CPU bundles</b> are made on the PC with the SDK's "
                "<font face='Courier'>qnn-genai-transformer-composer</font> from a Hugging Face "
                "model, for the architectures it has configurations for: Llama 2/3.x, TinyLlama, "
                "Mistral, Gemma 2B, Phi-3 and others. TinyLlama 1.1B Chat converts in about 12 "
                "seconds to a 585 MB Z8-quantised bundle. On Windows the composer needs a small "
                "wrapper. The SDK's Python package always loads its Genie binding, and the "
                "Windows build of that binding does not load, even though the composer never "
                "calls it.",
                "<b>NPU bundles</b> hold context binaries compiled for one Hexagon version. You "
                "export them through Qualcomm AI Hub with the "
                "<font face='Courier'>qai-hub-models</font> package, which needs a free AI Hub "
                "account; Llama models also need Hugging Face access. The QAIRT version the "
                "bundle was compiled with should match the SDK the app was built with.",
            ]),
            ("warn",
             "Genie runs only on Snapdragon. On other silicon even its CPU path refuses to start "
             "(QNN error 1006, platform not supported; checked on a Pixel 6), so the backend "
             "reports itself unavailable there. Older Snapdragons are out too. Genie's CPU path "
             "uses ARMv8.6 int8 matrix instructions (smmla), and a CPU without them dies with "
             "SIGILL; a OnePlus 8T's Snapdragon 865 crashed exactly this way. The NPU path needs "
             "Hexagon v73 or newer (Snapdragon 8 Gen 2 onward). The adapter checks both before a "
             "run: it offers CPU only where /proc/cpuinfo lists i8mm, and NPU only on a known "
             "v73-or-newer SoC."),
            ("p",
             "To use a bundle in the app, pick a <font face='Courier'>.zip</font> of the bundle "
             "folder with the file picker. The app unpacks it into a folder it owns, named after "
             "the zip, because a folder pushed with adb is one the app may not read. Unpacking "
             "streams one entry at a time into a hidden staging folder, and moves the bundle "
             "into place only once it is complete and holds a "
             "<font face='Courier'>genie_config.json</font>. The app refuses an entry whose path "
             "would leave the folder, or an archive that would unpack past the free space. A "
             "cancelled or failed import leaves nothing behind."),
            ("p",
             "On the harness, let the harness create a bundle's folder, as it does its other "
             "directories, before you push the files:"),
            ("code",
             "adb shell am instrument -w -e prepare true -e bundles tinyllama-cpu $RUNNER\n"
             "adb push tinyllama-cpu/. $DIR/models/tinyllama-cpu/"),
            ("h2", "The JNI layer"),
            ("p",
             "The JNI layer decodes one token per call for the same reason LiteRT-LM does: it is "
             "what makes an exact token-count stop possible. It also clears the context between "
             "iterations and decodes greedily, so sampling noise cannot pass itself off as "
             "throughput noise."),
        ],
    },

    # ------------------------------------------------------------------ 8
    {
        "title": "Telemetry, and what it honestly cannot see",
        "blocks": [
            ("p",
             "This is where most benchmark apps quietly fail. The table below is the realistic "
             "picture, not the optimistic one."),

            ("table", [
                ["Source", "API", "Availability"],
                ["Thermal status", "PowerManager.getCurrentThermalStatus()",
                 "API 29+, universal. The primary signal."],
                ["Thermal headroom", "PowerManager.getThermalHeadroom()",
                 "API 30+. Continuous 0..1. Some vendors return NaN."],
                ["Battery temperature", "ACTION_BATTERY_CHANGED",
                 "Universal but coarse, and lags die temperature by minutes."],
                ["Charging state", "BatteryManager.EXTRA_PLUGGED",
                 "Universal. Gated on, because charging is a heat source."],
                ["CPU frequency", "/sys/…/scaling_cur_freq",
                 "Vendor dependent and increasingly restricted."],
                ["Sensor temps", "/sys/class/thermal/thermal_zone*",
                 "Vendor SELinux decides. Recorded per iteration where readable; null otherwise."],
                ["Hardware temps", "HardwarePropertiesManager",
                 "Needs device-owner or system app. Not designed around."],
                ["Memory", "Debug.MemoryInfo, /proc/self/statm",
                 "Universal."],
            ], [18, 34, 48], (1,)),

            ("h2", "Nothing is ever fabricated"),
            ("warn",
             "CPU frequency is returned only when <b>every</b> core is readable. A partial list "
             "never poses as the whole SoC, and an unreadable value is "
             "<font face='Courier'>null</font>, never a guess or a default. I would trust a "
             "result that says \"CPU frequency was unreadable on this device\" over one that "
             "quietly leaves the field out."),

            ("p",
             "The same rule holds everywhere else. Battery temperature is recorded as "
             "corroboration and never treated as the main thermal signal, because it lags the "
             "die by minutes. Reading it to judge the chip is like judging the oven by the "
             "kitchen. If the platform reports a thermal status this tool does not recognise, "
             "it becomes <font face='Courier'>UNKNOWN</font> and blocks the run instead of "
             "being optimistically read as cold."),

            ("h2", "Which temperatures, exactly"),
            ("p",
             "Only one reading in degrees is guaranteed on every phone: the battery's, from "
             "<font face='Courier'>BatteryManager.EXTRA_TEMPERATURE</font> in tenths of a "
             "degree. It's what the \"Battery °C\" panel on every chart shows. Thermal status "
             "and headroom aren't temperatures at all. They're the vendor's verdict, computed "
             "from sensors the app never sees."),
            ("p",
             "Android gives apps no API for the individual sensors, so "
             "<font face='Courier'>ThermalZoneSource</font> goes to the kernel. It walks "
             "<font face='Courier'>/sys/class/thermal</font>, keeps every "
             "<font face='Courier'>thermal_zone*</font> whose <font face='Courier'>temp</font> "
             "file is readable and passes <font face='Courier'>isTemperatureZone</font> when "
             "first checked (see the warning below), and records each one by the "
             "vendor's own name (CPU clusters, GPU, modem, skin, battery) in "
             "<font face='Courier'>IterationSample.sensorTemperatures</font>. Most kernels "
             "report millidegrees; a few report whole degrees, and the reader handles both."),
            ("note",
             "Whether any of this works is the vendor's call, made in its SELinux policy. On "
             "the Pixel 6 the app can't even list <font face='Courier'>/sys/class/thermal</font> "
             "(<i>Permission denied</i>), so every Pixel 6 result in this manual has "
             "<font face='Courier'>sensorTemperatures: null</font>. The Pixel 6 doesn't expose "
             "its GPU clock to apps either. The Honor Magic V3 (Snapdragon 8 Gen 3, Android 17) "
             "is the same: adb's own shell sees 113 zones there, but the app gets "
             "<i>Permission denied</i>, and all eight Honor results so far, including the three "
             "charted in chapter 9, record only the battery. A phone that blocks every zone records "
             "<font face='Courier'>null</font>, never an empty list, so a locked-down phone "
             "can't pass for a cold one."),
            ("p",
             "The OnePlus 8T (Snapdragon 865, Android 14) sits at the other extreme: the app "
             "can read 90 zones, and records the 76 that are temperatures on every iteration. "
             "They include each CPU core "
             "(<font face='Courier'>cpu-0-*-usr</font>, <font face='Courier'>cpu-1-*-usr</font>), "
             "the GPU (<font face='Courier'>gpuss-0-usr</font>, "
             "<font face='Courier'>gpuss-1-usr</font>), the NPU, DSP and DDR, the modem, three "
             "case sensors (<font face='Courier'>shell_front</font>, "
             "<font face='Courier'>shell_frame</font>, <font face='Courier'>shell_back</font>) and "
             "the battery. In a twelve-second llama.cpp CPU run, the CPU cores went from about "
             "40 °C to 62 °C while <font face='Courier'>battery</font> moved from 30.7 to "
             "30.8 °C. That's the lag in the battery reading, in two numbers."),
            ("warn",
             "Not every thermal zone is a thermometer. On the OnePlus 8T, "
             "<font face='Courier'>soc</font> is the battery charge in percent (it read 99, and "
             "86 in a later run), <font face='Courier'>pm8150b-vbat-lvl*</font> is a voltage "
             "(4.185), the <font face='Courier'>bcl</font>, <font face='Courier'>ibat</font> and "
             "<font face='Courier'>vph</font> levels are limiter states that sit at 0, and "
             "<font face='Courier'>xo-therm-usr</font> and <font face='Courier'>mmw-pa1-usr</font> "
             "report exactly -40, the floor of an unpopulated sensor. Every one of those values "
             "falls inside -40 to 150 °C, so a range check alone lets them through. "
             "<font face='Courier'>ThermalZoneSource.isTemperatureZone</font> therefore also "
             "drops <font face='Courier'>soc</font>, any name ending in "
             "<font face='Courier'>-lvl</font> plus digits, and any zone reading exactly -40 "
             "at discovery: 14 zones on this phone. Newer Snapdragon kernels name some battery "
             "zones without the suffix. On the Honor Magic V3, <font face='Courier'>vbat</font> "
             "reads 4285 (millivolts, which the reader would take as 4.285 °C) and "
             "<font face='Courier'>socd</font> reads 10, so both names are on the list too. "
             "Every reading during the run goes through "
             "the same check, because a zone can pass discovery and then turn into a "
             "sentinel: four OnePlus 8T modem zones (<font face='Courier'>modem-mmw*-usr</font>, "
             "<font face='Courier'>modem-streamer-usr</font>) read -273 °C while the modem is "
             "idle, and those readings are left out. Results saved before that change still "
             "contain them. For those, and for vendors whose naming the rule doesn't know yet, "
             "pick sensors by name before you plot or average them, and treat a zone you don't "
             "recognise as unknown, not as a temperature."),
        ],
    },

    # ------------------------------------------------------------------ 9
    {
        "title": "Analysis: from samples to a verdict",
        "blocks": [
            ("p",
             "<font face='Courier'>core/analysis</font> is pure Kotlin with no Android "
             "dependency, so the identical code runs on the device for the live chart and on a "
             "workstation for the paper."),

            ("h2", "Peak"),
            ("p",
             "Peak is the best rolling-median window, not the single best iteration. One lucky "
             "iteration is noise. A sustained window is a capability. Take the maximum instead "
             "and you inflate the denominator of the retention ratio, which understates every "
             "device."),

            ("h2", "Steady state"),
            ("p",
             "Steady state needs a settled tail, defined by two conditions holding together: a "
             "coefficient of variation below 5%, and a normalized slope below 3% across the "
             "window. The steady-state value is the median of that settled tail."),
            ("p",
             "If the last window is not itself steady, the run never stabilized and the result "
             "is <font face='Courier'>null</font>. Calling the final value of a still-declining "
             "curve a floor would be a fabrication, and a plausible one, which is the worst "
             "kind."),

            ("h2", "Decay fit"),
            ("p",
             "The fit is Levenberg-Marquardt against "
             "<font face='Courier'>a + b·exp(−t/τ)</font>, and it gives an asymptote, an "
             "amplitude, a time constant and an R². If the fit does not converge, or the series "
             "is too short or too degenerate to fit, the profile reports the fit as absent. It "
             "never swaps in a straight line dressed up as a decay."),

            ("h2", "The validity verdict"),
            ("table", [
                ["Verdict", "Meaning"],
                ["VALID", "Preconditions were met and enough iterations completed."],
                ["PRECONDITIONS_UNMET", "The gate was not satisfied. Not comparable with other runs."],
                ["INSUFFICIENT_DATA", "Too few measured iterations for sustained-load analysis."],
            ], [30, 70], (0,)),

            ("note",
             "<b>A run cut short by the operating system's thermal floor stays VALID.</b> "
             "That is not a failed measurement. It is the finding, the same one the Galaxy S24 "
             "Ultra produced in the paper that motivated this tool. Only a run that ended for "
             "an unrelated reason, before reaching the end of its protocol, is marked as "
             "insufficient."),

            ("h2", "Everything the profile reports"),
            ("bullets", [
                "Peak and steady-state decode throughput, and the retention ratio between them.",
                "Time and iteration count to the first throttle event, and every throttle transition.",
                "The exponential decay fit, or an explicit absence.",
                "Time-to-first-token drift, in microseconds per iteration.",
                "Mean and minimum decode throughput across the measured iterations.",
                "Battery drain and battery temperature rise across the run.",
                "The termination reason and the validity verdict.",
            ]),

            ("h2", "A worked example: Pixel 6"),
            ("p",
             "Two harness runs on a Pixel 6 show these rules at work: the same model and prompt "
             "on the CPU and on the GPU, each run unplugged with the screen off, both VALID."),
            ("figure", "pixel6-cpu-gpu.png",
             "Gemma 3 1B via MediaPipe on a Pixel 6 (Tensor G1), 256-token answers. The GPU's "
             "peak is 12.1 tok/s, its best sustained window, not the single 15.8 tok/s "
             "iteration at minute 13. The CPU run was still declining when it ended, so the "
             "analyzer reports no steady state and therefore no retention ratio; it does not "
             "treat the last value as a floor. Line-end labels give the mean of the last ten "
             "iterations as a share of peak (83% CPU, 99% GPU), shown beside the retention "
             "ratio, never in place of it. Drawn with "
             "<font face='Courier'>thermalbench-analyze plot</font>."),
            ("p",
             "Read the two runs together and you have the reason this tool exists. The CPU "
             "starts about 40% ahead, throttles at 5.6 minutes and warms the battery by "
             "7.9 °C. The GPU holds 99% of its peak and warms it by 3.0 °C. A benchmark that "
             "stops after a minute reports the first fact and misses the second."),

            ("h2", "A cross-device example: Honor Magic V3 against Pixel 6"),
            ("p",
             "Same model file (same size, same name), same prompt, same MediaPipe release "
             "(0.10.35), this time on the CPU of an Honor Magic V3 (Snapdragon 8 Gen 3) and the "
             "Pixel 6 above. Both runs are VALID: unplugged, screen off, 256-token answers, 150 "
             "measured on the Honor and 70 on the Pixel."),
            ("figure", "honor-vs-pixel-cpu.png",
             "CPU decode throughput and battery temperature, Honor Magic V3 against Pixel 6. "
             "The Honor opens at 22.6 tok/s (mean of the first ten answers), throttles to LIGHT "
             "at 2.1 minutes and holds 18.3 tok/s for the remaining 35 minutes: 81% of its "
             "opening, with the battery 6.0 °C warmer. The Pixel opens at 16.8, throttles at "
             "5.6 minutes and ends at 14.2 (84%), 7.9 °C warmer. Line-end labels compare the "
             "first and last ten answers, so both devices are measured the same way."),
            ("p",
             "The Honor throttles sooner but settles more firmly. It is the first run in this "
             "manual with a detected steady state (18.26 tok/s), and so the first with a "
             "retention ratio: 0.76. Before you quote it, two cautions."),
            ("bullets", [
                "<b>The peak came late.</b> The analyzer's peak, 24.1 tok/s, is a burst of three "
                "fast iterations about 30 minutes in, not the opening of the run, which never "
                "went above 23.6. The best rolling-median window rules out a single lucky "
                "iteration but not a short burst. So the retention ratio comes out lower than "
                "the opening-to-closing figure (0.76 against 0.81). Read the chart, not only the "
                "ratio.",
                "<b>The two runs took different paths.</b> The Pixel ran in the headless "
                "harness. The Honor ran from the app with its live chart on, because Honor's "
                "power manager defeats the harness (see chapter 10). "
                "<font face='Courier'>executionPath</font> and "
                "<font face='Courier'>liveChartEnabled</font> record this in each document. "
                "The chart's observer effect works against the Honor, so if anything its lead "
                "is understated.",
            ]),

            ("h2", "A collapse that is not thermal: the Honor GPU, screen off and on"),
            ("p",
             "Now the Honor's GPU, with the same model and prompt, run twice: once with the "
             "screen off, as the default policy requires, and once with the app holding the "
             "screen on at minimum brightness (screen policy ON_DIMMED)."),
            ("figure", "honor-gpu-screen-on-off.png",
             "Gemma 3 1B on the Honor Magic V3 GPU. Screen off: about 31 tok/s until 4.8 "
             "minutes, then 3.3 tok/s for the rest of the run, with thermal status NONE "
             "throughout and the battery cooling from 33 to 28 °C. The run reached only 64 of "
             "150 iterations before its time limit. Screen on: a median of 30.9 tok/s for all "
             "150 iterations in 20 minutes, with thermal status SEVERE from 2.3 minutes and "
             "the battery at 40 °C. Both runs started warmer than the gate allows and are "
             "PRECONDITIONS_UNMET."),
            ("p",
             "Here is the puzzle. If heat were the cause, the hotter run should be the slower "
             "one. The opposite happened. The cooler run lost 90% of its throughput in one "
             "iteration and never recovered; the hotter run kept going. So something other "
             "than temperature is at work, and it hit everything at once: prefill fell from "
             "about 430 to about 50 tok/s at the same moment, and time-to-first-token rose "
             "from 0.3 s to 2–3 s. The whole GPU workload slowed, not one phase of it."),
            ("p",
             "What we cannot see is the mechanism. The battery history shows no event at that "
             "moment, and the GPU clock is not readable on this device, so I can't confirm the "
             "cause. The pattern is still telling: a cap that arrives a fixed time after the "
             "screen goes off, whatever the temperature. That points to the vendor's power "
             "management, not to thermal control."),
            ("p",
             "The screen-on run shows the thermal controller doing its job. Its bursts back to "
             "about 40 tok/s, at roughly 3.5, 6, 8, 10.5 and 19 minutes, each follow a step "
             "from SEVERE down to MODERATE in the thermal timeline. The platform relaxes its "
             "limit, the GPU speeds up, the device heats, and the limit comes back. A "
             "thermostat, in other words."),
            ("note",
             "<b>Neither number is the Honor's GPU benchmark.</b> Both runs are "
             "PRECONDITIONS_UNMET, and the screen-on run adds the display's own heat. What the "
             "pair does show is that on this phone, a screen-off result measures the power "
             "manager, not the GPU. Repeat both from a cold start before you quote a figure."),
        ],
    },

    # ------------------------------------------------------------------ 10
    {
        "title": "Running the headless harness",
        "blocks": [
            ("p",
             "This is the path that produces publishable numbers. It is a single "
             "self-instrumenting APK with no launcher activity. <font face='Courier'>am "
             "instrument</font> starts it, the benchmark runs, a document is written, and the "
             "process exits. No UI process, no rendering load, and no second test process to "
             "disturb the measurement."),

            ("h2", "Install and run"),
            ("code",
             "./gradlew :harness:installDebug\n"
             "\n"
             "RUNNER=com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner\n"
             "DIR=/sdcard/Android/data/com.thermalbench.harness/files\n"
             "\n"
             "# Let the harness create specs/, models/ and results/ itself.\n"
             "adb shell am instrument -w -e prepare true $RUNNER\n"
             "\n"
             "adb push my-model.litertlm $DIR/models/\n"
             "adb push my-spec.json $DIR/specs/\n"
             "\n"
             "adb shell am instrument -w \\\n"
             "  -e specPath $DIR/specs/my-spec.json \\\n"
             "  -e outputDir $DIR/results \\\n"
             "  $RUNNER\n"
             "\n"
             "adb pull $DIR/results/"),
            ("warn",
             "Don't create those directories with <font face='Courier'>adb shell mkdir</font>. "
             "It is the obvious move and the wrong one. A directory adb makes there belongs to "
             "shell: the harness cannot read a spec pushed into it, and adb cannot pull results "
             "the harness writes into it. <font face='Courier'>chmod</font> does not fix it. "
             "The harness checks who owns each directory before any device time is spent, and "
             "names the commands that fix it."),

            ("h2", "Phones that freeze background apps"),
            ("p",
             "Some vendors add their own power manager on top of Android's. OnePlus, Oppo and "
             "Realme phones run one (HANS) that freezes background processes about 25 seconds "
             "after the screen changes state. A frozen harness does not fail. The run simply "
             "stops mid-generation and never finishes. Adding the harness to the "
             "battery-optimisation whitelist does not prevent it."),
            ("p",
             "The harness protects itself by holding a foreground service for the whole run, "
             "which these managers leave alone. Android grants that service only while the app "
             "is not background-restricted, and on these phones an app is restricted unless "
             "someone has explicitly allowed it. To allow it, run this once per phone, and "
             "again after you uninstall the harness:"),
            ("code",
             "adb shell cmd appops set com.thermalbench.harness RUN_ANY_IN_BACKGROUND allow"),
            ("p",
             "If the setting is missing, the harness logs a warning with this command at the "
             "start of the run (<font face='Courier'>adb logcat -s ThermalBench</font>). The "
             "app needs no such step: it starts its foreground service from the screen you "
             "tap."),
            ("p",
             "Honor phones go further. Their power manager cancels an app's partial wake lock "
             "about two seconds after the screen goes off on battery, and gives it back when a "
             "charger is connected. You can see it in the battery history "
             "(<font face='Courier'>dumpsys batterystats --history</font>, "
             "<font face='Courier'>-longwake</font> and <font face='Courier'>-running</font> "
             "entries). With its wake lock gone, the harness stalls at the precondition gate "
             "and the run never starts. The app, granted the same "
             "<font face='Courier'>RUN_ANY_IN_BACKGROUND</font> permission and exempted from "
             "battery optimisation, keeps its lock and completes."),
            ("p",
             "Completing is not the same as measuring the chip. On the same Honor, a GPU run "
             "with the screen off slowed tenfold after about five minutes while the phone was "
             "cooling; with the screen held on, it did not (chapter 9). On Honor, use the app. "
             "When a GPU result looks wrong, run it again with "
             "<b>Keep the screen on, dimmed</b>. If the two differ, the screen-off number "
             "belongs to the power manager."),

            ("h2", "Instrumentation arguments"),
            ("table", [
                ["Argument", "Meaning"],
                ["specPath", "Path to a spec document on the device. Required unless preparing."],
                ["prepare", "Only create the harness's directories and report where they are."],
                ["bundles", "With prepare: model bundle folders to create under models/, comma-separated."],
                ["outputDir", "Where result documents are written. Defaults to the app's external files directory."],
                ["iterations", "Overrides the spec's measured iteration count. For smoke tests only."],
                ["ignorePreconditions", "Runs once the gate times out instead of failing. The result is marked "
                                        "PRECONDITIONS_UNMET. The full wait still happens first."],
                ["preconditionTimeoutSeconds", "Overrides the spec's maximum wait at the gate. With "
                                               "ignorePreconditions, a short value makes a quick smoke test."],
            ], [30, 70], (0,)),
            ("p",
             "The harness derives the spec identity from the spec it actually runs, not from "
             "the <font face='Courier'>specId</font> written in the file. A hand-edited spec, "
             "or one whose iteration count you overrode, is recorded under its own identity. "
             "If the file's identity was stale, the harness logs a warning."),

            ("h2", "Reading the result"),
            ("p",
             "The harness reports its headline numbers through the instrumentation status "
             "bundle, so a fleet script can read them without scraping logcat:"),
            ("code",
             "INSTRUMENTATION_STATUS: thermalbench.validity=VALID\n"
             "INSTRUMENTATION_STATUS: thermalbench.retentionRatio=0.4713…\n"
             "INSTRUMENTATION_STATUS: thermalbench.peakDecodeTokensPerSecond=16.6…\n"
             "INSTRUMENTATION_STATUS: thermalbench.measuredIterations=50\n"
             "INSTRUMENTATION_STATUS: thermalbench.document=/sdcard/…/result.json"),

            ("h2", "Across a fleet"),
            ("code",
             "./gradlew :tools:cli:installDist\n"
             "cd tools/cli/build/install/thermalbench/bin\n"
             "\n"
             "./thermalbench devices               # what is attached, and is the harness on it\n"
             "./thermalbench spec > my-spec.json   # a template to edit\n"
             "./thermalbench run --spec my-spec.json --screen-off\n"
             "./thermalbench pull --out results/"),
            ("p",
             "<font face='Courier'>--screen-off</font> matters more than it looks. Screen state "
             "is a precondition. Set it from the orchestrator and every device in the fleet "
             "runs under the same state, not whatever state each one happened to be left in."),
        ],
    },

    # ------------------------------------------------------------------ 11
    {
        "title": "The operator console",
        "blocks": [
            ("p",
             "The Compose app is optional by design. It is there for interactive use, and the "
             "numbers it shows come from the same engine the harness runs."),

            ("h2", "Screens"),
            ("table", [
                ["Screen", "Purpose"],
                ["Home", "Run history with retention ratio and validity per run."],
                ["Spec composer", "Pick model, backend, delegate and protocol; validate before committing."],
                ["Precondition gate", "Live view of what is blocking the run and how close the device is."],
                ["Live run", "Decay chart, thermal strip, throttle state, abort control."],
                ["Result detail", "The full degradation profile, validity verdict, and export."],
                ["Compare", "Backends on this device, or this device against imported results."],
                ["Leaderboard", "Community results, with an explicit consent flow before any upload."],
            ], [26, 74]),

            ("h2", "The run is a foreground service, not WorkManager"),
            ("warn",
             "Runs last tens of minutes and must survive Doze and screen-off. WorkManager may "
             "defer or reschedule work based on system conditions, which is <b>precisely the "
             "confound this tool exists to measure</b>. So the runner is a foreground service "
             "of type <font face='Courier'>specialUse</font>, with that justification in the "
             "manifest."),
            ("p",
             "The service receives only a spec identifier and re-resolves everything else from "
             "persistence, so restarting it is idempotent. It is deliberately "
             "<font face='Courier'>START_NOT_STICKY</font>. A run the system killed has a hole "
             "in its thermal history. Restart it silently and you get a document claiming to "
             "be one continuous measurement when it is really two."),
            ("p",
             "A wake lock is held for the whole run and released in a "
             "<font face='Courier'>finally</font> block. It affects power draw, so the result "
             "document records that it was held."),

            ("h2", "Screen-on runs"),
            ("p",
             "The composer's <b>Keep the screen on, dimmed</b> switch writes screen policy "
             "<font face='Courier'>ON_DIMMED</font> into the spec. The gate then waits for the "
             "screen to be on instead of off. While the run waits or runs, the app sets "
             "<font face='Courier'>FLAG_KEEP_SCREEN_ON</font> and forces the window to 1% "
             "brightness, so the run cannot time out into a screen-off one halfway through. "
             "One catch: the app has to stay in front for that to hold."),
            ("p",
             "Screen-off stays the default because the panel is a heat source. Think of the "
             "switch as a diagnostic. On phones whose power manager holds back screen-off work, "
             "two runs that differ only in this setting separate the power manager's effect "
             "from the chip's. The result document's <font face='Courier'>screenPolicy</font> "
             "field says which kind a result is."),

            ("h2", "The live chart caveat"),
            ("p",
             "In the app path, the live chart is the single largest source of observer effect. "
             "Three things reduce it. Chart recomposition is throttled to roughly 1 Hz. The "
             "series sit in a bounded ring buffer, so a long run cannot grow memory without "
             "limit. And a minimal-UI toggle, available while a run is in progress, turns the "
             "charts off entirely."),
            ("note",
             "<b>A measurement worth publishing.</b> Run the same spec twice on one device, "
             "once charted and once headless, and publish the delta. It is a small, credible "
             "result in its own right, and it answers the most obvious reviewer objection "
             "before anyone raises it. The result document records "
             "<font face='Courier'>executionPath</font> and "
             "<font face='Courier'>liveChartEnabled</font> precisely so you can make that "
             "comparison after the fact."),
        ],
    },

    # ------------------------------------------------------------------ 12
    {
        "title": "Result documents and canonical serialization",
        "blocks": [
            ("p",
             "A finished run is stored twice, on purpose. The canonical JSON document is the "
             "shareable artifact and the authoritative copy. The database holds a summary "
             "index, so the history list does not have to parse every document, plus the "
             "in-flight samples that let you recover a crashed run."),

            ("h2", "What canonical means here"),
            ("bullets", [
                "Object members sorted by key, so ordering is never a source of difference.",
                "No insignificant whitespace.",
                "Floating-point numbers written as the shortest decimal that reparses to the identical double.",
                "Non-finite numbers become null, since JSON cannot express them.",
            ]),
            ("p",
             "Encoding is <b>lossless</b>: decode a document, re-encode it, and you get the same "
             "bytes. That is what makes a digest a stable identity."),

            ("warn",
             "<b>A caught bug worth knowing about.</b> The encoder originally rounded to 15 "
             "significant digits. IEEE 754 doubles need up to 17, so a retention ratio of "
             "14.2 ÷ 17.5 decoded to a <i>different number</i> than it encoded. The round-trip "
             "property test caught it. Rounding to a fixed digit count is not cosmetic. It "
             "silently changes measurements as they pass through the file format."),

            ("h2", "Why get this right now"),
            ("p",
             "Canonical serialization is the one piece that is expensive to retrofit and free "
             "to get right up front. That is why it has property tests rather than examples. If "
             "ThermalBench later grows into a conformance toolkit, this code is reused verbatim "
             "while most other things change shape."),

            ("h2", "Crash safety"),
            ("p",
             "Samples are flushed to the database in batched transactions every few iterations, "
             "not held in memory until the end. The batching does two jobs at once. It keeps "
             "database I/O off the critical path of an iteration, where its own heat and "
             "latency would contaminate the measurement. And it limits what a crash can destroy "
             "to less than one batch. A crash at iteration 47 keeps the 46 that completed."),

            ("h2", "Export"),
            ("p",
             "Every run writes a JSON document and a CSV sidecar with one row per iteration. "
             "Reviewers will actually open the CSV."),
        ],
    },

    # ------------------------------------------------------------------ 13
    {
        "title": "Offline analysis and comparison",
        "blocks": [
            ("code",
             "./gradlew :tools:analysis-cli:installDist\n"
             "cd tools/analysis-cli/build/install/thermalbench-analyze/bin\n"
             "\n"
             "./thermalbench-analyze summary   results/\n"
             "./thermalbench-analyze compare   results/ --csv cross-device.csv\n"
             "./thermalbench-analyze reanalyze results/\n"
             "./thermalbench-analyze plot      results/ --out decay.png"),

            ("table", [
                ["Command", "What it does"],
                ["summary", "Headline numbers per document: peak, steady state, retention, validity."],
                ["compare", "Cross-device table sorted by retention ratio; optional CSV output."],
                ["reanalyze", "Recomputes the profile from recorded samples and flags any disagreement."],
                ["plot", "Speed and battery temperature over time as a PNG, each run labelled where its line ends. The same image the app's \"Share chart image\" produces."],
            ], [20, 80], (0,)),

            ("h2", "reanalyze is a correctness tool"),
            ("p",
             "It recomputes each profile from the stored samples and reports where the new "
             "value differs from the one recorded at run time. A disagreement means the "
             "analysis code has changed since the run, and that is exactly when previously "
             "published numbers may need a correction. It is meant to be loud about it."),

            ("h2", "Guardrails in the comparison table"),
            ("bullets", [
                "Invalid runs are excluded by default and included only with <font face='Courier'>--include-invalid</font>, which shows their verdict.",
                "A table containing more than one spec identifier prints a warning, because only runs of the same spec are comparable.",
                "A document that fails to parse is reported by name, not skipped in silence.",
                "Plots are anchored at zero on the y-axis, so a mild decline cannot be autoscaled into a cliff.",
            ]),
            ("p",
             "The figure in chapter 9 came from <font face='Courier'>plot</font>, run on the "
             "two Pixel 6 result files. <font face='Courier'>--width</font> and "
             "<font face='Courier'>--height</font> set its size (1600 × 1000 by default)."),
        ],
    },

    # ------------------------------------------------------------------ 14
    {
        "title": "The leaderboard and consent",
        "blocks": [
            ("p",
             "Submission is opt-in, per run, and gated on explicit consent. The client is "
             "written so you can read every field that can leave the device on one screen of "
             "code."),

            ("h2", "What is sent"),
            ("bullets", [
                "The analysis summary: peak, steady state, retention ratio, throttle timing, drift.",
                "The spec identity and digest, the model name and quantization, the backend and delegate.",
                "The SoC manufacturer and model, the Android release and SDK level.",
                "An anonymous per-install device identifier, derived by hashing and truncation.",
                "Whether the run was headless or charted, and whether preconditions were met.",
            ]),

            ("h2", "What is never sent"),
            ("bullets", [
                "The prompt or any generated text.",
                "The device model name, unless that box is separately ticked.",
                "Per-iteration data, unless that box is separately ticked.",
                "Anything at all when consent has not been given.",
            ]),

            ("note",
             "Each field is a separate choice, not one blanket agreement, and only runs marked "
             "<font face='Courier'>VALID</font> may be submitted at all. A leaderboard full of "
             "runs from warm, charging phones would be worse than no leaderboard."),
        ],
    },

    # ------------------------------------------------------------------ 15
    {
        "title": "Building and verifying",
        "blocks": [
            ("h2", "Toolchain"),
            ("table", [
                ["Component", "Version", "Why this one"],
                ["AGP", "8.7.3", "Android Studio Ladybug refuses to open newer AGP versions."],
                ["Kotlin", "2.2.21", "Matches the Kotlin that LiteRT-LM 0.16.1 was compiled with."],
                ["compileSdk / targetSdk", "36", "Needs android.suppressUnsupportedCompileSdk=36 under AGP 8.7."],
                ["minSdk", "29", "The thermal status API's floor. Below it there is no primary signal."],
                ["LiteRT-LM", "0.16.1", "0.17.0 carries Kotlin 2.4 metadata a 2.2 compiler cannot read."],
                ["OkHttp", "5.4.0 (strict)", "5.5.0 requires compileSdk 37. Strict, so Ktor cannot raise it."],
            ], [26, 18, 56]),

            ("h2", "Common commands"),
            ("code",
             "./gradlew build                    # everything: compile, test, lint, R8, coverage\n"
             "./gradlew verifyDependencyRules    # the layering guarantee, on its own\n"
             "./gradlew apiCheck                 # the published core API surface\n"
             "./gradlew apiDump                  # accept an intentional API change\n"
             "./gradlew detekt                   # static analysis\n"
             "./gradlew koverHtmlReport          # coverage"),

            ("h2", "Two environment notes that cost real time"),
            ("bullets", [
                "<b>Lint version.</b> The lint checks bundled inside androidx libraries are built against a newer Kotlin Analysis API than AGP 8.7.3's lint, and they crash with \"Found class Ka…, but interface was expected\". <font face='Courier'>android.experimental.lint.version</font> pins a newer standalone lint, so every check stays on instead of being switched off one at a time.",
                "<b>Intermittent file lock.</b> On Windows, a full build occasionally fails with a file lock on <font face='Courier'>bundleLibCompileToJarDebug</font>. It is environmental, not a code fault. Retry, and if it happens again, run <font face='Courier'>./gradlew --stop</font> first.",
            ]),

            ("h2", "APK size"),
            ("p",
             "Application modules package <font face='Courier'>arm64-v8a</font> only. That "
             "covers every device worth thermally profiling, and it avoids pushing three unused "
             "architectures to a fleet over ADB. For an emulator smoke test, add "
             "<font face='Courier'>-Pthermalbench.includeX86=true</font>. The ExecuTorch "
             "runtime is deliberately not linked while its adapter is a stub: shipping a "
             "runtime nothing can call would add 8 MB to every APK for no benefit."),
        ],
    },

    # ------------------------------------------------------------------ 16
    {
        "title": "Where to start, and where this goes",
        "blocks": [
            ("h2", "Build order"),
            ("p",
             "The order is deliberate. The project becomes useful at step 4 and publishable at "
             "step 6, long before it is complete."),
            ("steps", [
                "<font face='Courier'>core/domain</font> and <font face='Courier'>core/schema</font>, with canonical serialization and property tests. No UI, no device.",
                "<font face='Courier'>data/telemetry</font>, thermal first. Verify against a known-hot workload.",
                "One backend — <font face='Courier'>backend-litert</font>.",
                "<font face='Courier'>harness</font> producing a result document. <b>The project is useful here.</b>",
                "<font face='Courier'>core/analysis</font> — decay fit, steady-state detection, retention ratio.",
                "<font face='Courier'>tools/analysis-cli</font> across three or four devices. <b>Stop and write the preprint.</b>",
                "<font face='Courier'>app</font>, with the charted-versus-headless delta measured and published.",
                "Second and third backends, then <font face='Courier'>tools/cli</font> fleet orchestration.",
                "Leaderboard last: it needs traffic to mean anything, and traffic follows the paper.",
            ]),

            ("h2", "Growing into a conformance toolkit"),
            ("p",
             "If this later grows into an evidence and conformance toolkit, the seams are "
             "already cut."),
            ("table", [
                ["Today", "Becomes"],
                ["BenchmarkSpec", "A run manifest — add signing, no structural change."],
                ["BenchmarkResult", "One section of an evidence bundle."],
                ["DegradationProfile", "The sustained-performance-envelope evidence category."],
                ["ResultRepository", "An evidence repository with signature verification added."],
                ["Canonical serialization", "Reused verbatim. It is already the hard part."],
            ], [34, 66], (0,)),

            ("h2", "The habit worth keeping"),
            ("note",
             "Wherever this codebase could have guessed, it records an absence instead: a null "
             "frequency reading, an absent decay fit, a steady state that never settled, a "
             "backend that will not load. That habit is the whole reason a number from this "
             "tool is worth quoting. Keep it in anything you add."),
        ],
    },
]
