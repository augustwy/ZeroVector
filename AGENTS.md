# AGENTS.md

Companion to `CLAUDE.md` — read that file first. This file adds corrections and hard-won context an agent would otherwise miss.

## Corrections to CLAUDE.md

- **LLMProvider has a single method `chat(String prompt, RequestType type)`**, not 8 typed methods. The 8 `RequestType` enum values are `COMPREHEND_CHUNK`, `GENERATE_SUMMARY`, `CLUSTER_DOCUMENTS`, `EXTRACT_KEYWORDS`, `EXTRACT_ENTITIES`, `GENERATE_EXAMPLE_QUESTIONS`, `DECIDE_NAVIGATION`, `EXTRACT_QUERY_KEYWORDS`.
- **HookType enum values** are `DOCUMENT_UPLOAD_START/END/ERROR`, `DOCUMENT_COMPREHEND_START/END/ERROR`, `TREE_BUILD_START/END/ERROR`, `TREE_NODE_CREATED`, `NAVIGATION_START/END/ERROR/STEP` — **not** `PRE_INITIALIZE` / `POST_INITIALIZE` / etc.

## Build & Test

```bash
# Build all modules (Java 25, Maven 3.9+)
mvn clean compile

# Run all tests (only zerovector-core has tests)
mvn test

# Run only core module tests (faster)
mvn test -pl zerovector-core

# Run a specific test class
mvn test -Dtest=MMapDocumentStoreTest

# Run a single test method
mvn test -Dtest=MMapDocumentStoreTest#testMethodName

# Run Spring Boot example (port 8081)
mvn -pl zerovector-spring-boot-example spring-boot:run
```

- No lint, format, typecheck, or checkstyle commands exist. Compilation is the only static check.
- No CI/CD pipelines. No Maven wrapper (`.mvn/` is gitignored).
- Requires **JDK 25** specifically. The code uses virtual threads and other Java 21+ features.

## Architecture Notes

- **Single LLM choke point**: Every LLM interaction in the entire system goes through `LLMProvider.chat(prompt, requestType)`. The `CachedLLMProvider` decorator wraps it with per-request-type Caffeine caches.
- **Virtual threads**: `LLMExecutors` creates virtual thread executors configured by `ConcurrencyProperties`. Tree building runs 3 parallel LLM calls per node via virtual threads.
- **Zero-copy mmap**: `MMapDocumentStore` uses `MappedByteBuffer` — content is never copied to Java heap. Always use the store's read API, never read the raw `.data` file.
- **zerovector-app** module is intentionally empty (no source). Scaffold for future standalone app.
- **Runtime data dirs get large quickly** (sharded JSON + mmap binary). Add `data/` and `zerovector_storage/` to `.gitignore` if missing.

## Commit Style

Chinese short keyword messages (2-8 characters), no conventional commit format.

## Configuration Conventions

- Spring Boot entry via `@Autowired SemanticHub`. The auto-configuration is `@ConditionalOnProperty(prefix="zerovector", name="enabled", matchIfMissing=true)` — enabled by default.
- Storage SPI uses Java `ServiceLoader` (`META-INF/services/` files). Custom implementations get `@StorageProvider(type="...", priority=N)`.
