# ZeroVector

[![CI](https://github.com/nexonlab/zerovector/actions/workflows/ci.yml/badge.svg)](https://github.com/nexonlab/zerovector/actions/workflows/ci.yml)

**Zero-Copy, Zero-Distortion, Zero-Embedding Knowledge Base.**

ZeroVector 是一款基于 Java 25 构建的新一代知识库系统，拒绝传统的**向量切片**方案，回归文档的**原生逻辑结构**（章节树）。利用 **Memory Mapped Files (mmap)** 和 **Structured Concurrency** 实现近乎零拷贝的高性能文件读取。

## 核心特性

- 🚀 **Zero-Copy IO** — 基于 mmap 的毫秒级大文件读取
- 📚 **Chapter-Aware** — 以"章/节"为检索单位，而非碎切片段
- 🧠 **Long-Context Native** — 适配 128k~2M Token 长窗口大模型
- 📉 **Cost Efficient** — 无需 GPU 向量训练，无需向量数据库
- 🔍 **Hybrid Navigation** — 关键词倒排索引 + LLM 语义推理
- 📦 **Sharded Storage** — 分片存储，按需加载
- 🔌 **SPI 扩展** — 存储层支持自定义实现（mmap / Elasticsearch / MinIO）

## 项目结构

```
ZeroVector/
├── zerovector-core/                    # 核心模块（纯 Java，无框架依赖）
├── zerovector-app/                     # 独立应用脚手架（预留）
├── zerovector-spring-boot-starter/     # Spring Boot 4 自动配置
├── zerovector-spring-boot-example/     # 可运行示例（REST API + Web UI）
└── data/                               # 运行时数据目录（由 .gitignore 排除）
```

## 快速开始

### 环境要求

- JDK 25+
- 推荐使用项目内置的 Maven Wrapper（`./mvnw`），无需自行安装 Maven

### 编译与测试

```bash
./mvnw clean compile     # 编译
./mvnw test              # 运行测试（173 cases）
```

### 启动示例

```bash
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

```
文档 → DocumentComprehender（LLM 理解）→ TreeBuilder（语义树构建）
                                            ↓
查询 → 关键词定位 → HybridNavigator（LLM 导航）→ mmap 内容读取 → 结果
```

| 组件 | 职责 |
|------|------|
| `DocumentComprehender` | 调用 LLM 提取摘要、关键词、实体、示例问题 |
| `TreeBuilder` | 将文档按主题聚类为 ROOT → CATEGORY → LEAF 三层语义树 |
| `HybridNavigator` | Phase 1 关键词倒排 + Phase 2 LLM 导航决策 |
| `KeywordDictionary` | 关键词 → 节点倒排索引，解决专有名词识别 |
| `MMapDocumentStore` | mmap 零拷贝存储，支持索引持久化与压缩 |
| `ShardedTreeStorage` | 语义树分片存储，Caffeine 懒加载 |
| `CachedLLMProvider` | 按 RequestType 分类缓存，Levenshtein 相似去重 |

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
  use-sharded-storage: true
  shard-size: 100

  model:
    heavy: gpt-4o-mini    # 离线复杂任务（理解、聚类、摘要）
    light: gpt-4o-mini    # 离线简单任务（关键词、实体提取）
    fast: gpt-4o-mini     # 在线快速任务（导航决策）

  concurrency:
    max-concurrent-requests: 5
    requests-per-second: 2.0

  cache:
    enabled: true
    comprehend-chunk:
      max-size: 5000
      expire-after-access: 4
      time-unit: HOURS
    decide-navigation:
      max-size: 5000
      expire-after-access: 1
      time-unit: HOURS

  hook:
    enabled: true
    hooks:
      - cn.nexon.zerovector.core.hook.LoggingLifecycleHook
```

### 代码示例

```java
@Service
public class YourService {

    @Autowired
    private SemanticHub semanticHub;

    public void addDocument(Path filePath) {
        semanticHub.addDocument(filePath);
    }

    public SearchResult search(String query) {
        return semanticHub.search(query);
    }
}
```

### 多知识库

```java
// 创建知识库（返回 SemanticTreeManager）
SemanticTreeManager kb = semanticHub.createKnowledgeBase("tech-docs");

// 添加文档
semanticHub.addDocument(Paths.get("doc.md"), "tech-docs");

// 查询
SearchResult result = semanticHub.search("什么是单例模式？", "tech-docs");

// 切换知识库
semanticHub.switchKnowledgeBase("product-docs");

// 列出所有知识库
List<String> names = semanticHub.listKnowledgeBases();

// 删除知识库
semanticHub.deleteKnowledgeBase("old-docs");
```

## 无 Spring 框架使用

```java
LLMProvider llm = new YourLLMProvider();
DocumentComprehender comprehender = new DocumentComprehender(llm, 4000);
SemanticTreeManager manager = new SemanticTreeManager(
    llm, comprehender, Paths.get("./data/knowledge_bases/default"),
    true, new ConcurrencyProperties());
// 注意：构造时已自动初始化本地存储，无需额外调用 initialize()

// 上传文档
manager.addDocument(Paths.get("document.md"));

// 查询
NavigationResult result = manager.navigate("什么是单例模式？");

manager.close();
```

## 存储层 SPI 扩展

三类接口，通过 `ServiceLoader` 发现：

| 接口 | 存储内容 | 默认实现 |
|------|---------|---------|
| `ChunkStorage` | 文档分片 | `LocalFileChunkStorage`（mmap） |
| `DictionaryStorage` | 关键词字典 | `LocalFileDictionaryStorage`（JSON） |
| `DocumentCopyStorage` | 文件副本 | `LocalFileDocumentCopyStorage` |

### 自定义实现

```java
@StorageProvider(type = "elasticsearch", priority = 10, description = "ES 存储")
public class ESChunkStorage implements ChunkStorage {
    @Override
    public void initialize(ChunkStorageConfig config) throws StorageException {
        // 从 config.getExtended() 读取 ES 配置
    }

    @Override
    public String getStorageType() {
        return "elasticsearch";
    }
    // 实现其他方法...
}
```

在 `META-INF/services/cn.nexon.zerovector.core.storage.spi.ChunkStorage` 中注册：

```
com.example.ESChunkStorage
```

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
