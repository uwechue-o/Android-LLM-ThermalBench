"""Prose for the plain-English ThermalBench guide.

Same layout engine as the developer manual, different audience. This version
assumes no engineering background and explains every term it uses, while still
covering everything the tool does and every decision that shapes its numbers.
"""

TITLE = "ThermalBench"
SUBTITLE = "A plain-English guide to what this tool measures, and why the answer is hard to get right"
VERSION = "0.1.0"
OUTPUT_NAME = "ThermalBench-Guide.pdf"
RUNNING_HEAD = "ThermalBench — Guide"
ACCENT_HEX = "#1D6B4F"

DOCUMENT = [
    # ------------------------------------------------------------------ 1
    {
        "title": "The problem in one page",
        "blocks": [
            ("lead",
             "Phones get hot. When they get hot they slow themselves down on purpose, to "
             "protect the battery and the chips and your hand. This is normal and every "
             "phone does it. The trouble is that almost nobody measures it."),

            ("p",
             "When a phone is described as fast, the number quoted was almost certainly "
             "measured in the first few seconds of work, on a cool device. That is the "
             "easiest possible moment. Ask the same phone to keep working for ten minutes "
             "and it will quietly deliver a fraction of what it first promised."),

            ("p",
             "ThermalBench measures that fraction. It runs an artificial intelligence model "
             "on the phone, over and over, without pause, and records what happens as the "
             "device heats up: how quickly it slows, when the operating system starts "
             "holding it back, and where the speed eventually settles."),

            ("h2", "The one number this is all for"),
            ("p",
             "We call it the <b>retention ratio</b>. It is the settled speed divided by the "
             "best speed, written as a percentage."),
            ("note",
             "If a phone starts at 20 words per second and settles at 9, it retained 45%. "
             "The headline speed was real. It was also temporary. The retention ratio is "
             "the part that describes what using the device actually feels like."),

            ("p",
             "This matters to different people for different reasons. Someone buying a phone "
             "wants to know whether the advertised speed survives contact with real use. "
             "Someone building an app wants to know what performance to design for. Someone "
             "reviewing hardware wants a measurement that is not simply the first two "
             "seconds of a benchmark."),

            ("h2", "Why this is not already common knowledge"),
            ("p",
             "A published study in early 2026 tested the same AI model across several "
             "phones and found that heat, not raw processing power, was the main limit on "
             "all of them. One well-known phone lost close to half its speed within two "
             "repetitions. Another hit a limit imposed by its own operating system that "
             "stopped the work entirely."),
            ("p",
             "The same study noted that the industry-standard benchmark suite measures peak "
             "performance and does not track heat-related slowdown at all. Existing phone "
             "apps report a temperature reading as a snapshot. Nobody had turned the "
             "slowdown curve itself into a tool. That is what this is."),
        ],
    },

    # ------------------------------------------------------------------ 1b
    {
        "title": "Who would find ThermalBench most useful",
        "blocks": [
            ("p",
             "The clearest audience is engineers shipping AI that runs on the phone itself: "
             "Android developers, ML engineers and product teams deciding which model, size, "
             "file format and processor to use. Launch-day benchmarks report the first few "
             "seconds, when the phone is cool. Real use, like a long chat, live transcription "
             "or a summarising assistant, runs for minutes, and a phone can lose a large share "
             "of its speed once it heats up and slows itself down. ThermalBench measures the "
             "speed a user actually gets after ten minutes, not the peak. It also produces "
             "repeatable results tied to a spec ID, with checked model files and a pinned "
             "engine version, so a team can make decisions like \"the 1.5B model on GPU holds "
             "up better than the 3B model on CPU on mid-range phones\" from evidence."),
            ("p",
             "A second audience is people who compare phones: hardware reviewers, tech "
             "journalists, phone makers' performance and thermal teams, and university or "
             "industry researchers. Reviewers can add a \"sustained AI performance\" score that "
             "shows which phones keep their speed and which slow down sharply. Thermal "
             "engineers can watch how a cooling design and the phone's throttling rules behave "
             "under a realistic AI load. Researchers get a scriptable harness with exportable "
             "results for papers. Enthusiasts who run local models on their phones, and buyers "
             "choosing a phone for on-device AI, also benefit from a plain score like \"keeps "
             "78% of its speed\"."),
        ],
    },

    # ------------------------------------------------------------------ 2
    {
        "title": "Why measuring this honestly is hard",
        "blocks": [
            ("p",
             "Three problems make this measurement much harder than it sounds. Almost every "
             "unusual decision in the rest of this guide exists because of one of them, so "
             "they are worth understanding before anything else."),

            ("h2", "Problem one: the tool heats the phone too"),
            ("p",
             "A measuring app is itself a program running on the phone being measured. Its "
             "screen, its moving charts, its saving of results, all generate heat on the "
             "very chip whose heat we are trying to observe. It is like taking the "
             "temperature of a room with a thermometer that is slightly on fire."),
            ("p",
             "The fix is that the real measurement runs with <b>no visible app at all</b>. "
             "There is a mode with no screen, no charts and no buttons, driven from a "
             "computer over a cable. The app with the nice interface is a convenience "
             "wrapper, and every saved result records which of the two produced it, so the "
             "two are never quietly compared as though they were the same."),

            ("h2", "Problem two: the starting condition changes the answer"),
            ("p",
             "A phone that is already warm gives a different result from a cold one. A phone "
             "that is charging is being heated by its own battery. A phone with a dim screen "
             "is doing less work than one at full brightness. A phone at 15% battery is "
             "already slowing itself for a completely different reason."),
            ("p",
             "So before a measurement starts, the tool waits and checks. It will not begin "
             "until the phone is cool, unplugged, adequately charged, and has stayed that "
             "way continuously for a set period."),
            ("warn",
             "If those conditions are not met, the result is not simply a slightly worse "
             "measurement. It is marked <b>invalid</b> and labelled as such permanently. "
             "The tool never quietly adjusts a number to compensate for bad conditions, "
             "because there is no honest way to do that."),

            ("h2", "Problem three: clocks move"),
            ("p",
             "Phones correct their clocks against the internet. If that correction lands in "
             "the middle of a forty-minute measurement, every timing recorded afterwards "
             "shifts, and the resulting graph would be wrong in a way nobody could see by "
             "looking at it."),
            ("p",
             "The tool therefore times everything with a counter that only ever moves "
             "forward and is never corrected. The ordinary date and time is recorded exactly "
             "once, as a label saying when the run happened, and is never used to calculate "
             "anything."),
        ],
    },

    # ------------------------------------------------------------------ 3
    {
        "title": "The words you need",
        "blocks": [
            ("p",
             "A short glossary. Everything else in this guide is written in plain language, "
             "but these six terms are unavoidable because they name things that have no "
             "everyday equivalent."),

            ("table", [
                ["Term", "What it means here"],
                ["Model",
                 "The artificial intelligence itself, stored as a very large file of numbers. "
                 "It is data, not a program. The tool ships none and runs whichever one you give it."],
                ["Token",
                 "Roughly a short word or a piece of one. AI systems read and write in tokens, "
                 "so speed is measured in tokens per second."],
                ["Backend",
                 "The software engine that loads a model and does the actual arithmetic. Nothing "
                 "to do with servers or the internet. Everything runs on the phone."],
                ["Compute unit",
                 "Which part of the chip does the work: the main processor, the graphics "
                 "processor, or a dedicated AI accelerator."],
                ["Throttling",
                 "The phone deliberately slowing itself down to control heat. This is the "
                 "behaviour being measured, not a fault."],
                ["Iteration",
                 "One repetition of the work. A measurement is typically fifty or more of "
                 "them, run back to back with no rest."],
            ], [20, 80]),

            ("note",
             "The word <b>backend</b> causes the most confusion, because in most software it "
             "means a server somewhere on the internet. Here it means an engine running on "
             "the phone in your hand. Nothing in a measurement leaves the device unless you "
             "explicitly choose to publish a result."),
        ],
    },

    # ------------------------------------------------------------------ 4
    {
        "title": "What happens during a measurement",
        "blocks": [
            ("p", "Every run goes through the same five stages."),

            ("steps", [
                "<b>The plan is checked.</b> Before anything runs, the tool inspects what you "
                "have asked for and refuses combinations that could not produce a fair "
                "comparison. Catching this early matters, because the alternative is "
                "discovering it an hour into a measurement.",

                "<b>The phone is allowed to settle.</b> The tool waits for the device to be "
                "cool, unplugged and stable, and shows you exactly what is still blocking "
                "the start.",

                "<b>The model is loaded.</b> The file is checked and handed to the chosen "
                "engine. How long this takes is recorded.",

                "<b>Warm-up, then real measurement.</b> A few repetitions are run and thrown "
                "away, because the first few are never representative. Then the measured "
                "repetitions run back to back with no pause.",

                "<b>The results are worked out and saved.</b> The raw readings become a "
                "summary, and everything is written to a file you can keep, share or "
                "re-examine later.",
            ]),

            ("h2", "What is being recorded while this happens"),
            ("bullets", [
                "How fast the model produced words, for every single repetition.",
                "How long the phone took to produce its first word each time, which drifts upward as the device heats.",
                "The phone's own reported heat level, sampled steadily and also captured the instant it changes.",
                "Battery level and battery temperature.",
                "How hard the processor cores are running, on the phones that will report it.",
                "Memory use.",
            ]),

            ("p",
             "Two separate records of heat are kept on purpose. One is a steady sample taken "
             "at a fixed rhythm, which is what the graph is drawn from. The other captures "
             "the exact instant the phone changed its behaviour, which regular sampling "
             "would blur."),
        ],
    },

    # ------------------------------------------------------------------ 5
    {
        "title": "What you need before you start",
        "blocks": [
            ("h2", "A phone"),
            ("p",
             "Any reasonably modern Android device. Measurements are only meaningful on real "
             "hardware, since the whole subject is physical heat."),

            ("h2", "A model file"),
            ("p",
             "This is the part people are usually missing. ThermalBench includes no AI model. "
             "It measures whichever one you supply, which is deliberate: the model you care "
             "about is the one worth measuring."),
            ("p",
             "These files are large, typically between about 150 megabytes and several "
             "gigabytes. They are freely available to download, though some require agreeing "
             "to a licence first. Each engine reads its own file format and they are not "
             "interchangeable, which the tool works out for you from the file's name."),

            ("h2", "Getting the file onto the phone"),
            ("p",
             "Modern Android is strict about which files an app may open, so a model cannot "
             "simply be left anywhere. There are three ways to place one, and any of them "
             "works:"),
            ("bullets", [
                "Download it on the phone, then pick it in the app. The app copies it into its own storage and shows a progress bar while it does.",
                "Copy it from a computer over a cable, straight into the app's own folder. The app shows you the exact command to use.",
                "Use the fleet tool, which does the copying for several phones at once.",
            ]),
            ("note",
             "The app lists every model it can find and tells you which folder it looked in "
             "and when. If a file is not appearing, that line is the first thing to read: it "
             "distinguishes \"nothing there\" from \"looking in the wrong place\"."),
        ],
    },

    # ------------------------------------------------------------------ 6
    {
        "title": "Choosing what to measure",
        "blocks": [
            ("p",
             "Three choices define a measurement, and all three are recorded with the result "
             "so anyone can see exactly what was tested."),

            ("h2", "Which engine"),
            ("p",
             "Four are built in. Two come from Google, one is the widely used open-source "
             "engine that runs the file format most of the community publishes in, and one "
             "is not finished yet."),
            ("table", [
                ["Engine", "Where it comes from", "Notes"],
                ["LiteRT-LM", "Google",
                 "The reference choice. It reports its own timings in more detail than the others, so its numbers need the least interpretation."],
                ["MediaPipe GenAI", "Google",
                 "A second Google engine, useful as a cross-check on the same phone."],
                ["llama.cpp", "Open source",
                 "Reads the format most freely available models are published in. Needs an extra build step to enable."],
                ["ExecuTorch", "Meta",
                 "Not implemented yet. It appears in the list and says so."],
            ], [20, 20, 60]),

            ("note",
             "An engine that cannot run on your phone stays visible in the list with the "
             "reason written next to it, rather than disappearing. Knowing that a phone "
             "cannot do something is a finding, and hiding it would make the tool look more "
             "capable than it is."),

            ("h2", "Which part of the chip"),
            ("p",
             "The same model on the same phone behaves very differently depending on where "
             "it runs, and the differences in heat are as large as the differences in speed."),
            ("bullets", [
                "<b>Main processor.</b> Available on every device and usually the slowest.",
                "<b>Graphics processor.</b> Often the fastest, and often the first to be throttled.",
                "<b>AI accelerator.</b> Present on newer phones only, and usually the most efficient.",
            ]),

            ("h2", "How long to run"),
            ("p",
             "Fifty repetitions is the sensible default and is the point at which the tool "
             "considers a result solid enough for the conclusions it is designed to support. "
             "Fewer is allowed for a quick check, and the result is then labelled as having "
             "insufficient data rather than being silently treated as equivalent."),
            ("warn",
             "One rule is not adjustable, and it is the reason results can be compared at "
             "all. Every engine is told to produce exactly the same number of words from "
             "exactly the same starting text. Without that, comparing their speeds would be "
             "meaningless, and the tool refuses to run any engine that cannot obey it."),
        ],
    },

    # ------------------------------------------------------------------ 7
    {
        "title": "Running a measurement",
        "blocks": [
            ("h2", "The straightforward way"),
            ("steps", [
                "Open the app and choose New run.",
                "Pick a model from the list, or tap to browse for one.",
                "Pick an engine and which part of the chip to use.",
                "Leave the settings alone unless you have a reason not to.",
                "Press Start, then unplug the phone and leave it alone.",
            ]),
            ("p",
             "The tool will wait until the phone is ready, tell you what it is waiting for, "
             "and begin on its own. It keeps running with the screen off, protected from the "
             "phone's usual power saving. Some phones go further and slow down work done with "
             "the screen off; the next section but one covers that. Expect a run to take tens "
             "of minutes and to leave the device warm."),

            ("h2", "Hearing how the cool-down is going"),
            ("p",
             "A phone that has just been used is usually too warm to start, so the tool "
             "first waits for it to cool. That wait happens with the screen off, and turning "
             "the screen on to look would restart it. So the phone tells you instead. Every "
             "20 seconds it says the current reading out loud, for example: <i>\"Current "
             "thermal margin is 0.27.\"</i> The phone is ready once that number reaches "
             "0.50. It also says when it is halfway there, and when it is cool enough to "
             "begin."),
            ("p",
             "The voice plays even with the ringer on silent and follows the media volume, "
             "so turn that up if you cannot hear it. The same reading also appears in the "
             "notification on the lock screen. The phone goes quiet once the measurement "
             "starts, so the speaking cannot affect the result. At the end it says the run "
             "has finished and what share of its speed the phone kept."),

            ("h2", "Keeping the screen on"),
            ("p",
             "Normally the screen stays off, because the display warms the phone too. Some "
             "phones, though, quietly hold back work done while the screen is off, and on "
             "those a screen-off measurement reports the phone's power saving rather than its "
             "chip. Chapter 9 shows a phone doing exactly that."),
            ("p",
             "To check, turn on <b>Keep the screen on, dimmed</b> under Preconditions when "
             "setting up a run. The app then keeps the screen lit at its lowest brightness "
             "for the whole run. Leave the app open and the phone untouched. If the speed is "
             "very different from a screen-off run, the phone's power saving was "
             "responsible."),

            ("h2", "The rigorous way"),
            ("p",
             "For results intended to be published or compared across several phones, there "
             "is a mode driven from a computer with no app interface running at all. It is "
             "more setup, and it removes the last source of interference the app version "
             "still has."),
            ("p",
             "The same tool can also drive several phones at once, sending the same "
             "measurement to each and collecting the results together."),

            ("h2", "A word about the live graph"),
            ("p",
             "Watching the graph while a measurement runs is the single largest way the app "
             "affects its own results. The graph is therefore updated only about once a "
             "second, and there is a switch to turn it off entirely, which can be used "
             "mid-run."),
            ("note",
             "There is a worthwhile experiment here. Run the same measurement twice on one "
             "phone, once watching the graph and once without any interface at all, then "
             "compare. The difference is itself a small and genuinely useful result, and it "
             "answers the most obvious objection anyone will raise."),
        ],
    },

    # ------------------------------------------------------------------ 8
    {
        "title": "Reading the results",
        "blocks": [
            ("p",
             "The results screen leads with the retention ratio, because that is the point of "
             "the exercise. Everything else supports or qualifies it."),

            ("table", [
                ["What you see", "What it means"],
                ["Retained",
                 "The headline. The settled speed as a percentage of the best speed. Higher is better; 100% would mean no slowdown at all."],
                ["Peak",
                 "The best speed the phone sustained. Not its single luckiest moment, which would flatter the device."],
                ["Settled speed",
                 "Where the speed levelled off. If it never levelled off, this says so rather than guessing."],
                ["Repetitions to first slowdown",
                 "How long the phone lasted before it began holding itself back. A large number is a well-cooled device."],
                ["Slowdown pattern",
                 "How the decline is shaped, including roughly how many seconds the phone takes to lose most of its headroom."],
                ["First-word delay drift",
                 "How much slower the phone gets at starting to answer, as it heats up."],
                ["Battery used and heat gained",
                 "What the measurement cost the device."],
            ], [26, 74]),

            ("h2", "What a real result looks like"),
            ("p",
             "The figure below is two measurements from a Pixel 6: the same small language "
             "model answering the same question again and again for about twenty minutes, "
             "once on the main processor and once on the graphics chip. The upper panel is "
             "the speed of each answer; the lower panel is the battery's temperature over "
             "the same minutes."),
            ("figure", "pixel6-cpu-gpu.png",
             "Gemma 3 1B on a Pixel 6, unplugged with the screen off, 256-token answers "
             "back to back. The main processor starts about 40% faster (17.0 tokens a second), slows "
             "itself at 5.6 minutes and ends at 83% of its best, with the battery 7.9 °C "
             "warmer. The graphics chip is slower (12.1 tokens a second) but keeps 99% of its best "
             "and warms the battery only 3 °C. Both measurements were labelled Valid."),
            ("p",
             "A one-minute test would crown the main processor. Twenty minutes shows the "
             "trade-off: more speed now, paid for in heat, with a decline still under way "
             "when the measurement ended. That is also why its settled speed reads as "
             "unavailable. It never levelled off, and the tool does not pretend it did."),

            ("h2", "Two phones, one question"),
            ("p",
             "The same model and the same question on the main processor of two phones: the "
             "Pixel 6 above and a newer Honor Magic V3. Both measurements were labelled "
             "Valid."),
            ("figure", "honor-vs-pixel-cpu.png",
             "Gemma 3 1B on the main processor, unplugged with the screen off, 256-token "
             "answers back to back. The Honor starts at 22.6 tokens a second, slows itself "
             "after 2.1 minutes and holds 18.3 for the remaining 35 minutes: 81% of its "
             "start. The Pixel starts at 16.8, slows at 5.6 minutes and ends at 14.2, 84% of "
             "its start. The labels compare each phone's first ten answers with its last ten."),
            ("p",
             "Both phones slow down, and the newer one slows down sooner. It still finishes "
             "29% faster, and it warmed its battery less (6.0 °C against 7.9 °C). The Honor's "
             "line also flattens out completely, so for it the tool could report a settled "
             "speed and a Retained figure: 76%. That figure is a little lower than the 81% on "
             "the chart, because the tool's best speed came from a short burst about 30 "
             "minutes in rather than from the start. It is a reminder to look at the graph as "
             "well as the number."),

            ("h2", "When the screen matters more than the heat"),
            ("p",
             "The same Honor, this time on its graphics chip, measured twice: once with the "
             "screen off, as usual, and once with the screen kept on and dimmed."),
            ("figure", "honor-gpu-screen-on-off.png",
             "Gemma 3 1B on the Honor Magic V3's graphics chip. With the screen off, the "
             "speed fell from about 31 to 3.3 tokens a second at 4.8 minutes and stayed there, "
             "while the phone reported no overheating and its battery was cooling. With the "
             "screen on, the phone got much hotter (its most severe warning level, battery at "
             "40 °C) and still answered at about 31 tokens a second for all 20 minutes."),
            ("p",
             "If heat were to blame, the hotter run would be the slower one. It is the other "
             "way round, so the collapse came from something tied to the screen being off, "
             "most likely the phone's own power saving. For anyone building an app, that "
             "means a task left running with the screen off on this phone could take about "
             "nine times longer than testing suggests. For anyone measuring phones, it means "
             "a screen-off result on this phone describes its power saving, not its chip."),
            ("note",
             "Both of these runs were labelled <b>Conditions not met</b>, because the phone "
             "started each one a little warm. That does not change the comparison, but it "
             "does mean neither speed should be quoted as the phone's figure until the pair "
             "has been repeated from a cool start."),

            ("h2", "The trust label"),
            ("p",
             "Every result carries one of three labels, and it is displayed prominently "
             "because a number without it can be badly misleading."),
            ("table", [
                ["Label", "Meaning"],
                ["Valid", "Conditions were met and enough data was gathered. Comparable with other valid results."],
                ["Conditions not met", "The phone was warm, charging or otherwise unsuitable. Not comparable with anything."],
                ["Not enough data", "The run was too short to support the conclusions this tool is designed for."],
            ], [26, 74]),

            ("note",
             "One case deliberately stays <b>valid</b>: a run that the phone itself cut short "
             "by refusing to continue. That is not a failed measurement. It is the strongest "
             "possible version of the finding, and one of the phones in the original study "
             "behaved exactly that way."),

            ("h2", "When the tool says it does not know"),
            ("p",
             "In several places a result may read as unavailable rather than showing a "
             "number. This is intentional throughout. Some phones will not report how hard "
             "their processors are working. Some will not report how close they are to "
             "overheating. Some runs never settle to a steady speed, so there is no settled "
             "speed to report."),
            ("p",
             "In every one of those cases the tool records the absence rather than "
             "substituting an estimate. A result that says a figure could not be measured is "
             "more useful than one that quietly invents it, because only the first can be "
             "checked."),
        ],
    },

    # ------------------------------------------------------------------ 9
    {
        "title": "Comparing and sharing",
        "blocks": [
            ("h2", "Comparing"),
            ("p",
             "Results become interesting next to each other. The tool can compare different "
             "engines on one phone, and the same measurement across different phones."),
            ("p",
             "Two safeguards are built into every comparison. Results that were not marked "
             "valid are left out unless you explicitly ask for them, and putting genuinely "
             "different measurements side by side produces a warning, because only identical "
             "measurements are comparable."),

            ("h2", "The result file"),
            ("p",
             "Every run produces a file containing everything: what was measured, on what, "
             "under what conditions, every individual reading, and the conclusions. A second "
             "file is written in a spreadsheet format that opens in any spreadsheet program."),
            ("p",
             "These files are written in a strictly consistent way, so the same measurement "
             "always produces an identical file. That is what makes it possible to prove a "
             "result has not been altered, and to re-examine old measurements later with "
             "improved analysis."),

            ("h2", "Publishing a result"),
            ("p",
             "There is an optional shared leaderboard. Nothing is ever sent anywhere unless "
             "you switch it on for that specific result."),
            ("bullets", [
                "What is sent: the summary figures, the chip model, the Android version, and an anonymous identifier that cannot be traced back to you.",
                "What is never sent: the text used, anything the AI produced, and anything identifying you.",
                "Separately optional: your phone's model name, and the individual readings.",
                "Only results marked valid can be published at all.",
            ]),
        ],
    },

    # ------------------------------------------------------------------ 10
    {
        "title": "What this tool will not do",
        "blocks": [
            ("p",
             "Being clear about limits is part of being trustworthy, so this section lists "
             "them plainly."),

            ("bullets", [
                "<b>It cannot see inside the chip.</b> Detailed internal temperatures are restricted by Android to system software. The tool uses the heat level the phone reports about itself, which is coarser but available on every device.",
                "<b>Battery temperature is not chip temperature.</b> It lags by minutes and is used only as supporting evidence.",
                "<b>It will not guess.</b> Any figure a phone declines to report is recorded as unavailable.",
                "<b>It cannot make a phone comparable to itself under different conditions.</b> Hence the strict rules about starting cool and unplugged.",
                "<b>It does not measure answer quality.</b> Only speed and heat. Whether the AI produced anything sensible is a separate question entirely.",
                "<b>It cannot remove its own influence completely.</b> It can only minimise it, record which mode was used, and let that influence be measured directly.",
            ]),

            ("h2", "The habit behind all of this"),
            ("note",
             "Wherever this tool could have guessed, it records that it does not know: a "
             "reading the phone withheld, a pattern that would not fit, a speed that never "
             "settled, an engine that would not load. That habit is the entire reason a "
             "number from this tool is worth quoting. It is also the thing most worth "
             "preserving as the tool grows."),
        ],
    },

    # ------------------------------------------------------------------ 11
    {
        "title": "Where to go next",
        "blocks": [
            ("p",
             "If you want to start using it, the shortest useful path is this."),
            ("steps", [
                "Get a small model file so the first attempt is quick rather than a large download.",
                "Put it on the phone, using whichever of the three methods suits you.",
                "Run a short measurement, ten repetitions or so, purely to confirm everything works. Expect the result to be labelled as not having enough data. That is correct.",
                "Then run a proper one: fifty repetitions, phone cool and unplugged, left alone.",
                "Run the same thing again with a different part of the chip, and compare.",
            ]),
            ("p",
             "A small model will confirm that everything is working but will not heat the "
             "phone enough to show real slowdown. For any result worth reporting, use a "
             "model of a billion parameters or more, which is where the interesting "
             "behaviour begins."),

            ("h2", "The companion document"),
            ("p",
             "There is a second, more technical manual covering the internal structure, the "
             "reasoning behind each engineering decision, the exact statistics used, and how "
             "to build and extend the tool. If you want to know how something works rather "
             "than how to use it, that is where to look."),
        ],
    },
]
