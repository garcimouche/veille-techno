///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.rometools:rome:2.1.0

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "fetch-rss", mixinStandardHelpOptions = true,
        description = "Fetch RSS/Atom feeds for active sources, store new items.")
class FetchRss implements Callable<Integer> {

    @Option(names = "--topic", description = "Topic slug, or 'all' (default: ${DEFAULT-VALUE})")
    String topic = "all";

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    @Option(names = "--dry-run", description = "Parse feeds but do not write to DB")
    boolean dryRun;

    private static final String USER_AGENT = "veille-techno/0.1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    public static void main(String[] args) {
        System.exit(new CommandLine(new FetchRss()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Map<String, String> env = loadEnv(envPath);
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }

            List<Source> sources = loadSources(con, topic);
            if (sources.isEmpty()) {
                System.out.println("No matching active RSS sources for topic '" + topic + "'.");
                return 0;
            }

            int totalNew = 0, totalDup = 0, totalItemErrors = 0, sourceErrors = 0;
            for (Source s : sources) {
                System.out.printf("→ [%s] %s%n", s.topicSlug, s.name);
                try {
                    SyndFeed feed = fetchFeed(http, s.url);
                    int[] counts = persistEntries(con, s, feed.getEntries());
                    totalNew += counts[0];
                    totalDup += counts[1];
                    totalItemErrors += counts[2];
                    markSourceSuccess(con, s.id);
                    System.out.printf("    new=%d  dup=%d  errs=%d%n", counts[0], counts[1], counts[2]);
                } catch (Exception e) {
                    sourceErrors++;
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    markSourceError(con, s.id, msg);
                    System.out.printf("    ERROR  %s%n", msg);
                }
            }

            System.out.println("---");
            System.out.printf("Sources crawled : %d  (errors: %d)%n", sources.size(), sourceErrors);
            System.out.printf("Items new       : %d%n", totalNew);
            System.out.printf("Items duplicate : %d%n", totalDup);
            if (totalItemErrors > 0) System.out.printf("Items errored   : %d%n", totalItemErrors);
            if (dryRun) System.out.println("(dry-run — nothing written)");
        }

        return 0;
    }

    record Source(long id, String topicSlug, String name, String url) {}

    private static List<Source> loadSources(Connection con, String topic) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, t.slug, s.name, s.url " +
                        "FROM sources s JOIN topics t ON s.topic_id = t.id " +
                        "WHERE s.active = 1 AND s.type = 'rss'");
        if (!"all".equals(topic)) {
            sql.append(" AND t.slug = ?");
        }
        sql.append(" ORDER BY t.slug, s.name");

        List<Source> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            if (!"all".equals(topic)) ps.setString(1, topic);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Source(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    private SyndFeed fetchFeed(HttpClient http, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        try (XmlReader xr = new XmlReader(new ByteArrayInputStream(resp.body()))) {
            return new SyndFeedInput().build(xr);
        }
    }

    private int[] persistEntries(Connection con, Source source, List<SyndEntry> entries) throws Exception {
        int newCount = 0, dupCount = 0, errCount = 0;

        if (dryRun) {
            for (SyndEntry e : entries) {
                String externalId = externalIdFor(e);
                if (externalId == null) { errCount++; continue; }
                if (itemExists(con, source.id, externalId)) dupCount++; else newCount++;
            }
            return new int[]{newCount, dupCount, errCount};
        }

        boolean prevAutoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT OR IGNORE INTO items(source_id, external_id, title, url, author, content, " +
                        "content_hash, published_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NEW')")) {
            for (SyndEntry e : entries) {
                try {
                    String externalId = externalIdFor(e);
                    if (externalId == null) { errCount++; continue; }

                    String title = e.getTitle() != null ? e.getTitle().strip() : "(no title)";
                    String link = e.getLink();
                    String author = (e.getAuthor() != null && !e.getAuthor().isBlank()) ? e.getAuthor() : null;
                    String content = extractContent(e);
                    String hash = sha256Hex(title + "|" + (content == null ? "" : content));
                    Date published = e.getPublishedDate() != null ? e.getPublishedDate() : e.getUpdatedDate();

                    ps.setLong(1, source.id);
                    ps.setString(2, externalId);
                    ps.setString(3, title);
                    ps.setString(4, link);
                    ps.setString(5, author);
                    ps.setString(6, content);
                    ps.setString(7, hash);
                    if (published != null) ps.setTimestamp(8, new Timestamp(published.getTime()));
                    else ps.setNull(8, Types.TIMESTAMP);

                    int n = ps.executeUpdate();
                    if (n > 0) newCount++; else dupCount++;
                } catch (Exception ex) {
                    errCount++;
                }
            }
            con.commit();
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(prevAutoCommit);
        }

        return new int[]{newCount, dupCount, errCount};
    }

    private static String externalIdFor(SyndEntry e) {
        if (e.getUri() != null && !e.getUri().isBlank()) return e.getUri();
        if (e.getLink() != null && !e.getLink().isBlank()) return e.getLink();
        return null;
    }

    private static String extractContent(SyndEntry e) {
        if (e.getContents() != null && !e.getContents().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SyndContent c : e.getContents()) {
                if (c.getValue() != null) sb.append(c.getValue()).append("\n");
            }
            if (sb.length() > 0) return sb.toString().strip();
        }
        if (e.getDescription() != null && e.getDescription().getValue() != null) {
            return e.getDescription().getValue().strip();
        }
        return null;
    }

    private static boolean itemExists(Connection con, long sourceId, String externalId) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM items WHERE source_id = ? AND external_id = ?")) {
            ps.setLong(1, sourceId);
            ps.setString(2, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void markSourceSuccess(Connection con, long sourceId) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE sources SET last_checked = CURRENT_TIMESTAMP, last_error = NULL WHERE id = ?")) {
            ps.setLong(1, sourceId);
            ps.executeUpdate();
        }
    }

    private static void markSourceError(Connection con, long sourceId, String message) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE sources SET last_checked = CURRENT_TIMESTAMP, last_error = ? WHERE id = ?")) {
            ps.setString(1, message.length() > 500 ? message.substring(0, 500) : message);
            ps.setLong(2, sourceId);
            ps.executeUpdate();
        }
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> loadEnv(Path path) throws Exception {
        Map<String, String> env = new HashMap<>();
        if (!Files.exists(path)) return env;
        for (String line : Files.readAllLines(path)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String key = s.substring(0, eq).strip();
            String value = s.substring(eq + 1).strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            env.put(key, value);
        }
        return env;
    }
}
