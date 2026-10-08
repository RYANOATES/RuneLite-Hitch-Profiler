package runelitehitchprofiler;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public final class RuneLiteHitchProfilerLauncher
{
    private RuneLiteHitchProfilerLauncher()
    {
    }

    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RuneLiteHitchProfilerPlugin.class);
        RuneLite.main(args);
    }
}
