/**
 * demo/api.js 行为自检（零依赖）
 *
 * 覆盖最容易出错的部分：SSE 分帧（多帧 / 心跳注释 / CRLF / 跨 chunk 截断）、
 * 错误响应透出、列表接口映射。
 *
 * 用法: node scripts/test-api.mjs
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'demo/api.js'), 'utf8');

let failed = 0;
const fail = (msg) => { failed++; console.error('  FAIL  ' + msg); };
const pass = (msg) => console.log('  ok    ' + msg);

/** 用假的 window/fetch 加载 api.js，拿到 AicsApi */
function loadApi(fetchImpl) {
  const fakeWindow = {};
  const factory = new Function('window', 'fetch', 'AbortController', 'FormData', 'Blob', source);
  factory(fakeWindow, fetchImpl, AbortController, FormData, Blob);
  return fakeWindow.AicsApi;
}

/** 把 SSE 文本切成若干 Uint8Array，模拟网络分片 */
function streamOf(chunks) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    getReader() {
      return {
        async read() {
          if (i >= chunks.length) return { done: true, value: undefined };
          return { done: false, value: encoder.encode(chunks[i++]) };
        }
      };
    }
  };
}

function sseResponse(chunks) {
  return { ok: true, status: 200, body: streamOf(chunks) };
}

console.log('检查 demo/api.js');

// 1. 正常帧序列
{
  const frames = [
    'event:meta\ndata:{"traceId":"t1","sessionId":7,"turn":1}\n\n',
    ': ping\n\n',
    'event:retrieval\ndata:{"topScore":0.91,"threshold":0.7,"refs":[{"index":1,"doc":"a.md","sec":"1.1","score":0.91,"snip":"x"}]}\n\n',
    'event:delta\ndata:{"text":"保修 "}\n\n',
    'event:delta\ndata:{"text":"12 个月 [1]"}\n\n',
    'event:done\ndata:{"messageId":3,"cited":[1],"ttftMs":720}\n\n'
  ];
  const api = loadApi(async () => sseResponse(frames));
  const seen = [];
  const handle = await api.sendMessage(7, '保修期多久', { onEvent: (e, p) => seen.push([e, p]) });
  await handle.finished;

  const names = seen.map((x) => x[0]);
  if (names.join(',') === 'meta,retrieval,delta,delta,done') pass('SSE 事件顺序与解析正确');
  else fail('事件序列不符: ' + names.join(','));

  const text = seen.filter((x) => x[0] === 'delta').map((x) => x[1].text).join('');
  if (text === '保修 12 个月 [1]') pass('delta 拼接还原回答');
  else fail('delta 拼接错误: ' + text);

  if (seen[1][1].refs[0].doc === 'a.md') pass('retrieval 引用解析正确');
  else fail('引用解析错误');
}

// 2. 跨 chunk 截断 + CRLF
{
  const raw = 'event:delta\r\ndata:{"text":"半"}\r\n\r\nevent:delta\r\ndata:{"text":"句"}\r\n\r\n';
  const parts = [raw.slice(0, 12), raw.slice(12, 40), raw.slice(40)];
  const api = loadApi(async () => sseResponse(parts));
  const seen = [];
  const handle = await api.sendMessage(1, 'x', { onEvent: (e, p) => seen.push([e, p]) });
  await handle.finished;

  const text = seen.map((x) => x[1].text || '').join('');
  if (text === '半句') pass('跨 chunk 截断与 CRLF 处理正确');
  else fail('分片解析错误: ' + JSON.stringify(text));
}

// 3. 错误响应透出可读 message
{
  const api = loadApi(async () => ({
    ok: false,
    status: 400,
    async text() { return JSON.stringify({ code: 'BAD_REQUEST', message: '消息不能为空' }); }
  }));
  try {
    await api.sendMessage(1, '', { onEvent: () => {} });
    fail('错误响应未抛出异常');
  } catch (e) {
    if (e.message === '消息不能为空') pass('错误响应透出服务端文案');
    else fail('错误信息不符: ' + e.message);
  }
}

// 4. 列表接口
{
  const api = loadApi(async () => ({
    ok: true,
    status: 200,
    async json() {
      return [{ id: 1, name: 'a.md', size: '42 KB', chunks: 18, status: 'ready', time: '11-20 10:12' }];
    }
  }));
  const docs = await api.listDocuments();
  if (docs.length === 1 && docs[0].name === 'a.md') pass('文档列表解析正确');
  else fail('文档列表解析错误');
}

// 5. 上传使用 multipart
{
  let usedForm = false;
  const api = loadApi(async (url, init) => {
    usedForm = init && init.body instanceof FormData;
    return { ok: true, status: 201, async json() { return { id: 2, status: 'PENDING' }; } };
  });
  const file = new Blob(['# 文档'], { type: 'text/markdown' });
  file.name = 'b.md';
  const result = await api.uploadDocument(file);
  if (usedForm && result.id === 2) pass('上传走 multipart');
  else fail('上传未使用 multipart');
}

if (failed) {
  console.error(`\n未通过：${failed} 项失败`);
  process.exit(1);
}
console.log('\n全部通过');
