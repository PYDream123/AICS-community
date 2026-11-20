# AICS 企业智能客服 Agent 平台（MVP）

面向中小型 B 端企业的智能客服：**能查资料、能办业务、知道何时该认输**。

上传 Markdown/TXT 资料 → 用户提问 → 流式回答并标注引用 → 需要业务数据时调用工具 → 答不上来时拒答并转人工，全链路可在浏览器演示，且有一套可复跑的评测脚本给出 Recall@5、拒答准确率等指标。

产品与技术的设计依据见：

- `docs/产品设计文档.md`（做什么、做到什么程度算完成）
- `docs/技术设计文档.md`（怎么实现，本仓库按该文档的 D1–D8 决策落地）

---

## 1. 能力范围

| 能力 | 实现要点 |
|---|---|
| 知识库管理 | .md/.txt 上传（≤10MB）、异步解析切片向量化、状态机 `PENDING→PROCESSING→READY/FAILED`、删除级联清向量、按当前参数重建 |
| 结构化切片 | 按 Markdown 标题层级切，短段合并、超长二次切分、表格与代码块原子化；向量化文本额外拼 `headingPath` 补上下文 |
| 检索 | 条件门控的查询改写 → 向量检索 → 余弦阈值过滤 → 单文档限流 → 引用编号注入 |
| 流式问答 | SSE 事件协议 `meta → retrieval → (tool_call → tool_result)* → delta* → done`，首字目标 ≤1.5s（关闭 thinking） |
| 引用溯源 | 编号由服务端注入、模型只标角标，回答结束后正则回填 `trace_retrieval.cited`，越界编号丢弃 |
| 多轮记忆 | 最近 6 轮原文 + 超 12 轮压缩为 300 字摘要，`summary_upto_seq` 前移 |
| 工具调用 | 订单查询 / 物流查询 / 工单创建，模型自主决策、参数缺失追问、超时降级、写入幂等 |
| 兜底与转人工 | L1 阈值拒答（**不调用模型**）+ L2 模型自述拒答；`SUGGESTED → WAITING → HANDLED` 状态机 |
| 可观测 | 每次问答一条 `qa_trace` + 召回明细 `trace_retrieval`，异步落库不占首字预算 |
| 评测 | `--eval=` 跑评测集，输出 Recall@5 / 拒答准确率 / 引用覆盖率 / 首字 p50·p95 与人工评审表 |

## 2. 技术栈

| 层 | 选择 | 说明 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.5（MVC）+ Spring AI 1.1 | 单体、不拆微服务 |
| 流式 | `SseEmitter` + 自研 `SseSink` | 不引入 WebFlux 主栈；WebFlux 仅作为 AI 客户端的 HTTP 层 |
| 存储 | PostgreSQL 16 + pgvector | 向量与元数据同库，删除文档即 `ON DELETE CASCADE` |
| 对话模型 | DeepSeek 官方 API（默认 `deepseek-flash`） | 显式关闭 thinking，`reasoning_content` 不进正文 |
| 向量模型 | 任一 OpenAI 兼容 Embedding（默认 `BAAI/bge-m3`，1024 维） | DeepSeek 无 Embedding 接口，必须独立配置 |
| 迁移 | Flyway | `V1__init.sql` 建表、`V2__mock_business_data.sql` 灌模拟订单 |

## 3. 快速开始

### 3.1 前置

- JDK 21、Maven 3.9+
- Docker（启动 pgvector）
- 一个对话模型 Key（DeepSeek）和一个 Embedding Key（如硅基流动）

### 3.2 起数据库

```bash
docker compose up -d
```

### 3.3 配置密钥

```bash
cp .env.example .env      # 填入 DEEPSEEK_API_KEY / EMBEDDING_API_KEY
```

