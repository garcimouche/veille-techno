///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

@Command(name = "send-email", mixinStandardHelpOptions = true,
        description = "Send a digest by email via Resend API; mark sent_at + delivery_status.")
class SendEmail implements Callable<Integer> {

    @Option(names = "--digest-id", description = "Send a specific digest by id")
    Long digestId;

    @Option(names = "--topic", description = "Send the most recent unsent digest for this topic slug")
    String topic;

    @Option(names = "--to", description = "Override recipient (default: env DIGEST_RECIPIENT)")
    String toOverride;

    @Option(names = "--from", description = "Override sender (default: env RESEND_FROM)")
    String fromOverride;

    @Option(names = "--subject", description = "Override subject (default: 'Veille — {Topic} — {YYYY-MM-DD}')")
    String subjectOverride;

    @Option(names = "--force", description = "Re-send even if digest already has sent_at")
    boolean force;

    @Option(names = "--dry-run", description = "Print the email payload but do not send")
    boolean dryRun;

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    private static final String API_URL = "https://api.resend.com/emails";
    private static final int MAX_RETRIES = 4;

    public static void main(String[] args) {
        System.exit(new CommandLine(new SendEmail()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (digestId == null && (topic == null || topic.isBlank())) {
            System.err.println("ERROR: provide --digest-id <n> or --topic <slug>");
            return 2;
        }

        Map<String, String> env = loadEnv(envPath);
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");
        String apiKey = env.get("RESEND_API_KEY");
        String from = fromOverride != null ? fromOverride : env.get("RESEND_FROM");
        String to = toOverride != null ? toOverride : env.get("DIGEST_RECIPIENT");

        if (!dryRun) {
            if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("re_xxxx")) {
                System.err.println("ERROR: RESEND_API_KEY not set in " + envPath.toAbsolutePath());
                return 2;
            }
            if (from == null || from.isBlank() || from.contains("yourdomain")) {
                System.err.println("ERROR: RESEND_FROM not set (must be a verified Resend domain or 'onboarding@resend.dev')");
                return 2;
            }
            if (to == null || to.isBlank() || to.contains("@example.com")) {
                System.err.println("ERROR: DIGEST_RECIPIENT not set (use --to <email> or set in .env)");
                return 2;
            }
        }

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }

            DigestRow d = digestId != null ? loadDigestById(con, digestId) : loadLatestForTopic(con, topic);
            if (d == null) {
                System.err.println(digestId != null
                        ? "ERROR: no digest with id=" + digestId
                        : "ERROR: no unsent digest for topic '" + topic + "'");
                return 2;
            }
            if (d.sentAt != null && !force) {
                System.err.println("ERROR: digest id=" + d.id + " already sent at " + d.sentAt
                        + " (use --force to resend)");
                return 2;
            }

            String subject = subjectOverride != null ? subjectOverride : defaultSubject(d);
            String html = d.contentHtml;
            String text = d.contentMd;

            if (html == null || html.isBlank()) {
                System.err.println("ERROR: digest id=" + d.id + " has no content_html");
                return 2;
            }

            System.out.println("Email");
            System.out.println("-----");
            System.out.printf("  digest_id : %d (topic=%s, %d items, period %s → %s)%n",
                    d.id, d.topicSlug, d.itemCount,
                    formatDate(d.periodStartMs), formatDate(d.periodEndMs));
            System.out.printf("  from      : %s%n", from);
            System.out.printf("  to        : %s%n", to);
            System.out.printf("  subject   : %s%n", subject);
            System.out.printf("  html      : %d bytes%n", html.length());
            System.out.printf("  text      : %d bytes%n", text == null ? 0 : text.length());

            if (dryRun) {
                System.out.println("(dry-run — no email sent, no DB write)");
                return 0;
            }

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            ObjectMapper json = new ObjectMapper();
            String emailId = sendWithRetry(http, json, apiKey, from, to, subject, html, text);
            updateDigestSent(con, d.id, to, "SENT", emailId);

            System.out.println();
            System.out.printf("✓ Sent — Resend id=%s%n", emailId);
            System.out.printf("  digests.sent_at and delivery_status updated for id=%d%n", d.id);
        }
        return 0;
    }

    record DigestRow(long id, String topicSlug, String topicName, long periodStartMs, long periodEndMs,
                     int itemCount, String contentMd, String contentHtml, String sentAt) {}

    private static DigestRow loadDigestById(Connection con, long id) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT d.id, t.slug, t.name, d.period_start, d.period_end, d.item_count, " +
                        "       d.content_md, d.content_html, d.sent_at " +
                        "FROM digests d JOIN topics t ON d.topic_id = t.id WHERE d.id = ?")) {
            ps.setLong(1, id);
            return readOne(ps);
        }
    }

    private static DigestRow loadLatestForTopic(Connection con, String topicSlug) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT d.id, t.slug, t.name, d.period_start, d.period_end, d.item_count, " +
                        "       d.content_md, d.content_html, d.sent_at " +
                        "FROM digests d JOIN topics t ON d.topic_id = t.id " +
                        "WHERE t.slug = ? AND d.sent_at IS NULL " +
                        "ORDER BY d.generated_at DESC LIMIT 1")) {
            ps.setString(1, topicSlug);
            return readOne(ps);
        }
    }

    private static DigestRow readOne(PreparedStatement ps) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new DigestRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getTimestamp(4).getTime(), rs.getTimestamp(5).getTime(),
                    rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9));
        }
    }

    private String sendWithRetry(HttpClient http, ObjectMapper json, String apiKey,
                                 String from, String to, String subject, String html, String text) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return sendOnce(http, json, apiKey, from, to, subject, html, text);
            } catch (RetryableApiException e) {
                last = e;
                long sleepMs = (long) (Math.pow(2, attempt) * 1000) + ThreadLocalRandom.current().nextInt(500);
                Thread.sleep(sleepMs);
            }
        }
        throw last != null ? last : new RuntimeException("retry exhausted");
    }

    private static String sendOnce(HttpClient http, ObjectMapper json, String apiKey,
                                   String from, String to, String subject,
                                   String html, String text) throws Exception {
        ObjectNode payload = json.createObjectNode();
        payload.put("from", from);
        ArrayNode toArr = payload.putArray("to");
        toArr.add(to);
        payload.put("subject", subject);
        payload.put("html", html);
        if (text != null && !text.isBlank()) payload.put("text", text);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 429 || code >= 500) {
            throw new RetryableApiException("HTTP " + code + ": " + truncate(resp.body(), 200));
        }
        if (code / 100 != 2) {
            throw new RuntimeException("HTTP " + code + ": " + truncate(resp.body(), 300));
        }
        JsonNode body = json.readTree(resp.body());
        return body.path("id").asText("(no id)");
    }

    private static void updateDigestSent(Connection con, long digestId, String recipient,
                                         String status, String resendId) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE digests SET sent_at = CURRENT_TIMESTAMP, recipient = ?, " +
                        "delivery_status = ? WHERE id = ?")) {
            ps.setString(1, recipient);
            ps.setString(2, status + " (resend_id=" + resendId + ")");
            ps.setLong(3, digestId);
            ps.executeUpdate();
        }
    }

    private static String defaultSubject(DigestRow d) {
        return "Veille — " + d.topicName + " — " + formatDate(d.periodEndMs);
    }

    private static String formatDate(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static class RetryableApiException extends RuntimeException {
        RetryableApiException(String msg) { super(msg); }
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
