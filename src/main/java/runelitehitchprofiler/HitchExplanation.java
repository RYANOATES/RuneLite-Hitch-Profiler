package runelitehitchprofiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative plain-language explanations for observed severe-hitch evidence. */
final class HitchExplanation
{
    private static final Pattern CLASS_FRAME = Pattern.compile(
        "(?<![\\w$])((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z_$][\\w$]*)\\.[a-zA-Z_$][\\w$]*(?::\\d+)?");
    private static final Pattern CUE_COUNT = Pattern.compile("(?:object\\+|ground\\+)\\s+(\\d+)");

    private HitchExplanation() { }

    static Result explain(String evidence)
    {
        String detail = evidence == null ? "" : evidence;
        List<String> plugins = pluginNames(detail);
        String reason;
        String description;
        if (detail.matches("(?s).*?(GLMappedBuffer\\.map|GLBuffer\\.map|nglMapBuffer(?:Range)?).*"))
        {
            reason = "OpenGL buffer mapping was sampled";
            description = "A sampled client-thread stack entered an OpenGL buffer map call. This shows where a thread was sampled; it cannot tell whether CPU work, driver synchronization, or GPU progress accounts for the full frame gap.";
        }
        else if (detail.matches("(?s).*?(scanWidget|scanChildren|getDynamicChildren).*"))
        {
            reason = "A widget tree scan was sampled";
            description = "A sampled stack was walking interface or widget children. Repeated or deep scans can use client-thread time, but the sample does not measure their total cost or prove they caused the whole hitch.";
        }
        else if (detail.contains("No client-thread stack captured"))
        {
            reason = "No client-thread stack was captured";
            description = "The sampler did not observe a client-thread stack during this gap. The delay may have occurred between samples or outside that thread; this record cannot identify a cause.";
        }
        else if (detail.contains("map-start") || detail.contains("map-done")
            || detail.toLowerCase(java.util.Locale.ROOT).contains("premapload")
            || detail.toLowerCase(java.util.Locale.ROOT).contains("worldviewloaded"))
        {
            reason = "Map loading activity was nearby";
            description = "A map-load signal fell during the hitch or within the nearby-event window. Timing proximity is a clue, not proof that loading caused the gap.";
        }
        else if (sceneChanges(detail) >= 100)
        {
            reason = "Many scene objects changed nearby";
            description = "The nearby-event window contains " + sceneChanges(detail)
                + " object or ground-object changes. This can accompany a scene update; event counts are not render time and do not prove the changes caused the hitch.";
        }
        else if (!plugins.isEmpty())
        {
            reason = "Plugin code appeared in sampled stacks";
            description = "The sampler saw plugin code on the client thread during the frame gap. This identifies code that was active in sampled moments, not proof that it caused the full delay.";
        }
        else if (detail.contains("Client-thread stack samples"))
        {
            reason = "RuneLite client-thread work was sampled";
            description = "The recorded stack does not resolve to a readable plugin operation. The profiler can show the sampled frames, but cannot assign this gap to a specific cause.";
        }
        else
        {
            reason = "Cause not identified from this record";
            description = "The saved evidence is too limited to name a likely activity. Keep this event as an unresolved hitch rather than treating a guess as its cause.";
        }
        return new Result(reason, description, plugins, detail);
    }

    private static int sceneChanges(String detail)
    {
        int total = 0;
        Matcher matcher = CUE_COUNT.matcher(detail);
        while (matcher.find())
        {
            try { total += Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { }
        }
        return total;
    }

    private static List<String> pluginNames(String detail)
    {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CLASS_FRAME.matcher(detail);
        while (matcher.find() && names.size() < 3)
        {
            String className = matcher.group(1);
            if (isPlatformClass(className)) continue;
            if (className.startsWith("rs117.hd.")) names.add("117 HD (" + className + ")");
            else names.add(className);
        }
        return new ArrayList<>(names);
    }

    private static boolean isPlatformClass(String name)
    {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
            || name.startsWith("sun.") || name.startsWith("net.runelite.") || name.startsWith("org.lwjgl.")
            || name.startsWith("com.google.") || name.startsWith("com.sun.") || name.startsWith("org.slf4j.")
            || name.startsWith("ch.qos.") || name.startsWith("net.bytebuddy.");
    }

    static final class Result
    {
        final String reason, description, evidence;
        final List<String> plugins;

        Result(String reason, String description, List<String> plugins, String evidence)
        {
            this.reason = reason;
            this.description = description;
            this.plugins = plugins;
            this.evidence = evidence;
        }

        String asText()
        {
            StringBuilder text = new StringBuilder("Possible activity: ").append(reason).append('\n')
                .append(description);
            if (!plugins.isEmpty()) text.append("\nPlugin/class seen: ").append(String.join(", ", plugins));
            if (!evidence.isEmpty()) text.append("\n\nCaptured evidence:\n").append(evidence);
            return text.toString();
        }
    }
}