或用环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export EMBEDDING_API_KEY=sk-yyy
```

> 未配置 Key 时应用仍可启动（便于看页面结构），但上传文档与提问会失败；启动日志会打印实际的模型 ID 与 provider，避免沿用已下线模型而不自知。

### 3.4 启动

```bash
mvn spring-boot:run
```

浏览器打开 <http://localhost:8080>（`demo/index.html` 通过静态资源映射直接提供，不复制第二份）。

### 3.5 灌入演示知识库

```bash
node scripts/seed-kb.mjs
```

上传 `seed/kb/` 下的 4 篇文档（售后政策、保修条款、物流说明、积分规则），并轮询到 `READY`。

### 3.6 五个可演示场景

| 场景 | 操作 | 期望 |
|---|---|---|
| 知识问答 | 问「七天无理由退货运费谁承担」 | 流式回答 + 引用 `售后服务政策-v3.md · 2.3 退货运费承担` |
| 多轮记忆 | 先问「你们的保修期多久」，再问「那过了保修期维修怎么收费」 | 改写后命中 `2.2 保外维修收费标准`，Trace 里 `rewritten=true` |
| 工具调用 | 问「我的订单到哪了」→ 按提示输入 `SO202611180032` | 先出 `query_order` / `query_logistics` 卡片，再出文字 |
| 能力边界 | 问「你们在东南亚有代理商吗」 | 拒答 + 转人工按钮，人工队列出现 `WAITING` 记录 |
| 知识库管理 | 知识库页上传/删除文档 | 状态从「解析中」到「可用」，删除后不再被召回 |

模拟订单覆盖：已签收 6、运输中 6、已取消 3、已付款未发货 3；`SO202611209901` / `SO202611209902` 故意不存在，用于演示 `ORDER_NOT_FOUND`。

## 4. 配置

全部参数在 `src/main/resources/application.yml` 的 `aics.*` 下，通过 `@ConfigurationProperties` 绑定，无硬编码。

| 配置 | 默认 | 说明 |
|---|---|---|
| `aics.llm.model` | `deepseek-flash` | 换模型只改这里 |
| `aics.llm.thinking` | `disabled` | `disabled` 时通过 `extraBody` 显式关闭思考模式 |
| `aics.embedding.dimensions` | `1024` | 与 `kb_chunk.embedding vector(1024)` 绑定，改维度须配迁移 |
| `aics.retrieval.top-k` | 6 | 注入 Prompt 的片段数 |
| `aics.retrieval.score-threshold` | 0.70 | **必须用评测集校准**，直接决定拒答准确率 |
| `aics.retrieval.per-doc-limit` | 3 | 单文档最多保留条数 |
| `aics.chunking.*` | 800/120/80/3 | 切片上限、下限、重叠、切分标题层级 |
| `aics.memory.*` | 6/12/300 | 近期轮数、摘要触发轮数、摘要字数 |
| `aics.tool.timeout-ms` | 3000 | 工具超时 |
| `aics.sse.*` | 180000/15000 | SSE 超时与心跳 |

更换 provider：两者都走 OpenAI 兼容协议，只改 `base-url` / `model` / `api-key`。

## 5. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/kb/documents` | multipart 上传，返回 `{id, filename, status, reused}` |
| GET | `/api/kb/documents` | 列表面向 demo：`{id, name, size, chunks, status, statusText, time, error}` |
| GET | `/api/kb/documents/{id}/chunks` | 查看切片结果（调参用） |
| DELETE | `/api/kb/documents/{id}` | 级联删除切片与向量 |
| POST | `/api/kb/documents/{id}/rebuild` | 用当前参数重切 |
| POST | `/api/chat/sessions` | 新建会话 |
| GET | `/api/chat/sessions` | 历史会话 |
| GET | `/api/chat/sessions/{id}/messages` | 历史消息（含 refs / traceId，刷新恢复） |
| POST | `/api/chat/stream` | **SSE**，请求体 `{sessionId, message}` |
| POST | `/api/chat/handoff` | 用户点击转人工，`SUGGESTED → WAITING` |
| GET | `/api/handoff/queue` | 待接管队列 |
| GET | `/api/handoff/stats` | 待接管数与今日转人工率 |
| PATCH | `/api/handoff/{id}` | 置为 `HANDLED` |
| GET | `/api/trace/{traceId}` / `/api/trace?limit=20` | Trace 详情 / 最近列表 |

### SSE 事件

| event | data |
|---|---|
| `meta` | `{traceId, sessionId, turn}` |
| `retrieval` | `{topScore, threshold, refs:[{index, chunkId, doc, sec, score, snip}]}` |
| `tool_call` | `{callId, name, args, status}` |
| `tool_result` | `{callId, name, ok, costMs, rows:[[k,v]], errorCode?, message?, replayed?}` |
| `delta` | `{text}` |
| `refused` | `{reason, topScore, threshold, suggestHandoff, message}` |
| `done` | `{messageId, tokens:{in,out}, stages:[[name,ms]], cited, ttftMs, totalMs, refused, suggestHandoff}` |
| `error` | `{code, message}` |

## 6. 评测

```bash
# 只跑检索：验证召回与拒答，不产生生成费用
mvn spring-boot:run -Dspring-boot.run.arguments="--eval=eval/cases.jsonl --mode=retrieval"

# 全量：额外得到引用覆盖率、首字 p50/p95，并导出引用准确率人工评审表
mvn spring-boot:run -Dspring-boot.run.arguments="--eval=eval/cases.jsonl --mode=full --out=eval/report-$(date +%F).md"
```

评测集 `eval/cases.jsonl` 共 26 条：20 条正例（标注 `expectDoc` / `expectSection` / `expectKeywords`，其中 1 条带多轮历史）+ 6 条负例（`shouldRefuse=true`）。

指标口径：

