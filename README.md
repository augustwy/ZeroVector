# ZeroVector

**Zero-Copy, Zero-Distortion, Zero-Embedding Knowledge Base.**

ZeroVector 是一款基于 Java 25 构建的新一代知识库系统。我们拒绝了传统的**向量切片**方案，回归到文档的**原生逻辑结构**（章节树）。

利用 Java 的 **Memory Mapped Files (mmap)** 和 **Structured Concurrency**，ZeroVector 实现了近乎零拷贝的高性能文件读取，让大模型能够一次性“吃下”整本书，从而提供更精准、上下文更连贯的问答体验。

## Features
*   🚀 **Zero-Copy IO**: 基于 mmap 的毫秒级大文件读取。
*   📚 **Chapter-Aware**: 智能解析文档结构，以“章/节”为检索单元，而非碎切片段。
*   🧠 **Long-Context Native**: 完美适配 128k~2M Token 的长窗口大模型。
*   📉 **Cost Efficient**: 无需 GPU 进行向量训练，无需维护向量数据库，大幅降低架构复杂度。