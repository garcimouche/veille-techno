///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2
//DEPS com.rometools:rome:2.1.0
//DEPS org.commonmark:commonmark:0.22.0
//SOURCES init-db.java
//SOURCES fetch-rss.java
//SOURCES classify-relevance.java
//SOURCES summarize.java
//SOURCES generate-digest.java
//SOURCES send-email.java
//MAIN Veille

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "veille", mixinStandardHelpOptions = true,
        description = "Veille techno orchestrator. Subcommands: run, status, init.",
        subcommands = {
                Veille.Run.class,
                Veille.Status.class,
                Veille.Init.class,
        })
class Veille implements Runnable {

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Veille());
        if (args.length == 0) {
            cli.usage(System.out);
            System.exit(0);
        }
        System.exit(cli.execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Full pipeline: fetch → classify → summarize → digest [→ send]")
    static class Run implements Callable<Integer> {

        @Option(names = "--topic", description = "Topic slug, or 'all' (default: ${DEFAULT-VALUE})")
        String topic = "all";

        @Option(names = "--since", description = "Time window for classify/summarize/digest (default: ${DEFAULT-VALUE})")
        String since = "7d";

        @Option(names = "--send", description = "Send the resulting digest(s) via Resend")
        boolean send;

        @Option(names = "--skip-fetch", description = "Skip the fetch-rss step (useful for re-running on existing data)")
        boolean skipFetch;

        @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
        String envPath = "config/.env";

        @Option(names = "--db", description = "Override DB path from .env")
        String dbPath;

        @Override
        public Integer call() throws Exception {
            String[] commonArgs = dbPath != null
                    ? new String[]{"--env", envPath, "--db", dbPath}
                    : new String[]{"--env", envPath};

            if (!skipFetch) {
                step("fetch-rss", () -> new CommandLine(new FetchRss())
                        .execute(append(commonArgs, "--topic", topic)));
            }

            step("classify-relevance", () -> new CommandLine(new ClassifyRelevance())
                    .execute(append(commonArgs, "--topic", topic, "--since", since)));

            step("summarize", () -> new CommandLine(new Summarize())
                    .execute(append(commonArgs, "--topic", topic, "--since", since)));

            List<String> topics = "all".equals(topic)
                    ? listActiveTopicSlugs(envPath, dbPath)
                    : List.of(topic);

            for (String t : topics) {
                step("generate-digest [" + t + "]", () -> new CommandLine(new GenerateDigest())
                        .execute(append(commonArgs, "--topic", t, "--since", since)));
                if (send) {
                    step("send-email [" + t + "]", () -> new CommandLine(new SendEmail())
                            .execute(append(commonArgs, "--topic", t)));
                }
            }
            return 0;
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show a DB snapshot: sources by topic, items by status, recent digests.")
    static class Status implements Callable<Integer> {

        @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
        String envPath = "config/.env";

        @Option(names = "--db", description = "Override DB path from .env")
        String dbPathOverride;

        @Override
        public Integer call() throws Exception {
            String dbPath = resolveDbPath(envPath, dbPathOverride);

            try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                System.out.println("DB: " + Paths.get(dbPath).toAbsolutePath());
                System.out.println();

                System.out.println("Sources by topic");
                System.out.println("----------------");
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT t.slug, " +
                                     "  SUM(CASE WHEN s.active=1 THEN 1 ELSE 0 END) AS active, " +
                                     "  SUM(CASE WHEN s.last_error IS NOT NULL THEN 1 ELSE 0 END) AS errored, " +
                                     "  COUNT(*) AS total " +
                                     "FROM sources s JOIN topics t ON s.topic_id = t.id " +
                                     "GROUP BY t.slug ORDER BY t.slug")) {
                    System.out.printf("  %-10s %6s %8s %6s%n", "topic", "active", "errored", "total");
                    while (rs.next()) {
                        System.out.printf("  %-10s %6d %8d %6d%n",
                                rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    }
                }
                System.out.println();

                System.out.println("Items by status");
                System.out.println("---------------");
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT status, COUNT(*) AS n FROM items GROUP BY status ORDER BY status")) {
                    System.out.printf("  %-12s %6s%n", "status", "count");
                    int total = 0;
                    while (rs.next()) {
                        System.out.printf("  %-12s %6d%n", rs.getString(1), rs.getInt(2));
                        total += rs.getInt(2);
                    }
                    System.out.printf("  %-12s %6d%n", "total", total);
                }
                System.out.println();

                System.out.println("Recent digests");
                System.out.println("--------------");
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT d.id, t.slug, d.item_count, d.generated_at, " +
                                     "       COALESCE(d.sent_at, '—') AS sent " +
                                     "FROM digests d JOIN topics t ON d.topic_id = t.id " +
                                     "ORDER BY d.id DESC LIMIT 8")) {
                    System.out.printf("  %3s  %-9s %5s  %-19s  %s%n", "id", "topic", "items", "generated_at", "sent_at");
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        System.out.printf("  %3d  %-9s %5d  %-19s  %s%n",
                                rs.getInt(1), rs.getString(2), rs.getInt(3),
                                rs.getString(4), rs.getString(5));
                    }
                    if (!any) System.out.println("  (none)");
                }
            }
            return 0;
        }
    }

    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "Run init-db (create schema + seed topics/sources from topics.yaml).")
    static class Init implements Callable<Integer> {

        @Option(names = "--env", description = "Path to .env (default: ${DEFAULT-VALUE})")
        String envPath = "config/.env";

        @Option(names = "--db", description = "Override DB path from .env")
        String dbPath;

        @Option(names = "--config", description = "Path to topics.yaml (default: config/topics.yaml)")
        String configPath = "config/topics.yaml";

        @Override
        public Integer call() throws Exception {
            String[] args = dbPath != null
                    ? new String[]{"--env", envPath, "--db", dbPath, "--config", configPath}
                    : new String[]{"--env", envPath, "--config", configPath};
            return new CommandLine(new InitDB()).execute(args);
        }
    }

    static void step(String name, java.util.function.IntSupplier body) {
        System.out.println();
        System.out.println("==> " + name);
        long t0 = System.currentTimeMillis();
        int code = body.getAsInt();
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        if (code != 0) {
            System.err.printf("==> %s FAILED (exit=%d, %ds)%n", name, code, elapsed);
            System.exit(code);
        }
        System.out.printf("==> %s done (%ds)%n", name, elapsed);
    }

    static String[] append(String[] base, String... extra) {
        String[] out = new String[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    static List<String> listActiveTopicSlugs(String envPath, String dbPathOverride) throws Exception {
        String dbPath = resolveDbPath(envPath, dbPathOverride);
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT t.slug FROM topics t " +
                             "JOIN sources s ON s.topic_id = t.id " +
                             "WHERE s.active = 1 ORDER BY t.slug")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    static String resolveDbPath(String envPath, String dbPathOverride) throws Exception {
        if (dbPathOverride != null) return dbPathOverride;
        Map<String, String> env = loadEnv(Paths.get(envPath));
        return env.getOrDefault("DB_PATH", "./data/veille.db");
    }

    static Map<String, String> loadEnv(Path path) throws Exception {
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
