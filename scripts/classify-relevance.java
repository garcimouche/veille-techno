///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

@Command(name = "classify-relevance", mixinStandardHelpOptions = true,
        description = "Classify NEW items via Claude API; mark as CLASSIFIED or SKIPPED based on per-topic threshold.")
class ClassifyRelevance implements Callable<Integer> {

    @Option(names = "--topic", description = "Topic slug, or 'all' (default: ${DEFAULT-VALUE})")
    String topic = "all";

    @Option(names = "--limit", description = "Max items to process (default: no limit)")
    int limit = 0;

    @Option(names = "--since", description = "Only items published since: e.g. '30d', '7d', '24h', '1y', or 'YYYY-MM-DD' (default: no time filter)")
    String since;

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--config", description = "Path to topics.yaml (default: ${DEFAULT-VALUE})")
    Path configPath = Paths.get("config/topics.yaml");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    @Option(names = "--dry-run", description = "Read items + thresholds but do not call API or write DB")
    boolean dryRun;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 256;
    private static final int MAX_RETRIES = 5;
    private static final int CONTENT_EXCERPT_CHARS = 1500;
    private static final int DEFAULT_THRESHOLD = 6;

    private static final String SYSTEM_PROMPT = """
            Tu es un assistant de veille technologique. Tu évalues la pertinence \
            d'articles techniques par rapport à un topic d'intérêt.

            Réponds UNIQUEMENT avec un objet JSON valide au format :
            {
              "score": <int 0-10>,
              "reasoning": "<phrase courte expliquant le score>"
            }

            Critères de scoring :
            - 10 : article central, deep-dive, release majeure, breakthrough
            - 7-9 : article pertinent, technique substantielle, news importante
            - 4-6 : tangentiel, mention du topic mais pas le focus
            - 1-3 : très peu pertinent, mention marginale
            - 0 : hors sujet complet, sponsor, marketing pur""";

    public static void main(String[] args) {
        System.exit(new CommandLine(new ClassifyRelevance()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Map<String, String> env = loadEnv(envPath);
        String apiKey = env.get("ANTHROPIC_API_KEY");
        if (!dryRun && (apiKey == null || apiKey.isBlank() || apiKey.startsWith("sk-ant-xxxx"))) {
            System.err.println("ERROR: ANTHROPIC_API_KEY not set in " + envPath.toAbsolutePath());
            return 2;
        }
        String model = env.getOrDefault("CLAUDE_MODEL_FAST", "claude-haiku-4-5");
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");

        Map<String, TopicInfo> topics = loadTopicsFromYaml(configPath);
        if (topics.isEmpty()) {
            System.err.println("ERROR: no topics found in " + configPath);
            return 2;
        }

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
                System.out.println("No NEW items to classify for topic '" + topic + "'.");
                return 0;
            }

            System.out.printf("Model      : %s%n", model);
            if (sinceMs != null) System.out.printf("Since      : %s (cutoff=%d ms)%n", since, sinceMs);
            System.out.printf("Items      : %d to classify%n", items.size());
            System.out.printf("Topics     : %s%n", topics.keySet());
            if (dryRun) {
                System.out.println("(dry-run — no API calls, no DB writes)");
                return 0;
            }
            System.out.println();

            int classified = 0, skipped = 0, errors = 0;
            long t0 = System.currentTimeMillis();

            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                TopicInfo topicInfo = topics.get(it.topicSlug);
                if (topicInfo == null) {
                    markItemError(con, it.id, "topic not found in yaml: " + it.topicSlug);
                    errors++;
                    continue;
                }
                int threshold = topicInfo.threshold;

                try {
                    Result r = classifyWithRetry(http, json, apiKey, model, it, topicInfo);
                    String status = r.score >= threshold ? "CLASSIFIED" : "SKIPPED";
                    updateItemClassification(con, it.id, status, r.score, r.reasoning);
                    if (r.score >= threshold) classified++;
                    else skipped++;
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    markItemError(con, it.id, msg);
                    errors++;
                }

                int done = i + 1;
                if (done % 25 == 0 || done == items.size()) {
                    long elapsed = (System.currentTimeMillis() - t0) / 1000;
                    System.out.printf("  [%d/%d] classified=%d skipped=%d errors=%d  (%ds)%n",
                            done, items.size(), classified, skipped, errors, elapsed);
                }
            }

            System.out.println("---");
            System.out.printf("Classified : %d  (score >= per-topic threshold)%n", classified);
            System.out.printf("Skipped    : %d  (score < threshold, kept for history)%n", skipped);
            System.out.printf("Errors     : %d%n", errors);
        }

