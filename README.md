# ZeroVector

[![CI](https://github.com/nexonlab/zerovector/actions/workflows/ci.yml/badge.svg)](https://github.com/nexonlab/zerovector/actions/workflows/ci.yml)

**Zero-Copy, Zero-Distortion, Zero-Embedding Knowledge Base.**

ZeroVector 是一款基于 Java 25 构建的新一代知识库系统，拒绝传统的**向量切片**方案，回归文档的**原生逻辑结构**（章节树）。利用 **Memory Mapped Files (mmap)** 与**虚拟线程**实现高性能的内容读取与并发处理。

## 核心特性

- 🚀 **Zero-Copy IO** — 内联正文基于 mmap，大文件流式解析并按源区间读取，命中多少读多少。
- 📚 **Chapter-Aware** — 小文档整篇保留，大文档按章/节检索，而不是碎切片段。
- 🧠 **Long-Context Native** — 章节摘要用于导航，原文用于回答，按需适配长窗口模型。
- 📉 **Cost Efficient** — 无需 GPU 向量训练，无需向量数据库。
- 🔍 **Hybrid Navigation** — 关键词倒排索引 + LLM 语义推理。
- 📦 **Sharded Storage** — 分片存储，按需加载。
- 🔌 **SPI 扩展** — 存储层支持自定义实现（mmap / Elasticsearch / MinIO）。

## 项目结构

```
ZeroVector/
├── zerovector-core/                    # 纯 Java 核心：语义树、流式切片、导航、存储、缓存
├── zerovector-spring-boot-starter/     # Spring Boot 4 自动配置 + SemanticHub
├── zerovector-spring-boot-example/     # 可运行示例：REST API + 上传/检索/问答
└── data/                               # 运行时数据目录（由 .gitignore 排除）
```

## 快速开始

### 环境要求

- JDK 25+
- 推荐使用项目内置的 Maven Wrapper（`./mvnw`），无需自行安装 Maven

### 编译与测试

```bash
./mvnw clean compile     # 编译
./mvnw test              # 运行测试（190 core cases + 1 context test）
```

### 启动示例

```bash
# 首次运行或修改 core/starter 后必须先安装到本地仓库，
# 否则 example 会从本地仓库加载旧版 SNAPSHOT jar 导致启动失败
./mvnw install -DskipTests

cd zerovector-spring-boot-example
../mvnw spring-boot:run
```

访问 http://localhost:8080。

### 配置环境变量

```bash
export AI_OPENAI_API_KEY=sk-your-key
export AI_OPENAI_BASE_URL=https://api.openai.com   # 可选，兼容三方 API
```

## 架构概览

```text
上传：文件 → 流式解析 → 章节理解 → 语义树
                     ↘ COPY 副本 / REFERENCE 原文件
                     ↘ 内联正文 → mmap

查询：关键词定位 → LLM 树导航（只读摘要）→ 命中章节

回答：按源文件区间 / mmap 内联内容读取原文
```

| 组件 | 职责 |
|------|------|
| `StreamingDocumentChunker` | 流式解析文件，边读边记录章节区间，不把整篇文档塞进内存 |
| `DocumentSourceStore` | 托管 UTF-8 副本或引用原文件，回答时按字节区间读取章节 |
| `DocumentComprehender` | 调用 LLM 提取摘要、关键词、实体、示例问题 |
| `TreeBuilder` | 将文档/章节组织成可导航的语义树，支持增量更新 |
| `HybridNavigator` | 关键词倒排 + LLM 树导航 + 兜底检索，导航阶段不加载正文 |
| `KeywordDictionary` | 关键词 → 节点倒排索引，专治专有名词和精确匹配 |
| `MMapDocumentStore` | 内联正文的 mmap 存储，支持索引持久化与压缩 |
| `ShardedTreeStorage` | 语义树分片存储，按代际提交，Caffeine 懒加载 |
| `CachedLLMProvider` | 按请求类型分桶缓存，只缓存成功响应 |

## Spring Boot 集成

### 添加依赖

```xml
<dependency>
    <groupId>cn.nexon.zerovector</groupId>
    <artifactId>zerovector-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 配置

```yaml
zerovector:
  enabled: true
  storage-base-path: ./data/knowledge_bases
  shard-size: 100

  model:
    heavy: gpt-4o-mini    # 文档理解、聚类、摘要
    light: gpt-4o-mini    # 关键词、实体、示例问题
    fast: gpt-4o-mini     # 导航、查询抽词、最终问答

  concurrency:
    max-concurrent-requests: 2
    max-navigation-steps: 20

  llm-context:
    max-chunk-chars: 4000                  # 单次 LLM 调用的最大字符数
    chapter-split-threshold-chars: 100000  # 超过该字符数才按章节切片

  rate-limit:
    enabled: true
    max-requests: 5
    window-seconds: 60

  cache:
    enabled: true
    decide-navigation:
      max-size: 5000
      expire-after-access: 1
      time-unit: HOURS

  hook:
    enabled: true
    hooks:
      - cn.nexon.zerovector.core.hook.LoggingLifecycleHook
```

LLM 提供者会按 classpath 自动选择 Spring AI 或 LangChain4j；若同时存在多个候选，请标记 `@Primary` 或提供唯一的 `LLMProvider` Bean。

### 代码示例

```java
@Service
public class YourService {

    private final SemanticHub hub;

    public YourService(SemanticHub hub) {
        this.hub = hub;
    }

    public DocumentUploadResult add(Path file) {
        return hub.addDocument(file);
    }

    public String ask(String query) {
        SearchResult result = hub.search(query);
        return result.toDocumentText();
    }
}
```

### 多知识库

```java
// 创建知识库（返回 SemanticTreeManager）
SemanticTreeManager kb = hub.createKnowledgeBase("tech-docs");

// 添加文档
hub.addDocument(Paths.get("doc.md"), "tech-docs");

// 查询
SearchResult result = hub.search("什么是单例模式？", "tech-docs");

// 切换知识库
hub.switchKnowledgeBase("product-docs");

// 列出所有知识库
List<String> names = hub.listKnowledgeBases();

// 删除知识库
hub.deleteKnowledgeBase("old-docs");
```

## 无 Spring 框架使用

```java
LLMProvider llm = new YourLLMProvider();
DocumentComprehender comprehender = new DocumentComprehender(llm, 4000);
SemanticTreeManager manager = new SemanticTreeManager(
    llm, comprehender, Paths.get("./data/knowledge_bases/default"),
    true, 100, new ConcurrencyProperties(), new DefaultHookExecutor());
// 构造时已完成存储初始化与已有树加载，无需 initialize()

// 默认 COPY + AUTO：小文档整篇，超过 100k 字符才按章节切片
manager.addDocument(Paths.get("document.md"));

// 可选 REFERENCE：只保存原文件路径，不复制；必须保证原文件长期存在
manager.addDocument(Paths.get("document.md"), SourceMode.REFERENCE);

// 可显式覆盖：WHOLE 强制整篇，CHAPTER 强制章节
manager.addDocument(Paths.get("document.md"), SourceMode.COPY, ChunkingPolicy.CHAPTER);

NavigationResult result = manager.navigate("如何配置集群？");
// result.documents() 只有文档/章节摘要；需要回答时才读原文
String text = manager.readChunkContent(result.documents().get(0));
manager.close();
```

## 存储层 SPI 扩展

两个接口，通过 `ServiceLoader` 发现：

| 接口 | 存储内容 | 默认实现 |
|------|---------|---------|
| `ChunkStorage` | 接口直传小文档的内联正文（大文件原文走 `DocumentSourceStore`） | `LocalFileChunkStorage`（mmap） |
| `DictionaryStorage` | 关键词字典整体快照 | `LocalFileDictionaryStorage`（JSON） |

想接自己的存储？实现接口即可：

```java
@StorageProvider(type = "elasticsearch", priority = 10, description = "ES 正文存储")
public class EsChunkStorage implements ChunkStorage {
    @Override
    public void initialize(ChunkStorageConfig config) {
        // 从 config.getExtended() 读取 ES 配置
    }

    @Override
    public String getStorageType() {
        return "elasticsearch";
    }
    // 实现其他方法...
}
```

在 `META-INF/services/cn.nexon.zerovector.core.storage.spi.ChunkStorage` 中登记实现类。

> `DictionaryStorage` 只负责整体 `load()` / `save(dictionary)`；字典的增删改由内存中的 `KeywordDictionary` 完成，避免 SPI 层出现两套重复语义。

## 使用场景

- 📖 **技术文档检索** — 快速定位 API 参考、故障排查指南
- 🏢 **企业知识库** — 规章制度、SOP、培训材料
- 📚 **学术研究** — 论文级长文档语义检索
- 💼 **法律文档** — 条款、判例检索

## 贡献

1. Fork 本项目
2. 创建分支 (`git checkout -b feature/xxx`)
3. 提交修改
4. 确保 `./mvnw test` 通过
5. 提交 Pull Request

详见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 许可证

Apache License 2.0。详见 [LICENSE](LICENSE)。

---

**ZeroVector** — 让知识检索回归本质。
