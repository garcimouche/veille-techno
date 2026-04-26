# Architecture technique

## Pipeline de données

```
[Sources RSS/API/Reddit/HN]
        │
        ▼
   [fetch-*.java]  ──► insère raw items dans `items` table (status=NEW)
        │
        ▼
[classify-relevance.java] ──► Claude API évalue pertinence
        │                       met à jour score + status=CLASSIFIED
        ▼
   [summarize.java] ──► Claude API résume si score >= seuil
        │                status=SUMMARIZED
        ▼
[generate-digest.java] ──► compose digest HTML/MD pour topic + période
        │
        ▼
   [send-email.java] ──► livre par SMTP/Mailgun
                          status=DELIVERED
```

## Schéma SQLite

Voir `docs/SCHEMA.md` pour le DDL complet. Synthèse :

- **topics** : id, name, slug, description, keywords (JSON), created_at
- **sources** : id, topic_id, type (rss|github|hn|reddit), url, name, last_checked, active
- **items** : id, source_id, external_id (URL canonique ou ID source), title, url, content, published_at, fetched_at, status, relevance_score, summary
- **digests** : id, topic_id, generated_at, period_start, period_end, content_md, content_html, sent_at, recipient
- **digest_items** : digest_id, item_id (many-to-many)

## Conventions JBang

### Structure d'un script type

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

@Command(name = "script-name", mixinStandardHelpOptions = true,
         description = "What this script does")
public class script_name implements Callable<Integer> {

    @Option(names = "--topic", description = "Topic slug or 'all'")
    String topic = "all";

    public static void main(String[] args) {
        System.exit(new CommandLine(new script_name()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        // logique
        return 0;
    }
}
```

### Dépendances communes (à standardiser)

```java
//DEPS info.picocli:picocli:4.7.5
//DEPS org.xerial:sqlite-jdbc:3.46.0.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.rometools:rome:2.1.0
//DEPS org.slf4j:slf4j-simple:2.0.13
//SOURCES Common.java
```

### Code partagé

Créer un fichier `scripts/Common.java` (compagnon, inclus via `//SOURCES`) avec :
- `Db.connect()` : ouvre connexion SQLite
- `ClaudeClient.complete(model, system, user)` : wrapper API Claude
- `ConfigLoader.load()` : lit `.env` et `topics.yaml`
- Logging utilitaire

## Intégration Claude API

### Configuration

```bash
ANTHROPIC_API_KEY=sk-ant-...
CLAUDE_MODEL_FAST=claude-haiku-4-5  # classification + résumés courts
CLAUDE_MODEL_DEEP=claude-sonnet-4-6  # résumés longs si nécessaire
```

### Endpoints utilisés

- `POST https://api.anthropic.com/v1/messages`
- Headers : `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`

### Coûts estimés (volume perso)

- Classification : ~50-100 items/semaine × ~500 tokens input × Haiku = négligeable (cents)
- Summarization : ~30-50 items pertinents/semaine × ~3K tokens input + 500 output × Haiku = quelques cents/semaine
- **Total : <1$/mois** au démarrage

## Déduplication

### Niveau 1 : item-level (essentiel)
Clé unique : `(source_id, external_id)` où external_id = URL canonique ou ID source.
Implémentation : `INSERT OR IGNORE` SQLite.

### Niveau 2 : cross-source (post-MVP)
Si 3 sources publient sur "Claude 4.7 release", éviter de générer 3 résumés séparés.

Approche :
1. Items récents (24-48h) → embedding ou hash de titre
2. Cluster les items similaires (similarité > seuil)
3. Pour chaque cluster, un seul résumé "fusionné" avec toutes les URLs sources

Optionnel pour MVP, mais essentiel pour topic AI (très bruyant).

## Scheduling

### macOS (cron utilisateur)

```cron
# crontab -e
0 * * * * cd /Users/franckgarcia/work/veille-techno && jbang scripts/fetch-rss.java --topic=all >> data/cron.log 2>&1
0 * * * * cd /Users/franckgarcia/work/veille-techno && jbang scripts/fetch-github-releases.java >> data/cron.log 2>&1
30 * * * * cd /Users/franckgarcia/work/veille-techno && jbang scripts/classify-relevance.java >> data/cron.log 2>&1
```

### Alternative : launchd plist
Plus moderne sur macOS, à explorer si cron pose problème.

## Email delivery

### Option A : Mailgun (recommandé MVP)
- 100 emails/jour gratuit
- API HTTP simple (curl-style depuis Java)
- Domaine sandbox utilisable au démarrage

### Option B : SMTP Gmail
- Nécessite App Password (2FA Google obligatoire)
- Limite 500 emails/jour
- Pas de logs/dashboard

### Option C : Resend
- 3000 emails/mois gratuit
- API moderne
- DX très propre

**Recommandation** : Resend ou Mailgun pour la simplicité d'API.

## Format du digest

### Structure proposée (Markdown)

```markdown
# Veille {Topic} — Semaine du {date}

{N} items pertinents, {N} sources actives

## ⭐ Highlights

{si déduplication cross-source détecte un sujet trending, 1-3 highlights ici}

## 📰 Items

### {Title}
**Source** : {source name} • **Date** : {date} • **Score** : {score}/10
**URL** : {url}

{summary}

---

### {Title 2}
...
```

### Considérations

- Markdown converti en HTML pour email (lib `commonmark-java`)
- Inline CSS pour compatibilité clients email
- Texte alternatif simple pour clients sans HTML

## Gestion d'erreurs

Stratégie globale :
- Une source qui plante n'arrête pas les autres (try/catch par source)
- Items en erreur restent en status `ERROR` avec message
- Un script `retry-errors.java` pour relancer les items en erreur
- Logs structurés vers `data/cron.log` avec niveaux

## Évolutions futures envisageables

- **Frontend web** : Spring Boot + React si besoin de dashboard
- **Multi-utilisateurs** : si projet partagé
- **RSS de sortie** : générer un feed RSS des digests pour reader apps
- **Recherche** : SQLite FTS5 sur résumés
- **Trending detection** : ML simple sur fréquence de mots-clés cross-temps
- **Custom prompts par topic** : prompts différents selon nature du contenu
