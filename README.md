# Dataset Schema Registry

数据集 schema 版本注册与兼容性校验服务。元数据与历史版本保存在本地文件存储中，
为不同来源的 schema 在注册、演进和回查时提供稳定、可解释的结论。

## 功能概览

- 一个数据集可注册多个 schema 版本，支持按指定版本与最新版查询；
- 注册时进行兼容性判定，破坏性变更被明确拒绝并指出冲突位置；
- 显式回滚：以历史版本的内容在演进链尾部产生新版本；
- 并发安全：同一数据集的并发注册被串行化，不丢版本、不写一半。

## 版本语义

- **版本号**：由客户端指定，数据集内唯一，只要求唯一、不要求连续或单调。
- **父版本**：每个版本记录 `parentVersion`，首版本为 `null`。注册时不指定父版本则
  默认以当前**链头**为父版本；显式指定的父版本必须存在且必须是当前链头。
- **最新版（链头）**：演进链中不被任何其他版本当作父版本的版本。由于链是严格线性的，
  链头唯一。注意链头不一定是版本号最大的版本。
- **内容指纹**：字段按名称排序后拼接规范化串取 SHA-256，与字段顺序无关，
  用于识别内容完全相同的重复提交。
- **回滚**：`rollback(targetVersion, newVersion)` 以目标版本的内容产生一个新版本
  （`parentVersion` 为当前链头，`rollbackOf` 记录目标版本号）。回滚是显式操作，
  不受"内容相同拒绝"规则限制。

### 注册拒绝场景（错误码可互相区分）

| 场景 | 错误码 | HTTP |
| --- | --- | --- |
| 版本号已存在 | `DUPLICATE_VERSION` | 409 |
| 内容与已有版本完全相同 | `IDENTICAL_CONTENT` | 409 |
| 指定的父版本不存在 | `UNKNOWN_PARENT_VERSION` | 409 |
| 父版本不是当前链头（链路断裂） | `BROKEN_CHAIN` | 409 |
| 相对父版本不兼容 | `INCOMPATIBLE_SCHEMA` | 409 |
| 无效回滚（目标不存在 / 目标即最新 / 空数据集） | `INVALID_ROLLBACK` | 409 |
| 数据集不存在 | `DATASET_NOT_FOUND` | 404 |
| 版本不存在 | `VERSION_NOT_FOUND` | 404 |
| 请求非法（字段为空、字段名重复等） | `INVALID_REQUEST` | 400 |

所有校验失败都不会落盘，已有版本不会被静默覆盖，也不会出现部分生效。

## 兼容性规则

判定只依赖新旧两个字段列表，不依赖外部状态，结论确定且可复现。
相对父版本：

| 变更 | 结论 | 冲突类型 |
| --- | --- | --- |
| 删除字段 | 不兼容 | `FIELD_REMOVED` |
| 类型变化（非加宽） | 不兼容 | `TYPE_CHANGED` |
| 类型加宽（INT→LONG/DOUBLE、LONG→DOUBLE、FLOAT→DOUBLE） | 兼容 | — |
| 可空 → 非空（收窄） | 不兼容 | `NULLABILITY_NARROWED` |
| 非空 → 可空（放宽） | 兼容 | — |
| 新增非空且无默认值字段 | 不兼容 | `REQUIRED_FIELD_ADDED` |
| 新增可空字段或带默认值的字段 | 兼容 | — |

不兼容时错误信息会列出每一处冲突的字段位置与原因。

## 并发与一致性

- 每个数据集一把锁，注册的"读取-校验-写入"全程在锁内完成，并发提交被串行化，
  不会出现版本号冲突、丢版本；
- 写盘采用"临时文件 + 原子移动"，不会留下写一半的文件；
- 校验失败抛异常且完全不落盘，链路断裂的变更与无效回滚不会部分生效。

## 本地存储

默认存储目录为 `./data/schema-registry`（每个数据集一个 JSON 文件），
可通过配置覆盖：

```properties
schema.registry.dir=/path/to/storage
```

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/datasets/{dataset}/schemas` | 注册新版本，body: `{"version":1,"parentVersion":null,"fields":[{"name":"id","type":"LONG","nullable":false,"defaultValue":null}]}` |
| POST | `/api/datasets/{dataset}/schemas/rollback` | 回滚，body: `{"targetVersion":1,"newVersion":3}` |
| GET | `/api/datasets/{dataset}/schemas` | 列出全部版本 |
| GET | `/api/datasets/{dataset}/schemas/latest` | 查询最新版（链头） |
| GET | `/api/datasets/{dataset}/schemas/{version}` | 按版本号查询 |

失败响应形如 `{"code":"INCOMPATIBLE_SCHEMA","message":"..."}`。

## 本地验证

```bash
# 运行全部测试（重复注册、不兼容变更、并发提交、回滚等场景）
mvn test

# 启动服务后手工验证
mvn spring-boot:run
curl -X POST localhost:8080/api/datasets/orders/schemas \
  -H 'Content-Type: application/json' \
  -d '{"version":1,"fields":[{"name":"id","type":"LONG","nullable":false,"defaultValue":null}]}'
curl localhost:8080/api/datasets/orders/schemas/latest
```

测试日志会打印每个场景的输入、判定依据与最终结论，可直接从 surefire 输出中查看。
