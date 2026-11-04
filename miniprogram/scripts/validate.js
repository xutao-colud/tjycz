/* Run: node scripts/validate.js. Uses only Node built-ins. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
let count = 0;
function assert(condition, message) { if (!condition) throw new Error(message); }
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(item => item.isDirectory() ? walk(path.join(dir, item.name)) : [path.join(dir, item.name)]); }
const files = walk(root);
for (const file of files) {
  if (file.endsWith('.json')) { JSON.parse(fs.readFileSync(file, 'utf8')); count++; }
  if (file.endsWith('.js')) { new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file }); count++; }
}
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
const locationDesc = app.permission?.['scope.userLocation']?.desc;
assert(typeof locationDesc === 'string' && locationDesc.trim() && Array.from(locationDesc).length <= 30, 'scope.userLocation.desc 须为1至30字，避免真机上传错误80058');
assert(app.pages.includes('pages/dashboard/index') && app.pages.includes('pages/login/index'), '须包含首页和登录页');
assert(!app.tabBar, '应使用通用导航而非tabBar');
assert(new Set(app.pages).size === app.pages.length, '存在重复页面路由');
for (const route of app.pages) for (const ext of ['.js', '.wxml', '.wxss', '.json']) assert(fs.existsSync(path.join(root, route + ext)), route + ' 缺少 ' + ext);
for (const [name, route] of Object.entries(app.usingComponents)) {
  const local = route.replace(/^\//, '');
  for (const ext of ['.js', '.wxml', '.wxss', '.json']) assert(fs.existsSync(path.join(root, local + ext)), name + ' 缺少 ' + ext);
  assert(JSON.parse(fs.readFileSync(path.join(root, local + '.json'), 'utf8')).component, name + ' 未声明component');
}
const knownTags = new Set(['view', 'text', 'button', 'input', 'textarea', 'picker', 'block', 'image', 'scroll-view', 'progress', 'slot', 'switch', 'checkbox'].concat(Object.keys(app.usingComponents)));
let templates = 0;
for (const file of files.filter(name => name.endsWith('.wxml'))) {
  const content = fs.readFileSync(file, 'utf8');
  for (const match of content.matchAll(/wx:(?:if|elif|for)="([^"]*)"/g)) assert(match[1].startsWith('{{') && match[1].endsWith('}}'), file + ' 条件/循环缺少动态绑定 ' + match[0]);
  const stack = [];
  const tagPattern = /<\/?([a-z][\w-]*)(?:\s+(?:"[^"]*"|'[^']*'|[^'">])*)?\s*\/?>/g;
  const pageJS = file.replace(/\.wxml$/, '.js');
  const handlers = fs.readFileSync(pageJS, 'utf8') + fs.readFileSync(path.join(root, 'utils/page.js'), 'utf8');
  for (const match of content.matchAll(tagPattern)) {
    const tag = match[1];
    assert(knownTags.has(tag), file + ' 未知标签 ' + tag);
    assert(!(['select-picker', 'date-picker'].includes(tag) && match[0].endsWith('/>')), file + ' ' + tag + ' 缺少展示当前值的插槽，会显示为空白');
    if (match[0].startsWith('</')) assert(stack.pop() === tag, file + ' 标签未匹配 ' + tag);
    else if (!match[0].endsWith('/>')) stack.push(tag);
  }
  assert(stack.length === 0, file + ' 有未闭合标签 ' + stack.join(','));
  for (const match of content.matchAll(/(?:bind|catch)(?::)?[\w-]+="([\w]+)"/g)) assert(new RegExp('\\b' + match[1] + '\\s*\\(').test(handlers), file + ' 未定义事件 ' + match[1]);
  for (const match of content.matchAll(/data-url="(\/pages\/[^"?]+)(?:\?[^\"]*)?"/g)) {
    if (!match[1].includes('{{')) assert(app.pages.includes(match[1].slice(1)), file + ' 无效导航 ' + match[1]);
  }
  for (const match of content.matchAll(/src="(\/assets\/[^"{}]+)"/g)) assert(fs.existsSync(path.join(root, match[1].slice(1))), file + ' 缺少资源 ' + match[1]);
  for (const match of content.matchAll(/data-page="([\w-]+)"/g)) assert(app.pages.includes('pages/' + match[1] + '/index'), file + ' 无效导航 ' + match[1]);
  templates++;
}
for (const file of files.filter(name => name.endsWith('.js') && !name.includes('/scripts/'))) {
  const source = fs.readFileSync(file, 'utf8');
  for (const match of source.matchAll(/require\(['"](\.[^'"]+)['"]\)/g)) assert(fs.existsSync(path.resolve(path.dirname(file), match[1] + '.js')), file + ' 无效require ' + match[1]);
  for (const match of source.matchAll(/['"](\/pages\/[\w-]+\/index)(?:\?[^'"]*)?['"]/g)) assert(app.pages.includes(match[1].slice(1)), file + ' 无效路由 ' + match[1]);
}
console.log('通过：' + app.pages.length + ' 个页面路由、' + Object.keys(app.usingComponents).length + ' 个组件、' + templates + ' 个WXML模板、' + count + ' 个JSON/JS文件；依赖、导航、事件绑定完整。');