        return 0;
    }

    record Item(long id, String topicSlug, String title, String author, String content,
                String sourceName) {}

    record TopicInfo(String slug, String name, String description, List<String> keywords,
                     int threshold) {}

    record Result(int score, String reasoning) {}

    private static List<Item> loadItems(Connection con, String topic, Long sinceMs, int limit) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT i.id, t.slug, i.title, i.author, i.content, s.name " +
                        "FROM items i " +
                        "JOIN sources s ON i.source_id = s.id " +
                        "JOIN topics  t ON s.topic_id  = t.id " +
                        "WHERE i.status = 'NEW'");
        if (!"all".equals(topic)) sql.append(" AND t.slug = ?");
        if (sinceMs != null) sql.append(" AND i.published_at >= ?");
        sql.append(" ORDER BY i.published_at DESC, i.id");
        if (limit > 0) sql.append(" LIMIT ").append(limit);

        List<Item> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int p = 1;
            if (!"all".equals(topic)) ps.setString(p++, topic);
            if (sinceMs != null) ps.setLong(p++, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Item(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
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

    private static Map<String, TopicInfo> loadTopicsFromYaml(Path path) throws Exception {
        if (!Files.exists(path)) return Map.of();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode root = yaml.readTree(path.toFile());
        Map<String, TopicInfo> map = new HashMap<>();
        for (JsonNode t : root.path("topics")) {
            String slug = t.path("slug").asText();
            String name = t.path("name").asText();
            String description = t.path("description").asText("");
            List<String> keywords = new ArrayList<>();
            for (JsonNode k : t.path("keywords")) keywords.add(k.asText());
            int threshold = t.path("relevance_threshold").asInt(DEFAULT_THRESHOLD);
            map.put(slug, new TopicInfo(slug, name, description, keywords, threshold));
        }
        return map;
    }

    private Result classifyWithRetry(HttpClient http, ObjectMapper json, String apiKey,
                                     String model, Item item, TopicInfo topicInfo) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return classifyOnce(http, json, apiKey, model, item, topicInfo);
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

    private Result classifyOnce(HttpClient http, ObjectMapper json, String apiKey,
                                String model, Item item, TopicInfo topicInfo) throws Exception {
        String userPrompt = buildUserPrompt(item, topicInfo);

        ObjectNode payload = json.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", MAX_TOKENS);
        payload.put("system", SYSTEM_PROMPT);
        var messages = payload.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", userPrompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60))
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
        String text = body.path("content").path(0).path("text").asText("");
        JsonNode parsed = parseJsonObject(json, text);
        if (parsed == null) {
            throw new RuntimeException("response was not parsable JSON: " + truncate(text, 200));
        }
        int score = parsed.path("score").asInt(-1);
        String reasoning = parsed.path("reasoning").asText("");
        if (score < 0 || score > 10) {
            throw new RuntimeException("invalid score: " + score + " (response: " + truncate(text, 200) + ")");
        }
        return new Result(score, reasoning);
    }

    private static JsonNode parseJsonObject(ObjectMapper json, String text) {
        if (text == null) return null;
        String s = text.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return json.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildUserPrompt(Item item, TopicInfo topic) {
        String content = item.content == null ? "" : item.content;
        if (content.length() > CONTENT_EXCERPT_CHARS) {
            content = content.substring(0, CONTENT_EXCERPT_CHARS) + "…";
        }
        String keywords = String.join(", ", topic.keywords);
        return """
                Topic : %s
                Description : %s
                Mots-clés : %s

                Article à évaluer :
                Titre : %s
                Source : %s
                Auteur : %s
                Contenu :
                %s

                Évalue la pertinence de cet article par rapport au topic.""".formatted(
                topic.name,
                topic.description,
                keywords,
                item.title,
                item.sourceName,
                item.author == null ? "(inconnu)" : item.author,
                content);
    }

    private static void updateItemClassification(Connection con, long itemId, String status,
                                                 int score, String reasoning) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE items SET status = ?, relevance_score = ?, relevance_reasoning = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, score);
            ps.setString(3, reasoning);
            ps.setLong(4, itemId);
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
