# Veille Techno — Project Brief

## Vision

Système personnel de veille technologique automatisé. Pour chaque **topic** (technologie d'intérêt), un crawler récupère les nouveaux contenus depuis des **sources de référence** (RSS, GitHub releases, Reddit, HackerNews, etc.), Claude API filtre et résume les items pertinents, et un **digest email** est généré à la demande par topic.

**Pas de YouTube** au scope initial. Focus sur sources textuelles structurées.

## Principes directeurs

1. **JBang-first** : chaque fonctionnalité = un script JBang autonome (un fichier `.java`). Pas de Spring Boot, pas de Maven/Gradle au démarrage.
2. **SQLite** comme storage : zero config, file-based, parfait pour usage perso.
3. **CLI driven** : tout se déclenche en ligne de commande. Pas de frontend web au MVP.
4. **Claude API** pour la classification de pertinence et la summarization.
5. **Idempotence** : on peut relancer un script sans dégâts (tracking via DB des items déjà traités).
6. **Modulaire** : un script = une responsabilité (fetch, classify, summarize, digest, email).

## Architecture

```
veille-techno/
├── PROJECT_BRIEF.md              # Ce fichier
├── docs/
│   ├── ARCHITECTURE.md           # Détails techniques
│   ├── TOPICS_AND_SOURCES.md     # Topics curés + sources
│   ├── PROMPTS.md                # Prompts Claude API utilisés
│   └── SCHEMA.md                 # Schéma SQLite
├── config/
│   ├── topics.yaml               # Configuration des topics et sources
│   └── .env.example              # Variables d'environnement
├── scripts/
│   ├── init-db.java              # Initialise SQLite + seed topics
│   ├── fetch-rss.java            # Crawler RSS générique
│   ├── fetch-github-releases.java
│   ├── fetch-hackernews.java
│   ├── fetch-reddit.java
│   ├── classify-relevance.java   # Claude API : score 0-10 par item
│   ├── summarize.java            # Claude API : résumé par item
│   ├── generate-digest.java      # Compile digest pour un topic
│   ├── send-email.java           # Envoi via SMTP/Mailgun
│   └── veille.java               # Script orchestrateur (commande unifiée)
├── data/
│   └── veille.db                 # SQLite (généré, gitignored)
└── jbang-catalog.json            # Pour exposer scripts comme commandes
```

## Stack technique

| Composant | Choix | Pourquoi |
|---|---|---|
| Language | **Java 21** | Maîtrise existante, JBang compatible |
| Runtime | **JBang** | Scripts autonomes, zéro setup |
| Storage | **SQLite** | File-based, zero config, suffisant pour volume perso |
| RSS parsing | **Rome (rometools)** | Standard Java RSS/Atom |
| HTTP | **java.net.http.HttpClient** | Built-in Java 21 |
| JSON | **Jackson** | Standard, robuste |
| CLI args | **picocli** | Idiomatique pour JBang |
| LLM | **Claude API** (claude-haiku-4-5 par défaut, sonnet-4-6 si profondeur) | Coût/qualité optimal |
| Scheduling | **cron natif macOS** | Simple, fiable |
| Email | **Jakarta Mail (SMTP)** ou **Mailgun API** | À décider — voir ARCHITECTURE.md |

## Workflow utilisateur

### Setup initial (une fois)
```bash
# Clone + configure
cd veille-techno
cp config/.env.example config/.env
# édite .env : ANTHROPIC_API_KEY, MAILGUN_API_KEY, etc.
jbang scripts/init-db.java
```

### Crawl périodique (cron)
```bash
# Toutes les heures, par cron
jbang scripts/fetch-rss.java --topic=all
jbang scripts/fetch-github-releases.java --topic=all
jbang scripts/fetch-hackernews.java
jbang scripts/fetch-reddit.java
```

### Génération digest à la demande
```bash
jbang scripts/veille.java digest --topic=ai --since=7d --send-email
# ou en pipeline :
jbang scripts/classify-relevance.java --topic=ai --since=7d
jbang scripts/summarize.java --topic=ai --min-score=7
jbang scripts/generate-digest.java --topic=ai --output=digest-ai.html
jbang scripts/send-email.java --file=digest-ai.html
```

## Topics au démarrage

Voir `docs/TOPICS_AND_SOURCES.md` pour la liste complète et curée.

1. **Java** — ~10 sources (Inside Java, Spring, Baeldung, experts)
2. **Angular** — ~10 sources (officiel, inDepthDev, experts signals)
3. **AI / LLM** — ~12 sources (labs, newsletters experts, Simon Willison, etc.)

Volume estimé : ~30 sources, ~50-100 items nouveaux par semaine.

## Étapes recommandées de développement

### Phase 1 — Foundation (1-2 soirées)
1. `init-db.java` : créer schéma SQLite + seed topics depuis `topics.yaml`
2. `fetch-rss.java` : crawler RSS générique avec dedup
3. Tester sur 2-3 sources Java pour valider le pipeline

### Phase 2 — Intelligence (1 soirée)
4. `classify-relevance.java` : appel Claude pour scorer pertinence
5. `summarize.java` : appel Claude pour résumer items pertinents

### Phase 3 — Delivery (1 soirée)
6. `generate-digest.java` : compose email HTML/Markdown structuré
7. `send-email.java` : envoi SMTP ou Mailgun

### Phase 4 — Extensions (au fur et à mesure)
8. `fetch-github-releases.java`
9. `fetch-hackernews.java` (Algolia API)
10. `fetch-reddit.java`
11. Détection blog posts dans descriptions (post-MVP)
12. Déduplication cross-source intelligente (Claude détecte que 3 sources parlent de la même chose)

## Décisions ouvertes

- [ ] **Email provider** : SMTP Gmail vs Mailgun vs SendGrid ?
- [ ] **Format digest** : Markdown converti en HTML, ou HTML direct ? Newsletter-style avec sections ou liste plate ?
- [ ] **Langue résumés** : EN (langue source) ou FR (préférence personnelle) ?
- [ ] **Filtre pertinence** : seuil minimum (ex : score >= 7/10) configurable par topic ?
- [ ] **Cache blog posts** : si même URL référencée par plusieurs items, dedup le résumé ?

## Contexte développeur

- **Franck** : dev Java/Spring Boot + Angular/React, Montréal
- Stack maison habituel : Java 21, Spring Boot 3.3, PostgreSQL, React/TS
- MacBook Air M3 16GB pour développement
- Utilise Claude API et Ollama (qwen3.5:9b) localement
- Workflow : Claude.ai pour design/analyse, Claude Code pour implémentation
