/**
 * demo/index.html 结构自检（零依赖）
 * 用法: node scripts/verify-demo.mjs
 *
 * 检查内容:
 *   1. 关键 DOM 节点 id 是否齐全（JS 依赖这些 id）
 *   2. 内联脚本能否通过语法解析
 *   3. 常见容器标签是否成对闭合
 *   4. 文案中是否残留未替换的占位符
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const file = resolve(root, 'demo/index.html');

const REQUIRED_IDS = [
  'thread', 'content', 'input', 'sendBtn',
  'traceBody', 'trace', 'queueBody', 'kbBody',
  'queueBadge', 'statWait', 'kbCount', 'convMeta',
];

const PAIRED_TAGS = [
  'div', 'section', 'aside', 'header', 'footer', 'nav',
  'table', 'thead', 'tbody', 'tr', 'td', 'th', 'button', 'span', 'textarea', 'dl', 'h3',
];

let failed = 0;
const fail = (msg) => { failed++; console.error('  FAIL  ' + msg); };
const pass = (msg) => console.log('  ok    ' + msg);

let html;
try {
  html = readFileSync(file, 'utf8');
} catch (e) {
  console.error('无法读取 demo/index.html:', e.message);
  process.exit(1);
}

console.log('检查 demo/index.html');

// 1. 关键 id
const missing = REQUIRED_IDS.filter((id) => !new RegExp(`id="${id}"`).test(html));
if (missing.length) fail('缺少关键 id: ' + missing.join(', '));
else pass(`关键 id 齐全（${REQUIRED_IDS.length} 个）`);

// 2. 脚本语法
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) {
  fail('未找到内联 script');
} else {
  try {
    new Function(scriptMatch[1]);
    pass('内联脚本语法正确');
  } catch (e) {
    fail('内联脚本语法错误: ' + e.message);
  }
}

// 3. 标签配对
const unbalanced = [];
for (const tag of PAIRED_TAGS) {
  const open = (html.match(new RegExp(`<${tag}[\\s>]`, 'g')) || []).length;
  const close = (html.match(new RegExp(`</${tag}>`, 'g')) || []).length;
  if (open !== close) unbalanced.push(`${tag}(${open}/${close})`);
}
if (unbalanced.length) fail('标签未配对: ' + unbalanced.join(', '));
else pass(`标签配对正常（${PAIRED_TAGS.length} 类）`);

// 4. 模板占位符残留（只查模板标记，避免误报正常代码中的括号与 undefined）
const leftovers = ['TODO', 'FIXME', '{{'].filter((k) => html.includes(k));
if (leftovers.length) fail('存在残留内容: ' + leftovers.join(', '));
else pass('无占位符残留');

if (failed) {
  console.error(`\n未通过：${failed} 项失败`);
  process.exit(1);
}
console.log('\n全部通过');
