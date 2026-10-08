package runelitehitchprofiler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;

/** Append-only JSON Lines event log with an occasionally refreshed HTML report. */
final class HitchLog
{
    static final String HEADER = "schema,session,timestamp,type,context,duration_ms,world_view,regions,near_load,detail";
    static final String FOOTER = "</tbody></table></body></html>\n";
    static final String HTML_HEADER = loadTemplate();
    private static final long REPORT_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_LOG_BYTES = 100 * 1024 * 1024;
    private final ThreadPoolExecutor writer;
    private final Filepath directory, file, report;
    private volatile String error = "", savedHistory = "Reading saved events...";
    private final AtomicLong rejected = new AtomicLong();
    private long bytes, lastReportNanos;
    private volatile boolean ready;

    HitchLog(Filepath directory)
    {
        this.directory = directory;
        file = directory.joinSegment("sailing-hitches.jsonl");
        report = directory.joinSegment("sailing-hitches.html");
        writer = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2048), task -> {
            Thread thread = new Thread(task,"RuneLite Hitch Profiler log"); thread.setDaemon(true); return thread;
        });
        writer.execute(() -> {
            try
            {
                directory.createDirectories();
                if (!file.exists()) migrateLegacyHtml();
                bytes = file.exists() ? file.size() : 0;
                List<String> recent = new ArrayList<>();
                if (file.exists())
                {
                    try (BufferedReader in = file.openBufferedReader())
                    {
                        String line;
                        while ((line = in.readLine()) != null)
                        {
                            JsonObject event = parseEvent(line);
                            if (event == null) continue;
                            recent.add(historyLine(event));
                            if (recent.size() > 150) recent.remove(0);
                        }
                    }
                }
                savedHistory = recent.isEmpty() ? "No earlier events. New events appear here after restarting the plugin." : String.join("\n", recent);
                ready = true;
                refreshReport();
            }
            catch (IOException | RuntimeException e) { error = "Local session log unavailable. Check RuneLite's client log for details."; }
        });
    }

    void write(String row) { write(row, false); }

    void write(String row, boolean severe)
    {
        try
        {
            writer.execute(() -> {
                if (!ready || !error.isEmpty()) return;
                try
                {
                    JsonObject event = eventFromRow(row, severe);
                    byte[] data = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes + data.length > MAX_LOG_BYTES)
                    { error = "Local session log reached 100 MB. Archive it from your plugin data folder, then restart the plugin."; return; }
                    try (FileChannel channel = file.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND))
                    {
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        while (buffer.hasRemaining()) channel.write(buffer);
                    }
                    bytes += data.length;
                    if (System.nanoTime() - lastReportNanos >= REPORT_REFRESH_NANOS) refreshReport();
                }
                catch (IOException | RuntimeException e) { error = "Could not write the local session log. Check RuneLite's client log for details."; }
            });
        }
        catch (RejectedExecutionException e) { rejected.incrementAndGet(); }
    }

    String status()
    {
        String value = error.isEmpty()
            ? ready ? "Logging active · files stay local." : "Preparing local session files…"
            : error;
        return value + (rejected.get() == 0 ? "" : " | Dropped log rows: " + rejected.get());
    }
    String history() { return savedHistory; }

    void close()
    {
        try { writer.execute(() -> { if (ready && error.isEmpty()) refreshReport(); }); }
        catch (RejectedExecutionException ignored) { }
        writer.shutdown();
    }

    private void refreshReport()
    {
        try
        {
            StringBuilder html = new StringBuilder(HTML_HEADER);
            try (BufferedReader in = file.openBufferedReader())
            {
                String line;
                while ((line = in.readLine()) != null)
                {
                    JsonObject event = parseEvent(line);
                    if (event == null) continue;
                    String csv = row(event);
                    html.append(htmlRow(csv, event.get("severe").getAsBoolean()));
                }
            }
            html.append(FOOTER);
            Filepath temporary = directory.createTempFile("hitch-report-", ".tmp");
            try
            {
                temporary.write(html.toString());
                temporary.moveTo(report, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            finally { temporary.deleteIfExists(); }
            lastReportNanos = System.nanoTime();
        }
        catch (IOException | RuntimeException e) { error = "Could not refresh the HTML report. Check RuneLite's client log for details."; }
    }

    /** One-time import keeps existing HTML history when migrating to JSON Lines. */
    private void migrateLegacyHtml() throws IOException
    {
        if (!report.exists()) return;
        try (BufferedReader in = report.openBufferedReader(); FileChannel out = file.openFileChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                if (line.startsWith("<!--record:"))
                {
                    boolean severe = line.contains("class=\"severe\"");
                    List<String> cells = readHtmlRow(line);
                    if (!cells.isEmpty())
                    {
                        JsonObject event = eventFromCells(cells, severe);
                        byte[] data = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        while (buffer.hasRemaining()) out.write(buffer);
                    }
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            file.deleteIfExists();
            throw new IOException("Could not migrate the existing HTML log: " + e.getMessage(), e);
        }
    }

    private static JsonObject eventFromRow(String row, boolean severe)
    {
        List<String> cells = parse(row.trim());
        if (cells.size() != 10 && cells.size() != 11) throw new IllegalArgumentException("Expected ten or eleven log fields");
        return eventFromCells(cells, severe);
    }

    private static JsonObject eventFromCells(List<String> cells, boolean severe)
    {
        JsonObject event = new JsonObject();
        String[] names = {"schema","session","timestamp","type","context","duration_ms","world_view","regions","near_load","detail","location"};
        for (int i=0;i<names.length;i++) event.addProperty(names[i], i<cells.size()?cells.get(i):"");
        event.addProperty("severe", severe);
        return event;
    }

    private static JsonObject parseEvent(String line)
    {
        try
        {
            JsonObject event = new JsonParser().parse(line).getAsJsonObject();
            if (!event.has("schema") || !event.has("timestamp") || !event.has("type")) return null;
            if (!event.has("location")) event.addProperty("location", "");
            if (!event.has("severe")) event.addProperty("severe", false);
            return event;
        }
        catch (RuntimeException e) { return null; }
    }

    private static String historyLine(JsonObject event)
    {
        String duration = string(event,"duration_ms"), detail = string(event,"detail");
        return string(event,"timestamp") + " | " + string(event,"type") + " | " + string(event,"context")
            + (duration.isEmpty() ? "" : " | " + duration + " ms") + "\n" + detail + "\n";
    }

    private static String row(JsonObject event)
    {
        StringJoiner out = new StringJoiner(",");
        String[] names = {"schema","session","timestamp","type","context","duration_ms","world_view","regions","near_load","detail","location"};
        for (String name : names)
        {
            String value = string(event,name).replace('\r',' ').replace('\n',' ');
            out.add("\"" + value.replace("\"","\"\"") + "\"");
        }
        return out.toString() + "\n";
    }

    private static String string(JsonObject object, String key)
    { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }

    private static String loadTemplate()
    {
        try (InputStream in = HitchLog.class.getResourceAsStream("/runelitehitchprofiler/report-header.html"))
        {
            if (in == null) throw new IOException("Report template is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) { throw new IllegalStateException("Cannot load report template", ex); }
    }

    /** Retained for legacy-report migration and compatibility with existing log-format checks. */
    static String upgradeReport(String original) throws IOException
    {
        if (!original.startsWith("<!doctype html>\n<!-- sailing-hitch-finder-html-v1 -->\n") || !original.endsWith(FOOTER))
            throw new IOException("Unrecognised or incomplete log; file left unchanged");
        int body = original.indexOf("<tbody>");
        if (body < 0) throw new IOException("Missing event table; file left unchanged");
        String rows = original.substring(body + "<tbody>".length());
        if (rows.startsWith("\n")) rows = rows.substring(1);
        return HTML_HEADER + rows;
    }

    static String htmlRow(String row, boolean severe)
    {
        List<String> cells = parse(row.trim());
        if (cells.size() != 10 && cells.size() != 11) throw new IllegalArgumentException("Expected ten or eleven log fields");
        StringBuilder html = new StringBuilder("<!--record:")
            .append(Base64.getEncoder().encodeToString(row.getBytes(StandardCharsets.UTF_8)))
            .append("--><tr>");
        for (int i=0;i<Math.min(10, cells.size());i++)
        {
            html.append(i==5 && severe && "HITCH".equals(cells.get(3)) ? "<td class=\"severe\">" : "<td>")
                .append(escapeHtml(cells.get(i))).append("</td>");
        }
        html.append("<td class=\"location-data\" hidden>")
            .append(cells.size() > 10 ? escapeHtml(cells.get(10)) : "").append("</td>");
        return html.append("</tr>\n").toString();
    }

    static List<String> readHtmlRow(String line)
    {
        if (!line.startsWith("<!--record:")) return Collections.emptyList();
        int end=line.indexOf("-->");
        if (end<0) return Collections.emptyList();
        try { return parse(new String(Base64.getDecoder().decode(line.substring(11,end)),StandardCharsets.UTF_8).trim()); }
        catch (IllegalArgumentException ex) { return Collections.emptyList(); }
    }

    private static String escapeHtml(String value)
    {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&#39;");
    }

    static String row(String session, long wall, String type, String context, String ms, String view, String regions, String near, String detail)
    { return row(session, wall, type, context, ms, view, regions, near, detail, ""); }

    static String row(String session, long wall, String type, String context, String ms, String view, String regions, String near, String detail, String location)
    {
        String[] cells = {"2",session,Instant.ofEpochMilli(wall).toString(),type,context,ms,view,regions,near,detail,location};
        StringJoiner out = new StringJoiner(",");
        for (String cell : cells) out.add("\"" + cell.replace('\r',' ').replace('\n',' ').replace("\"","\"\"") + "\"");
        return out + "\n";
    }

    static List<String> parse(String line)
    {
        List<String> values = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quote = false;
        for (int i=0;i<line.length();i++)
        {
            char c=line.charAt(i);
            if (c=='"')
            {
                if (quote && i+1<line.length() && line.charAt(i+1)=='"') { cell.append('"'); i++; }
                else quote=!quote;
            }
            else if(c==',' && !quote) { values.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        if (quote) return Collections.emptyList();
        values.add(cell.toString()); return values;
    }
}
