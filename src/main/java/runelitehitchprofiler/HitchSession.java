package runelitehitchprofiler;

import java.util.*;

/** Client-thread owned measurements. No game API or disk access. */
final class HitchSession
{
    static final long WINDOW = 1_000_000_000L;
    final String id = UUID.randomUUID().toString();
    final int threshold, severeThreshold;
    final Deque<Entry> history = new ArrayDeque<>();
    final Deque<Entry> waiting = new ArrayDeque<>();
    final Deque<Long> loadSignals = new ArrayDeque<>();
    final Map<String, Stats> contexts = new LinkedHashMap<>();
    final Stats total = new Stats();
    long loads, correlated, dropped;

    HitchSession(int threshold, int severe)
    {
        this.threshold = threshold;
        this.severeThreshold = Math.max(threshold, severe);
        contexts.put("BOAT_LIKELY", new Stats());
        contexts.put("LAND_LIKELY", new Stats());
        contexts.put("UNKNOWN", new Stats());
    }

    Entry frame(long start, long end, long wall, String context)
    {
        return frame(start, end, wall, context, "");
    }

    Entry frame(long start, long end, long wall, String context, String location)
    {
        return frame(start, end, wall, context, location, "");
    }

    Entry frame(long start, long end, long wall, String context, String location, String diagnostic)
    {
        double ms = (end - start) / 1_000_000.0;
        total.add(ms, threshold, severeThreshold);
        contexts.get(context).add(ms, threshold, severeThreshold);
        if (ms < threshold) return null;
        Entry e = new Entry("HITCH", start, end, wall, ms, context, -1, -1, diagnostic, location);
        for (long signal : loadSignals)
            if (signal >= start - WINDOW && signal <= end + WINDOW) e.nearLoad = true;
        waiting.add(e);
        remember(e);
        return e;
    }

    void signal(long time)
    {
        loadSignals.add(time);
        while (loadSignals.size() > 256) loadSignals.removeFirst();
        for (Entry e : waiting)
            if (time >= e.start - WINDOW && time <= e.end + WINDOW) e.nearLoad = true;
    }

    Entry load(long start, long end, long wall, String context, int view, int regions)
    {
        loads++;
        signal(start);
        signal(end);
        Entry e = new Entry("LOAD_INTERVAL", start, end, wall, (end - start) / 1_000_000.0,
            context, view, regions, "PreMapLoad to WorldViewLoaded; partial interval only", "");
        remember(e);
        return e;
    }

    List<Entry> drain(long now, boolean force)
    {
        while (!loadSignals.isEmpty() && now - loadSignals.peek() > 60_000_000_000L) loadSignals.removeFirst();
        if (waiting.isEmpty() || (!force && now - waiting.peek().end <= WINDOW)) return Collections.emptyList();
        List<Entry> result = new ArrayList<>();
        while (!waiting.isEmpty() && (force || now - waiting.peek().end > WINDOW))
        {
            Entry e = waiting.removeFirst();
            e.finalized = true;
            if (e.nearLoad) correlated++;
            result.add(e);
        }
        while (!loadSignals.isEmpty() && now - loadSignals.peek() > 60_000_000_000L) loadSignals.removeFirst();
        return result;
    }

    Entry note(long time, String text)
    {
        Entry e = new Entry("NOTE", time, time, System.currentTimeMillis(), 0, "UNKNOWN", -1, -1, text, "");
        remember(e);
        return e;
    }

    private void remember(Entry e)
    {
        history.addFirst(e);
        while (history.size() > 300) history.removeLast();
    }

    String report()
    {
        StringBuilder b = new StringBuilder("RuneLite Hitch Profiler 2.0\nSession: ").append(id)
            .append("\nThresholds: ").append(threshold).append(" / ").append(severeThreshold).append(" ms\n")
            .append(total.describe()).append("\nCompleted map intervals: ").append(loads)
            .append("\nHitches near load signals: ").append(correlated)
            .append("\nPending correlation: ").append(waiting.size())
            .append("\nDropped capture events: ").append(dropped).append('\n');
        contexts.forEach((key, value) -> b.append(key).append(": ").append(value.describe()).append('\n'));
        return b.append("Frame gaps measure callback cadence, not GPU execution. Near-load means within 1 second of a load signal or a signal inside the gap; association does not prove cause.\nBoat context is inferred from camera focus; unknown is retained.\n").toString();
    }

    static final class Stats
    {
        long frames, hitches, severe;
        double millis, peak;
        final long[] histogram = new long[2001];
        void add(double ms, int threshold, int severeThreshold)
        {
            frames++; millis += ms; peak = Math.max(peak, ms);
            histogram[Math.min(2000, (int) Math.ceil(ms))]++;
            if (ms >= threshold) hitches++;
            if (ms >= severeThreshold) severe++;
        }
        int percentile(double q)
        {
            if (frames == 0) return 0;
            long target = (long) Math.ceil(frames * q), sum = 0;
            for (int i = 0; i < histogram.length; i++) { sum += histogram[i]; if (sum >= target) return i; }
            return 2000;
        }
        String describe()
        {
            return String.format(Locale.ROOT, "%.1f min sampled; %d hitches; %d severe; peak %.1f ms; median %s ms; p95 %s ms; %.1f hitches/min",
                millis / 60000, hitches, severe, peak, label(percentile(.5)), label(percentile(.95)), millis == 0 ? 0 : hitches * 60000 / millis);
        }
        private String label(int ms) { return ms == 2000 ? "2000+" : Integer.toString(ms); }
    }

    static final class Entry
    {
        final String type, context, detail, location;
        final long start, end, wall;
        final double ms;
        final int view, regions;
        boolean nearLoad, finalized;
        Entry(String type, long start, long end, long wall, double ms, String context, int view, int regions, String detail, String location)
        {
            this.type = type; this.start = start; this.end = end; this.wall = wall; this.ms = ms;
            this.context = context; this.view = view; this.regions = regions; this.detail = detail; this.location = location;
            finalized = !type.equals("HITCH");
        }
    }
}
