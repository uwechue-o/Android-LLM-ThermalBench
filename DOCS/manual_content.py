"""Prose for the ThermalBench developer manual.

Kept apart from `build_manual.py` so the writing can be edited without touching
layout code. Each section is a title plus a list of typed blocks.
"""

TITLE = "ThermalBench"
SUBTITLE = "Developer Manual — a thermal-aware sustained-load inference benchmark for Android"
VERSION = "0.1.0"

DOCUMENT = [
    # ------------------------------------------------------------------ 1
    {
        "title": "Why this tool exists",
        "blocks": [
            ("lead",
             "Every mobile inference benchmark reports peak throughput. Peak throughput is "
             "also the number a phone stops delivering after about ninety seconds. "
             "ThermalBench measures what happens after that: how throughput decays under "
             "sustained load, when the operating system begins throttling, and what "
             "fraction of peak the device can actually hold."),

            ("p",
             "The gap this fills is documented. A March 2026 arXiv paper benchmarking a "
             "4-bit Qwen 2.5 1.5B across mobile hardware found that thermal management, not "
             "peak compute, is the primary constraint on mobile platforms. The iPhone 16 Pro "
             "lost nearly half its throughput within two iterations. The Galaxy S24 Ultra hit "
             "an OS-enforced GPU frequency floor that terminated inference outright. The same "
             "paper notes that MLPerf Inference measures peak performance without tracking "
             "thermal throttling or sustained-workload behaviour."),

            ("p",
             "Existing Android LLM applications report load time, memory use and a thermal "
             "state as a snapshot. Nobody has shipped the sustained degradation curve as "
             "tooling. That curve is what ThermalBench produces."),

            ("h2", "The headline number"),
            ("p",
             "The <b>retention ratio</b> is steady-state throughput divided by peak "
             "throughput. It answers the question a buyer, an app developer and a reviewer "
             "all actually have: of the performance this device advertises, how much of it "
             "survives a real workload? Every design decision in this manual exists to make "
             "that one number defensible."),

            ("note",
             "<b>What this tool is.</b> A measuring instrument that happens to have an app "
             "attached, not an app that happens to measure. When the two goals conflict, "
             "measurement integrity wins, and the code says so at each point where it does."),
        ],
    },

    # ------------------------------------------------------------------ 2
    {
        "title": "Three constraints that shape everything",
        "blocks": [
            ("p",
             "Ordinary Clean Architecture explains most of this codebase. Three constraints "
             "explain the rest, and they are what separate a credible instrument from a "
             "phone benchmark app. Every unusual decision later in this manual traces back "
             "to one of them."),

            ("h2", "1. The observer effect is real and large"),
            ("p",
             "A Compose UI, live charts, logging and database writes all consume CPU and "
             "generate heat on the same die being measured. A benchmark whose own "
             "instrumentation perturbs the thermal envelope produces numbers nobody should "
             "trust."),
            ("p",
             "So the primary execution path is <b>headless</b>: an instrumentation harness "
             "with no UI process at all. The Compose app is a convenience wrapper around a "
             "harness that runs without it. The result document records which path produced "
             "the numbers and whether live charts were on, so the two are never silently "
             "compared."),

            ("h2", "2. Preconditions are part of the measurement"),
            ("p",
             "A run starting from a warm device measures something different from one "
             "starting cold. Charging state, screen state, battery level and prior workload "
             "all change the answer. These are gated before a run starts and recorded in the "
             "result."),
            ("warn",
             "A run that starts without satisfying its policy is not a slightly worse run. "
             "It is an <b>invalid</b> run, and the result document says so with "
             "<font face='Courier'>PRECONDITIONS_UNMET</font>. The tool never silently "
             "proceeds and never adjusts a number to compensate."),

            ("h2", "3. Time must be monotonic"),
            ("p",
             "Every duration comes from "
             "<font face='Courier'>SystemClock.elapsedRealtimeNanos()</font>. Wall-clock time "
             "appears exactly once per run, as a label for the document. A network time "
             "correction in the middle of a forty-minute run would silently corrupt a decay "
             "curve, and the corruption would be invisible in the output."),
            ("p",
             "<font face='Courier'>elapsedRealtimeNanos</font> is chosen over "
             "<font face='Courier'>System.nanoTime()</font> deliberately: it keeps counting "
             "through deep sleep, which matters for runs that last tens of minutes with the "
             "screen off."),
        ],
    },

    # ------------------------------------------------------------------ 3
    {
        "title": "Module topology",
        "blocks": [
            ("p",
             "Sixteen Gradle modules. Dependencies point inward only, and the direction is "
             "enforced by a convention plugin rather than by review."),

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
             "<font face='Courier'>app</font>. If it does, the headless path is lost and with "
             "it the credibility of every number the tool produces. This is enforced "
             "mechanically by <font face='Courier'>DependencyRulesPlugin</font>, which fails "
             "the build at configuration time."),
            ("p", "Adding that dependency produces exactly this, and no APK:"),
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
             "The harness and the app both need the same run engine, assembled from the same "
             "backend registry, telemetry sources and repositories. Neither can depend on the "
             "other: the harness must never see the app, and both are Android application "
             "modules, which Gradle cannot make one depend on the other anyway."),
            ("p",
             "So the dependency-injection wiring lives in one library that both consume. It "
             "is the only module permitted to reach sideways across the data layer, and the "
             "convention plugin allows that for this module alone. The alternative, "
             "duplicating the wiring in two application modules, is exactly the kind of drift "
             "the plugin exists to prevent."),

            ("h2", "Why core/analysis is pure Kotlin"),
            ("p",
             "The same decay-fitting code powers the live on-device chart and the offline "
             "cross-device report. Two implementations of the same statistics is how a "
             "discrepancy ends up in a published paper. There is one, and "
             "<font face='Courier'>tools/analysis-cli</font> exists partly to prove it by "
             "re-deriving on a workstation the numbers a phone reported."),
        ],
    },

    # ------------------------------------------------------------------ 4
    {
        "title": "How a run actually works",
        "blocks": [
            ("p",
             "A benchmark run moves through five phases. Understanding them is enough to "
             "read almost any part of the codebase."),

            ("steps", [
                "<b>Validate the spec.</b> <font face='Courier'>ValidateSpecUseCase</font> "
                "checks the spec against the resolved backend's capabilities and rejects "
                "anything that cannot produce comparable results — before any device time is "
                "spent.",

                "<b>Wait at the gate.</b> <font face='Courier'>AwaitPreconditionsUseCase</font> "
                "emits a state stream until every condition has held continuously for the "
                "stabilization window, or the maximum wait elapses.",

                "<b>Load the model.</b> The backend adapter verifies the file against the "
                "digest in the spec, then loads it. Load time is recorded.",

                "<b>Warm up, then measure.</b> Warmup iterations are discarded from the "
                "analysis and still written to the document. Measured iterations follow "
                "back-to-back by default.",

                "<b>Analyze and write.</b> Samples become a "
                "<font face='Courier'>DegradationProfile</font>, and the whole run becomes a "
                "canonical JSON document plus a CSV sidecar.",
            ]),

            ("h2", "The sampling loop"),
            ("p",
             "Telemetry sampling runs on its own dedicated thread and its own coroutine, "
             "entirely independent of the inference loop. If they shared a dispatcher, a long "
             "generation call could delay a thermal sample and leave a gap in precisely the "
             "series being measured."),
            ("p",
             "Both write into one bounded channel consumed by a single collector that owns "
             "ordering and assembles the result. That is the only place run state is "
             "mutated, so there is no lock and no interleaving to reason about."),

            ("code",
             "  ┌─ inference thread ──┐\n"
             "  │ prefill → decode ×N │──┐\n"
             "  └─────────────────────┘  │   ┌──────────────────┐\n"
             "                           ├──▶│ bounded channel  │──▶ single collector\n"
             "  ┌─ telemetry thread ──┐  │   └──────────────────┘    owns ordering,\n"
             "  │ poll / listen / pwr │──┘                           assembles result\n"
             "  └─────────────────────┘"),
            ("caption",
             "Two producers, one consumer. Neither producer can starve the other, and the "
             "consumer is the only writer of run state."),

            ("h2", "Two independent thermal streams"),
            ("p",
             "Polled samples give a regular time series suitable for plotting. Listener "
             "transitions give exact throttle-onset timestamps that polling would blur. Both "
             "are recorded, and the analysis prefers listener events for onset timing, "
             "falling back to iteration boundaries only when the platform delivered none."),
        ],
    },

    # ------------------------------------------------------------------ 5
    {
        "title": "The spec: what makes runs comparable",
        "blocks": [
            ("p",
             "A <font face='Courier'>BenchmarkSpec</font> is the complete, immutable "
             "description of a run. Anything that could change the numbers belongs in it."),

            ("p",
             "Its identity is the SHA-256 of its own canonical serialization. Two people who "
             "write the same spec get the same identifier, so results are comparable by "
             "construction rather than by convention. The model path and the run label are "
             "excluded from that hash, because they differ between devices without changing "
             "what is measured."),

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
             "If one backend stops on end-of-sequence and another on a fixed token count, "
             "their tokens-per-second figures are not comparable. Output length is fixed "
             "across all backends and recorded. A spec whose backend cannot honour a "
             "token-count stop is <b>rejected</b> by the validator before any device time is "
             "spent."),

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
             "Too few measured iterations is a warning rather than a rejection. A short smoke "
             "run is a legitimate thing to want; the analysis marks the result "
             "<font face='Courier'>INSUFFICIENT_DATA</font> rather than the validator "
             "refusing to run it."),

            ("h2", "Where model files have to live"),
            ("p",
             "ThermalBench ships no models. It measures whatever you point it at, which is why "
             "the model's digest is part of the run identity."),
            ("warn",
             "Both the app and the harness target a scoped-storage SDK, so neither can open an "
             "arbitrary path under <font face='Courier'>/sdcard</font> however the manifest is "
             "written. Models, specs and results all live in the relevant app's own external "
             "files directory. It needs no permission and adb can still reach it."),
            ("table", [
                ["Consumer", "Directory"],
                ["Headless harness", "/sdcard/Android/data/com.thermalbench.harness/files/models"],
                ["App, debug build", "/sdcard/Android/data/com.thermalbench.debug/files/models"],
            ], [26, 74], (1,)),
            ("p",
             "The app lists whatever sits in that directory and lets you tap one, which sets the "
             "format from the file extension rather than leaving it to be typed. The extension is "
             "the only thing identifying a format here, so a file with an unrecognised one is "
             "listed as ignored rather than guessed at. The fleet tool places models with:"),
            ("code", "thermalbench push-model --model gemma-3n-e2b-it-q4.litertlm"),

            ("h2", "Generating a spec"),
            ("code",
             "./gradlew :tools:cli:installDist\n"
             "./tools/cli/build/install/thermalbench/bin/thermalbench spec > my-spec.json"),
            ("p",
             "The template comes out with a fixed reference prompt, its hash already "
             "computed, and the spec identity already derived. Edit the model path and the "
             "backend, then re-derive the identity by loading and re-saving it through the "
             "codec."),
        ],
    },

    # ------------------------------------------------------------------ 6
    {
        "title": "The precondition gate",
        "blocks": [
            ("p",
             "The gate is what makes two runs comparable at all. It is a policy plus a "
             "stabilization window, and it emits a live state stream so an operator can see "
             "what is blocking a run."),

            ("table", [
                ["Condition", "Default", "Why"],
                ["Thermal status", "NONE", "A warm start measures a different thing than a cold one."],
                ["Thermal headroom", "≥ 0.5 margin", "Continuous 0..1 signal, far richer than the coarse status enum."],
                ["Unplugged", "required", "Charging is a heat source in its own right."],
                ["Battery", "≥ 50%", "A low battery triggers its own independent throttling."],
                ["Screen", "OFF", "Must be fixed rather than incidental; the panel is a heat source."],
                ["Stabilization", "30 s", "Conditions must hold continuously, not merely be true once."],
                ["Maximum wait", "15 min", "Beyond this the gate times out rather than waiting forever."],
            ], [24, 20, 56]),

            ("note",
             "<b>The window restarts on any failure.</b> Holding for twenty seconds, briefly "
             "charging, then holding again is not a stable device. Treating it as one would "
             "put a warm start into a run that claims to be cold."),

            ("h2", "Following the cool-down with the screen off"),
            ("p",
             "The default policy wants the screen off, and turning it on to check progress "
             "would itself fail the screen condition and restart the window. In the app, the "
             "runner service therefore reports the cool-down in two ways that need no screen:"),
            ("bullets", [
                "<b>Notification.</b> The run's ongoing notification shows the margin against "
                "its target, for example <font face='Courier'>Cooling: margin 0.27 of 0.50"
                "</font>, with a progress bar toward the target. It is visible on the lock "
                "screen.",
                "<b>Spoken cues.</b> While the thermal conditions are unmet, the phone reads "
                "the margin aloud every 20 seconds: <i>\"Current thermal margin is 0.27.\"</i> "
                "When only the thermal status is blocking, it says the status instead. Two "
                "one-off cues mark progress: <i>\"halfway cooled\"</i>, spoken only when the "
                "device started at least partly warm, and <i>\"cool enough\"</i>, which also "
                "says what is still blocking, or roughly when the run will start.",
            ]),
            ("p",
             "Every displayed and spoken margin goes through one formatter, "
             "<font face='Courier'>formatThermalMargin</font>. It shows two decimals and "
             "rounds down, so a margin is never presented as reaching a threshold it has "
             "not reached: 0.499 reads 0.49, not 0.50. The voice can still lag the screen "
             "by up to 20 seconds while the margin is moving."),
            ("p",
             "Speech goes out as navigation guidance, like a satnav prompt. It plays with the "
             "ringer on silent and follows the media volume. The speech engine is started "
             "only when there is something to say and is shut down before the first "
             "iteration, so nothing of it is resident while the device is measured. The run "
             "itself is silent, apart from a closing <i>\"run finished\"</i>, with the "
             "retention percentage, or a notice that it stopped with an error. A device with "
             "no speech engine stays silent rather than failing the run."),
            ("p",
             "Every sentence spoken is also written to logcat, so what the phone said can be "
             "checked afterwards:"),
            ("code", "adb logcat -s ThermalBench | grep said"),
            ("p",
             "The headless harness does not speak. It is driven from a computer, which "
             "already sees the same state as log lines."),

            ("h2", "Overriding the gate"),
            ("p",
             "Sometimes a run must happen on a device that cannot satisfy the policy. The "
             "harness accepts an explicit override:"),
            ("code", "-e ignorePreconditions true"),
            ("p",
             "The run then proceeds and the result is permanently marked "
             "<font face='Courier'>PRECONDITIONS_UNMET</font>, with the specific unmet "
             "conditions listed in the document. The leaderboard refuses such a result, the "
             "comparison tables exclude it by default, and the app shows a red banner. The "
             "override changes what the tool does, never what it reports."),

            ("h2", "Handling unavailable signals"),
            ("p",
             "Some vendors return NaN for thermal headroom. That becomes "
             "<font face='Courier'>null</font>, the headroom check is skipped, and the "
             "absence is recorded. The alternative, treating an unavailable reading as a "
             "failure, would make the tool unusable on those devices; treating it as a pass "
             "would be a quiet lie."),
        ],
    },

    # ------------------------------------------------------------------ 7
    {
        "title": "Backends",
        "blocks": [
            ("p",
             "Every inference runtime sits behind the "
             "<font face='Courier'>InferenceBackend</font> port. Adapters live in "
             "<font face='Courier'>data/backend-*</font> and are assembled into a registry by "
             "dependency injection, so adding a module adds a backend."),

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
             "gives an unambiguous time-to-first-token, a real prefill rate, and a decode "
             "rate measured over a token count fixed by the spec rather than by the model's "
             "end-of-sequence behaviour. Nothing has to be inferred."),
            ("p",
             "A fresh session is created for every iteration. Reusing one would let iteration "
             "two inherit iteration one's key-value cache, which means iteration two would be "
             "measuring a longer sequence — a different workload wearing the same label."),

            ("h2", "How MediaPipe is handled honestly"),
            ("p",
             "MediaPipe stops on end-of-sequence as well as on its token budget, so it cannot "
             "be told to produce exactly N tokens. Rather than let that quietly make its "
             "numbers incomparable, the adapter timestamps every streamed token and ends the "
             "measurement window at the Nth one. Tokens the runtime emits afterwards fall "
             "outside the window. A generation that stopped early is reported with the count "
             "it actually reached, which the analysis records as a shorter iteration rather "
             "than a faster one."),

            ("h2", "Unavailable backends stay visible"),
            ("note",
             "A backend that cannot run on a device is still registered and still appears in "
             "the list, with the reason attached: a missing native library, an unsupported "
             "delegate, a phase-2 adapter. <b>A gap in coverage is a finding, not something "
             "to hide from a comparison table.</b> The same contract covers a device without "
             "an NPU and an adapter nobody has written yet."),

            ("h2", "Building the llama.cpp backend"),
            ("p",
             "The native half needs the NDK, CMake and a llama.cpp checkout, so it is off by "
             "default and the project builds on a machine that has none of them. Without it "
             "the Kotlin adapter still compiles and reports the backend unavailable."),
            ("code",
             "git clone --depth 1 --branch b11879 \\\n"
             "  https://github.com/ggml-org/llama.cpp ~/src/llama.cpp"),
            ("p",
             "Then enable it in your user-level <font face='Courier'>~/.gradle/gradle.properties"
             "</font>, which overrides the project file, so a checkout path never lands in the "
             "repository. Use forward slashes, because a properties file treats a backslash "
             "as an escape:"),
            ("code",
             "thermalbench.nativeLlama=true\n"
             "thermalbench.llamaCppDir=C:/Users/you/src/llama.cpp"),
            ("p",
             "The pinned release, <font face='Courier'>thermalbench.llamaCppTag</font>, stays "
             "in the project's own <font face='Courier'>gradle.properties</font>."),
            ("p",
             "A directory that is not a llama.cpp checkout fails the build at configuration "
             "time with a message saying so, rather than as a CMake error three layers down."),
            ("h2", "Why the checkout is pinned"),
            ("p",
             "llama.cpp has no stable API and no stable performance. Functions are renamed "
             "between releases, and kernel and threading changes move tokens per second on "
             "the same device. A run's spec does not capture the runtime version, so an "
             "unrecorded upgrade would show up in a comparison as a difference between "
             "devices. That is the kind of hidden confound the rest of this manual exists to "
             "exclude."),
            ("p",
             "So the project names one release, <font face='Courier'>thermalbench.llamaCppTag"
             "</font>, and the native build does two things with it:"),
            ("bullets", [
                "It stamps the checkout's actual version, from <font face='Courier'>git "
                "describe --tags --always --dirty</font>, into the library. "
                "<font face='Courier'>runtimeVersion()</font> returns it, so every result "
                "document names the llama.cpp release that produced it. A locally modified "
                "checkout reads <font face='Courier'>b11879-dirty</font>, so a modified "
                "runtime cannot pass as the release.",
                "It warns when the checkout is not the pinned tag. The build still "
                "completes, so a newer release can be tried, and the stamp records that "
                "it was.",
            ]),
            ("p",
             "Upgrading is two changes made together: check out the new tag, and move the "
             "pinned tag property to match it. Compile errors in "
             "<font face='Courier'>llamacpp_jni.cpp</font> after an upgrade mean the API moved; "
             "compare the calls against the new <font face='Courier'>include/llama.h</font>."),
            ("warn",
             "Results from different llama.cpp versions are not comparable, even for the same "
             "spec on the same device. Check the runtime version in each document before "
             "putting two llamacpp runs in one table."),
            ("h2", "Always an optimised build"),
            ("p",
             "The native code is built with <font face='Courier'>CMAKE_BUILD_TYPE=Release"
             "</font> for every variant, debug included. AGP would otherwise compile it as "
             "Debug, and an unoptimised llama.cpp is roughly an order of magnitude slower. A "
             "benchmark built that way measures its own compiler flags rather than the device, "
             "and still produces a plausible-looking decay curve."),
            ("h2", "GPU offload with Vulkan"),
            ("p",
             "A llama.cpp build without a GPU backend offers only the CPU delegate. Asking it "
             "to offload layers is accepted and silently ignored, so a run labelled GPU would "
             "really run on the CPU. GPU offload comes from ggml's Vulkan backend, which is "
             "off by default because it needs more on the build machine:"),
            ("bullets", [
                "<b>From the NDK:</b> the C Vulkan headers, the Vulkan loader and the "
                "<font face='Courier'>glslc</font> shader compiler. Nothing to install.",
                "<b>Two Khronos header checkouts:</b> Vulkan-Hpp (C++) and SPIR-V, which the "
                "NDK does not ship. Pin them to one Vulkan SDK release.",
                "<b>A compiler for the build machine itself.</b> llama.cpp compiles a small "
                "shader generator that runs during the build. On Windows this is Visual "
                "Studio Build Tools with the C++ workload; the build finds it with "
                "<font face='Courier'>vswhere</font>, so no developer shell is needed. On "
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
             "The first Vulkan build compiles several hundred shaders and takes noticeably "
             "longer than a CPU-only build; later builds are incremental. The APK gains "
             "<font face='Courier'>libggml-vulkan.so</font>, about 20 MB. A machine with its "
             "own host toolchain can pass it as the CMake argument "
             "<font face='Courier'>THERMALBENCH_HOST_TOOLCHAIN</font>."),
            ("p",
             "Headers newer than the NDK's are safe on older devices. ggml loads every Vulkan "
             "entry point at run time, so the device's own driver decides what is available."),
            ("h3", "GPU is offered only when a GPU is found"),
            ("p",
             "A Vulkan build on a device whose driver exposes no usable GPU would fall back "
             "to the CPU without saying so. The adapter therefore asks ggml for a GPU device "
             "at startup and offers the GPU delegate only when one exists. Phone GPUs share "
             "system memory, so ggml lists them as integrated (IGPU); both kinds count. The "
             "decision is logged:"),
            ("code",
             "adb logcat -s ThermalBenchLlama\n"
             "  GPU device: Vulkan0 (Mali-G78)\n"
             "  load_tensors: offloaded 25/25 layers to GPU"),
            ("p",
             "llama.cpp's own log is routed to logcat under the same tag. Its load-time lines "
             "are the record of which device a run used and how many layers it offloaded, so "
             "check them before trusting a GPU result from a new device."),
            ("h3", "What to expect"),
            ("p",
             "GPU is not automatically faster. On a Pixel 6 (Mali-G78), qwen2.5-0.5B Q4_K_M "
             "decoded at about 16 tokens per second on the GPU against 8 on the CPU, but "
             "processed the prompt about four times more slowly, so each iteration took twice "
             "as long. Vulkan is the portable path. Qualcomm Adreno GPUs also have a dedicated "
             "OpenCL backend in llama.cpp, which this build does not yet include."),
            ("warn",
             "A GPU and a CPU run of the same model have different spec identities, because "
             "the delegate is part of the spec. Compare them as two specs on one device, not "
             "as two runs of one spec."),
            ("h2", "GPU offload with OpenCL (Adreno)"),
            ("p",
             "llama.cpp also has an OpenCL backend written for Qualcomm Adreno GPUs. It needs only "
             "the Khronos OpenCL headers on the build machine, plus Python 3, which embeds the "
             "kernels. The OpenCL library itself is the GPU vendor's, on the device:"),
            ("code",
             "git clone --depth 1 --branch v2026.05.29 \\\n"
             "  https://github.com/KhronosGroup/OpenCL-Headers ~/src/OpenCL-Headers\n"
             "\n"
             "# ~/.gradle/gradle.properties\n"
             "thermalbench.llamaOpenCl=true\n"
             "thermalbench.openClHeadersDir=C:/Users/you/src/OpenCL-Headers"),
            ("p",
             "Linking needs a <font face='Courier'>libOpenCL.so</font>, so the build generates a "
             "stand-in with every function the pinned <font face='Courier'>cl.h</font> declares. The "
             "app and harness exclude it from their APKs: packaged, it would shadow the vendor's "
             "library. The manifest declares <font face='Courier'>libOpenCL.so</font> with "
             "<font face='Courier'>uses-native-library</font>, which Android 12 and later require "
             "before an app may load a vendor library."),
            ("p",
             "Every ggml backend is built as its own library and loaded by name at startup. A "
             "backend that cannot load is skipped and logged, so a phone without vendor OpenCL, "
             "such as a Pixel, still runs llama.cpp on the CPU and Vulkan. On a Snapdragon both "
             "OpenCL and Vulkan reach the same Adreno. The composer then offers a GPU API choice, "
             "recorded in the spec as the <font face='Courier'>gpuApi</font> option, and a GPU run "
             "is pinned to exactly one device. A spec that names no API gets OpenCL. The device "
             "that ran is recorded in the result's <font face='Courier'>computeDevice</font> either "
             "way."),

            ("h2", "NPU on Snapdragon (LiteRT-LM)"),
            ("p",
             "LiteRT-LM can run a model on the Qualcomm Hexagon NPU, but neither piece it needs "
             "is in its Maven package: a LiteRT dispatch library, and Qualcomm's QAIRT runtime "
             "libraries. Started without them, an NPU engine aborts the whole process natively. "
             "The adapter therefore offers NPU only when a "
             "<font face='Courier'>libLiteRtDispatch_*.so</font> is actually packaged."),
            ("steps", [
                "Download the Qualcomm AI Runtime (QAIRT) SDK, version 2.42, from Qualcomm's "
                "software centre. It needs a Qualcomm account and accepting their licence, which "
                "is why it cannot be fetched by the build.",
                "Build LiteRT's Qualcomm dispatch library with Bazel and NDK r28b or newer (Linux "
                "or WSL): <font face='Courier'>bazel build --config=android_arm64 "
                "@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so</font>. It must come "
                "from the LiteRT release that matches the LiteRT-LM version in the version "
                "catalog; a mismatched pair fails at engine creation.",
                "Collect, in one folder under <font face='Courier'>arm64-v8a/</font>: the dispatch "
                "library; <font face='Courier'>libQnnHtp.so</font>, "
                "<font face='Courier'>libQnnSystem.so</font> and "
                "<font face='Courier'>libQnnHtpPrepare.so</font> from "
                "<font face='Courier'>lib/aarch64-android/</font>; and the Hexagon stub and skel for "
                "the target chip, <font face='Courier'>libQnnHtpV75Stub.so</font> and "
                "<font face='Courier'>libQnnHtpV75Skel.so</font> for the Snapdragon 8 Gen 3 "
                "(SM8650).",
                "Point <font face='Courier'>thermalbench.npuLibsDir</font> at the folder in "
                "<font face='Courier'>~/.gradle/gradle.properties</font> and rebuild.",
            ]),
            ("p",
             "With the property set, the libraries are packaged and the APK extracts its native "
             "libraries to disk, because the QNN runtime loads its DSP-side skel by directory "
             "path. The adapter points <font face='Courier'>ADSP_LIBRARY_PATH</font> at that "
             "directory before creating the engine. The manifest declares the vendor FastRPC "
             "library, <font face='Courier'>libcdsprpc.so</font>, which the runtime uses to reach "
             "the DSP. Without the property nothing changes: the APK carries no vendor code and "
             "NPU is not offered."),
            ("p",
             "NPU models are compiled per chip. The catalog lists Gemma 3 1B for the SM8650 "
             "(<font face='Courier'>Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm</font>), gated behind "
             "Google's Gemma licence like the other Gemma files. Google publishes NPU builds for "
             "SM8550, SM8650, SM8750 and SM8850 only. A file compiled for another chip fails when "
             "it loads."),
            ("warn",
             "Older Snapdragons cannot run LLMs on the NPU by this route. The OnePlus 8's "
             "Snapdragon 865 (SM8250) has no published NPU models, so the honest result there is "
             "CPU against GPU."),
            ("h2", "Qualcomm Genie (QAIRT)"),
            ("p",
             "Genie is Qualcomm's own LLM runtime, part of the Qualcomm AI Runtime (QAIRT) SDK. "
             "It runs a model on the Hexagon NPU (the QnnHtp backend) or on Qualcomm's CPU path "
             "(QnnGenAiTransformer), and it is the most direct route to the NPU on a Snapdragon. "
             "The SDK is downloaded from Qualcomm under their licence and is never part of this "
             "repository. Point the build at an unpacked copy:"),
            ("code",
             "# ~/.gradle/gradle.properties\n"
             "thermalbench.qairtDir=C:/PROJECTS/Android/2.44.0.260225\n"
             "thermalbench.qairtHexagon=v75        # v73, v75, v79: one per NPU generation"),
            ("p",
             "The build then compiles a small JNI adapter against the Genie headers and packages "
             "only what running a prepared model needs: <font face='Courier'>libGenie.so</font>, "
             "the QNN system, HTP and GenAiTransformer libraries, and a stub and DSP-side skel per "
             "listed Hexagon version. The 85 MB <font face='Courier'>libQnnHtpPrepare.so</font> is "
             "left out: it compiles graphs on the device, and Genie bundles arrive compiled. The "
             "adapter opens <font face='Courier'>libGenie.so</font> at run time rather than "
             "linking it, so a build without the SDK, or a phone it cannot load on, reports the "
             "backend unavailable with the reason."),
            ("p",
             "The licence allows the libraries to ship inside an application, which is what an APK "
             "built this way is. It does not allow distributing them on their own, so they must "
             "not be committed. No account is involved at run time."),
            ("h3", "Model bundles"),
            ("p",
             "A Genie model is a folder: <font face='Courier'>genie_config.json</font>, a tokenizer "
             "and the model files. Push the folder into the models directory; the library lists it "
             "by folder name, and a spec's model path is its config. Genie resolves relative paths "
             "against the working directory, so the adapter makes every path in the config "
             "absolute before loading. It also makes sampling greedy with the run's seed, and caps "
             "generation at the spec's token count."),
            ("p",
             "The compute unit is fixed when a bundle is built, so it is read from the config "
             "(QnnHtp is NPU, QnnGenAiTransformer is CPU) and preselected. A spec that asks a bundle "
             "for a different unit is refused at load, never quietly run elsewhere."),
            ("bullets", [
                "<b>CPU bundles</b> are made on the PC with the SDK's "
                "<font face='Courier'>qnn-genai-transformer-composer</font> from a Hugging Face "
                "model, for the architectures it has configurations for: Llama 2/3.x, TinyLlama, "
                "Mistral, Gemma 2B, Phi-3 and others. TinyLlama 1.1B Chat converts in about 12 "
                "seconds to a 585 MB Z8-quantised bundle. On Windows the composer needs a small "
                "wrapper: the SDK's Python package always loads its Genie binding, whose Windows "
                "build does not load, although the composer never calls it.",
                "<b>NPU bundles</b> hold context binaries compiled for one Hexagon version. They are "
                "exported through Qualcomm AI Hub with the "
                "<font face='Courier'>qai-hub-models</font> package, which needs a free AI Hub "
                "account; Llama models also need Hugging Face access. The QAIRT version the bundle "
                "was compiled with should match the SDK the app was built with.",
            ]),
            ("warn",
             "Genie runs only on Snapdragon. On other silicon even its CPU path refuses to start "
             "(QNN error 1006, platform not supported; checked on a Pixel 6), so the backend "
             "reports itself unavailable there. Older Snapdragons are out too. Genie's CPU path "
             "uses ARMv8.6 int8 matrix instructions (smmla), and on a CPU without them it dies "
             "with SIGILL; a OnePlus 8T's Snapdragon 865 crashed this way. Its NPU path needs "
             "Hexagon v73 or newer (Snapdragon 8 Gen 2 onward). The adapter checks both before a "
             "run: it offers CPU only where /proc/cpuinfo lists i8mm, and NPU only on a known "
             "v73-or-newer SoC."),
            ("p",
             "In the app, pick a <font face='Courier'>.zip</font> of the bundle folder with the "
             "file picker. The app unpacks it into a folder it owns, named after the zip, because "
             "a folder pushed with adb is one the app may not read. Unpacking streams one entry at "
             "a time into a hidden staging folder and moves the bundle into place only when it is "
             "complete and holds a <font face='Courier'>genie_config.json</font>. An entry whose "
             "path would leave the folder, or an archive that would unpack past the free space, "
             "is refused, and a cancelled or failed import leaves nothing behind."),
            ("p",
             "On the harness, a bundle's folder must be created by the harness, like its other "
             "directories, before the files are pushed:"),
            ("code",
             "adb shell am instrument -w -e prepare true -e bundles tinyllama-cpu $RUNNER\n"
             "adb push tinyllama-cpu/. $DIR/models/tinyllama-cpu/"),
            ("h2", "The JNI layer"),
            ("p",
             "The JNI layer decodes one token per call for the same reason LiteRT-LM does: it "
             "is what allows an exact token-count stop. It also clears the context between "
             "iterations and decodes greedily, so sampling noise cannot masquerade as "
             "throughput noise."),
        ],
    },

    # ------------------------------------------------------------------ 8
    {
        "title": "Telemetry, and what it honestly cannot see",
        "blocks": [
            ("p",
             "This is where most benchmark applications quietly fail. The table below is the "
             "realistic picture, not the optimistic one."),

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
                ["Hardware temps", "HardwarePropertiesManager",
                 "Needs device-owner or system app. Not designed around."],
                ["Memory", "Debug.MemoryInfo, /proc/self/statm",
                 "Universal."],
            ], [18, 34, 48], (1,)),

            ("h2", "Nothing is ever fabricated"),
            ("warn",
             "CPU frequency is returned only when <b>every</b> core is readable. A partial "
             "list never masquerades as the whole SoC, and an unreadable value is "
             "<font face='Courier'>null</font>, never a guess or a default. A result that "
             "says \"CPU frequency was unreadable on this device\" is more credible than one "
             "that quietly omits the field."),

            ("p",
             "The same principle applies throughout. Battery temperature is recorded as "
             "corroboration and never treated as the primary thermal signal, because it lags "
             "the die by minutes. A thermal status the platform reports that this tool does "
             "not recognise becomes <font face='Courier'>UNKNOWN</font> and blocks a run "
             "rather than being optimistically read as cold."),
        ],
    },

    # ------------------------------------------------------------------ 9
    {
        "title": "Analysis: from samples to a verdict",
        "blocks": [
            ("p",
             "<font face='Courier'>core/analysis</font> is pure Kotlin with no Android "
             "dependency, so the identical code runs on the device for the live chart and on "
             "a workstation for the paper."),

            ("h2", "Peak"),
            ("p",
             "The best rolling-median window, not the single best iteration. One lucky "
             "iteration is noise; a sustained window is a capability. Using a maximum here "
             "would inflate the denominator of the retention ratio and understate every "
             "device."),

            ("h2", "Steady state"),
            ("p",
             "A settled tail is required, defined by two conditions holding together: a "
             "coefficient of variation below 5%, and a normalized slope below 3% across the "
             "window. The steady-state value is the median of the settled tail."),
            ("p",
             "If the last window is not itself steady, the run never stabilized and the "
             "result is <font face='Courier'>null</font>. Reporting the final value of a "
             "still-declining curve as a floor would be a fabrication, and a plausible one."),

            ("h2", "Decay fit"),
            ("p",
             "Levenberg-Marquardt against <font face='Courier'>a + b·exp(−t/τ)</font>, giving "
             "an asymptote, an amplitude, a time constant and an R². A fit that does not "
             "converge, or a series too short or too degenerate to fit, is reported as "
             "absent rather than replaced by a straight line dressed up as a decay."),

            ("h2", "The validity verdict"),
            ("table", [
                ["Verdict", "Meaning"],
                ["VALID", "Preconditions were met and enough iterations completed."],
                ["PRECONDITIONS_UNMET", "The gate was not satisfied. Not comparable with other runs."],
                ["INSUFFICIENT_DATA", "Too few measured iterations for sustained-load analysis."],
            ], [30, 70], (0,)),

            ("note",
             "<b>A run cut short by the operating system's thermal floor stays VALID.</b> "
             "That is not a failed measurement, it is the finding — the same finding the "
             "Galaxy S24 Ultra produced in the paper that motivated this tool. Only a run "
             "that ended for an unrelated reason before reaching its protocol is marked as "
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
             "Two harness runs on a Pixel 6 show the rules above at work: the same model and "
             "prompt on the CPU and on the GPU, each run unplugged with the screen off, both "
             "VALID."),
            ("figure", "pixel6-cpu-gpu.png",
             "Gemma 3 1B via MediaPipe on a Pixel 6 (Tensor G1), 256-token answers. The GPU's "
             "peak is 12.1 tok/s, its best sustained window, not the single 15.8 tok/s "
             "iteration at minute 13. The CPU run was still declining when it ended, so the "
             "analyzer reports no steady state and therefore no retention ratio, rather than "
             "treating the last value as a floor. Line-end labels give the mean of the last "
             "ten iterations as a share of peak (83% CPU, 99% GPU), reported beside the "
             "retention ratio, never instead of it. Drawn with "
             "<font face='Courier'>thermalbench-analyze plot</font>."),
            ("p",
             "Read together, the two runs are the reason this tool exists. The CPU leads by "
             "about 40% at the start, throttles at 5.6 minutes and raises the battery 7.9 °C; "
             "the GPU holds 99% of its peak and raises it 3.0 °C. A benchmark that stops after "
             "a minute reports the first fact and misses the second."),
        ],
    },

    # ------------------------------------------------------------------ 10
    {
        "title": "Running the headless harness",
        "blocks": [
            ("p",
             "This is the path that produces publishable numbers. It is a single "
             "self-instrumenting APK with no launcher activity: <font face='Courier'>am "
             "instrument</font> starts it, the benchmark runs, a document is written, the "
             "process exits. No UI process, no rendering load, and no second test process to "
             "perturb the measurement."),

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
             "Never create those directories with <font face='Courier'>adb shell mkdir</font>. "
             "A directory adb makes there belongs to shell: the harness cannot read a spec "
             "pushed into it, and adb cannot pull results the harness writes into it. "
             "<font face='Courier'>chmod</font> does not repair it. The harness checks who owns "
             "each directory before any device time is spent, and names the commands that fix it."),

            ("h2", "Phones that freeze background apps"),
            ("p",
             "Some vendors add their own power manager on top of Android's. OnePlus, Oppo and "
             "Realme phones run one (HANS) that freezes background processes about 25 seconds "
             "after the screen changes state. A frozen harness does not fail: the run simply "
             "stops mid-generation and never finishes. Adding the harness to the "
             "battery-optimisation whitelist does not prevent it."),
            ("p",
             "The harness protects itself by holding a foreground service for the whole run, "
             "which these managers leave alone. Android grants that service only while the "
             "app is not background-restricted, and on these phones an app is restricted "
             "unless it has been explicitly allowed. Allow it once per phone, and again after "
             "uninstalling the harness:"),
            ("code",
             "adb shell cmd appops set com.thermalbench.harness RUN_ANY_IN_BACKGROUND allow"),
            ("p",
             "When the setting is missing, the harness logs a warning that gives this command "
             "at the start of the run (<font face='Courier'>adb logcat -s ThermalBench</font>). "
             "The app needs no such step: it starts its foreground service from the screen "
             "you tap."),

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
             "the <font face='Courier'>specId</font> written in the file. A spec edited by "
             "hand, or one whose iteration count was overridden, is recorded under its own "
             "identity. The harness logs a warning when the file's identity was stale."),

            ("h2", "Reading the result"),
            ("p",
             "The harness reports its headline numbers back through the instrumentation "
             "status bundle, so a fleet script can read them without scraping logcat:"),
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
             "<font face='Courier'>--screen-off</font> matters more than it looks. Screen "
             "state is a precondition, and setting it from the orchestrator means every "
             "device in the fleet runs under the same one instead of whatever state each "
             "happened to be left in."),
        ],
    },

    # ------------------------------------------------------------------ 11
    {
        "title": "The operator console",
        "blocks": [
            ("p",
             "The Compose app is optional by design. It exists for interactive use, and the "
             "numbers it displays come from the same engine the harness runs."),

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
             "confound this tool exists to measure</b>. The runner is a foreground service of "
             "type <font face='Courier'>specialUse</font> with that justification in the "
             "manifest."),
            ("p",
             "The service is handed only a spec identifier and re-resolves everything else "
             "from persistence, so restarting it is idempotent. It is deliberately "
             "<font face='Courier'>START_NOT_STICKY</font>: a run the system killed has a "
             "hole in its thermal history, and silently restarting it would produce a "
             "document claiming to be one continuous measurement when it is two."),
            ("p",
             "A wake lock is held for the whole run and released in a "
             "<font face='Courier'>finally</font> block. It affects power draw, so the result "
             "document records that it was held."),

            ("h2", "The live chart caveat"),
            ("p",
             "The live chart is the single largest source of observer effect in the app path. "
             "Three things reduce it: chart recomposition is throttled to roughly 1 Hz, the "
             "series are held in a bounded ring buffer so a long run cannot grow memory "
             "without limit, and a minimal-UI toggle suppresses the charts entirely — "
             "available while a run is in progress."),
            ("note",
             "<b>A measurement worth publishing.</b> Run the same spec twice on one device, "
             "once charted and once headless, and publish the delta. It is a small credible "
             "result in its own right, and it inoculates the project against the most obvious "
             "reviewer objection. The result document records "
             "<font face='Courier'>executionPath</font> and "
             "<font face='Courier'>liveChartEnabled</font> precisely so that comparison is "
             "possible after the fact."),
        ],
    },

    # ------------------------------------------------------------------ 12
    {
        "title": "Result documents and canonical serialization",
        "blocks": [
            ("p",
             "A finished run is stored twice on purpose. The canonical JSON document is the "
             "shareable artifact and the authoritative copy. The database holds a summary "
             "index, so the history list does not parse every document, and the in-flight "
             "samples that make a crashed run recoverable."),

            ("h2", "What canonical means here"),
            ("bullets", [
                "Object members sorted by key, so ordering is never a source of difference.",
                "No insignificant whitespace.",
                "Floating-point numbers written as the shortest decimal that reparses to the identical double.",
                "Non-finite numbers become null, since JSON cannot express them.",
            ]),
            ("p",
             "Encoding is <b>lossless</b>: decoding a document and re-encoding it reproduces "
             "the same bytes, which is what makes a digest a stable identity."),

            ("warn",
             "<b>A caught bug worth knowing about.</b> The encoder originally rounded to 15 "
             "significant digits. IEEE 754 doubles need up to 17, so a retention ratio of "
             "14.2 ÷ 17.5 decoded to a <i>different number</i> than it encoded. The "
             "round-trip property test caught it. Rounding to a fixed digit count is not a "
             "cosmetic choice; it silently changes measurements as they pass through the file "
             "format."),

            ("h2", "Why get this right now"),
            ("p",
             "Canonical serialization is the one piece that is expensive to retrofit and free "
             "to get right up front, which is why it has property tests rather than examples. "
             "If ThermalBench later grows into a conformance toolkit, this code is reused "
             "verbatim while most other things change shape."),

            ("h2", "Crash safety"),
            ("p",
             "Samples are flushed to the database in batched transactions every few "
             "iterations rather than held in memory until the end. The batching serves two "
             "purposes at once: it keeps database I/O off the critical path of an iteration, "
             "where its own heat and latency would contaminate the measurement, and it bounds "
             "what a crash can destroy to less than one batch. A crash at iteration 47 keeps "
             "the 46 that completed."),

            ("h2", "Export"),
            ("p",
             "Every run writes a JSON document and a CSV sidecar, one row per iteration. CSV "
             "is the format reviewers will actually open."),
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
             "analysis code changed since the run, which is exactly when previously published "
             "numbers may need a correction. Surfacing that loudly is the point."),

            ("h2", "Guardrails in the comparison table"),
            ("bullets", [
                "Invalid runs are excluded by default and included only with <font face='Courier'>--include-invalid</font>, which shows their verdict.",
                "A table containing more than one spec identifier prints a warning, because only runs of the same spec are comparable.",
                "A document that fails to parse is reported by name rather than skipped in silence.",
                "Plots are anchored at zero on the y-axis, so a mild decline cannot be autoscaled into a cliff.",
            ]),
            ("p",
             "The figure in chapter 9 was produced by <font face='Courier'>plot</font> from the "
             "two Pixel 6 result files; <font face='Courier'>--width</font> and "
             "<font face='Courier'>--height</font> set its size (1600 × 1000 by default)."),
        ],
    },

    # ------------------------------------------------------------------ 14
    {
        "title": "The leaderboard and consent",
        "blocks": [
            ("p",
             "Submission is opt-in, per-run, and gated on explicit consent. The client is "
             "written so the entire set of fields that can leave the device is readable in "
             "one screen of code."),

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
             "Each field is a separate choice rather than one blanket agreement, and only "
             "runs marked <font face='Courier'>VALID</font> may be submitted at all. A "
             "leaderboard populated with runs from warm, charging phones would be worse than "
             "no leaderboard."),
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
                "<b>Lint version.</b> The lint checks bundled inside androidx libraries are built against a newer Kotlin Analysis API than AGP 8.7.3's lint, and crash with \"Found class Ka…, but interface was expected\". <font face='Courier'>android.experimental.lint.version</font> pins a newer standalone lint so every check stays enabled instead of being switched off one by one.",
                "<b>Intermittent file lock.</b> On Windows a full build occasionally fails with a file lock on <font face='Courier'>bundleLibCompileToJarDebug</font>. It is environmental, not a code fault. Retry, and if it repeats run <font face='Courier'>./gradlew --stop</font> first.",
            ]),

            ("h2", "APK size"),
            ("p",
             "Application modules package <font face='Courier'>arm64-v8a</font> only, which "
             "covers every device worth thermally profiling and avoids pushing three unused "
             "architectures to a fleet over ADB. Add "
             "<font face='Courier'>-Pthermalbench.includeX86=true</font> for an emulator "
             "smoke test. The ExecuTorch runtime is deliberately not linked while its adapter "
             "is a stub, because shipping a runtime nothing can call would add 8 MB to every "
             "APK for no benefit."),
        ],
    },

    # ------------------------------------------------------------------ 16
    {
        "title": "Where to start, and where this goes",
        "blocks": [
            ("h2", "Build order"),
            ("p",
             "The order below is deliberate: the project becomes useful at step 4 and "
             "publishable at step 6, long before it is complete."),
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
             "If this later expands into an evidence and conformance toolkit, the seams are "
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
             "Every place this codebase could have guessed, it records an absence instead: a "
             "null frequency reading, an absent decay fit, a steady state that never settled, "
             "a backend that will not load. That habit is the whole reason a number from this "
             "tool is worth quoting. Preserve it in anything added."),
        ],
    },
]
