package runelitehitchprofiler;
import net.runelite.client.config.*;
@ConfigGroup("sailing-load-profiler")
public interface RuneLiteHitchProfilerConfig extends Config {
 @Range(min=20,max=1000)
 @ConfigItem(keyName="hitchMs",name="Hitch threshold (ms)",description="Applies to the next session.",position=0)
 default int hitchMs() { return 50; }
 @Range(min=50,max=5000)
 @ConfigItem(keyName="severeMs",name="Severe threshold (ms)",description="Applies to the next session.",position=1)
 default int severeMs() { return 150; }
 @ConfigItem(keyName="ignoreUnfocused",name="Pause when unfocused",description="Exclude background FPS limiting and alt-tab pauses.",position=2)
 default boolean ignoreUnfocused() { return true; }
}
