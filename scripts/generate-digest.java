///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS org.commonmark:commonmark:0.22.0

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "generate-digest", mixinStandardHelpOptions = true,
        description = "Compose a Markdown digest of SUMMARIZED items for one topic over a period.")
class GenerateDigest implements Callable<Integer> {

    @Option(names = "--topic", required = true,
            description = "Topic slug (e.g. java, ai, angular) — required")
    String topic;

    @Option(names = "--since",
            description = "Time window: '7d', '24h', '30d', '1y', or 'YYYY-MM-DD' (default: ${DEFAULT-VALUE})")
    String since = "7d";

    @Option(names = "--output",
            description = "Markdown output file (default: data/digests/digest-<topic>-<YYYYMMDD>.md)")
    Path outputPath;

    @Option(names = "--html-output",
            description = "Optional HTML output file (default: same as --output with .html extension)")
    Path htmlOutputPath;

    @Option(names = "--no-html", description = "Skip HTML rendering")
    boolean noHtml;

    @Option(names = "--no-save", description = "Don't insert into the digests table (preview mode)")
    boolean noSave;

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    public static void main(String[] args) {
        System.exit(new CommandLine(new GenerateDigest()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Map<String, String> env = loadEnv(envPath);
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");

        long periodEndMs = System.currentTimeMillis();
        long periodStartMs = parseSinceCutoff(since);

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }

            TopicInfo topicInfo = loadTopic(con, topic);
            if (topicInfo == null) {
                System.err.println("ERROR: topic not found: " + topic);
                return 2;
            }

            List<Item> items = loadItems(con, topicInfo.id, periodStartMs);
            if (items.isEmpty()) {
                System.out.println("No SUMMARIZED items for topic '" + topic + "' since " + since + ".");
                return 0;
            }

            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path mdPath = outputPath != null ? outputPath
                    : Paths.get("data/digests/digest-" + topic + "-"
                    + today.replace("-", "") + ".md");
            if (mdPath.getParent() != null) Files.createDirectories(mdPath.getParent());

            String md = renderMarkdown(topicInfo, items, periodStartMs, periodEndMs);
            Files.writeString(mdPath, md);

            String html = null;
            Path htmlPath = null;
            if (!noHtml) {
                html = renderHtml(md, topicInfo, today);
                htmlPath = htmlOutputPath != null ? htmlOutputPath
                        : Paths.get(mdPath.toString().replaceAll("\\.md$", "") + ".html");
                if (htmlPath.getParent() != null) Files.createDirectories(htmlPath.getParent());
                Files.writeString(htmlPath, html);
            }

            long digestId = -1;
            if (!noSave) {
                digestId = saveDigest(con, topicInfo.id, periodStartMs, periodEndMs,
                        items.size(), md, html);
                saveDigestItems(con, digestId, items);
            }

            Set<String> uniqueSources = new LinkedHashSet<>();
            for (Item it : items) uniqueSources.add(it.sourceName);

            System.out.println("Digest");
            System.out.println("------");
            System.out.printf("  Topic     : %s (%s)%n", topicInfo.name, topicInfo.slug);
            System.out.printf("  Period    : %s → %s%n",
                    Instant.ofEpochMilli(periodStartMs).atZone(ZoneId.systemDefault()).toLocalDate(),
                    Instant.ofEpochMilli(periodEndMs).atZone(ZoneId.systemDefault()).toLocalDate());
            System.out.printf("  Items     : %d  (%d unique sources)%n", items.size(), uniqueSources.size());
            System.out.printf("  Markdown  : %s (%d bytes)%n", mdPath.toAbsolutePath(), md.length());
            if (htmlPath != null) {
                System.out.printf("  HTML      : %s (%d bytes)%n", htmlPath.toAbsolutePath(), html.length());
            }
            if (digestId > 0) {
                System.out.printf("  Saved     : digests.id=%d (with %d digest_items rows)%n", digestId, items.size());
            } else if (noSave) {
                System.out.println("  Saved     : (skipped — --no-save)");
            }
        }
        return 0;
    }

    record TopicInfo(long id, String slug, String name) {}

    record Item(long id, String title, String url, String author, String sourceName,
                Long publishedAtMs, int score, String summary) {}

