///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "init-db", mixinStandardHelpOptions = true,
        description = "Initialise SQLite DB and seed topics/sources from topics.yaml. Idempotent.")
class InitDB implements Callable<Integer> {

    @Option(names = "--config", description = "Path to topics.yaml (default: ${DEFAULT-VALUE})")
    Path configPath = Paths.get("config/topics.yaml");

    @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
    Path envPath = Paths.get("config/.env");

    @Option(names = "--db", description = "Override DB path from .env")
    String dbPathOverride;

    public static void main(String[] args) {
        System.exit(new CommandLine(new InitDB()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(configPath)) {
            System.err.println("ERROR: topics config not found: " + configPath.toAbsolutePath());
            return 2;
        }

        Map<String, String> env = loadEnv(envPath);
        String dbPath = dbPathOverride != null ? dbPathOverride
                : env.getOrDefault("DB_PATH", "./data/veille.db");

        Path dbFile = Paths.get(dbPath);
        if (dbFile.getParent() != null) {
            Files.createDirectories(dbFile.getParent());
        }

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ObjectMapper json = new ObjectMapper();
        JsonNode root = yaml.readTree(configPath.toFile());

        int topicsCreated = 0, topicsUpdated = 0;
        int sourcesCreated = 0, sourcesUpdated = 0;

        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            createSchema(con);

            for (JsonNode t : root.path("topics")) {
                String slug = t.path("slug").asText();
                String name = t.path("name").asText();
                String description = t.path("description").asText(null);
                String keywords = t.has("keywords")
                        ? json.writeValueAsString(t.get("keywords"))
                        : "[]";

                long topicId;
                try (PreparedStatement sel = con.prepareStatement(
                        "SELECT id FROM topics WHERE slug = ?")) {
                    sel.setString(1, slug);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            topicId = rs.getLong(1);
                            try (PreparedStatement up = con.prepareStatement(
                                    "UPDATE topics SET name = ?, description = ?, keywords = ? WHERE id = ?")) {
                                up.setString(1, name);
                                up.setString(2, description);
                                up.setString(3, keywords);
                                up.setLong(4, topicId);
                                up.executeUpdate();
                            }
                            topicsUpdated++;
                        } else {
                            try (PreparedStatement ins = con.prepareStatement(
                                    "INSERT INTO topics(slug, name, description, keywords) VALUES (?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS)) {
                                ins.setString(1, slug);
                                ins.setString(2, name);
                                ins.setString(3, description);
                                ins.setString(4, keywords);
                                ins.executeUpdate();
                                try (ResultSet keys = ins.getGeneratedKeys()) {
                                    keys.next();
                                    topicId = keys.getLong(1);
                                }
                            }
                            topicsCreated++;
                        }
                    }
                }

                for (JsonNode s : t.path("sources")) {
                    String type = s.path("type").asText();
                    String sName = s.path("name").asText();
                    String url = s.path("url").asText();
                    String configJson = s.has("config")
                            ? json.writeValueAsString(s.get("config"))
                            : null;

                    try (PreparedStatement sel = con.prepareStatement(
                            "SELECT id FROM sources WHERE topic_id = ? AND type = ? AND url = ?")) {
                        sel.setLong(1, topicId);
                        sel.setString(2, type);
                        sel.setString(3, url);
                        try (ResultSet rs = sel.executeQuery()) {
                            if (rs.next()) {
                                long sourceId = rs.getLong(1);
                                try (PreparedStatement up = con.prepareStatement(
                                        "UPDATE sources SET name = ?, config = ?, active = 1 WHERE id = ?")) {
                                    up.setString(1, sName);
                                    up.setString(2, configJson);
                                    up.setLong(3, sourceId);
                                    up.executeUpdate();
                                }
                                sourcesUpdated++;
                            } else {
                                try (PreparedStatement ins = con.prepareStatement(
                                        "INSERT INTO sources(topic_id, type, name, url, config, active) " +
                                                "VALUES (?, ?, ?, ?, ?, 1)")) {
                                    ins.setLong(1, topicId);
                                    ins.setString(2, type);
                                    ins.setString(3, sName);
                                    ins.setString(4, url);
                                    ins.setString(5, configJson);
                                    ins.executeUpdate();
                                }
                                sourcesCreated++;
                            }
                        }
                    }
                }
            }

            con.commit();

            int totalTopics, activeSources, totalSources;
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT (SELECT COUNT(*) FROM topics), " +
                                 "(SELECT COUNT(*) FROM sources WHERE active = 1), " +
                                 "(SELECT COUNT(*) FROM sources)")) {
                rs.next();
                totalTopics = rs.getInt(1);
                activeSources = rs.getInt(2);
                totalSources = rs.getInt(3);
            }

            System.out.println("DB      : " + dbFile.toAbsolutePath());
            System.out.println("Topics  : " + totalTopics
                    + "  (this run — created: " + topicsCreated
                    + ", updated: " + topicsUpdated + ")");
            System.out.println("Sources : " + activeSources + " active / " + totalSources + " total"
                    + "  (this run — created: " + sourcesCreated
                    + ", updated: " + sourcesUpdated + ")");
        }

        return 0;
    }

    private static void createSchema(Connection con) throws Exception {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS topics (" +
                        "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "  slug TEXT UNIQUE NOT NULL," +
                        "  name TEXT NOT NULL," +
                        "  description TEXT," +
                        "  keywords TEXT," +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")",
                "CREATE TABLE IF NOT EXISTS sources (" +
                        "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "  topic_id INTEGER NOT NULL," +
                        "  type TEXT NOT NULL CHECK(type IN ('rss','github','hn','reddit','arxiv'))," +
                        "  name TEXT NOT NULL," +
                        "  url TEXT NOT NULL," +
                        "  config TEXT," +
                        "  last_checked TIMESTAMP," +
                        "  last_error TEXT," +
                        "  active BOOLEAN DEFAULT 1," +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  FOREIGN KEY (topic_id) REFERENCES topics(id)" +
                        ")",
                "CREATE INDEX IF NOT EXISTS idx_sources_topic  ON sources(topic_id)",
                "CREATE INDEX IF NOT EXISTS idx_sources_active ON sources(active)",
                "CREATE TABLE IF NOT EXISTS items (" +
                        "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "  source_id INTEGER NOT NULL," +
                        "  external_id TEXT NOT NULL," +
                        "  title TEXT NOT NULL," +
                        "  url TEXT," +
                        "  author TEXT," +
                        "  content TEXT," +
                        "  content_hash TEXT," +
                        "  published_at TIMESTAMP," +
                        "  fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  status TEXT DEFAULT 'NEW' CHECK(status IN " +
                        "    ('NEW','CLASSIFIED','SKIPPED','SUMMARIZED','INCLUDED','ERROR'))," +
                        "  relevance_score INTEGER," +
                        "  relevance_reasoning TEXT," +
                        "  summary TEXT," +
                        "  summary_model TEXT," +
                        "  error_message TEXT," +
                        "  UNIQUE(source_id, external_id)," +
                        "  FOREIGN KEY (source_id) REFERENCES sources(id)" +
                        ")",
                "CREATE INDEX IF NOT EXISTS idx_items_source    ON items(source_id)",
                "CREATE INDEX IF NOT EXISTS idx_items_status    ON items(status)",
                "CREATE INDEX IF NOT EXISTS idx_items_published ON items(published_at)",
                "CREATE INDEX IF NOT EXISTS idx_items_score     ON items(relevance_score)",
                "CREATE TABLE IF NOT EXISTS digests (" +
                        "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "  topic_id INTEGER NOT NULL," +
                        "  generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  period_start TIMESTAMP," +
                        "  period_end TIMESTAMP," +
                        "  item_count INTEGER," +
                        "  content_md TEXT," +
                        "  content_html TEXT," +
                        "  sent_at TIMESTAMP," +
                        "  recipient TEXT," +
                        "  delivery_status TEXT," +
                        "  FOREIGN KEY (topic_id) REFERENCES topics(id)" +
                        ")",
                "CREATE TABLE IF NOT EXISTS digest_items (" +
                        "  digest_id INTEGER NOT NULL," +
                        "  item_id INTEGER NOT NULL," +
                        "  position INTEGER," +
                        "  PRIMARY KEY (digest_id, item_id)," +
                        "  FOREIGN KEY (digest_id) REFERENCES digests(id) ON DELETE CASCADE," +
                        "  FOREIGN KEY (item_id) REFERENCES items(id)" +
                        ")"
        };
        try (Statement st = con.createStatement()) {
            for (String stmt : ddl) {
                st.execute(stmt);
            }
        }
    }

    private static Map<String, String> loadEnv(Path path) throws Exception {
        Map<String, String> env = new HashMap<>();
        if (!Files.exists(path)) {
            System.err.println("WARN : .env not found at " + path.toAbsolutePath() + " — using defaults");
            return env;
        }
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
