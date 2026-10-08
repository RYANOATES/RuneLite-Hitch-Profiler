package runelitehitchprofiler;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(internalName="runelite-hitch-profiler", legacyDataDirectory="sailing-load-profiler",
 name="RuneLite Hitch Profiler",
 description="Records frame hitches and nearby map loading signals with local session reports.",
 tags={"performance","hitch","frame","loading","stutter","lag"})
public class RuneLiteHitchProfilerPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar toolbar;
    @Inject private RuneLiteHitchProfilerConfig config;
    private final ArrayBlockingQueue<Signal> signals = new ArrayBlockingQueue<>(256);
    private final AtomicLong lostSignals = new AtomicLong();
    private final AtomicBoolean uiQueued = new AtomicBoolean();
    private final Map<WorldView, Signal> pending = new IdentityHashMap<>();
    private volatile boolean running, accepting;
    private volatile int generation;
    private boolean paused, focused, warmup = true;
    private long lastFrame, lastUi, lastSummary;
    private String lastContext = "UNKNOWN", status = "Waiting for login", previousReport = "";
    private HitchSession session;
    private HitchDiagnostics diagnostics;
    private volatile HitchLog log;
    private volatile RuneLiteHitchProfilerPanel panel;
    private NavigationButton navigation;
    private javax.swing.Timer timer;

    @Provides RuneLiteHitchProfilerConfig provideConfig(ConfigManager manager)
    { return manager.getConfig(RuneLiteHitchProfilerConfig.class); }

    @Override protected synchronized void startUp() throws Exception
    {
        running = true;
        paused = false;
        focused = client.getCanvas().isFocusOwner();
        session = new HitchSession(config.hitchMs(), config.severeMs());
        diagnostics = new HitchDiagnostics();
        log = new HitchLog(getPluginDirectory());
        lastFrame = lastUi = lastSummary = 0;
        warmup = true;
        signals.clear(); pending.clear(); lostSignals.set(0);
        writeMarker("SESSION_START", environment());
        final HitchLog currentLog = log;
        SwingUtilities.invokeLater(() -> {
            if (!running || log != currentLog) return;
            panel = new RuneLiteHitchProfilerPanel(
                () -> clientThread.invoke(this::togglePause),
                () -> clientThread.invoke(this::newSession),
                text -> clientThread.invoke(() -> { if (running) recordNote(text); }));
            navigation = NavigationButton.builder().tooltip("RuneLite Hitch Profiler")
                .icon(icon()).priority(15).panel(panel).build();
            toolbar.addNavigation(navigation);
            timer = new javax.swing.Timer(500, e -> {
                if (!uiQueued.compareAndSet(false, true)) return;
                clientThread.invoke(() -> {
                    try { if (running && log == currentLog) tickPanel(); }
                    finally { uiQueued.set(false); }
                });
            });
            timer.start();
        });
    }

    // The loader thread only hands off the event's reference and a timestamp.
    // It must never read world-view properties, reset the frame clock, or update UI.
    @Subscribe public void onPreMapLoad(PreMapLoad event)
    {
        int epoch = generation;
        if (!running || !accepting) return;
        if (!signals.offer(new Signal(event.getWorldView(), System.nanoTime(), System.currentTimeMillis(), epoch)))
            lostSignals.incrementAndGet();
    }

    private void drainSignals()
    {
        Signal signal;
        while ((signal = signals.poll()) != null)
        {
            if (signal.generation != generation) continue;
            session.signal(signal.nano);
            diagnostics.record("map-start", signal.nano);
            Signal old = pending.put(signal.view, signal);
            if (old != null) writeMarker("LOAD_UNPAIRED", "Another PreMapLoad replaced an unfinished interval");
            writeMarker("LOAD_SIGNAL", "PreMapLoad", signal.wall);
        }
        long now = System.nanoTime();
        Iterator<Signal> iterator = pending.values().iterator();
        while (iterator.hasNext())
        {
            if (now - iterator.next().nano > TimeUnit.SECONDS.toNanos(60))
            { iterator.remove(); writeMarker("LOAD_UNPAIRED", "No completion within 60 seconds"); }
        }
        session.dropped += lostSignals.getAndSet(0);
    }

    @Subscribe public synchronized void onWorldViewLoaded(WorldViewLoaded event)
    {
        if (!running || !accepting) return;
        long now = System.nanoTime();
        diagnostics.record("map-done", now);
        drainSignals();
        session.signal(now);
        Signal start = pending.remove(event.getWorldView());
        if (start == null || start.nano > now) { writeMarker("LOAD_UNPAIRED", "Completion without a matching earlier PreMapLoad"); return; }
        WorldView view = event.getWorldView();
        int[] regions = view.getMapRegions();
        writeEntry(session.load(start.nano, now, System.currentTimeMillis(), context(), view.getId(),
            regions == null ? -1 : regions.length));
    }

    @Subscribe public synchronized void onFocusChanged(FocusChanged event)
    {
        focused = event.isFocused();
        if (running && config.ignoreUnfocused()) resetSampling();
    }

    @Subscribe public synchronized void onGameStateChanged(GameStateChanged event)
    {
        if (!running) return;
        // Keep the frame clock through normal area loading, but not login/hop transitions.
        if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.LOADING)
            resetSampling();
    }

    @Subscribe public void onNpcSpawned(NpcSpawned event) { recordBreadcrumb("NPC+"); }
    @Subscribe public void onNpcDespawned(NpcDespawned event) { recordBreadcrumb("NPC-"); }
    @Subscribe public void onNpcChanged(NpcChanged event) { recordBreadcrumb("NPC~"); }
    @Subscribe public void onGameObjectSpawned(GameObjectSpawned event) { recordBreadcrumb("object+"); }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned event) { recordBreadcrumb("object-"); }
    @Subscribe public void onGroundObjectSpawned(GroundObjectSpawned event) { recordBreadcrumb("ground+"); }
    @Subscribe public void onWallObjectSpawned(WallObjectSpawned event) { recordBreadcrumb("wall+"); }
    @Subscribe public void onGraphicsObjectCreated(GraphicsObjectCreated event) { recordBreadcrumb("graphic+"); }
    @Subscribe public void onProjectileMoved(ProjectileMoved event) { recordBreadcrumb("projectile"); }

    private void recordBreadcrumb(String kind)
    {
        HitchDiagnostics current = diagnostics;
        if (running && accepting && current != null) current.record(kind, System.nanoTime());
    }

    private void resetSampling()
    {
        if (diagnostics != null) diagnostics.reset();
        accepting = false;
        generation++;
        lastFrame = 0;
        warmup = true;
        pending.clear(); signals.clear();
        if (session != null)
        {
            for (HitchSession.Entry e : session.drain(System.nanoTime(), true)) writeEntry(e);
            session.loadSignals.clear();
        }
    }

    @Subscribe public synchronized void onBeforeRender(BeforeRender event)
    {
        if (!running) return;
        long now = System.nanoTime();
        GameState state = client.getGameState();
        boolean eligible = !paused && (!config.ignoreUnfocused() || focused)
            && (state == GameState.LOGGED_IN || (state == GameState.LOADING && !warmup));
        if (!eligible)
        {
            if (accepting || lastFrame != 0) resetSampling();
            else diagnostics.reset();
            return;
        }
        String stackSamples = diagnostics.onFrame(now, session.severeThreshold);
        accepting = true;
        drainSignals();
        String context = state == GameState.LOADING ? lastContext : context();
        if (lastFrame != 0)
        {
            String frameContext = context.equals(lastContext) ? context : "UNKNOWN";
            long gap = now - lastFrame;
            String location = gap >= TimeUnit.MILLISECONDS.toNanos(session.severeThreshold) ? severeHitchLocation() : "";
            String diagnostic = gap >= TimeUnit.MILLISECONDS.toNanos(session.severeThreshold)
                ? diagnostics.describeGap(lastFrame, now, stackSamples) : "";
            session.frame(lastFrame, now, System.currentTimeMillis(), frameContext, location, diagnostic);
        }
        lastFrame = now;
        lastContext = context;
        warmup = false;
        for (HitchSession.Entry e : session.drain(now, false)) writeEntry(e);
        if (now - lastUi > TimeUnit.MILLISECONDS.toNanos(500)) tickPanel();
    }

    private String context()
    {
        CameraFocusableEntity focus = client.getCameraFocusEntity();
        if (focus instanceof WorldEntity) return "BOAT_LIKELY";
        if (focus != null && focus == client.getLocalPlayer()) return "LAND_LIKELY";
        return "UNKNOWN";
    }

    /** Capture a tile only for severe hitches; boat locations use the top-level world entity position. */
    private String severeHitchLocation()
    {
        try
        {
            CameraFocusableEntity focus = client.getCameraFocusEntity();
            WorldPoint point;
            if (focus instanceof WorldEntity)
            {
                WorldView topLevel = client.getTopLevelWorldView();
                LocalPoint local = ((WorldEntity) focus).getLocalLocation();
                if (topLevel == null || local == null) return "";
                point = WorldPoint.fromLocal(topLevel, local.getX(), local.getY(), topLevel.getPlane());
            }
            else
            {
                Player player = client.getLocalPlayer();
                if (player == null) return "";
                point = player.getWorldLocation();
            }
            return point == null ? "" : point.getX() + ", " + point.getY() + ", plane " + point.getPlane();
        }
        catch (RuntimeException ignored) { return ""; }
    }

    private synchronized void togglePause()
    {
        if (!running) return;
        paused = !paused;
        resetSampling();
        writeMarker(paused ? "PAUSE" : "RESUME", "User action");
        tickPanel();
    }

    private synchronized void newSession()
    {
        if (!running) return;
        resetSampling();
        previousReport = session.report();
        writeMarker("SESSION_END", previousReport);
        session = new HitchSession(config.hitchMs(), config.severeMs());
        paused = false;
        writeMarker("SESSION_START", environment());
        tickPanel();
    }

    private synchronized void recordNote(String text)
    {
        String value = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (!value.isEmpty()) writeEntry(session.note(System.nanoTime(), value.substring(0, Math.min(160, value.length()))));
        tickPanel();
    }

    private String environment()
    {
        return "RuneLite " + RuneLiteProperties.getVersion() + "; plugin 2.0; Java " + System.getProperty("java.version")
            + "; OS " + System.getProperty("os.name") + "; GPU active=" + client.isGpu()
            + "; thresholds=" + session.threshold + "/" + session.severeThreshold
            + "; exclude background=" + config.ignoreUnfocused();
    }

    private synchronized void tickPanel()
    {
        if (!running) return;
        long now = System.nanoTime();
        lastUi = now;
        if (accepting) drainSignals();
        for (HitchSession.Entry e : session.drain(now, false)) writeEntry(e);
        status = paused ? "Paused" : config.ignoreUnfocused() && !focused ? "Paused: window unfocused"
            : accepting ? "Recording: " + lastContext.replace('_', ' ').toLowerCase(Locale.ROOT) : "Waiting for game frames";
        if (now - lastSummary > TimeUnit.SECONDS.toNanos(30))
        { lastSummary = now; writeMarker("SUMMARY", session.report()); }
        RuneLiteHitchProfilerPanel target = panel;
        if (target == null) return;
        // Build immutable display strings before crossing onto Swing's thread.
        List<String[]> rows = new ArrayList<>();
        for (HitchSession.Entry e : session.history)
        {
            String eventDetail = !e.type.equals("HITCH") ? e.detail
                : !e.finalized ? "Checking nearby loads..."
                : e.nearLoad ? "Near map-load signal" : "No nearby map-load signal";
            if (!e.detail.isEmpty() && e.type.equals("HITCH")) eventDetail += "\n" + e.detail;
            rows.add(new String[]{Instant.ofEpochMilli(e.wall).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString(),
                e.type, String.format(Locale.ROOT, "%.1f", e.ms), e.context,
                eventDetail,
                e.ms >= session.severeThreshold && e.type.equals("HITCH") ? "severe" : "normal"});
        }
        String report = session.report();
        String previous = previousReport;
        String state = status;
        String logging = log.status();
        String saved = log.history();
        boolean isPaused = paused;
        long hitchCount=session.total.hitches, severeCount=session.total.severe, loadCount=session.loads;
        double peak=session.total.peak;
        SwingUtilities.invokeLater(() -> {
            if (panel == target) {
                target.update(state, report, previous, rows, logging, isPaused);
                target.savedHistory(saved);
                target.metrics(hitchCount,severeCount,peak,loadCount);
            }
        });
    }

    private void writeEntry(HitchSession.Entry e)
    {
        log.write(HitchLog.row(session.id, e.wall, e.type, e.context, Double.toString(e.ms),
            e.type.equals("LOAD_INTERVAL") ? Integer.toString(e.view) : "", e.regions < 0 ? "" : Integer.toString(e.regions),
            e.type.equals("HITCH") ? Boolean.toString(e.nearLoad) : "", e.detail, e.location),
            e.type.equals("HITCH") && e.ms >= session.severeThreshold);
    }
    private void writeMarker(String type, String text) { writeMarker(type, text, System.currentTimeMillis()); }
    private void writeMarker(String type, String text, long wall)
    { log.write(HitchLog.row(session.id, wall, type, "", "", "", "", "", text)); }

    @Override protected synchronized void shutDown()
    {
        accepting = false;
        running = false;
        resetSampling();
        if (diagnostics != null) diagnostics.close();
        writeMarker("SESSION_END", session.report());
        log.close();
        NavigationButton oldNavigation = navigation;
        javax.swing.Timer oldTimer = timer;
        panel = null; navigation = null; timer = null;
        SwingUtilities.invokeLater(() -> {
            if (oldTimer != null) oldTimer.stop();
            if (oldNavigation != null) toolbar.removeNavigation(oldNavigation);
        });
    }

    private BufferedImage icon()
    {
        BufferedImage image = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(32.0/120.0,32.0/120.0);
        JournalTheme.paintCrest(g,4f);
        g.dispose(); return image;
    }
    private static final class Signal
    {
        final WorldView view; final long nano, wall; final int generation;
        Signal(WorldView view, long nano, long wall, int generation)
        { this.view=view; this.nano=nano; this.wall=wall; this.generation=generation; }
    }
}
