///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

@Command(name = "summarize", mixinStandardHelpOptions = true,
        description = "Summarize CLASSIFIED items via Claude API; transition to SUMMARIZED.")
class Summarize implements Callable<Integer> {

    @Option(names = "--topic", description = "Topic slug, or 'all' (default: ${DEFAULT-VALUE})")
    String topic = "all";

    @Option(names = "--limit", description = "Max items to process (default: no limit)")
    int limit = 0;

    @Option(names = "--since", description = "Only items published since: '30d', '7d', '24h', '1y', or 'YYYY-MM-DD' (default: no filter)")
    String since;

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    @Option(names = "--model", description = "Model: 'fast' (Haiku, default), 'deep' (Sonnet), or a full model ID")
    String modelOverride;

    @Option(names = "--dry-run", description = "Read items but do not call API or write DB")
    boolean dryRun;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 800;
    private static final int MAX_CONTENT_CHARS = 30_000;
    private static final int MAX_RETRIES = 5;

    private static final String SYSTEM_PROMPT = """
            You are a technical digest writer for a senior software engineer. \
            You produce concise, factual summaries focused on "what should I \
            take away as an engineer".

            Output format: structured Markdown.

            Tone: factual, no hype, no empty superlatives ("incredible", \
            "game-changing", "revolutionary", etc.).

            Language: English. Keep technical terms and product names verbatim \
            (API, hook, signal, JEP, etc.). Use direct quotes from the article \
            in their original language when relevant.

            Target length: 100-200 words for a standard article, up to 350 \
            words for a deep-dive.""";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Summarize()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Map<String, String> env = loadEnv(envPath);
        String apiKey = env.get("ANTHROPIC_API_KEY");
        if (!dryRun && (apiKey == null || apiKey.isBlank() || apiKey.startsWith("sk-ant-xxxx"))) {
            System.err.println("ERROR: ANTHROPIC_API_KEY not set in " + envPath.toAbsolutePath());
            return 2;
        }
        String model = resolveModel(modelOverride, env);
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ObjectMapper json = new ObjectMapper();

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }

            Long sinceMs = parseSince(since);
            List<Item> items = loadItems(con, topic, sinceMs, limit);
            if (items.isEmpty()) {
                System.out.println("No CLASSIFIED items to summarize for topic '" + topic + "'.");
                return 0;
            }

            System.out.printf("Model      : %s%n", model);
            if (sinceMs != null) System.out.printf("Since      : %s%n", since);
            System.out.printf("Items      : %d to summarize%n", items.size());
            if (dryRun) {
                System.out.println("(dry-run — no API calls, no DB writes)");
                return 0;
            }
            System.out.println();

            int summarized = 0, errors = 0;
            long t0 = System.currentTimeMillis();

            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                try {
                    String summary = summarizeWithRetry(http, json, apiKey, model, it);
                    updateItemSummary(con, it.id, summary, model);
                    summarized++;
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    markItemError(con, it.id, msg);
                    errors++;
                }

                int done = i + 1;
                if (done % 10 == 0 || done == items.size()) {
                    long elapsed = (System.currentTimeMillis() - t0) / 1000;
                    System.out.printf("  [%d/%d] summarized=%d errors=%d  (%ds)%n",
                            done, items.size(), summarized, errors, elapsed);
                }
            }

            System.out.println("---");
            System.out.printf("Summarized : %d%n", summarized);
            System.out.printf("Errors     : %d%n", errors);
        }
        return 0;
    }

    record Item(long id, String topicName, String title, String url, String author,
                String content, String sourceName, Long publishedAtMs) {}

    private static List<Item> loadItems(Connection con, String topic, Long sinceMs, int limit) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT i.id, t.name, i.title, i.url, i.author, i.content, s.name, i.published_at " +
                        "FROM items i " +
                        "JOIN sources s ON i.source_id = s.id " +
                        "JOIN topics  t ON s.topic_id  = t.id " +
                        "WHERE i.status = 'CLASSIFIED'");
        if (!"all".equals(topic)) sql.append(" AND t.slug = ?");
        if (sinceMs != null) sql.append(" AND i.published_at >= ?");
        sql.append(" ORDER BY i.relevance_score DESC, i.published_at DESC");
        if (limit > 0) sql.append(" LIMIT ").append(limit);

        List<Item> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int p = 1;
            if (!"all".equals(topic)) ps.setString(p++, topic);
            if (sinceMs != null) ps.setLong(p++, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong(8);
                    Long published = rs.wasNull() ? null : ts;
                    out.add(new Item(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), published));
                }
            }
        }
        return out;
    }

    private String summarizeWithRetry(HttpClient http, ObjectMapper json, String apiKey,
                                      String model, Item item) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return summarizeOnce(http, json, apiKey, model, item);
            } catch (RetryableApiException e) {
                last = e;
                long sleepMs = e.retryAfterSeconds > 0
                        ? e.retryAfterSeconds * 1000L
                        : (long) (Math.pow(2, attempt) * 1000) + ThreadLocalRandom.current().nextInt(500);
                Thread.sleep(sleepMs);
            }
        }
        throw last != null ? last : new RuntimeException("retry exhausted");
    }

    private String summarizeOnce(HttpClient http, ObjectMapper json, String apiKey,
                                 String model, Item item) throws Exception {
        String userPrompt = buildUserPrompt(item);

        ObjectNode payload = json.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", MAX_TOKENS);
        payload.put("system", SYSTEM_PROMPT);
        var messages = payload.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(120))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code == 429 || code >= 500) {
            int retryAfter = resp.headers().firstValue("retry-after")
                    .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } })
                    .orElse(0);
            throw new RetryableApiException("HTTP " + code + ": " + truncate(resp.body(), 200), retryAfter);
        }
        if (code / 100 != 2) {
            throw new RuntimeException("HTTP " + code + ": " + truncate(resp.body(), 300));
        }

        JsonNode body = json.readTree(resp.body());
        String text = body.path("content").path(0).path("text").asText("").strip();
        if (text.isEmpty()) {
            throw new RuntimeException("empty response: " + truncate(resp.body(), 200));
        }
        return text;
    }

    private static String buildUserPrompt(Item item) {
        String content = item.content == null ? "" : item.content;
        boolean truncated = content.length() > MAX_CONTENT_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CONTENT_CHARS) + "\n\n[...content truncated for prompt size...]";
        }
        String date = item.publishedAtMs != null
                ? java.time.Instant.ofEpochMilli(item.publishedAtMs).toString()
                : "(unknown)";
        return """
                Topic: %s

                Article to summarize:
                Title: %s
                Source: %s
                URL: %s
                Author: %s
                Date: %s

                Full content:
                %s

                Produce a Markdown summary with this exact structure:

                **TL;DR**: 1-2 sentences maximum.

                **Key points**:
                - 2 to 4 factual bullets (no filler).

                **Why it matters**: 1 sentence explaining why a senior engineer \
                should pay attention. If the article doesn't really deserve attention, \
                say so honestly.

                Do not include the title or the URL in the output (already known to the digest).""".formatted(
                item.topicName,
                item.title,
                item.sourceName,
                item.url == null ? "(inconnue)" : item.url,
                item.author == null || item.author.isBlank() ? "(inconnu)" : item.author,
                date,
                content);
    }

    private static void updateItemSummary(Connection con, long itemId, String summary, String model) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE items SET status = 'SUMMARIZED', summary = ?, summary_model = ? WHERE id = ?")) {
            ps.setString(1, summary);
            ps.setString(2, model);
            ps.setLong(3, itemId);
            ps.executeUpdate();
        }
    }

    private static void markItemError(Connection con, long itemId, String message) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE items SET status = 'ERROR', error_message = ? WHERE id = ?")) {
            ps.setString(1, message.length() > 500 ? message.substring(0, 500) : message);
            ps.setLong(2, itemId);
            ps.executeUpdate();
        }
    }

    private static String resolveModel(String override, Map<String, String> env) {
        if (override == null || override.isBlank()) {
            return env.getOrDefault("CLAUDE_MODEL_FAST", "claude-haiku-4-5");
        }
        return switch (override) {
            case "fast" -> env.getOrDefault("CLAUDE_MODEL_FAST", "claude-haiku-4-5");
            case "deep" -> env.getOrDefault("CLAUDE_MODEL_DEEP", "claude-sonnet-4-6");
            default -> override;
        };
    }

    private static Long parseSince(String since) {
        if (since == null || since.isBlank()) return null;
        String s = since.strip();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([hdwy])$").matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("--since: expected like '30d', '7d', '24h', '2w', '1y', or 'YYYY-MM-DD'; got: " + since);
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static class RetryableApiException extends RuntimeException {
        final int retryAfterSeconds;
        RetryableApiException(String msg, int retryAfterSeconds) {
            super(msg);
            this.retryAfterSeconds = retryAfterSeconds;
        }
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
