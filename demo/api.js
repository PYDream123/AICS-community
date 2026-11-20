/**
 * AICS 前端 API 层：把 demo 的模拟数据全部换成真实接口。
 *
 * 设计要点（对应技术设计 §5.4）：
 *   - SSE 用 fetch + ReadableStream 解析，而不是 EventSource —— 需要 POST 与中途取消，EventSource 都不支持；
 *   - 所有方法返回 Promise，调用方只关心数据，不关心传输格式；
 *   - 出错时抛出带可读 message 的 Error，由页面统一提示。
 */
(function (global) {
  'use strict';

  var BASE = '';

  function url(path) {
    return BASE + path;
  }

  async function parseError(res) {
    var text = '';
    try {
      text = await res.text();
    } catch (e) {
      text = '';
    }
    var message = '请求失败（HTTP ' + res.status + '）';
    try {
      var body = JSON.parse(text);
      if (body && body.message) message = body.message;
    } catch (e) {
      if (text) message = text.slice(0, 200);
    }
    return new Error(message);
  }

  async function json(res) {
    if (!res.ok) throw await parseError(res);
    if (res.status === 204) return null;
    return res.json();
  }

  /* ---------------- 知识库 ---------------- */

  function listDocuments() {
    return fetch(url('/api/kb/documents')).then(json);
  }

  function uploadDocument(file) {
    var form = new FormData();
    form.append('file', file, file.name);
    return fetch(url('/api/kb/documents'), { method: 'POST', body: form }).then(json);
  }

  function deleteDocument(id) {
    return fetch(url('/api/kb/documents/' + id), { method: 'DELETE' }).then(json);
  }

  function rebuildDocument(id) {
    return fetch(url('/api/kb/documents/' + id + '/rebuild'), { method: 'POST' }).then(json);
  }

  /* ---------------- 会话 ---------------- */

  function createSession() {
    return fetch(url('/api/chat/sessions'), { method: 'POST' }).then(json);
  }

  function listSessions() {
    return fetch(url('/api/chat/sessions')).then(json);
  }

  function messages(sessionId) {
    return fetch(url('/api/chat/sessions/' + sessionId + '/messages')).then(json);
  }

  /* ---------------- 转人工 ---------------- */

  function requestHandoff(sessionId, reasonCode, question) {
    return fetch(url('/api/chat/handoff'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId, reasonCode: reasonCode, question: question })
    }).then(json);
  }

  function queue() {
    return fetch(url('/api/handoff/queue')).then(json);
  }

  function queueStats() {
    return fetch(url('/api/handoff/stats')).then(json);
  }

  function handleTicket(id) {
    return fetch(url('/api/handoff/' + id), { method: 'PATCH' }).then(json);
  }

  /* ---------------- Trace ---------------- */

  function trace(traceId) {
    return fetch(url('/api/trace/' + traceId)).then(json);
  }

  /* ---------------- 流式对话 ---------------- */

  /**
   * 解析一帧 SSE 文本（event: xxx / data: {...}）。
   * 注释行（以 ":" 开头，心跳用）直接丢弃。
   */
  function parseFrame(raw, onEvent) {
    var event = 'message';
    var dataLines = [];
    var lines = raw.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line || line.charAt(0) === ':') continue;
      if (line.indexOf('event:') === 0) {
        event = line.slice(6).trim();
      } else if (line.indexOf('data:') === 0) {
        dataLines.push(line.slice(5).replace(/^\s/, ''));
      }
    }
    if (!dataLines.length) return;
    var payload = {};
    try {
      payload = JSON.parse(dataLines.join('\n'));
    } catch (e) {
      payload = { raw: dataLines.join('\n') };
    }
    onEvent(event, payload);
  }

  /**
   * 发起一轮对话。
   * @param {number} sessionId
   * @param {string} text
   * @param {{onEvent: function(string, object): void}} handlers
   * @returns {Promise<{abort: function(): void}>}
   */
  async function sendMessage(sessionId, text, handlers) {
    var controller = new AbortController();
    var res = await fetch(url('/api/chat/stream'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId, message: text }),
      signal: controller.signal
    });
    if (!res.ok) throw await parseError(res);
    if (!res.body) throw new Error('当前浏览器不支持流式读取');

    var donePromise = (async function () {
      var reader = res.body.getReader();
      var decoder = new TextDecoder('utf-8');
      var buffer = '';
      while (true) {
        var chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        buffer = buffer.replace(/\r\n/g, '\n');
        var idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          var frame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          parseFrame(frame, handlers.onEvent);
        }
      }
      if (buffer.trim()) parseFrame(buffer, handlers.onEvent);
    })();

    return {
      abort: function () {
        controller.abort();
      },
      finished: donePromise
    };
  }

  global.AicsApi = {
    listDocuments: listDocuments,
    uploadDocument: uploadDocument,
    deleteDocument: deleteDocument,
    rebuildDocument: rebuildDocument,
    createSession: createSession,
    listSessions: listSessions,
    messages: messages,
    sendMessage: sendMessage,
    requestHandoff: requestHandoff,
    queue: queue,
    queueStats: queueStats,
    handleTicket: handleTicket,
    trace: trace
  };
})(window);
