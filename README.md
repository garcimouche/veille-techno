# Veille Techno

Système personnel de veille technologique automatisée. Crawler des sources de référence par topic, classifier la pertinence avec Claude, résumer les items pertinents, livrer un digest par email à la demande.

## TL;DR

- **Stack** : Java 21 + JBang + SQLite + Claude API
- **Pas de Maven/Gradle** : chaque feature = un script JBang autonome
- **Topics initiaux** : Java, Angular, AI/LLM (~30 sources)
- **Volume estimé** : 50-100 items/semaine, coût Claude < 1$/mois

## Pour Claude Code

**Si tu lis ce projet pour la première fois, lis dans cet ordre :**

1. `PROJECT_BRIEF.md` — vision, principes, architecture haute
2. `docs/ARCHITECTURE.md` — détails techniques, conventions JBang
3. `docs/SCHEMA.md` — schéma SQLite
4. `docs/PROMPTS.md` — prompts Claude API
5. `docs/TOPICS_AND_SOURCES.md` — sources curées par topic
6. `config/topics.yaml` — configuration runtime

## Quick start

```bash
# 1. Installer JBang
curl -Ls https://sh.jbang.dev | bash -s - app setup

# 2. Configurer
cp config/.env.example config/.env
# édite .env : ANTHROPIC_API_KEY au minimum

# 3. Initialiser la DB et seed des topics
jbang scripts/init-db.java

# 4. Premier crawl (à la main)
jbang scripts/fetch-rss.java --topic=java

# 5. Classifier les nouveaux items
jbang scripts/classify-relevance.java --topic=java

# 6. Résumer les items pertinents
jbang scripts/summarize.java --topic=java

# 7. Générer le digest
jbang scripts/generate-digest.java --topic=java --since=7d --send
```

## Étapes de développement

Voir `PROJECT_BRIEF.md` section "Étapes recommandées de développement" pour le découpage en phases.

**Phase 1 (next)** : `init-db.java` + `fetch-rss.java` + premier topic Java en mode test.

## Décisions ouvertes

Voir `PROJECT_BRIEF.md` section "Décisions ouvertes" :
- Email provider (penche : Resend)
- Format digest (Markdown→HTML, structure exacte)
- Langue résumés (penche : FR pour explicatif, EN pour termes techniques)
- Seuil pertinence par topic (configuré dans `topics.yaml`)

## Contexte développeur

Franck — dev Java/Spring Boot + Angular/React, Montréal. MacBook Air M3 16GB.
Workflow : Claude.ai pour design/analyse, Claude Code pour implémentation.
