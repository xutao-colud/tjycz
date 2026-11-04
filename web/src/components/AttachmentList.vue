<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, contextChanged, contextVersion, isCancelled } from '../lib/api'
import type { Attachment } from '../lib/types'
import AppIcon from './AppIcon.vue'
import { nativeApp,serviceBase } from '../mobile/platform'
import { attachmentUrl } from '../mobile/endpoints'
import { Browser } from '@capacitor/browser'
const props = defineProps<{ files?: Attachment[]; removable?: boolean }>()
const emit = defineEmits<{ remove: [id: number] }>()
const rows = ref<Attachment[]>([])
const failed = ref<Record<number, boolean>>({})
let generation = 0
let alive = true
const requests = new Map<number, Promise<Attachment>>()
const retried = new Set<number>()
const players = new Set<HTMLMediaElement>()
const positions = new Map<number, { media: HTMLMediaElement; time: number; resume: boolean }>()
function reset() {
  generation++
  players.forEach(media => media.pause())
  players.clear(); requests.clear(); retried.clear(); positions.clear(); failed.value = {}
}
watch(() => props.files, files => { reset(); rows.value = (files || []).map(file => ({ ...file,url:attachmentUrl(serviceBase,file.url) })) }, { immediate: true, deep: true })
watch(contextVersion, () => { reset(); rows.value = [] })
onBeforeUnmount(() => { alive = false; reset() })
const isAudio = (file: Attachment) => file.contentType?.startsWith('audio/') || /\.(mp3|m4a|aac|wav|ogg|webm)$/i.test(file.name) && !file.contentType?.startsWith('video/')
const isVideo = (file: Attachment) => file.contentType?.startsWith('video/')
function fresh(file: Attachment) {
  // 服务端业务时间使用上海时区，不能按访问设备的本地时区解释。
  const value = file.expiresAt || ''
  const expires = Date.parse(value && !/(Z|[+-]\d\d:\d\d)$/i.test(value) ? `${value}+08:00` : value)
  return Number.isFinite(expires) && expires > Date.now() + 10000
}
async function access(file: Attachment, force = false): Promise<Attachment> {
  const current = rows.value.find(row => row.id === file.id)
  if (!current) throw contextChanged()
  if (!force && fresh(current)) return current
  const existing = requests.get(file.id)
  if (existing) return existing
  const version = generation
  const request = api<Attachment>(`/files/${file.id}/access`).then(result => {
    if (!alive || version !== generation) throw contextChanged()
    const value = {...result,url:attachmentUrl(serviceBase,result.url)}
    rows.value = rows.value.map(row => row.id === file.id ? { ...row, ...value } : row)
    return value
  }).finally(() => { if (requests.get(file.id) === request) requests.delete(file.id) })
  requests.set(file.id, request)
  return request
}
async function open(file: Attachment, event: MouseEvent) {
  if(nativeApp){
    event.preventDefault()
    try {const value=await access(file);await Browser.open({url:value.url})}
    catch(error){if(!isCancelled(error))failed.value[file.id]=true}
    return
  }
  if (fresh(file)) return
  event.preventDefault()
  const target = window.open('about:blank', '_blank')
  if (target) target.opener = null
  try {
    const value = await access(file)
    if (target) target.location.replace(value.url)
    else failed.value[file.id] = true
  } catch (error) {
    target?.close()
    if (!isCancelled(error)) failed.value[file.id] = true
  }
}
async function reload(file: Attachment, media: HTMLMediaElement, resume: boolean, force = false) {
  const version = generation
  const time = media.currentTime || 0
  players.add(media)
  try {
    await access(file, force)
    if (version !== generation || !alive) return
    positions.set(file.id, { media, time, resume })
    failed.value[file.id] = false
    await nextTick()
    if (version === generation && alive) media.load()
  } catch (error) {
    if (version === generation && !isCancelled(error)) failed.value[file.id] = true
  }
}
function ready(file: Attachment, event: Event) {
  failed.value[file.id] = false
  const position = positions.get(file.id)
  if (!position || position.media !== event.target) return
  positions.delete(file.id)
  try { position.media.currentTime = position.time } catch { /* 尚无可寻址区间时从头播放。 */ }
  if (position.resume) void position.media.play().catch(() => { failed.value[file.id] = true })
}
function prepare(file: Attachment, event: Event) {
  const media = event.currentTarget as HTMLMediaElement
  if (!fresh(file) && media.paused) void reload(file, media, false)
}
function play(file: Attachment, event: Event) {
  const current = event.target as HTMLMediaElement
  players.add(current)
  current.closest('.attachment-list')?.querySelectorAll('audio,video').forEach(media => {
    if (media !== current) (media as HTMLMediaElement).pause()
  })
  if (!fresh(file)) { current.pause(); void reload(file, current, true) }
}
function error(file: Attachment, event: Event) {
  failed.value[file.id] = true
  if (retried.has(file.id)) return
  retried.add(file.id)
  const media = event.target as HTMLMediaElement
  void reload(file, media, !media.paused, true)
}
function retry(file: Attachment, event: Event) {
  const media = (event.currentTarget as HTMLElement).closest('.attachment-item')?.querySelector('audio,video') as HTMLMediaElement | null
  if (media) void reload(file, media, false, true)
  else void access(file, true).then(() => { failed.value[file.id] = false }).catch(error => { if (!isCancelled(error)) failed.value[file.id] = true })
}
</script>
<template>
  <div v-if="rows.length" class="attachment-list">
    <div v-for="file in rows" :key="file.id" class="attachment-item">
      <div class="attachment-heading">
        <a :href="file.url" target="_blank" rel="noopener noreferrer" class="attachment" @click="open(file, $event)"><AppIcon name="Paperclip" :size="15" /><strong>{{ file.name }}</strong><span>{{ (file.size / 1024).toFixed(0) }} KB</span></a>
        <button v-if="removable" type="button" class="text-btn" :aria-label="`移除附件：${file.name}`" @click="emit('remove', file.id)">移除</button>
      </div>
      <audio v-if="isAudio(file)" :src="file.url" controls preload="metadata" :aria-label="`播放音频：${file.name}`" @pointerdown.capture="prepare(file, $event)" @keydown.capture="prepare(file, $event)" @error="error(file, $event)" @loadedmetadata="ready(file, $event)" @play="play(file, $event)">当前浏览器不支持音频播放，请点击文件名打开。</audio>
      <video v-else-if="isVideo(file)" :src="file.url" controls playsinline preload="metadata" :aria-label="`播放视频：${file.name}`" @pointerdown.capture="prepare(file, $event)" @keydown.capture="prepare(file, $event)" @error="error(file, $event)" @loadedmetadata="ready(file, $event)" @play="play(file, $event)">当前浏览器不支持视频播放，请点击文件名打开。</video>
      <div v-if="failed[file.id]" class="attachment-error" role="alert"><span>暂时无法读取附件，请重新加载；登录过期时请重新登录。</span><button type="button" class="text-btn" @click="retry(file, $event)">重新加载</button></div>
    </div>
  </div>
</template>
<style scoped>
.attachment-item{min-width:0;max-width:100%}
.attachment-heading{display:flex;align-items:center;gap:10px;min-width:0}
.attachment-heading .attachment{flex:1;min-width:0}
.attachment strong{font-weight:500;overflow-wrap:anywhere;min-width:0}
.attachment-item audio{display:block;width:100%;max-width:100%;height:44px;margin-top:8px}
.attachment-item video{display:block;width:100%;max-height:320px;background:#172b22;border-radius:6px;margin-top:8px}
.attachment-error{display:flex;align-items:center;gap:12px;color:#ac6549;font-size:11px;padding:8px 0;flex-wrap:wrap}
</style>
