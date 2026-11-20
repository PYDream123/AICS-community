CREATE EXTENSION IF NOT EXISTS vector;

-- ========== 知识库 ==========
CREATE TABLE kb_document (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL DEFAULT 1,   -- 预留，MVP 不隔离
    filename       VARCHAR(255) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    content_sha256 CHAR(64)     NOT NULL,
    status         VARCHAR(16)  NOT NULL,             -- PENDING/PROCESSING/READY/FAILED
    error_msg      TEXT,
    chunk_count    INT          NOT NULL DEFAULT 0,
    uploaded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);
CREATE INDEX idx_kb_doc_status ON kb_document (status);
CREATE INDEX idx_kb_doc_sha    ON kb_document (content_sha256);

CREATE TABLE kb_chunk (
    id            BIGSERIAL    PRIMARY KEY,
    doc_id        BIGINT       NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    chunk_index   INT          NOT NULL,
    heading_path  VARCHAR(512) NOT NULL,
    heading_text  VARCHAR(255) NOT NULL,
    heading_level SMALLINT     NOT NULL,
    content       TEXT         NOT NULL,
    char_start    INT          NOT NULL,
    char_end      INT          NOT NULL,
    token_count   INT          NOT NULL,
    oversized     BOOLEAN      NOT NULL DEFAULT false,
    content_sha256 CHAR(64)    NOT NULL,             -- Embedding 缓存键（见技术设计 §3.1）
    embedding     vector(1024) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (doc_id, chunk_index)
);
CREATE INDEX idx_kb_chunk_doc  ON kb_chunk (doc_id);
CREATE INDEX idx_kb_chunk_sha  ON kb_chunk (content_sha256);
CREATE INDEX idx_kb_chunk_vec  ON kb_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ========== 会话 ==========
CREATE TABLE chat_session (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL DEFAULT 1,
    title            VARCHAR(120) NOT NULL DEFAULT '新会话',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/HANDOFF/CLOSED
    summary          TEXT,
    summary_upto_seq INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    seq        INT         NOT NULL,
    role       VARCHAR(16) NOT NULL,        -- user/assistant/tool
    content    TEXT        NOT NULL,
    refs       JSONB,
    tool_calls JSONB,
    trace_id   VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);

-- ========== 可观测 ==========
CREATE TABLE qa_trace (
    id                 VARCHAR(32) PRIMARY KEY,
    session_id         BIGINT      REFERENCES chat_session(id) ON DELETE CASCADE,
    message_id         BIGINT,
    turn               INT         NOT NULL,
    query_raw          TEXT        NOT NULL,
    query_used         TEXT        NOT NULL,   -- 改写后实际检索用的
    rewritten          BOOLEAN     NOT NULL DEFAULT false,
    top_score          REAL        NOT NULL,
    threshold          REAL        NOT NULL,
    refused            BOOLEAN     NOT NULL DEFAULT false,
    tool_used          VARCHAR(64),
    ttft_ms            INT,
    total_ms           INT,
    prompt_tokens      INT,
    completion_tokens  INT,
    stages             JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trace_created ON qa_trace (created_at DESC);

CREATE TABLE trace_retrieval (
    trace_id VARCHAR(32) NOT NULL REFERENCES qa_trace(id) ON DELETE CASCADE,
    chunk_id BIGINT      NOT NULL,
    rank     INT         NOT NULL,
    score    REAL        NOT NULL,
    cited    BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (trace_id, chunk_id)
);

-- ========== 工具 ==========
CREATE TABLE tool_invocation (
    id          BIGSERIAL   PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    trace_id    VARCHAR(32) NOT NULL,
    tool_name   VARCHAR(64) NOT NULL,
    idem_key    CHAR(64)    NOT NULL,
    args_masked JSONB       NOT NULL,        -- 联系方式等已脱敏
    status      VARCHAR(16) NOT NULL,        -- IN_PROGRESS/OK/ERROR/TIMEOUT
    error_code  VARCHAR(32),
    result      JSONB,
    latency_ms  INT         NOT NULL DEFAULT 0,
    replayed    BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, tool_name, idem_key)
);

-- ========== 人工接管 ==========
CREATE TABLE handoff_ticket (
    id              BIGSERIAL    PRIMARY KEY,
    session_id      BIGINT       NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    reason_code     VARCHAR(32)  NOT NULL,   -- USER_REQUEST/LOW_CONFIDENCE/TOOL_FAILURE/OUT_OF_SCOPE
    reason_text     VARCHAR(255),
    question_digest VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'SUGGESTED',  -- SUGGESTED/WAITING/HANDLED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_handoff_status ON handoff_ticket (status, created_at DESC);
-- 同一会话同一原因只保留一条未处理记录，避免用户反复追问时刷屏队列
CREATE UNIQUE INDEX uq_handoff_open ON handoff_ticket (session_id, reason_code)
    WHERE status <> 'HANDLED';

-- ========== 业务模拟数据 ==========
CREATE TABLE mock_order (
    order_no     VARCHAR(32)    PRIMARY KEY,
    status       VARCHAR(32)    NOT NULL,   -- PAID/SHIPPED/SIGNED/CANCELLED
    placed_at    TIMESTAMPTZ    NOT NULL,
    amount       NUMERIC(12,2)  NOT NULL,
    item_summary VARCHAR(255)   NOT NULL,
    carrier      VARCHAR(32),
    waybill_no   VARCHAR(64)
);

CREATE TABLE mock_logistics_event (
    id        BIGSERIAL    PRIMARY KEY,
    order_no  VARCHAR(32)  NOT NULL REFERENCES mock_order(order_no) ON DELETE CASCADE,
    node_time TIMESTAMPTZ  NOT NULL,
    node_desc VARCHAR(255) NOT NULL
);
CREATE INDEX idx_mock_logistics ON mock_logistics_event (order_no, node_time DESC);

CREATE TABLE support_ticket (
    ticket_no   VARCHAR(32) PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    description TEXT        NOT NULL,
    contact     VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    eta_hours   INT         NOT NULL DEFAULT 24,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
