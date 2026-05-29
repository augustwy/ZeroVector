# ZeroVector — REASONIX.md

## Stack

- **Java 25** with Maven 3.9+ (multi-module, parent POM at root)
- **Jackson 2.15.2** — JSON/YAML serialization for storage & config
- **Caffeine 3.1.8** — per-LLM-operation-type caches (comprehendChunk, generateSummary, etc.)
- **Spring Boot 4.0** — auto-configuration in `zerovector-spring-boot-starter`; optional integration with Spring AI / LangChain4j
- **JUnit Jupiter 5.10** — test framework (declared dep; no test source files currently exist)

## Layout

```
zerovector-core/           — All business logic (plain Java, no Spring)
zerovector-app/            — Empty scaffold for standalone app (no source files)
zerovector-spring-boot-starter/  — Spring Boot 4 auto-configuration + @Component facade
zerovector-spring-boot-example/  — Runnable demo app (REST API + Thymeleaf UI, port 8081)
```

## Commands

| Action | Command |
|--------|---------|
| Build | `mvn clean compile` |
| Test  | `mvn test` |
| Run example app | `cd zerovector-spring-boot-example && mvn spring-boot:run` (port 8081) |
| Single test class | `mvn test -Dtest=MMapDocumentStoreTest` |

## Conventions

- **Package root**: `cn.nexon.zerovector.core.*` for all core code
- **Commit messages**: Chinese short keywords (2–8 characters), no conventional-commit format (visible in `git log` — e.g. `接口完善`, `模型槽拆分`, `代码简化`)
- **SPI storage**: 3 interfaces (`ChunkStorage`, `DictionaryStorage`, `DocumentCopyStorage`) in `storage.spi` package, discovered via `ServiceLoader` (`META-INF/services/`). Default implementations in `storage.local` package, annotated with `@StorageProvider`
- **Package structure by concern**: `ai/` (LLM abstraction + caching), `storage/` (mmap + SPI), `navigator/`, `tree/`, `index/`, `document/comprehend/`, `hook/`, `model/`, `config/`, `exception/`, `util/`

## Watch out for

- **`zerovector-app/` is an empty scaffold** — has a `pom.xml` but no source files. Don't expect it to build anything useful.
- **Test sources are missing** — `zerovector-core/src/test/` directory exists but is empty, even though CLAUDE.md references `MMapDocumentStoreTest`. Run `mvn test` before editing tests to confirm current state.
- **Runtime data lives in `./data/knowledge_bases/`** — mmap binary files (`.data`, `.index`), semantic tree (`.tree`), keyword dict (`.dict`), and shards are generated at runtime. Listed in `.gitignore`; don't commit or edit by hand.
- **No lint/format checker configured** — no checkstyle, spotbugs, pmd, or editorconfig found. Style consistency is manual.
