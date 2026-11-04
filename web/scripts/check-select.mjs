import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { createServer } from 'vite'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const server = await createServer({ root, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
try {
  const { default: SoftSelect } = await server.ssrLoadModule('/src/components/SoftSelect.vue')
  const render = props => renderToString(createSSRApp({ render: () => h(SoftSelect, props) }))
  const html = await render({ modelValue: '2', options: [{ value: 2, label: '向日葵班' }], 'aria-label': '筛选班级', required: true })
  assert.match(html, /role="combobox"/, '提供可识别的选择控件')
  assert.match(html, /aria-label="筛选班级"/, '调用方的可访问名称不能丢失')
  assert.match(html, /aria-required="true"/, '表单必填语义保留')
  assert.match(html, /向日葵班/, 'URL 查询中的字符串 ID 能匹配数字选项')
  assert.match(html, /lucide-chevron-down/, '使用真实展开箭头，不能回退成圆圈')
  assert.doesNotMatch(html, /\sdisabled(?:[\s=>])/, '可用列表保持可交互')
  const zero = await render({ modelValue: 0, options: [{ value: 0, label: '全部校区' }] })
  assert.match(zero, /全部校区/, '零值不能被当成空选项')
  const empty = await render({ options: [], placeholder: '暂无授权班级' })
  assert.match(empty, /暂无授权班级/)
  assert.match(empty, /\sdisabled(?:[\s=>])/, '空列表不可展开')
  const disabled = await render({ options: [{ value: 1, label: '已停用', disabled: true }] })
  assert.match(disabled, /\sdisabled(?:[\s=>])/, '没有可用选项时禁用控件')
  console.log('PASS：选择控件可访问名称、必填语义、字符串/数字 ID、零值、展开箭头、空列表和禁用选项')
} finally {
  await server.close()
}
