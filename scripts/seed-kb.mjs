/**
 * 一键灌入演示知识库：把 seed/kb 下的文档通过真实接口上传，并轮询到 READY。
 *
 * 用法: node scripts/seed-kb.mjs [baseUrl]
 * 前置: 服务已启动（默认 http://localhost:8080），且 Embedding API Key 已配置。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const kbDir = join(root, 'seed', 'kb');
const baseUrl = (process.argv[2] || 'http://localhost:8080').replace(/\/$/, '');

const files = readdirSync(kbDir).filter((f) => /\.(md|markdown|txt)$/i.test(f));

if (!files.length) {
  console.error('seed/kb 下没有可上传的文档');
  process.exit(1);
}

console.log(`向 ${baseUrl} 上传 ${files.length} 篇文档`);

for (const name of files) {
  const path = join(kbDir, name);
  const buffer = readFileSync(path);
  const form = new FormData();
  form.append('file', new Blob([buffer], { type: 'text/markdown' }), name);

  const res = await fetch(`${baseUrl}/api/kb/documents`, { method: 'POST', body: form });
  if (!res.ok) {
    const text = await res.text();
    console.error(`  FAIL ${name}: HTTP ${res.status} ${text}`);
    continue;
  }
  const doc = await res.json();
  console.log(`  ok   ${name} -> id=${doc.id} status=${doc.status}${doc.reused ? '（复用已有）' : ''}`);
}

// 轮询到全部处理完成
const deadline = Date.now() + 120_000;
let pending = true;
while (pending && Date.now() < deadline) {
  const list = await (await fetch(`${baseUrl}/api/kb/documents`)).json();
  pending = list.some((d) => d.status === 'processing');
  if (!pending) {
    console.log('\n最终状态：');
    for (const d of list) {
      const detail = d.status === 'failed' ? ` 原因: ${d.error}` : ` ${d.chunks} 个切片`;
      console.log(`  ${d.status.padEnd(10)} ${d.name}${detail}`);
    }
    break;
  }
  await new Promise((r) => setTimeout(r, 1500));
}

if (pending) {
  console.warn('\n仍有文档在处理中，请稍后刷新知识库页面查看');
  process.exit(1);
}