    private static TopicInfo loadTopic(Connection con, String slug) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, slug, name FROM topics WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TopicInfo(rs.getLong(1), rs.getString(2), rs.getString(3));
            }
        }
    }

    private static List<Item> loadItems(Connection con, long topicId, long sinceMs) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT i.id, i.title, i.url, i.author, s.name, i.published_at, " +
                        "       i.relevance_score, i.summary " +
                        "FROM items i " +
                        "JOIN sources s ON i.source_id = s.id " +
                        "WHERE i.status = 'SUMMARIZED' " +
                        "  AND s.topic_id = ? " +
                        "  AND (i.published_at IS NULL OR i.published_at >= ?) " +
                        "ORDER BY i.relevance_score DESC, i.published_at DESC")) {
            ps.setLong(1, topicId);
            ps.setLong(2, sinceMs);
            List<Item> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong(6);
                    Long published = rs.wasNull() ? null : ts;
                    out.add(new Item(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), published,
                            rs.getInt(7), rs.getString(8)));
                }
            }
            return out;
        }
    }

    private static String renderMarkdown(TopicInfo topic, List<Item> items,
                                         long periodStartMs, long periodEndMs) {
        Set<String> uniqueSources = new LinkedHashSet<>();
        for (Item it : items) uniqueSources.add(it.sourceName);

        DateTimeFormatter dateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
        String periodStart = Instant.ofEpochMilli(periodStartMs).atZone(ZoneId.systemDefault())
                .toLocalDate().format(dateFmt);
        String periodEnd = Instant.ofEpochMilli(periodEndMs).atZone(ZoneId.systemDefault())
                .toLocalDate().format(dateFmt);

        StringBuilder sb = new StringBuilder();
        sb.append("# Veille — ").append(topic.name).append('\n');
        sb.append('\n');
        sb.append("**").append(periodStart).append(" → ").append(periodEnd).append("** · ");
        sb.append(items.size()).append(items.size() == 1 ? " item" : " items").append(" · ");
        sb.append(uniqueSources.size()).append(uniqueSources.size() == 1 ? " source: " : " sources: ");
        sb.append(String.join(", ", uniqueSources));
        sb.append("\n\n");
        sb.append("---\n\n");

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String date = it.publishedAtMs != null
                    ? Instant.ofEpochMilli(it.publishedAtMs).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(dateFmt)
                    : "(no date)";

            sb.append("### ").append(it.title).append('\n');
            sb.append('\n');
            sb.append("*").append(it.sourceName).append("*");
            sb.append(" · ").append(date);
            sb.append(" · score **").append(it.score).append("/10**");
            String author = cleanAuthor(it.author);
            if (author != null && !author.isBlank()) {
                sb.append(" · ").append(author);
            }
            if (it.url != null && !it.url.isBlank()) {
                sb.append(" · [link](").append(it.url).append(')');
            }
            sb.append("\n\n");
            sb.append(it.summary == null ? "*(no summary)*" : it.summary.strip());
            sb.append("\n\n");
            if (i < items.size() - 1) sb.append("---\n\n");
        }
        return sb.toString();
    }

    private static String renderHtml(String markdown, TopicInfo topic, String today) {
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        String body = renderer.render(parser.parse(markdown));

        String css = """
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                       max-width: 720px; margin: 2rem auto; padding: 0 1rem;
                       color: #1f2328; line-height: 1.55; }
                h1 { border-bottom: 1px solid #d0d7de; padding-bottom: .3em; }
                h3 { margin-top: 2em; }
                hr { border: 0; border-top: 1px solid #d0d7de; margin: 2em 0; }
                a  { color: #0969da; }
                code { background: #f6f8fa; padding: .15em .35em; border-radius: 3px;
                       font-size: 88%; }
                blockquote { color: #57606a; border-left: .25em solid #d0d7de;
                             padding: 0 1em; margin: 0; }
                em { color: #57606a; }
                """;

        return "<!DOCTYPE html>\n<html lang=\"en\"><head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>Veille — " + escapeHtml(topic.name) + " — " + today + "</title>\n"
                + "<style>" + css + "</style>\n"
                + "</head><body>\n"
                + body
                + "\n</body></html>\n";
    }

    private static String cleanAuthor(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\"", "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static long saveDigest(Connection con, long topicId, long periodStartMs, long periodEndMs,
                                   int itemCount, String contentMd, String contentHtml) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO digests(topic_id, period_start, period_end, item_count, " +
                        "content_md, content_html) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, topicId);
            ps.setTimestamp(2, new Timestamp(periodStartMs));
            ps.setTimestamp(3, new Timestamp(periodEndMs));
            ps.setInt(4, itemCount);
            ps.setString(5, contentMd);
            ps.setString(6, contentHtml);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void saveDigestItems(Connection con, long digestId, List<Item> items) throws Exception {
        boolean prevAuto = con.getAutoCommit();
        con.setAutoCommit(false);
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO digest_items(digest_id, item_id, position) VALUES (?, ?, ?)")) {
            for (int i = 0; i < items.size(); i++) {
                ps.setLong(1, digestId);
                ps.setLong(2, items.get(i).id);
                ps.setInt(3, i);
                ps.addBatch();
            }
            ps.executeBatch();
            con.commit();
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(prevAuto);
        }
    }

    private static long parseSinceCutoff(String since) {
        if (since == null || since.isBlank()) return 0L;
        String s = since.strip();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([hdwy])$").matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("--since: expected like '7d', '24h', '2w', '1y', or 'YYYY-MM-DD'; got: " + since);
        }
        long n = Long.parseLong(m.group(1));
        long secs = switch (m.group(2)) {
            case "h" -> n * 3600;
            case "d" -> n * 86400;
            case "w" -> n * 7 * 86400;
            case "y" -> n * 365 * 86400;
            default -> throw new IllegalStateException();
        };
        return (System.currentTimeMillis() / 1000 - secs) * 1000;
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
