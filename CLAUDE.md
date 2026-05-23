# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules (Java 25, Maven 3.9+)
mvn clean compile

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=MMapDocumentStoreTest

# Run a single test method
mvn test -Dtest=MMapDocumentStoreTest#testMethodName

# Run the Spring Boot example app (starts on port 8081)
cd zerovector-spring-boot-example && mvn spring-boot:run
```

The project has only one test file: `zerovector-core/src/test/java/cn/nexon/zerovector/core/storage/MMapDocumentStoreTest.java` (JUnit Jupiter 5).

## Architecture

ZeroVector is a zero-embedding knowledge base: instead of chunking + vector DB, it parses documents into a **semantic tree** organized by chapter/section structure and navigates via **hybrid strategy** (keyword inverted index + LLM semantic reasoning). Chunk content is stored via **mmap** for zero-copy reads.

### Module Map

| Module | Purpose | Key Dependencies |
|--------|---------|-----------------|
| `zerovector-core` | All business logic: document comprehension, tree building, hybrid navigation, mmap storage, keyword indexing, SPI storage extension, hook system, LLM caching. Plain Java library, no Spring. | Jackson, Caffeine |
| `zerovector-spring-boot-starter` | Spring Boot 4.0 auto-configuration. Exposes `SemanticHub` as the `@Component` facade. Provides `SpringAiLLMProvider` and `LangChain4jLLMProvider`. | zerovector-core, Spring Boot, Spring AI / LangChain4j (optional) |
| `zerovector-spring-boot-example` | Runnable demo app with REST API (`/api/documents/upload`, `/api/documents/search`, `/api/cache/statistics`) and Thymeleaf UI. | zerovector-spring-boot-starter, Apache POI, PDFBox |
| `zerovector-app` | Empty scaffold for a future standalone (non-Spring) app. Contains no source files. | zerovector-core |

### Data Flow (Upload → Query)

1. **Upload** → `DocumentComprehender` chunks the document, calls LLM to extract summary/keywords/entities/example-questions per chunk
2. **Build Tree** → `TreeBuilder` recursively clusters chunks via LLM into `TreeNode`s (ROOT → CATEGORY → LEAF), stored in `ShardedTreeStorage`
3. **Index** → `KeywordDictionary` builds an inverted index mapping keywords → tree nodes with weights
4. **Store** → `MMapDocumentStore` writes chunk content to mmap binary files (`.data` + `.index`)
5. **Query** → `HybridNavigator`: Phase 1 keyword lookup in `KeywordDictionary` → Phase 2 LLM semantic navigation decision → Phase 3 mmap zero-copy read of matching chunks

### Key Classes (core package `cn.nexon.zerovector.core`)

- **`KnowledgeBaseManager`** — top-level orchestrator managing multiple named knowledge bases, each with isolated storage under `{storageBasePath}/{kbName}/`
- **`SemanticTreeManager`** — lifecycle management for a single tree (init, addDocument, navigate, close)
- **`LLMProvider`** (interface, 8 methods) — the only LLM abstraction. All LLM calls go through typed methods: `comprehendChunk`, `generateSummary`, `clusterDocuments`, `extractKeywords`, `extractEntities`, `generateExampleQuestions`, `decideNavigation`, `extractQueryKeywords`. Each returns `LLMResponse` (content + token usage + timing).
- **`CachedLLMProvider`** — decorator wrapping any `LLMProvider` with per-request-type Caffeine caches, configurable via `SmartCacheStrategy.RequestType`
- **`MMapDocumentStore`** — zero-copy storage engine; content is mmap'd directly to virtual memory, never copied to heap
- **`ShardedTreeStorage`** — splits large trees into shards (default 100 nodes/shard), loads on demand via Caffeine cache

### Storage SPI

Three SPI interfaces in `cn.nexon.zerovector.core.storage.spi`:
- `ChunkStorage` — chunk content storage (default: `LocalFileChunkStorage` via mmap)
- `DictionaryStorage` — keyword dictionary storage (default: `LocalFileDictionaryStorage`)
- `DocumentCopyStorage` — original document file storage (default: `LocalFileDocumentCopyStorage`)

Custom implementations are discovered via `ServiceLoader` (`META-INF/services/`). Annotate implementations with `@StorageProvider(type="...", priority=N)`.

### Hook System

`LifecycleHook` interface with 13 hook points (`HookType` enum): `PRE_INITIALIZE`, `POST_INITIALIZE`, `PRE_ADD_DOCUMENT`, `POST_ADD_DOCUMENT`, `PRE_COMPREHEND_CHUNK`, `POST_COMPREHEND_CHUNK`, `PRE_BUILD_TREE`, `POST_BUILD_TREE`, `PRE_NAVIGATE`, `POST_NAVIGATE`, `PRE_CLOSE`, `POST_CLOSE`, `ON_ERROR`. Hooks are registered via `HookRegistry` and executed by `DefaultHookExecutor`. Configure hook class names via `zerovector.hook.hooks` in YAML.

### Spring Boot Integration

`ZeroVectorAutoConfiguration` is conditional on `zerovector.enabled=true` (default). It auto-detects Spring AI (`ChatModel`) or LangChain4j (`ChatLanguageModel`) on the classpath and creates the corresponding `LLMProvider` bean, wrapped in `CachedLLMProvider`. Spring entry point for applications is `SemanticHub` — inject it via `@Autowired`.

### Configuration Properties

All under `zerovector.*` prefix. Key properties:
- `zerovector.storage-base-path` (default: `./data/knowledge_bases`)
- `zerovector.use-sharded-storage` (default: true)
- `zerovector.shard-size` (10-10000, default: 100)
- `zerovector.cache.*` — per-LLM-operation-type Caffeine cache configs
- `zerovector.hook.enabled` / `zerovector.hook.hooks`
- `zerovector.concurrency.max-concurrent-requests` / `requests-per-second`

### Commit Style

Chinese short keyword messages (2-8 characters), no conventional commit format.

### Runtime Data Layout

```
{storageBasePath}/{kbName}/
├── zerovector_storage.data          # mmap chunk data file
├── zerovector_storage.data.index    # mmap index file
├── zerovector_storage.tree          # semantic tree JSON
├── zerovector_storage.dict          # keyword dictionary
├── zerovector_storage_docs/         # original document copies
└── zerovector_storage_shards/       # shard files (when use-sharded-storage=true)
```
