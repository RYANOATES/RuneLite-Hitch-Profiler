![](readme-assets/profiler-header.svg)

# RuneLite Hitch Profiler

<p align="center"><strong>Find the pauses between frames. Keep the evidence. Compare the run.</strong></p>

RuneLite Hitch Profiler records frame-time gaps and nearby client activity in a readable side panel and a local HTML report. Use it while travelling, questing, bossing, or comparing graphics settings. It does not change RuneLite's renderer or game settings.

## Side panel guide

The annotated panel below explains the live metrics, hitch graph, event history, session controls, and local notes. The personal file path in the supplied screenshot is hidden in this public-facing graphic.

![Annotated RuneLite Hitch Profiler side panel guide](readme-assets/profiler-banner.svg)

![Three steps: measure a frame gap, inspect bounded clues, review the local history](readme-assets/profiler-workflow.svg)

## Install from the RuneLite Plugin Hub

1. Open the RuneLite client and select **Plugin Hub** from the sidebar.
2. Search for **RuneLite Hitch Profiler** and install it.
3. In the plugin list, enable **RuneLite Hitch Profiler**.
4. Select its shield-and-graph sidebar button to open the profiler.

## Use the profiler

The **Live** tab shows the current session summary, hitch graph, event history, and filters. Select an event to read its context. Use **Pause** to stop recording temporarily, or **New session** to start a fresh run while keeping the previous session available for comparison. Add short notes for route, renderer, and settings so the conditions are recorded with the run.

The **Compare** tab places the current and previous session summaries together. The **Saved** tab lists earlier events from disk. Use **Copy report** to copy the current session summary and logging status. Open the HTML report from the local data folder described below.

### Settings

- **Hitch threshold** — minimum frame gap to record; default **50 ms**.
- **Severe threshold** — marks larger gaps in red and enables severe-event diagnostics; default **150 ms**.
- **Pause when unfocused** — avoids treating RuneLite's background frame limiting as a gameplay hitch; enabled by default.

Threshold changes apply to the next session. Severe events can include bounded client-thread stack samples, nearby event counts, map-loading clues, and a world location when RuneLite provides one. Location is captured only for severe hitches. Boat position is inferred from the camera focus entity; on land it uses the local player's tile.

## What the measurements mean

The profiler measures time between RuneLite frame callbacks. That is useful for finding stalls, but it is not a direct measurement of GPU execution or monitor presentation. Stack samples and nearby events are clues that can help narrow down a hitch; they do not prove a single cause.

The profiler keeps a bounded in-memory event history and appends session records to JSON Lines (JSONL). It refreshes a dark, brass-trimmed HTML report as you play and when the plugin closes. The report includes a severe-location table; clicking a timestamp opens the pinned OSRS map for that event. Map tiles load only when the map is opened.

## Local data and privacy

Session records and reports stay on your computer. The plugin does not upload them. The map viewer requests map tiles from its tile provider only when you open a location.

Files are stored in RuneLite's plugin data directory:

- `sailing-hitches.jsonl` — append-only session events.
- `sailing-hitches.html` — readable report generated from those events.

The folder is `~/.runelite/plugin-data/sailing-load-profiler/` (on Windows, `%USERPROFILE%\.runelite\plugin-data\sailing-load-profiler\`). These legacy names are retained so your existing settings and recordings remain available after the project was renamed.

## License

See [LICENSE](LICENSE).
