package runelitehitchprofiler;

import java.util.*;
import java.util.concurrent.*;

/** Bounded in-memory breadcrumbs and low-frequency client-thread stack samples. */
final class HitchDiagnostics implements AutoCloseable
{
    private static final int MAX_STACK_SAMPLES = 40;
    private static final int MAX_STACKS = 6;
    private static final int MAX_CUES = 256;
    private static final long CUE_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Object stackLock = new Object();
    private final Map<String, Integer> stacks = new LinkedHashMap<>();
    private final Deque<Cue> cues = new ArrayDeque<>();
    private final ScheduledExecutorService sampler;
    private volatile Thread clientThread;
    private volatile long heartbeat;
    private volatile long severeThresholdNanos;
    private int samples;
    private boolean closed;

    HitchDiagnostics()
    {
        sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "RuneLite Hitch Profiler stall sampler");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(this::sampleSafely, 20, 20, TimeUnit.MILLISECONDS);
    }

    /** Ends the previous frame interval, returns its samples, and arms sampling for the next one. */
    String onFrame(long now, int severeThresholdMs)
    {
        String previous;
        synchronized (stackLock)
        {
            previous = formatStacks();
            stacks.clear();
            samples = 0;
            clientThread = Thread.currentThread();
            severeThresholdNanos = TimeUnit.MILLISECONDS.toNanos(severeThresholdMs);
            heartbeat = now;
        }
        return previous;
    }

    void record(String kind, long nano)
    {
        synchronized (cues)
        {
            cues.addLast(new Cue(kind, nano));
            prune(nano - CUE_RETENTION_NANOS);
            while (cues.size() > MAX_CUES) cues.removeFirst();
        }
    }

    String describeGap(long start, long end, String stackSummary)
    {
        String events = summarizeCues(start, end);
        if (stackSummary == null || stackSummary.isEmpty())
            stackSummary = "No client-thread stack captured; possible JVM-wide pause or native/GPU wait.";
        return stackSummary + (events.isEmpty() ? "" : " | Nearby events: " + events);
    }

    void reset()
    {
        synchronized (stackLock)
        {
            heartbeat = 0;
            clientThread = null;
            stacks.clear();
            samples = 0;
        }
        synchronized (cues) { cues.clear(); }
    }

    private void sampleSafely()
    {
        try { sample(); }
        catch (RuntimeException ignored) { /* Diagnostics must never affect game capture. */ }
    }

    private void sample()
    {
        long beat = heartbeat;
        Thread target = clientThread;
        long threshold = severeThresholdNanos;
        if (closed || beat == 0 || target == null || System.nanoTime() - beat < threshold) return;
        synchronized (stackLock) { if (samples >= MAX_STACK_SAMPLES) return; }

        String signature = signature(target.getStackTrace());
        if (signature.isEmpty()) return;
        synchronized (stackLock)
        {
            if (heartbeat != beat || samples >= MAX_STACK_SAMPLES) return;
            samples++;
            if (stacks.containsKey(signature)) stacks.put(signature, stacks.get(signature) + 1);
            else if (stacks.size() < MAX_STACKS) stacks.put(signature, 1);
        }
    }

    private static String signature(StackTraceElement[] trace)
    {
        StringBuilder out = new StringBuilder();
        int included = 0;
        for (StackTraceElement frame : trace)
        {
            String name = frame.getClassName();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.google.common.eventbus")
                || name.startsWith("net.runelite.client.eventbus")) continue;
            if (out.length() > 0) out.append(" <- ");
            out.append(name).append('.').append(frame.getMethodName());
            if (frame.getLineNumber() >= 0) out.append(':').append(frame.getLineNumber());
            if (++included == 4) break;
        }
        if (out.length() == 0 && trace.length > 0) out.append(trace[0].toString());
        return out.length() > 360 ? out.substring(0, 357) + "..." : out.toString();
    }

    private String formatStacks()
    {
        if (samples == 0) return "";
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(stacks.entrySet());
        ranked.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder("Client-thread stack samples (20 ms sampling; ")
            .append(samples).append(" samples): ");
        for (int i=0;i<ranked.size();i++)
        {
            if (i>0) out.append(" | ");
            out.append(ranked.get(i).getValue()).append("x ").append(ranked.get(i).getKey());
            if (out.length() > 900) { out.setLength(897); out.append("..."); break; }
        }
        return out.toString();
    }

    private String summarizeCues(long start, long end)
    {
        Map<String, Integer> during = new LinkedHashMap<>(), before = new LinkedHashMap<>();
        synchronized (cues)
        {
            prune(start - CUE_RETENTION_NANOS);
            for (Cue cue : cues)
            {
                if (cue.nano >= start && cue.nano <= end) increment(during, cue.kind);
                else if (cue.nano >= start - CUE_RETENTION_NANOS && cue.nano < start) increment(before, cue.kind);
            }
        }
        String duringText = formatCounts(during), beforeText = formatCounts(before);
        if (!duringText.isEmpty() && !beforeText.isEmpty()) return "during [" + duringText + "]; preceding 5s [" + beforeText + "]";
        if (!duringText.isEmpty()) return "during [" + duringText + "]";
        if (!beforeText.isEmpty()) return "preceding 5s [" + beforeText + "]";
        return "";
    }

    private void prune(long cutoff)
    { while (!cues.isEmpty() && cues.peekFirst().nano < cutoff) cues.removeFirst(); }

    private static void increment(Map<String, Integer> counts, String kind)
    { counts.put(kind, counts.getOrDefault(kind, 0) + 1); }

    private static String formatCounts(Map<String, Integer> counts)
    {
        StringJoiner out = new StringJoiner(", ");
        counts.forEach((kind,count) -> out.add(kind + " " + count));
        String value = out.toString();
        return value.length() > 260 ? value.substring(0,257) + "..." : value;
    }

    @Override public void close()
    {
        closed = true;
        reset();
        sampler.shutdownNow();
    }

    private static final class Cue
    {
        final String kind;
        final long nano;
        Cue(String kind, long nano) { this.kind = kind; this.nano = nano; }
    }
}
