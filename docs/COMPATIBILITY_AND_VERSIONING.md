# 数据集 Schema 版本注册与兼容性校验服务

本服务为数据集提供 **多版本 schema 注册、向后兼容性校验、按版本/最新版回查、head 指针回滚** 能力。
元数据与全部历史版本持久化在本地文件系统，判定规则确定、无随机因素，相同输入永远得到相同结论。

- 技术栈：Java 21（兼容 17 语法）、Spring Boot 3.4、本地文件存储、JUnit 5
- 包根：`com.github.h3yow.datasetschemaregistryjava`

---

## 1. 版本语义

| 概念 | 含义 |
| --- | --- |
| `version` | 版本号，**从 1 开始严格连续递增**（1, 2, 3 …），不允许跳号 |
| `parentVersion` | 父版本号。首个版本无父（`null`）；后续版本的父版本**必须是当前最大版本**，构成一条线性链，不允许分叉 |
| `contentHash` | 版本内容的 SHA-256 指纹，基于规范化 JSON 计算，跨机器/跨时间稳定 |
| `head` | "当前生效版本"指针，独立于最大版本号存在 |

规则要点：

1. **注册不可变**：版本一旦写入，文件即不可变；重复版本号会被拒绝，绝不静默覆盖。
2. **线性演进**：父版本不是当前最新版本时按"链路断裂"拒绝（`INVALID_EVOLUTION_CHAIN`）；
   引用不存在的父版本单独报 `PARENT_VERSION_NOT_FOUND`，两者可区分。
3. **回滚只移动指针**：`rollback` 把 head 移动到任一已存在的历史版本，**不删除、不改写任何版本**；
   之后新注册的版本号仍按"最大版本 + 1"继续（例如回滚到 v1 后，再注册得到 v3）。
4. **自动编号**：请求省略 `version` 时自动分配"最大版本 + 1"；省略 `parentVersion` 时自动取当前最大版本。

## 2. 内容指纹（"完全相同"如何定义）

注册前将 schema 规范化为确定性 JSON（保留字段声明顺序，输出 name/type/nullable/comment），
再计算 SHA-256。以下任何差异都会导致哈希不同：字段集合、字段类型、可空性、备注、字段顺序。
与**任何**历史版本内容完全一致都会被拒绝（`DUPLICATE_SCHEMA`），且错误信息中给出已存在版本与哈希。

## 3. 兼容性规则（BACKWARD：新版本必须能读旧数据）

按字段名匹配，与字段声明顺序无关。

| 变更类型 | 结论 | 冲突码（reason） | 冲突定位（path） |
| --- | --- | --- | --- |
| 删除字段 | ❌ 不兼容 | `FIELD_REMOVED` | `fields.<字段名>` |
| 字段类型变更（含 INTEGER→LONG 等任何改动） | ❌ 不兼容 | `TYPE_CHANGED` | `fields.<字段名>.type` |
| 可空性收紧 nullable: true→false | ❌ 不兼容 | `NULLABILITY_TIGHTENED` | `fields.<字段名>.nullable` |
| 新增**必填**字段 | ❌ 不兼容 | `NEW_FIELD_REQUIRED` | `fields.<字段名>` |
| 新增**可空**字段 | ✅ 兼容 | — | — |
| 可空性放宽 false→true | ✅ 兼容 | — | — |
| 仅备注变化 | ✅ 兼容 | — | — |
| 调整字段声明顺序 | ✅ 兼容 | — | — |

说明：兼容性只相对**父版本**判定；一次提交中的所有冲突会**全部报出**（不是只报第一个），
响应体内逐条给出定位与原因，结论可解释、可复现。

支持的字段类型：`STRING / INTEGER / LONG / DOUBLE / BOOLEAN / DATE / TIMESTAMP`。

## 4. 失败原因对照表（错误码互不重叠）

| 场景 | errorCode | HTTP 状态码 |
| --- | --- | --- |
| 版本号已存在 / 提交的版本号 ≤ 当前最大 | `DUPLICATE_VERSION` | 409 |
| 内容与某历史版本完全相同 | `DUPLICATE_SCHEMA` | 409 |
| 父版本不存在 | `PARENT_VERSION_NOT_FOUND` | 404 |
| 父版本不是最新 / 首版本号不为 1 / 版本跳号 | `INVALID_EVOLUTION_CHAIN` | 400 |
| 破坏性 schema 变更（含冲突明细） | `INCOMPATIBLE_SCHEMA` | 422 |
| 数据集不存在 | `DATASET_NOT_FOUND` | 404 |
| 指定版本不存在 | `VERSION_NOT_FOUND` | 404 |
| 回滚到当前 head 等无效回滚 | `INVALID_ROLLBACK` | 400 |
| 请求体非法（空字段列表、重名字段、非法类型、非法数据集名） | `INVALID_SCHEMA` | 400 |

