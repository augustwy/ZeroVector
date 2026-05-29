# Contributing to ZeroVector

## 开发环境

- JDK 25+
- Maven 3.9+（推荐使用项目内置的 `mvnw`）
- 推荐 IDE：IntelliJ IDEA

## 构建与测试

```bash
# 完整编译
./mvnw clean compile

# 运行所有测试
./mvnw test

# 运行指定测试
./mvnw test -Dtest=MMapDocumentStoreTest
```

## 提交规范

- 提交信息使用中文短关键词（2-8 字），如 `接口完善`、`代码简化`
- 一个提交只做一件事

## 提交流程

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/your-feature`)
3. 提交你的更改
4. 确保测试通过 (`./mvnw test`)
5. 推送到分支 (`git push origin feature/your-feature`)
6. 提交 Pull Request

## 代码风格

- 包路径统一在 `cn.nexon.zerovector.core.*` 下
- 存储层扩展通过 `storage.spi` 包下的接口实现，在 `META-INF/services/` 注册
- 所有公共 API 应有 Javadoc

## 问题反馈

- 使用 GitHub Issues 报告 bug 或提出功能建议
