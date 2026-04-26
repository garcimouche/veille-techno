# Schéma SQLite

## DDL

```sql
-- Topics : technologies suivies
CREATE TABLE IF NOT EXISTS topics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    keywords TEXT,  -- JSON array
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sources : feeds/APIs à crawler
CREATE TABLE IF NOT EXISTS sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    topic_id INTEGER NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('rss', 'github', 'hn', 'reddit', 'arxiv')),
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    config TEXT,  -- JSON pour params spécifiques (ex: subreddit name, GH repo)
    last_checked TIMESTAMP,
    last_error TEXT,
    active BOOLEAN DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (topic_id) REFERENCES topics(id)
);

CREATE INDEX idx_sources_topic ON sources(topic_id);
CREATE INDEX idx_sources_active ON sources(active);

-- Items : contenus récupérés
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id INTEGER NOT NULL,
    external_id TEXT NOT NULL,  -- URL canonique ou ID source pour dedup
    title TEXT NOT NULL,
    url TEXT,
    author TEXT,
    content TEXT,        -- contenu brut/excerpt
    content_hash TEXT,   -- hash pour détecter modifs
    published_at TIMESTAMP,
    fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status TEXT DEFAULT 'NEW' CHECK(status IN ('NEW', 'CLASSIFIED', 'SKIPPED', 'SUMMARIZED', 'INCLUDED', 'ERROR')),
    relevance_score INTEGER,  -- 0-10
    relevance_reasoning TEXT,
    summary TEXT,
    summary_model TEXT,
    error_message TEXT,
    UNIQUE(source_id, external_id),
    FOREIGN KEY (source_id) REFERENCES sources(id)
);

CREATE INDEX idx_items_source ON items(source_id);
CREATE INDEX idx_items_status ON items(status);
CREATE INDEX idx_items_published ON items(published_at);
CREATE INDEX idx_items_score ON items(relevance_score);

-- Digests : compilations envoyées
CREATE TABLE IF NOT EXISTS digests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    topic_id INTEGER NOT NULL,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    period_start TIMESTAMP,
    period_end TIMESTAMP,
    item_count INTEGER,
    content_md TEXT,
    content_html TEXT,
    sent_at TIMESTAMP,
    recipient TEXT,
    delivery_status TEXT,
    FOREIGN KEY (topic_id) REFERENCES topics(id)
);

-- Many-to-many : items inclus dans un digest
CREATE TABLE IF NOT EXISTS digest_items (
    digest_id INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    position INTEGER,
    PRIMARY KEY (digest_id, item_id),
    FOREIGN KEY (digest_id) REFERENCES digests(id) ON DELETE CASCADE,
    FOREIGN KEY (item_id) REFERENCES items(id)
);
```

## Cycle de vie d'un item

```
NEW → fetched, pas encore évalué
  ↓
CLASSIFIED → relevance_score assigné
  ↓
  ├─ score < seuil → SKIPPED (pas dans digest, mais gardé pour historique)
  └─ score >= seuil → continue
       ↓
  SUMMARIZED → résumé Claude généré
       ↓
  INCLUDED → inclus dans un digest envoyé
```

État `ERROR` à n'importe quelle étape si problème (avec message).

## Convention external_id

Pour garantir l'unicité et la déduplication :

| Source type | external_id |
|---|---|
| RSS | URL canonique de l'item (Atom `<id>` ou `<link>`) |
| GitHub releases | `{owner}/{repo}@{tag}` |
| HackerNews | HN item ID |
| Reddit | post fullname (`t3_xxxxxx`) |
| arXiv | arXiv ID |

## Requêtes types

### Items à classifier
```sql
SELECT i.*, s.topic_id
FROM items i
JOIN sources s ON i.source_id = s.id
WHERE i.status = 'NEW'
  AND s.topic_id = ?
ORDER BY i.published_at DESC;
```

### Items à résumer (post-classification)
```sql
SELECT * FROM items
WHERE status = 'CLASSIFIED'
  AND relevance_score >= ?
ORDER BY published_at DESC;
```

### Items à inclure dans digest
```sql
SELECT i.*
FROM items i
JOIN sources s ON i.source_id = s.id
WHERE s.topic_id = ?
  AND i.status = 'SUMMARIZED'
  AND i.published_at >= ?
ORDER BY i.relevance_score DESC, i.published_at DESC;
```