- **Recall@5**：期望片段出现在 top-5 的比例，只看检索不看生成；
- **拒答准确率**：负例被正确拒答的比例；
- **引用覆盖率**：知识类回答携带 ≥1 条有效引用的比例；
- **引用准确率**：无法自动判定，脚本导出 `*-review.csv`，人工打勾后回读，不使用 LLM-as-judge。

> 阈值 0.70 只是基线。M1 必须做一次校准：把正例与负例的 top1 分数各画一条分布，取误判代价最小的切点，再把结果写回 `aics.retrieval.score-threshold`。跳过这步，后续所有指标都不可信。

## 7. 测试

```bash
mvn test
```

当前结果：**66 个用例，60 通过、6 跳过**（跳过的是 `PgVectorIntegrationTest`，需要 Docker，本机没有 Docker 时由 `@Testcontainers(disabledWithoutDocker = true)` 自动跳过）。

| 测试 | 覆盖 |
|---|---|
| `MarkdownChunkerTest` | 标题层级、短段合并、跨父节点不合并、超长二次切分、表格原子性、代码块不被破坏、空节点丢弃、偏移量可回原文定位 |
| `ChatOrchestratorTest` | 阈值之下**零模型调用**且事件为 `meta→refused→done`、`retrieval` 先于 `delta`、`tool_call/tool_result` 先于首个 `delta`、L2 拒答、模型异常不落半截回答、寒暄短路、会话落库 |
| `ToolFacadeTest` | `INVALID_ARGS` 不发起调用、四种错误码、幂等回放不重复执行业务、进行中重复提交、同参数同幂等键、落库参数脱敏、超时降级 |
| `MemoryServiceTest` | 6 轮窗口、12 轮不压缩、14 轮压缩且 `summary_upto_seq` 前移、摘要截断 |
| `IdempotencyKeysTest` / `MaskingTest` / `CitationParserTest` / `QueryRewriteGateTest` / `RefusalDetectorTest` | 幂等键归一化、脱敏规则、引用越界丢弃、改写门控、拒答识别 |
| `PgVectorIntegrationTest`（Docker） | Flyway 迁移、模拟数据覆盖度、向量 top-k 顺序、删除级联、工具幂等唯一约束、转人工单条未处理记录 |

前端结构自检：

```bash
node scripts/verify-demo.mjs
```

## 8. 工程结构

```
src/main/java/com/aics/
├── AicsApplication.java
├── common/      # AicsProperties、TraceContext、SseSink/ChatSink、脱敏、幂等键、JSON、向量字面量
├── config/      # AiConfig（双 provider）、ExecutorConfig（入库/Trace/工具/SSE 心跳）
├── kb/          # MarkdownChunker、IngestService、EmbeddingService、Repository、KbController
├── retrieval/   # QueryRewriteGate、QueryRewriter、RetrievalService
├── chat/        # ChatOrchestrator、PromptBuilder、MemoryService、LlmClient、Controller
├── tool/        # ToolFacade、AgentTools(@Tool)、MockOrderService、ToolInvocationRepository
├── trace/       # TraceRecorder、JdbcTraceRecorder、TraceController
├── handoff/     # HandoffService、HandoffController
├── eval/        # EvalRunner、EvalCase、ReportWriter
└── web/         # GlobalExceptionHandler
demo/            # index.html（UI 不变）+ api.js（真实数据源）
seed/kb/         # 演示知识库文档
eval/cases.jsonl # 评测集
```

## 9. 与设计文档的差异（均为实现所需，已尽量最小化）

| 项 | 设计文档 | 实现 | 原因 |
|---|---|---|---|
| `kb_chunk` 列 | 无 `content_sha256` | 新增该列 + 索引 | 文档 §3.1 要求「Embedding 缓存按 content_sha256 命中」，没有列无法落地 |
| 错误码 | 5 个 | 增加 `DUPLICATE_IN_FLIGHT` / `INTERNAL_ERROR` | 幂等预留后发现同键请求仍在执行时，需要一个明确的中间态 |
| 寒暄处理 | 未提及 | 纯问候语短路，不检索不生成 | 否则「你好」会被阈值判成低置信并误转人工，演示体验直接破功 |
| 工具事件参数 | `args` 为展示字符串 | 展示用真实值、落库用脱敏值 | 前端要看订单号，日志不能留明文 |
| 会话标题 | 默认「新会话」 | 首条用户问题前 30 字 | 历史会话列表可读性 |
| 单文档限流位置 | 检索 SQL 之后 | 应用层过滤 | 避免复杂窗口函数，且便于 Trace 保留原始排序（Recall@5 需要） |

## 10. 已知边界

- 不支持 PDF/Word/扫描件；不解析图片。
- 不区分租户，`tenant_id` 仅预留。
- 无坐席工作台、无实时双向会话、无排队分配。
- 未做混合检索与重排（属 M5，需先有评测基线）。
- `demo/index.html` 的 UI/CSS 未改动，只替换了数据源层；`scripts/verify-demo.mjs` 依赖的 12 个 id 全部保留。
