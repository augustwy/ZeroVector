 ZeroVector Java 25 实现

基于Java 25特性的高性能语义知识库引擎实现。

## 核心特性

### 1. 领域模型

使用Java 25的Records和Sealed Interfaces实现不可变数据载体和行为定义：

- `NodeType` - 语义树节点类型枚举
- `TreeNode` - 语义树节点记录
- `DocumentChunk` - 文档块记录
- `NavigationAction` - 密封接口定义导航行为
- `NavigationPath` - 导航路径记录
- `SemanticTree` - 语义树记录

### 2. 高性能存储引擎

基于内存映射文件(mmap)的高性能存储方案：

- `MMapDocumentStore` - 内存映射文档存储引擎
- 利用操作系统虚拟内存管理，按需加载
- 支持远超内存限制的数据量，保持低GC开销
- 零拷贝读取，极高性能
- **索引持久化** - 支持文档块索引的保存和加载，避免重复构建

### 3. 语义树构建器

利用虚拟线程进行并发处理：

- `TreeBuilder` - 语义树构建器
- 使用Java 21+的虚拟线程(`Executors.newVirtualThreadPerTaskExecutor()`)
- 并发处理文档摘要生成
- 递归构建树结构

### 4. 语义导航器

基于用户查询在语义树中进行导航：

- `Navigator` - 语义导航器
- 状态机模式维护当前节点状态
- 记录导航路径和推理过程
- 支持模式匹配处理导航动作

### 5. LLM服务集成

设计LLM服务接口，支持多种实现：

- `LLMService` - LLM服务接口
- 支持文档摘要生成
- 支持文档聚类
- 支持导航决策
- 提供Mock实现用于演示
- **语义树持久化** - 支持语义树的序列化和反序列化，下次启动时直接加载

## 项目结构

```
zerovector/
├── zerovector-core/           # 核心模块
│   ├── src/main/java/cn/nexon/zerovector/core/
│   │   ├── ai/               # LLM服务接口
│   │   │   └── LLMService.java
│   │   ├── model/            # 领域模型
│   │   │   ├── DocumentChunk.java
│   │   │   ├── NavigationAction.java
│   │   │   ├── NavigationPath.java
│   │   │   ├── NodeType.java
│   │   │   ├── SemanticTree.java
│   │   │   └── TreeNode.java
│   │   ├── service/          # 服务层
│   │   │   └── SemanticTreeService.java
│   │   ├── storage/          # 存储引擎
│   │   │   └── MMapDocumentStore.java
│   │   └── tree/             # 语义树构建和导航
│   │       ├── Navigator.java
│   │       └── TreeBuilder.java
├── zerovector-app/           # 应用模块
└── zerovector-example/       # 示例模块
    └── src/main/java/cn/nexon/zerovector/example/
        ├── ai/               # Mock LLM实现
        │   └── MockLLMService.java
        ├── SemanticTreeExample.java  # 交互式示例
        └── ZeroVectorDemo.java      # 演示程序
```

## 运行示例

### 编译项目

```bash
mvn clean compile
```

### 运行演示程序

```bash
cd zerovector-example
java -cp "target/classes;../zerovector-core/target/classes" cn.nexon.zerovector.example.ZeroVectorDemo
```

### 运行交互式示例

```bash
cd zerovector-example
java -cp "target/classes;../zerovector-core/target/classes" cn.nexon.zerovector.example.SemanticTreeExample
```

## 设计亮点

1. **Java 25特性应用**：
   - Records实现不可变数据载体
   - Sealed Interfaces定义行为
   - Pattern Matching简化导航逻辑
   - Virtual Threads提高并发性能

2. **高性能存储**：
   - mmap实现零拷贝读取
   - 按需加载，支持大数据量
   - 低GC开销

3. **模块化设计**：
   - 清晰的分层架构
   - 接口与实现分离
   - 易于扩展和测试

4. **LLM集成**：
   - 预留LLM服务接口
   - 支持多种实现
   - 便于后续扩展

## 后续扩展方向

1. **LLM实现**：
   - 集成LangChain4j
   - 支持OpenAI、Claude等模型
   - 实现结构化输出

2. **存储优化**：
   - 完善索引持久化
   - 支持增量更新
   - 优化内存使用

3. **功能增强**：
   - 添加更多导航策略
   - 支持多模态数据
   - 实现分布式存储

## 总结

本实现基于工程实现大纲，成功构建了一个高性能、可扩展的Java语义知识库引擎框架。通过使用Java 25的最新特性，实现了简洁、高效的代码结构，为后续功能扩展奠定了坚实基础。

## 代码清理

项目已完成代码清理，移除了以下不再使用的旧实现：

1. **pipeline包** - 基于旧管道模式的实现，已被语义树替代
2. **index包** - 旧的索引实现，已被语义树替代
3. **repository包** - 旧的元数据仓库实现
4. **storage包中的旧实现** - ChunkLoader, MMapFileReader, TextCacheManager等
5. **model包中的旧模型类** - ChunkingInput, TextChunk等
6. **ZeroVectorApp.java** - 旧的应用入口

当前项目结构更加清晰，只保留了语义树实现所需的核心组件。