## 5. 并发与一致性保证

- 每个数据集一把 `ReentrantLock`，注册/回滚的"读取现状 → 全部校验 → 落盘"在同一临界区内完成，
  不同数据集互不阻塞。
- 落盘采用 **临时文件 + 原子 rename**（版本文件与 head 指针均如此），不会出现只写入一半的文件。
- 并发提交的结果只有两种：**串行化后成功**（版本号连续、parent 指向前一版本、head 前推），
  或被明确拒绝（`DUPLICATE_VERSION` / `DUPLICATE_SCHEMA`），不存在丢版本、版本号冲突或部分生效。
- 存储以磁盘上的不可变版本文件为准，进程重启后自动重建视图。

## 6. 本地存储布局

默认目录 `./data/schema-registry`（可用配置项 `registry.data-dir` 覆盖）：

```
data/schema-registry/
  <dataset>/
    versions/
      v-000001.json     # 不可变版本文件（完整 schema、parent、hash、时间戳）
      v-000002.json
    head.txt            # 当前生效版本号
```

## 7. REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/datasets/{dataset}/versions` | 注册新版本 |
| GET | `/api/datasets/{dataset}/versions` | 列出全部版本（升序） |
| GET | `/api/datasets/{dataset}/versions/latest` | 查询当前生效版本 |
| GET | `/api/datasets/{dataset}/versions/{version}` | 查询指定版本 |
| POST | `/api/datasets/{dataset}/versions/rollback` | 回滚 head 指针 |

注册请求体（`version`、`parentVersion` 可省略）：

```json
{
  "version": 2,
  "parentVersion": 1,
  "fields": [
    {"name": "id", "type": "LONG", "nullable": false, "comment": "主键"},
    {"name": "email", "type": "STRING", "nullable": true}
  ]
}
```

失败响应体：

```json
{
  "errorCode": "INCOMPATIBLE_SCHEMA",
  "message": "schema 存在不兼容变更: [...]",
  "conflicts": ["位置=fields.id.type, 类型=TYPE_CHANGED, 说明=字段类型由 LONG 变更为 STRING ..."]
}
```

### curl 快速体验

```bash
# 骨架默认 server.port=0（随机端口），本地联跑时显式指定端口
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

curl -s -X POST localhost:8080/api/datasets/orders/versions \
  -H 'Content-Type: application/json' \
  -d '{"fields":[{"name":"id","type":"LONG","nullable":false}]}'

curl -s localhost:8080/api/datasets/orders/versions/latest

curl -s -X POST localhost:8080/api/datasets/orders/versions/rollback \
  -H 'Content-Type: application/json' -d '{"version":1}'
```

## 8. 本地验证方式

```bash
# 全量测试（20 个用例，含兼容判定 / 重复注册 / 演进链 / 回滚 / 并发 / HTTP 端到端）
mvn clean test

# 单测日志会逐条打印【输入】【判定依据（冲突位置/错误码）】【最终结论】
cat target/surefire-reports/com.github.h3yow.datasetschemaregistryjava.tests.*.txt

# 只跑某一类场景
mvn test -Dtest=CompatibilityCheckerTest      # 兼容/不兼容规则
mvn test -Dtest=SchemaRegistryServiceTest     # 重复注册、断链、回滚
mvn test -Dtest=ConcurrentRegistrationTest    # 12 线程并发注册、8 线程相同内容竞争
mvn test -Dtest=SchemaRegistryWebIntegrationTest  # HTTP 状态码与错误码区分
```

测试使用独立数据目录（`src/test/resources/application.properties` 中
`registry.data-dir=./target/test-registry-data`），每次 `clean` 自动清空；
集成测试与文件存储单测均使用 `@TempDir` 隔离，互不污染。

## 9. 测试覆盖与"结论可解释"的落实

- **重复注册**：重复版本号（`DUPLICATE_VERSION`）与完全相同内容（`DUPLICATE_SCHEMA`）分别断言，
  并验证失败后版本数不增加、无覆盖。
- **不兼容变更**：删字段、改类型、收紧可空、新增必填字段分别有用例；同时验证兼容变更
  （新增可空字段、放宽可空、字段重排）不会被误判。
- **并发提交**：CountDownLatch 让 12 个线程同时提交互异的兼容 schema，
  断言版本号 1..N 连续无重复、parent 链不断裂、内容指纹互异、head 为最大版本；
  另用 8 线程提交完全相同内容，断言恰有 1 个成功、其余被明确拒绝。
- 每个测试用例通过 SLF4J 打印输入 schema、判定依据（规范化 JSON / 冲突定位 / 错误码）与最终结论。
