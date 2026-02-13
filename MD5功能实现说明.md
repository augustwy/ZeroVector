# MD5功能实现说明

## 概述

为了优化文档存储和避免重复处理相同内容的文档，我们在ZeroVector项目中实现了MD5校验功能。该功能包括：

1. 为每个文档计算MD5哈希值
2. 在添加新文档时检查是否已存在相同MD5的文档
3. 对于基于文件路径的文档块，不再存储文档内容，节省存储空间
4. 使用MD5值作为文档ID的一部分，确保相同内容的文档有相同的ID

## 问题解决

### 1. 重复文档检测问题

**问题**：两个相同的文件，重复导入时，MD5查找没找到

**解决方案**：
- 在`SemanticTreeService`中实现了完整的MD5检查逻辑
- 在添加文档前计算MD5值，并与现有文档块的MD5值进行比较
- 如果找到相同MD5的文档，跳过处理并记录日志
- **修复**：在`addDocument`和`addDocumentAsync`方法中添加了语义树加载逻辑，确保在检查MD5时语义树已正确加载

### 2. 文档ID生成问题

**问题**：chunk不要用文件名称做ID，可以用MD5值

**解决方案**：
- 修改了`MarkdownDocumentProcessor`和`TextDocumentProcessor`的`generateDocumentId`方法
- 新增`md5`参数，使用MD5值作为ID的一部分
- 对于有MD5值的文档，使用"doc_" + MD5前8位作为ID
- 确保相同内容的文档有相同的ID，便于识别和管理

### 3. 存储优化问题

**问题**：chunk文件中还是存在原文内容

**解决方案**：
- 修改了`DocumentChunk`类，添加了`@JsonInclude(JsonInclude.Include.NON_NULL)`注解，确保null值字段不被序列化
- 在构造函数中添加了逻辑，确保基于文件路径的文档块的content字段为null
- 修改了`TreeBuilder`类，对于基于文件路径的文档块，不再将内容存储到MMapDocumentStore中
- 在`SemanticTreeService`中，确保在创建基于文件路径的DocumentChunk时正确传递MD5值

### 4. 第二次加载问题

**问题**：第二次加载文件时semanticTree.chunks()为空

**解决方案**：
- 在`addDocument`和`addDocumentAsync`方法中添加了语义树加载逻辑
- 如果semanticTree为null，尝试从文件加载
- 如果加载失败，创建一个空的语义树

### 5. 基于文件路径的文档块内容读取问题

**问题**：DocumentChunk不存在文件中，但是提供给大模型的时候，应该根据路径使用mmap去读取文件中的内容，再传给大模型

**解决方案**：
- 修改了`HybridNavigator`类的`loadChunks`方法，使其在访问基于文件路径的DocumentChunk时，使用`MMapDocumentStore.getChunkContent`方法读取文件内容
- 修改了`Navigator`类的所有返回DocumentChunk的方法，使其也能处理基于文件路径的DocumentChunk
- 在读取文件内容后，创建一个新的DocumentChunk对象，包含从文件读取的实际内容，然后传递给大模型
- 添加了异常处理，如果读取失败，返回原始块

## 实现细节

### 1. DocumentChunk模型更新

在`DocumentChunk`记录中添加了`md5`字段：

```java
public record DocumentChunk(
        String id,
        String content,
        String summary,
        String filePath,  // 文件路径字段
        String md5,      // 新增MD5字段
        Map<String, Object> metadata
)
```

### 2. MD5计算工具类

创建了`MD5Util`工具类，提供以下方法：

- `calculateMD5(Path filePath)` - 计算文件的MD5值
- `calculateMD5(InputStream inputStream)` - 计算输入流的MD5值
- `calculateMD5(String content)` - 计算字符串内容的MD5值

### 3. 文档处理器更新

更新了`MarkdownDocumentProcessor`和`TextDocumentProcessor`，在处理文档时计算并存储MD5值。

### 4. 重复文档检测

在`SemanticTreeService`中实现了重复文档检测逻辑：

- 在添加文档前计算MD5值
- 检查现有文档块中是否已存在相同MD5的文档
- 如果存在，跳过处理，避免重复

### 5. 存储优化

修改了`TreeBuilder`类，对于基于文件路径的文档块，不再将内容存储到MMapDocumentStore中，节省存储空间。

## 使用示例

### 基本MD5计算

```java
// 计算文件MD5
Path file = Paths.get("example.txt");
String md5 = MD5Util.calculateMD5(file);

// 计算字符串MD5
String content = "示例内容";
String md5 = MD5Util.calculateMD5(content);
```

### 文档处理与重复检测

```java
// 创建语义树服务
SemanticTreeService service = new SemanticTreeService(llmService, storagePath, false, concurrencyProps);
service.initialize();

// 添加文档（自动检测重复）
service.addDocument(Paths.get("document1.md"));
service.addDocument(Paths.get("document2.md")); // 如果与document1内容相同，将被跳过
```

## 优势

1. **避免重复处理**：相同内容的文档不会被重复处理，节省计算资源
2. **节省存储空间**：基于文件路径的文档块不存储内容，大幅减少存储需求
3. **提高性能**：减少不必要的文档处理和存储操作
4. **数据一致性**：确保相同内容的文档在系统中只存在一份

## 注意事项

1. MD5虽然能高效检测重复，但存在极小的碰撞概率
2. 对于非常大的文件，MD5计算可能需要一些时间
3. 基于文件路径的文档块在访问时需要从文件系统读取内容，可能比直接从内存读取稍慢

## 测试

项目包含了完整的测试用例，验证MD5功能的正确性：

- `MD5Test` - 基本MD5计算功能测试
- `MD5DocumentExample` - 文档处理和重复检测示例

可以通过以下命令运行测试：

```bash
mvn test -Dtest=MD5Test -pl zerovector-core
```

## 未来改进

1. 考虑支持其他哈希算法（如SHA-256）
2. 实现增量MD5计算，提高大文件处理效率
3. 添加MD5缓存机制，避免重复计算相同文件的MD5值