import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { createServer } from 'vite'
import { createSSRApp, createRenderer, h, nextTick, ref, ssrContextKey } from 'vue'
import { renderToString } from '@vue/server-renderer'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const server = await createServer({ root, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
try {
  const { default: AttachmentList } = await server.ssrLoadModule('/src/components/AttachmentList.vue')
  const file = { id: 1, name: '语音作业.mp3', url: '/api/files/1?ticket=short-fixture', contentType: 'audio/mpeg', size: 19928 }
  const html = await renderToString(createSSRApp({ render: () => h(AttachmentList, { files: [file] }) }))
  assert.match(html, /<audio\b[^>]*\bcontrols(?:[\s=>])/, '语音上传后的附件必须提供页内音频播放控件')
  assert.match(html, /<audio\b[^>]*\bsrc="\/api\/files\/1\?ticket=short-fixture"/, '播放器必须使用后端返回的授权文件地址')
  assert.doesNotMatch(html, /[?&]token=/, '附件地址不能暴露原始会话')
  assert.match(html, /语音作业\.mp3/, '保留附件名称')
  const extensionOnly = await renderToString(createSSRApp({ render: () => h(AttachmentList, { files: [{ ...file, contentType: 'application/octet-stream' }] }) }))
  assert.match(extensionOnly, /<audio\b/, '缺少音频MIME的旧附件仍能按文件扩展名播放')
  const previewHtml = await renderToString(createSSRApp({ render: () => h(AttachmentList, { files: [file], removable: true }) }))
  assert.match(previewHtml, /<audio\b[^>]*\bcontrols(?:[\s=>])/, '可移除的上传预览同样必须提供播放器')
  assert.match(previewHtml, /移除附件：语音作业\.mp3/, '上传预览必须能移除附件')
  const videoHtml = await renderToString(createSSRApp({ render: () => h(AttachmentList, { files: [{ ...file, name: '朗读.mp4', contentType: 'video/mp4' }] }) }))
  assert.match(videoHtml, /<video\b[^>]*\bcontrols(?:[\s=>])/, '视频附件保留独立播放控件')
  assert.doesNotMatch(videoHtml, /<audio\b/, '视频不可误判为音频')
  const { setRequestContext } = await server.ssrLoadModule('/src/lib/api.ts')
  globalThis.localStorage = { getItem: () => 'local-fixture' }
  let requests = []
  let respond
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    if (respond) return respond(url, options)
    return { ok: true, json: async () => ({ ...file, url: '/api/files/1?ticket=refreshed', expiresAt: '2099-01-01T00:00:00' }) }
  }
  const renderer = createRenderer({
    patchProp() {}, insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null
  })
  function mount() {
    let state
    const files = ref([{ ...file, expiresAt: '2000-01-01T00:00:00' }])
    const component = { ...AttachmentList, setup(props, context) { state = AttachmentList.setup(props, context); return state }, render: () => h('div') }
    const app = renderer.createApp({ render: () => h(component, { files: files.value }) })
    app.provide(ssrContextKey, { modules: new Set() })
    app.mount({})
    return { state, files, app }
  }
  const media = () => ({ currentTime: 23, paused: false, pauseCalls: 0, loadCalls: 0, playCalls: 0,
    pause() { this.paused = true; this.pauseCalls++ }, load() { this.loadCalls++ },
    play() { this.paused = false; this.playCalls++; return Promise.resolve() }, closest() { return null } })
  const flush = () => new Promise(resolve => setImmediate(resolve))
  {
    const { state, app } = mount()
    const player = media()
    state.play(state.rows.value[0], { target: player })
    await flush()
    assert.equal(requests[0].url, '/api/files/1/access')
    assert.equal(requests[0].options.headers.Authorization, 'Bearer local-fixture')
    assert.equal(player.pauseCalls, 1)
    assert.equal(player.loadCalls, 1)
    assert.match(state.rows.value[0].url, /ticket=refreshed/)
    player.currentTime = 0
    state.ready(state.rows.value[0], { target: player })
    assert.equal(player.currentTime, 23, '换票据后保留音视频播放位置')
    assert.equal(player.playCalls, 1)
    state.play(state.rows.value[0], { target: player })
    await flush()
    assert.equal(requests.length, 1, '票据有效期间续播不应重复加载')
    const shanghai = Date.now() + 60000
    assert.equal(state.fresh({ ...file, expiresAt: new Date(shanghai + 8 * 3600000).toISOString().slice(0, -1) }), true, '无时区业务时间必须按上海解释')
    app.unmount()
    assert.ok(player.pauseCalls > 1, '离页必须停止播放')
  }
  for (const invalidate of [view => { view.files.value = [] }, view => view.app.unmount(), () => setRequestContext(2)]) {
    requests = []
    const view = mount()
    let finish
    respond = () => new Promise(resolve => { finish = resolve })
    const pending = view.state.access(view.state.rows.value[0])
    invalidate(view)
    await nextTick()
    finish({ ok: true, json: async () => ({ ...file, url: '/api/files/1?ticket=stale-response', expiresAt: '2099-01-01T00:00:00' }) })
    await assert.rejects(pending, error => error.name === 'AbortError', '旧附件、离页或切换校区的响应必须失效')
    assert.ok(!view.state.rows.value.some(row => row.url.includes('stale-response')))
    view.app.unmount()
  }
  respond = null
  {
    requests = []
    const { state, app } = mount()
    const player = media()
    player.paused = true
    state.error(state.rows.value[0], { target: player })
    await flush()
    state.error(state.rows.value[0], { target: player })
    await flush()
    assert.equal(requests.length, 1, '坏文件不能无限刷新票据')
    assert.equal(state.failed.value[1], true, '刷新后仍失败必须提供明确重试入口')
    app.unmount()
  }
  console.log('PASS：短票据刷新、上海时间、音视频进度和离页/换校区旧响应隔离')
  console.log('PASS：语音详情、可移除预览、旧文件类型和视频均提供正确的播放控件，授权地址保持完整')
} finally {
  await server.close()
}
