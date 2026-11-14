"""Prose for the plain-English ThermalBench guide, second edition.

Same layout engine and the same facts as guide_content.py, rewritten in a
plainer, more conversational voice: one engineer explaining the tool to another.
"""

TITLE = "ThermalBench"
SUBTITLE = "A plain-English guide to what this tool measures, and why the answer is hard to get right"
VERSION = "0.1.0"
OUTPUT_NAME = "ThermalBench-Guide_02.pdf"
RUNNING_HEAD = "ThermalBench — Guide"
ACCENT_HEX = "#1D6B4F"
COVER_LEAD = (
    "How fast does your phone run AI after twenty minutes of hard work, not after "
    "twenty seconds? This guide explains what ThermalBench measures, how to run it, "
    "and how to read what it tells you.")
COVER_META = [
    ["Audience", "Anyone who wants to measure a phone, no engineering background needed"],
    ["Companion", "The ThermalBench Developer Manual, for how it works inside"],
]

DOCUMENT = [
    # ------------------------------------------------------------------ 1
    {
        "title": "The problem in one page",
        "blocks": [
            ("lead",
             "Phones get hot. When they do, they slow themselves down on purpose to protect "
             "the battery, the chips and your hand. Every phone does this, and it's normal. "
             "The trouble is that almost nobody measures it."),

            ("p",
             "When you read that a phone is fast, the number was almost certainly measured "
             "in the first few seconds of work, on a cool device. That's the easiest moment "
             "there is. Ask the same phone to keep working for ten minutes and it will "
             "quietly give you a fraction of what it first promised."),

            ("p",
             "ThermalBench measures that fraction. It runs an artificial intelligence model "
             "on the phone, over and over with no pause, and records what happens as the "
             "phone heats up: how quickly it slows, when the operating system starts holding "
             "it back, and where the speed finally settles."),

            ("h2", "The one number this is all for"),
            ("p",
             "We call it the <b>retention ratio</b>: the settled speed divided by the best "
             "speed, written as a percentage."),
            ("note",
             "A phone that starts at 20 words per second and settles at 9 retained 45%. The "
             "headline speed was real. It was also temporary. The retention ratio describes "
             "what using the phone actually feels like."),

            ("p",
             "Different people care for different reasons. If you're buying a phone, you want "
             "to know whether the advertised speed survives real use. If you're building an "
             "app, you want to know what performance to design for. If you review hardware, "
             "you want a measurement that isn't just the first two seconds of a benchmark."),

            ("h2", "Why this isn't already common knowledge"),
            ("p",
             "A study published in early 2026 tested the same AI model on several phones. "
             "On every one of them, heat, not raw processing power, was the main limit. One "
             "well-known phone lost close to half its speed within two repetitions. Another "
             "hit a limit set by its own operating system, which stopped the work entirely."),
            ("p",
             "The same study pointed out that the industry-standard benchmark suite measures "
             "peak performance and doesn't track heat-related slowdown at all. Existing phone "
             "apps show you a temperature reading as a snapshot. Nobody had turned the "
             "slowdown curve itself into a tool. So that's what this is."),
        ],
    },

    # ------------------------------------------------------------------ 1b
    {
        "title": "Who would find ThermalBench most useful",
        "blocks": [
            ("p",
             "The clearest audience is engineers shipping AI that runs on the phone itself: "
             "Android developers, ML engineers, and product teams choosing a model, a size, a "
             "file format and a processor. Launch-day benchmarks report the first few seconds, "
             "while the phone is cool. Real use lasts minutes. Think of a long chat, live "
             "transcription, or an assistant summarising your inbox. Over that time a phone "
             "can lose a large share of its speed as it heats up and slows itself down."),
            ("p",
             "ThermalBench measures the speed you actually get after ten minutes, not the "
             "peak. Its results are repeatable and tied to a spec ID, with checked model "
             "files and a pinned engine version. That lets a team settle questions like "
             "\"the 1.5B model on GPU holds up better than the 3B model on CPU on mid-range "
             "phones\" with evidence instead of opinion."),
            ("p",
             "The second audience is people who compare phones: hardware reviewers, tech "
             "journalists, phone makers' performance and thermal teams, and researchers in "
             "universities and industry. Reviewers can add a \"sustained AI performance\" "
             "score that shows which phones keep their speed and which fall away sharply. "
             "Thermal engineers can watch how a cooling design and the phone's throttling "
             "rules behave under a realistic AI load. Researchers get a scriptable harness "
             "with results they can export for papers."),
            ("p",
             "And if you run local models on your own phone, or you're choosing a phone for "
             "on-device AI, a plain score like \"keeps 78% of its speed\" tells you more than "
             "any launch-day chart."),
        ],
    },

    # ------------------------------------------------------------------ 2
    {
        "title": "Why measuring this honestly is hard",
        "blocks": [
            ("p",
             "Three problems make this much harder than it sounds. Nearly every unusual "
             "decision later in this guide comes from one of them, so start here."),

            ("h2", "Problem one: the tool heats the phone too"),
            ("p",
             "A measuring app is itself a program running on the phone it measures. Its "
             "screen, its moving charts and its saving of results all make heat on the very "
             "chip whose heat you're trying to watch. It's like taking a room's temperature "
             "with a thermometer that is slightly on fire."),
            ("p",
             "So the real measurement runs with <b>no visible app at all</b>. There's a mode "
             "with no screen, no charts and no buttons, driven from a computer over a cable. "
             "The app with the nice interface is a convenience. Every saved result records "
             "which of the two produced it, so nobody can quietly compare them as if they "
             "were the same thing."),

            ("h2", "Problem two: the starting condition changes the answer"),
            ("p",
             "A warm phone gives a different result from a cold one. A charging phone is "
             "being heated by its own battery. A dim screen is less work than a bright one. "
             "And a phone at 15% battery is already slowing itself down for a completely "
             "different reason."),
            ("p",
             "So before a measurement starts, the tool waits and checks. It won't begin until "
             "the phone is cool, unplugged and charged enough, and has stayed that way for a "
             "set period without a break."),
            ("warn",
             "If those conditions aren't met, you don't just get a slightly worse "
             "measurement. The result is marked <b>invalid</b>, and the label stays with it "
             "for good. The tool never quietly adjusts a number to make up for bad "
             "conditions, because there's no honest way to do that."),

            ("h2", "Problem three: clocks move"),
            ("p",
             "Phones correct their clocks against the internet. If a correction lands in the "
             "middle of a forty-minute measurement, every timing after it shifts. The graph "
             "would be wrong, and nothing about it would look wrong."),
            ("p",
             "So the tool times everything with a counter that only ever moves forward and is "
             "never corrected. It records the ordinary date and time exactly once, as a label "
             "saying when the run happened, and never uses it to calculate anything."),
        ],
    },

    # ------------------------------------------------------------------ 3
    {
        "title": "The words you need",
        "blocks": [
            ("p",
             "The rest of this guide sticks to plain language, but you can't avoid these six "
             "terms. They name things with no everyday equivalent."),

            ("table", [
                ["Term", "What it means here"],
                ["Model",
                 "The artificial intelligence itself, stored as a very large file of numbers. "
                 "It's data, not a program. The tool ships none and runs whichever one you give it."],
                ["Token",
                 "Roughly a short word, or part of one. AI systems read and write in tokens, "
                 "so speed is measured in tokens per second."],
                ["Backend",
                 "The software engine that loads a model and does the arithmetic. Nothing to "
                 "do with servers or the internet: everything runs on the phone."],
                ["Compute unit",
                 "Which part of the chip does the work: the main processor, the graphics "
                 "processor, or a dedicated AI accelerator."],
                ["Throttling",
                 "The phone slowing itself down on purpose to control heat. This is the "
                 "behaviour being measured, not a fault."],
                ["Iteration",
                 "One repetition of the work. A measurement is usually fifty or more of them, "
                 "back to back with no rest."],
            ], [20, 80]),

            ("note",
             "<b>Backend</b> trips people up the most. In most software it means a server "
             "somewhere on the internet. Here it means an engine running on the phone in your "
             "hand. Nothing from a measurement leaves the device unless you choose to publish "
             "a result."),
        ],
    },

    # ------------------------------------------------------------------ 4
    {
        "title": "What happens during a measurement",
        "blocks": [
            ("p", "Every run goes through the same five stages."),

            ("steps", [
                "<b>The plan is checked.</b> Before anything runs, the tool looks at what "
                "you've asked for and refuses combinations that couldn't give a fair "
                "comparison. Better to find out now than an hour into a measurement.",

                "<b>The phone settles.</b> The tool waits for the phone to be cool, unplugged "
                "and stable, and shows you exactly what is still holding up the start.",

                "<b>The model loads.</b> The tool checks the file and hands it to the engine "
                "you chose, and records how long that took.",

                "<b>Warm-up, then the real measurement.</b> The first few repetitions are run "
                "and thrown away, because they're never typical. Then the measured "
                "repetitions run back to back with no pause.",

                "<b>The results are worked out and saved.</b> The raw readings become a "
                "summary, and everything goes into a file you can keep, share or look at "
                "again later.",
            ]),

            ("h2", "What gets recorded along the way"),
            ("bullets", [
                "How fast the model produced words, for every single repetition.",
                "How long the phone took to produce its first word each time. This creeps upward as the phone heats.",
                "The phone's own reported heat level, sampled steadily and also caught the instant it changes.",
                "Battery level and battery temperature.",
                "The temperature of each chip sensor (processor, graphics, case and so on), on phones that allow apps to read them.",
                "How hard the processor cores are running, on phones that will say.",
                "Memory use.",
            ]),

            ("p",
             "The tool keeps two records of heat on purpose. One is a steady sample at a fixed "
             "rhythm, and that's what the graph is drawn from. The other catches the exact "
             "moment the phone changed its behaviour, which regular sampling would blur."),

            ("h2", "Which temperature, exactly?"),
            ("p",
             "The only temperature every phone reports to an app is the battery's, and that's "
             "the one on the charts. Think of the battery as a big, slow lump sitting next to "
             "the chip. It warms up minutes after the chip does and never gets as hot, so it "
             "tells you how much heat soaked into the phone, not how hot the chip is right "
             "now."),
            ("p",
             "The phone's heat level isn't a temperature at all. It's the phone's own verdict "
             "(none, light, moderate, severe and so on), worked out from sensors in the chip "
             "and the case that the app doesn't get to see. On some phones the tool can read those "
             "sensors directly and records them too. It depends entirely on the phone maker. "
             "The Pixel 6 and the Honor Magic V3 allow none, so their results only have the "
             "battery figure. A Samsung Galaxy Tab S7+ allows 52, though none in its case. The "
             "OnePlus 8T allows 76, including every processor core, the graphics chip and "
             "three sensors in the case."),
            ("note",
             "The OnePlus shows how far behind the battery lags. In a twelve-second test its "
             "processor cores went from about 40 °C to 62 °C. The battery went from 30.7 °C "
             "to 30.8 °C. On the Samsung tablet, a test of just over a minute took its "
             "processor cores from about 50 °C to 76 °C, while the battery went from 27.3 °C "
             "to 27.5 °C."),
        ],
    },

    # ------------------------------------------------------------------ 4b
    {
        "title": "What the phone is actually running",
        "blocks": [
            ("lead",
             "Each repetition is text generation. The model reads one fixed question and "
             "writes an answer to it, one token at a time. It doesn't classify, score or "
             "analyse anything. It just writes."),

            ("p",
             "Here's the question. It's the same for every repetition, every engine and "
             "every phone:"),
            ("code",
             "You are a careful technical writer. In plain English, explain how a mobile\n"
             "phone manages heat while running a demanding application for a long time.\n"
             "Cover the roles of the battery, the system-on-chip, the operating system's\n"
             "thermal policy, and the trade-off between performance and temperature.\n"
             "Write in complete sentences, avoid lists, and keep a steady, measured tone\n"
             "throughout the whole explanation without stopping early."),
            ("p",
             "Yes, the phone is being asked to explain its own overheating. What matters more "
             "is that the question asks for steady prose: no lists, and nothing that invites "
             "a short answer."),

            ("h2", "One repetition, step by step"),
            ("steps", [
                "<b>A fresh start.</b> The engine opens a new, empty conversation. Nothing "
                "from the previous answer is carried over, so answer fifty is exactly as much "
                "work as answer one.",

                "<b>Reading the question.</b> The model takes in all of the question's roughly "
                "128 tokens in one go. This is the quick part, and it ends when the first word "
                "of the answer appears. That wait is the first-word delay you see in the results.",

                "<b>Writing the answer.</b> The model then produces the answer one token at a "
                "time. Every single token means running the whole model once, start to finish: "
                "billions of multiplications for a billion-parameter model. This is the part "
                "that heats the chip, and its speed is the tokens-per-second figure on every "
                "chart.",

                "<b>A hard stop.</b> Generation stops after exactly the number of tokens the "
                "run asked for: 128 by default, 256 in the charts in this guide. It doesn't "
                "stop when the model feels it has finished. The answer text is then thrown "
                "away; it's never saved.",
            ]),

            ("h2", "Why it's set up this way"),
            ("bullets", [
                "<b>Always the most likely next word.</b> The model is told to pick its single "
                "most likely next token every time, with no randomness. A random choice could "
                "change how long the work takes, and that would look like a change in speed.",
                "<b>A fixed length, not a natural ending.</b> Different models decide they're "
                "\"done\" at different points. Stopping at a fixed count means every engine "
                "does the same amount of writing, so their speeds can be compared fairly.",
                "<b>A fixed question.</b> The question's fingerprint is saved with the "
                "result. Change one word and the tool treats it as a different measurement.",
            ]),

            ("note",
             "So a 50-repetition run with 256-token answers asks the phone to write 12,800 "
             "tokens, roughly 9,500 words, plus 768 more in the three warm-up answers it "
             "throws away. That's about the length of a long magazine feature, written from "
             "scratch with no pause."),
        ],
    },

    # ------------------------------------------------------------------ 5
    {
        "title": "What you need before you start",
        "blocks": [
            ("h2", "A phone"),
            ("p",
             "Any reasonably modern Android phone. Use real hardware: the whole subject is "
             "physical heat, and an emulator doesn't have any."),

            ("h2", "A model file"),
            ("p",
             "This is the part people usually don't have yet. ThermalBench doesn't include an "
             "AI model. It measures whichever one you give it, on purpose: the model you care "
             "about is the one worth measuring."),
            ("p",
             "These files are big, typically from about 150 megabytes to several gigabytes. "
             "You can download them free, though some ask you to accept a licence first. Each "
             "engine reads its own file format and they aren't interchangeable. You don't "
             "need to track that yourself; the tool works it out from the file's name."),

            ("h2", "Getting the file onto the phone"),
            ("p",
             "Modern Android is strict about which files an app may open, so you can't just "
             "drop a model anywhere. Use any of these three ways:"),
            ("bullets", [
                "Download it on the phone, then pick it in the app. The app copies it into its own storage and shows a progress bar while it works.",
                "Copy it from a computer over a cable, straight into the app's own folder. The app shows you the exact command.",
                "Use the fleet tool, which copies to several phones at once.",
            ]),
            ("note",
             "The app lists every model it finds, and tells you which folder it looked in and "
             "when. If your file isn't showing up, read that line first. It tells \"nothing "
             "there\" apart from \"looking in the wrong place\"."),
        ],
    },

    # ------------------------------------------------------------------ 6
    {
        "title": "Choosing what to measure",
        "blocks": [
            ("p",
             "Three choices define a measurement. The tool saves all three with the result, so "
             "anyone can see exactly what was tested."),

            ("h2", "Which engine"),
            ("p",
             "Four are built in. Two come from Google. One is the widely used open-source "
             "engine that runs the file format most of the community publishes in. The fourth "
             "isn't finished yet."),
            ("table", [
                ["Engine", "Where it comes from", "Notes"],
                ["LiteRT-LM", "Google",
                 "The reference choice. It reports its own timings in more detail than the others, so its numbers need the least interpretation."],
                ["MediaPipe GenAI", "Google",
                 "A second Google engine. Handy as a cross-check on the same phone."],
                ["llama.cpp", "Open source",
                 "Reads the format most freely available models come in. Needs an extra build step to turn on."],
                ["ExecuTorch", "Meta",
                 "Not implemented yet. It appears in the list and says so."],
            ], [20, 20, 60]),

            ("note",
             "If an engine can't run on your phone, it stays in the list with the reason next "
             "to it instead of vanishing. Knowing a phone can't do something is a finding. "
             "Hiding it would make the tool look more capable than it is."),

            ("h2", "Which part of the chip"),
            ("p",
             "The same model on the same phone behaves very differently depending on where it "
             "runs. The differences in heat are as large as the differences in speed."),
            ("bullets", [
                "<b>Main processor.</b> Available on every phone, and usually the slowest.",
                "<b>Graphics processor.</b> Often the fastest, and often the first to be throttled.",
                "<b>AI accelerator.</b> Only on newer phones, and usually the most efficient.",
            ]),

            ("h2", "How long to run"),
            ("p",
             "Fifty repetitions is the sensible default. It's the point where the tool treats a "
             "result as solid enough for the conclusions it's built to support. You can run "
             "fewer for a quick check. The result is then labelled as not having enough data, "
             "rather than being quietly treated as just as good."),
            ("warn",
             "One rule you can't change, and it's the reason results can be compared at all. "
             "Every engine is told to produce exactly the same number of words from exactly "
             "the same starting text. Without that, comparing their speeds would mean nothing, "
             "so the tool refuses to run any engine that can't obey it."),
        ],
    },

    # ------------------------------------------------------------------ 7
    {
        "title": "Running a measurement",
        "blocks": [
            ("h2", "The quick way"),
            ("steps", [
                "Open the app and choose New run.",
                "Pick a model from the list, or tap to browse for one.",
                "Pick an engine and the part of the chip to use.",
                "Leave the settings alone unless you have a reason to change them.",
                "Press Start, then unplug the phone and leave it alone.",
            ]),
            ("p",
             "The tool waits until the phone is ready, tells you what it's waiting for, and "
             "starts by itself. It keeps running with the screen off, protected from the "
             "phone's usual power saving. Expect a run to take tens of minutes and to leave "
             "the phone warm."),
            ("note",
             "Some phones go further than normal power saving and slow down any work done "
             "with the screen off. If yours might be one of them, see <b>Keeping the screen "
             "on</b> below."),

            ("h2", "Hearing how the cool-down is going"),
            ("p",
             "A phone you've just been using is usually too warm to start, so the tool first "
             "waits for it to cool. That wait happens with the screen off, and turning the "
             "screen on to check would restart it. So the phone tells you instead. Every 20 "
             "seconds it reads out the current figure, for example: <i>\"Current thermal "
             "margin is 0.27.\"</i> Once that number reaches 0.50, the phone is ready. It also "
             "tells you when it's halfway there, and when it's cool enough to begin."),
            ("note",
             "On a Samsung Galaxy Tab S7+, that number may never get there. Unplugged, screen "
             "off, with its processor back down to room temperature, the tablet still reported "
             "0.34 for more than ten minutes. It seems to judge by the temperature of its "
             "case, which cools slowly, rather than by the chip. If your Samsung sits below "
             "0.50 for a long time, the tool stops waiting after 15 minutes, runs anyway and "
             "labels the result <b>Conditions not met</b>. Starting in a cooler room is the "
             "only thing likely to help."),
            ("p",
             "The voice plays even with the ringer on silent. It follows the media volume, so "
             "if you can't hear it, turn that up. The same reading also appears in the "
             "notification on the lock screen. The phone goes quiet once the measurement "
             "starts, so the voice can't affect the result. At the end it tells you the run "
             "has finished and what share of its speed the phone kept."),

            ("h2", "Keeping the screen on"),
            ("p",
             "Normally the screen stays off, because the display warms the phone too. But "
             "some phones quietly hold back work done while the screen is off. On those, a "
             "screen-off measurement tells you about the phone's power saving, not its chip. "
             "Chapter 10 shows a phone doing exactly that."),
            ("p",
             "To check, turn on <b>Keep the screen on, dimmed</b> under Preconditions when you "
             "set up a run. The app keeps the screen lit at its lowest brightness for the whole "
             "run. Leave the app open and don't touch the phone. If the speed is very "
             "different from a screen-off run, the phone's power saving was the cause."),

            ("h2", "The rigorous way"),
            ("p",
             "For results you plan to publish, or to compare across several phones, use the "
             "mode driven from a computer, with no app interface running at all. It takes more "
             "setup, and it removes the last source of interference the app still has."),
            ("p",
             "The same tool can also drive several phones at once: it sends each one the same "
             "measurement and collects the results together."),

            ("h2", "A word about the live graph"),
            ("p",
             "Watching the graph during a measurement is the biggest single way the app "
             "affects its own results. So the graph updates only about once a second, and a "
             "switch turns it off completely, even mid-run."),
            ("note",
             "Here's an experiment worth doing. Run the same measurement twice on one phone, "
             "once watching the graph and once with no interface at all, and compare. The "
             "difference is a small but useful result in its own right, and it answers the "
             "first objection anyone will raise."),
        ],
    },

    # ------------------------------------------------------------------ 8
    {
        "title": "Reading the results",
        "blocks": [
            ("p",
             "The results screen opens with the retention ratio, because that's the point of "
             "the whole exercise. Everything else backs it up or qualifies it."),

            ("table", [
                ["What you see", "What it means"],
                ["Retained",
                 "The headline: the settled speed as a percentage of the best speed. Higher is better; 100% would mean no slowdown at all."],
                ["Peak",
                 "The best speed the phone kept up, not its single luckiest moment, which would flatter it."],
                ["Settled speed",
                 "Where the speed levelled off. If it never did, this says so instead of guessing."],
                ["Repetitions to first slowdown",
                 "How long the phone lasted before it started holding itself back. A big number means a well-cooled phone."],
                ["Slowdown pattern",
                 "The shape of the decline, including roughly how many seconds the phone takes to lose most of its headroom."],
                ["First-word delay drift",
                 "How much slower the phone gets at starting an answer as it heats up."],
                ["Battery used and heat gained",
                 "What the measurement cost the phone."],
            ], [26, 74]),

            ("h2", "What a real result looks like"),
            ("p",
             "Here are two measurements from a Pixel 6. The same small language model answers "
             "the same question again and again for about twenty minutes, once on the main "
             "processor and once on the graphics chip. The top panel shows the speed of each "
             "answer. The bottom panel shows the battery temperature over the same minutes."),
            ("figure", "pixel6-cpu-gpu.png",
             "Gemma 3 1B on a Pixel 6, unplugged with the screen off, 256-token answers "
             "back to back. The main processor starts about 40% faster (17.0 tokens a second), slows "
             "itself at 5.6 minutes and ends at 83% of its best, with the battery 7.9 °C "
             "warmer. The graphics chip is slower (12.1 tokens a second) but keeps 99% of its best "
             "and warms the battery only 3 °C. Both measurements were labelled Valid."),
            ("p",
             "A one-minute test would crown the main processor. Twenty minutes shows the "
             "trade-off: more speed now, paid for in heat, and a decline still going when the "
             "measurement ended. That's also why its settled speed reads as unavailable. It "
             "never levelled off, and the tool won't pretend it did."),

            ("h2", "Two phones, one question"),
            ("p",
             "Now the same model and the same question on the main processor of two phones: "
             "the Pixel 6 from above and a newer Honor Magic V3. Both measurements were "
             "labelled Valid."),
            ("figure", "honor-vs-pixel-cpu.png",
             "Gemma 3 1B on the main processor, unplugged with the screen off, 256-token "
             "answers back to back. The Honor starts at 22.6 tokens a second, slows itself "
             "after 2.1 minutes and holds 18.3 for the remaining 35 minutes: 81% of its "
             "start. The Pixel starts at 16.8, slows at 5.6 minutes and ends at 14.2, 84% of "
             "its start. The labels compare each phone's first ten answers with its last ten."),
            ("p",
             "Both phones slow down, and the newer one slows down sooner. It still finishes "
             "29% faster, and it warmed its battery less: 6.0 °C against 7.9 °C. The Honor's "
             "line also goes completely flat, so for it the tool could report a settled speed "
             "and a Retained figure of 76%."),
            ("note",
             "Why 76% and not the 81% on the chart? The tool's best speed for the Honor came "
             "from a short burst about 30 minutes in, not from the start. Look at the graph as "
             "well as the number."),

            ("h2", "When the screen matters more than the heat"),
            ("p",
             "The same Honor again, this time on its graphics chip, measured twice: once with "
             "the screen off, as usual, and once with the screen kept on and dimmed."),
            ("figure", "honor-gpu-screen-on-off.png",
             "Gemma 3 1B on the Honor Magic V3's graphics chip. With the screen off, the "
             "speed fell from about 31 to 3.3 tokens a second at 4.8 minutes and stayed there, "
             "while the phone reported no overheating and its battery was cooling. With the "
             "screen on, the phone got much hotter (its most severe warning level, battery at "
             "40 °C) and still answered at about 31 tokens a second for all 20 minutes."),
            ("p",
             "Follow the reasoning. If heat caused the collapse, the hotter run would be the "
             "slower one. It's the other way round. So the collapse came from something tied "
             "to the screen being off, most likely the phone's own power saving."),
            ("p",
             "If you build apps, that means a task left running with the screen off on this "
             "phone could take about nine times longer than your testing suggests. If you "
             "measure phones, it means a screen-off result on this phone describes its power "
             "saving, not its chip."),
            ("note",
             "Both runs were labelled <b>Conditions not met</b>, because the phone started each "
             "one a little warm. That doesn't change the comparison. It does mean you "
             "shouldn't quote either speed as the phone's figure until the pair has been "
             "repeated from a cool start."),

            ("h2", "A tablet that slows down before you start counting"),
            ("p",
             "The same test on a 2020 Samsung Galaxy Tab S7+, alongside the two phones."),
            ("figure", "three-devices-cpu.png",
             "Gemma 3 1B on the main processor of three devices, unplugged with the screen "
             "off. The tablet holds about 19.5 tokens a second for 36 minutes and warms its "
             "battery by only 0.6 °C. After half an hour it's the fastest of the three."),
            ("p",
             "The tablet's line is almost perfectly flat, and it finishes ahead of a phone "
             "four years newer. How? It slows itself down within the first 16 seconds, much "
             "harder and much earlier than the phones, and keeps its processor at a steady "
             "75–83 °C from then on. Its big case soaks up what's left, so the battery hardly "
             "warms. It gives up some opening speed to get a pace it can keep."),
            ("note",
             "That early slowdown fools the headline number. The tablet's result says it "
             "kept 98% of its speed, because it had already slowed down during the three "
             "warm-up answers the tool throws away. A short test from a cooler start opened at "
             "25.6 tokens a second; measured from there, it kept about 77%. When a result "
             "shows the phone slowing down before the first measured answer, read Retained as "
             "a best case. This run is also labelled <b>Conditions not met</b>, because the "
             "tablet never passed the start check (see Running a measurement)."),

            ("h2", "The trust label"),
            ("p",
             "Every result carries one of three labels, shown prominently, because a number "
             "without its label can badly mislead you."),
            ("table", [
                ["Label", "Meaning"],
                ["Valid", "Conditions were met and enough data was gathered. Comparable with other valid results."],
                ["Conditions not met", "The phone was warm, charging or otherwise unsuitable. Not comparable with anything."],
                ["Not enough data", "The run was too short to support the conclusions this tool is designed for."],
            ], [26, 74]),

            ("note",
             "One case stays <b>valid</b> on purpose: a run the phone itself cut short by "
             "refusing to continue. That isn't a failed measurement. It's the strongest "
             "possible version of the finding, and one of the phones in the original study "
             "did exactly that."),

            ("h2", "When the tool says it doesn't know"),
            ("p",
             "Sometimes a result reads as unavailable instead of showing a number. That's "
             "deliberate. Some phones won't report how hard their processors are working. "
             "Some won't report how close they are to overheating. Some runs never settle to "
             "a steady speed, so there's no settled speed to report."),
            ("p",
             "In every one of those cases, the tool records the gap instead of filling it with "
             "an estimate. A figure marked as unmeasured is more useful than one quietly "
             "invented, because you can only check the first."),
        ],
    },

    # ------------------------------------------------------------------ 9
    {
        "title": "Comparing and sharing",
        "blocks": [
            ("h2", "Comparing"),
            ("p",
             "Results get interesting side by side. The tool can compare different engines on "
             "one phone, and the same measurement across different phones."),
            ("p",
             "Two safeguards come with every comparison. Results not marked valid are left out "
             "unless you ask for them. And if you put genuinely different measurements side by "
             "side, you get a warning, because only identical measurements are comparable."),

            ("h2", "The result file"),
            ("p",
             "Every run writes a file holding everything: what was measured, on what, under "
             "what conditions, every individual reading, and the conclusions. A second file "
             "comes in spreadsheet format and opens in any spreadsheet program."),
            ("p",
             "These files are written in a strictly consistent way, so the same measurement "
             "always produces an identical file. That's how you can prove a result hasn't been "
             "altered, and how old measurements can be re-examined later with better analysis."),

            ("h2", "Publishing a result"),
            ("p",
             "There's an optional shared leaderboard. Nothing is sent anywhere unless you "
             "switch it on for that particular result."),
            ("bullets", [
                "What's sent: the summary figures, the chip model, the Android version, and an anonymous identifier that can't be traced back to you.",
                "What's never sent: the text used, anything the AI produced, and anything that identifies you.",
                "Optional, and asked separately: your phone's model name, and the individual readings.",
                "Only results marked valid can be published at all.",
            ]),
        ],
    },

    # ------------------------------------------------------------------ 10
    {
        "title": "What this tool will not do",
        "blocks": [
            ("p",
             "You can only trust a tool if you know its limits, so here they are."),

            ("bullets", [
                "<b>It often can't see inside the chip.</b> Android has no official way for apps to read the chip's own temperature sensors. The tool tries anyway and records them on phones that allow it, like the OnePlus 8T, but many don't: the Pixel 6 and the Honor Magic V3 block every one. What every phone does give is its own verdict on how hot it is, which is coarser but always there.",
                "<b>Battery temperature isn't chip temperature.</b> It lags by minutes, so the tool only uses it as supporting evidence.",
                "<b>It won't guess.</b> Any figure a phone declines to report is recorded as unavailable.",
                "<b>It can't make a phone comparable with itself under different conditions.</b> That's why the rules about starting cool and unplugged are so strict.",
                "<b>It doesn't measure answer quality.</b> Only speed and heat. Whether the AI said anything sensible is a separate question.",
                "<b>It can't remove its own influence completely.</b> It can keep it small, record which mode was used, and let you measure that influence directly.",
            ]),

            ("h2", "The habit behind all of this"),
            ("note",
             "Wherever this tool could have guessed, it records that it doesn't know instead: "
             "a reading the phone held back, a pattern that wouldn't fit, a speed that never "
             "settled, an engine that wouldn't load. That habit is the whole reason a number "
             "from this tool is worth quoting, and it's the thing most worth keeping as the "
             "tool grows."),
        ],
    },

    # ------------------------------------------------------------------ 11
    {
        "title": "Where to go next",
        "blocks": [
            ("p",
             "To get started, this is the shortest useful path."),
            ("steps", [
                "Get a small model file, so your first attempt is quick and not a long download.",
                "Put it on the phone, using whichever of the three methods suits you.",
                "Run a short measurement, ten repetitions or so, just to confirm everything works. Expect it to be labelled as not having enough data. That's correct.",
                "Then run a proper one: fifty repetitions, phone cool and unplugged, left alone.",
                "Run the same thing again on a different part of the chip, and compare.",
            ]),
            ("note",
             "A small model proves everything works, but it won't heat the phone enough to "
             "show real slowdown. For any result worth reporting, use a model of a billion "
             "parameters or more. That's where the interesting behaviour starts."),

            ("h2", "The companion document"),
            ("p",
             "If you want to know how something works rather than how to use it, read the "
             "Developer Manual. It covers the internal structure, the reasoning behind each "
             "engineering decision, the exact statistics, and how to build and extend the "
             "tool."),
        ],
    },
]
