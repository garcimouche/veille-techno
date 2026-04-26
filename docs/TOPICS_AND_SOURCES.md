# Topics et sources curées

Liste de référence des topics et sources, validée pour le MVP. La configuration runtime se trouve dans `config/topics.yaml`.

---

## Topic 1 : Java

**Slug** : `java`
**Description** : Java 21+, JVM, JEPs, performance, modern Java practices
**Keywords** : java, jvm, jep, openjdk, jdk, java21, java22, java23

### Sources officielles

| Source | URL RSS | Notes |
|---|---|---|
| Inside Java | https://inside.java/feed.xml | Oracle engineers, JEP authors |
| Oracle Java Blog | https://blogs.oracle.com/java/rss | Annonces officielles |
| OpenJDK JEPs | https://openjdk.org/jeps/0 | Liste des JEPs (pas de RSS officiel, scrape) |

### Communautés et multi-auteurs

| Source | URL RSS |
|---|---|
| Foojay.io | https://foojay.io/feed/ |
| InfoQ Java | https://feed.infoq.com/java/ |
| Baeldung | https://www.baeldung.com/feed |
| JetBrains IDEA Blog | https://blog.jetbrains.com/idea/feed/ |

### Experts

| Source | URL RSS |
|---|---|
| Vlad Mihalcea | https://vladmihalcea.com/feed/ |
| Thomas Vitale | https://www.thomasvitale.com/index.xml |
| SivaLabs | https://www.sivalabs.in/index.xml |
| Marco Behler | https://www.marcobehler.com/feed.atom |

### Spécifique stack

| Source | URL RSS |
|---|---|
| Spring Blog | https://spring.io/blog.atom |

---

## Topic 2 : Angular

**Slug** : `angular`
**Description** : Angular framework, signals, zoneless, modern reactivity
**Keywords** : angular, signals, zoneless, ngrx, rxjs, standalone

### Sources officielles

| Source | URL |
|---|---|
| Angular Blog | https://blog.angular.dev/feed (à confirmer) |
| Angular GitHub Releases | https://github.com/angular/angular/releases.atom |

### Communautés

| Source | URL |
|---|---|
| Angular Love | https://angular.love/feed |
| inDepthDev (Angular tag) | https://indepth.dev/feed |
| Angular Experts | https://angularexperts.io/blog/feed.xml |
| dev.to "This is Angular" | https://dev.to/feed/this-is-angular |

### Experts

| Source | URL |
|---|---|
| Netanel Basal | https://netbasal.com/feed |
| Tim Deschryver | https://timdeschryver.dev/blog/rss.xml |
| Manfred Steyer (Angular Architects) | https://www.angulararchitects.io/en/feed/ |
| Brandon Roberts | https://brandonroberts.dev/rss.xml |

---

## Topic 3 : AI / LLM

**Slug** : `ai`
**Description** : LLMs, AI agents, MCP, building with Claude/GPT, AI engineering
**Keywords** : claude, anthropic, openai, gpt, llm, agent, mcp, ai-engineering, rag

### Labs officiels

| Source | URL RSS |
|---|---|
| Anthropic News | https://www.anthropic.com/news/rss.xml |
| Anthropic Engineering | https://www.anthropic.com/engineering/rss.xml |
| OpenAI Blog | https://openai.com/blog/rss.xml |
| Google DeepMind Blog | https://deepmind.google/blog/rss.xml |
| HuggingFace Blog | https://huggingface.co/blog/feed.xml |

### Newsletters / curated

| Source | URL |
|---|---|
| Latent Space (swyx) | https://www.latent.space/feed |
| Import AI (Jack Clark) | https://importai.substack.com/feed |
| The Batch (Andrew Ng) | https://www.deeplearning.ai/the-batch/feed |
| Ahead of AI (Sebastian Raschka) | https://magazine.sebastianraschka.com/feed |
| TLDR AI | https://tldr.tech/api/rss/ai |

### Practitioner experts

| Source | URL |
|---|---|
| Simon Willison | https://simonwillison.net/atom/everything/ |
| Chip Huyen | https://huyenchip.com/feed.xml |
| Eugene Yan | https://eugeneyan.com/rss/ |
| Hamel Husain | https://hamel.dev/index.xml |
| Jason Liu | https://jxnl.co/feed.xml |

### Stack-specific

| Source | URL |
|---|---|
| Spring AI Blog | filtre Spring Blog par tag |
| LangChain Blog | https://blog.langchain.dev/rss/ |

### Communauté (signal)

| Source | Type |
|---|---|
| r/LocalLLaMA | Reddit API top weekly |
| Hacker News "AI" | Algolia API filtre |
| arXiv cs.CL | RSS http://export.arxiv.org/rss/cs.CL |

---

## Notes de curation

### Vérifications à faire avant init

1. **Tester chaque RSS** avec `curl` pour confirmer disponibilité — certaines URLs sont approximatives et à valider
2. Pour les sources sans RSS officiel (Reddit, HN), utiliser scripts dédiés
3. Pour Substack newsletters : ajouter `/feed` à l'URL principale

### Critères de qualité d'une source

- Publication active (>= 1 post / mois)
- Auteur identifié et crédible
- Contenu technique substantiel (pas juste news aggregation)
- RSS feed disponible et complet (pas juste résumé)

### À ajouter potentiellement plus tard

- **Spring Boot** comme topic dédié (séparé de Java) si volume justifie
- **React/TypeScript** topic
- **Shopify ecosystem** (vu projet pricing-agent)
- **Local AI / Ollama** comme sous-topic

---

## Topology mémoire pour Claude API

Quand on appelle Claude pour classifier ou résumer un item, on veut lui donner :

1. Le **topic context** (description + keywords)
2. L'**item content** (titre + contenu/excerpt)
3. **Instructions claires** (voir `docs/PROMPTS.md`)

Cela permet au modèle de bien évaluer la pertinence — un post sur "TypeScript decorators" peut être pertinent pour Angular mais pas pour Java.
