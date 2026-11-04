<script setup lang="ts">
import SoftSelect from './SoftSelect.vue'
import { nextTick, ref, watch, onBeforeUnmount } from 'vue'
import { dialog, closeDialog, notify, type FormValues } from '../lib/ui'
import { post } from '../lib/api'
import type { Attachment } from '../lib/types'
import AppIcon from './AppIcon.vue'
import AttachmentList from './AttachmentList.vue'
import VoiceRecorder from '../mobile/VoiceRecorder.vue'
import { mobileApp } from '../mobile/platform'
const values = ref<FormValues>({}); const error = ref(''); const busy = ref(false); const uploading = ref(false); const modal = ref<HTMLElement>(); const files = ref<Record<string, Attachment[]>>({})
const recording=ref(false)
let previousFocus: HTMLElement | null = null
watch(dialog, async config => {
  error.value = ''; busy.value = false; uploading.value = false; recording.value=false; files.value = {}; values.value = { ...(config?.initial || {}) }
  config?.fields?.forEach(field => { if (values.value[field.key] === undefined) values.value[field.key] = field.type === 'multi' || field.type === 'file' ? [] : '' })
  document.body.style.overflow = config ? 'hidden' : ''
  if (config) { previousFocus = document.activeElement as HTMLElement; await nextTick(); modal.value?.querySelector<HTMLElement>('input,textarea,select,button')?.focus() }
  else previousFocus?.focus()
})
function keydown(e: KeyboardEvent) {
  if (!dialog.value) return
  if (e.key === 'Escape' && !busy.value && !uploading.value && !recording.value) closeDialog()
  if (e.key === 'Tab') {
    const nodes = modal.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]')
    if (!nodes?.length) return
    const first = nodes[0], last = nodes[nodes.length - 1]
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus() }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus() }
  }
}
document.addEventListener('keydown', keydown)
onBeforeUnmount(() => { document.removeEventListener('keydown', keydown); document.body.style.overflow = '' })
async function upload(event: Event, key: string) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  await uploadFiles(Array.from(input.files),key)
  input.value=''
}
async function uploadFiles(selected:File[],key:string){
  if(uploading.value||busy.value)return
  const current=dialog.value
  uploading.value = true; error.value = ''
  try {
    for (const file of selected) {
      if (file.size > 20 * 1024 * 1024) throw new Error('单个文件不能超过 20MB。')
      if (!/^(image|audio|video)\//.test(file.type)) throw new Error('仅支持图片、音频或视频文件。')
      const form = new FormData(); form.append('file', file)
      const result = await post<Attachment>('/files', form)
      if(dialog.value!==current)return
      ;(files.value[key] ||= []).push(result); values.value[key].push(result.id)
    }
  } catch (err) { error.value = (err as Error).message }
  finally { if(dialog.value===current)uploading.value = false }
}
async function submit() {
  if (busy.value || uploading.value || recording.value || !dialog.value) return
  for (const field of dialog.value.fields || []) {
    const value = values.value[field.key]
    if (field.required && (value === '' || value == null || (Array.isArray(value) && !value.length))) { error.value = `请填写${field.label}`; return }
    if (field.type === 'number' && value !== '') {
      const num = Number(value)
      if (!Number.isFinite(num) || (field.min !== undefined && num < field.min) || (field.max !== undefined && num > field.max)) { error.value = `${field.label}超出允许范围`; return }
      values.value[field.key] = num
    }
  }
  busy.value = true; error.value = ''
  try { const config = dialog.value; await config.submit?.(values.value); if (config.success) notify(config.success); closeDialog(true) }
  catch (err) { error.value = (err as Error).message }
  finally { busy.value = false }
}
</script>
<template>
  <Teleport to="body"><div v-if="dialog" class="modal-overlay form-overlay" @mousedown.self="!busy && !uploading && !recording && closeDialog()"><section ref="modal" class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <header class="modal-header"><div><span class="eyebrow">{{ dialog.danger ? '请确认此操作' : mobileApp ? '天健悦成长' : '晚辅智管' }}</span><h2 id="modal-title">{{ dialog.title }}</h2></div><button class="icon-btn" aria-label="关闭弹窗" :disabled="busy || uploading || recording" @click="closeDialog()"><AppIcon name="X" /></button></header>
    <p v-if="dialog.description" class="modal-description">{{ dialog.description }}</p>
    <form @submit.prevent="submit"><div class="modal-body"><component :is="field.type === 'file' || field.type === 'multi' ? 'div' : 'label'" v-for="field in dialog.fields" :key="field.key" class="field"><span>{{ field.label }}<em v-if="field.required"> *</em></span>
      <textarea v-if="field.type === 'textarea'" v-model="values[field.key]" rows="3" :required="field.required" :placeholder="field.placeholder" />
      <SoftSelect v-else-if="field.type === 'select'" v-model="values[field.key]" :required="field.required" :aria-label="field.label" :options="field.options || []" />
      <div v-else-if="field.type === 'multi'" class="multi-options"><label v-for="option in field.options" :key="option.value"><input v-model="values[field.key]" type="checkbox" :value="option.value" />{{ option.label }}</label><span v-if="!field.options?.length" class="muted">没有可选记录</span></div>
      <div v-else-if="field.type === 'file'"><div class="upload-box"><AppIcon name="UploadCloud" :size="24" /><span>{{ uploading ? '正在上传…' : '选择图片、音频或视频' }}</span><input type="file" accept="image/*,audio/*,video/*" multiple :disabled="uploading||recording" @change="upload($event, field.key)" /><AttachmentList class="uploaded-media" :files="files[field.key]" removable @remove="id => { values[field.key] = values[field.key].filter((value: number) => value !== id); files[field.key] = files[field.key]!.filter(file => file.id !== id) }" /></div><template v-if="mobileApp"><div class="native-camera"><label class="btn secondary">拍照<input type="file" accept="image/*" capture="environment" :disabled="uploading||recording" @change="upload($event,field.key)" /></label><label class="btn secondary">拍视频<input type="file" accept="video/*" capture="environment" :disabled="uploading||recording" @change="upload($event,field.key)" /></label></div><VoiceRecorder :disabled="uploading||busy" @busy="value=>recording=value" @file="file=>uploadFiles([file],field.key)" /></template></div>
      <input v-else v-model="values[field.key]" :type="field.type || 'text'" :required="field.required" :min="field.min" :max="field.max" :step="field.step || (field.type === 'number' ? 1 : undefined)" :placeholder="field.placeholder" />
      <small v-if="field.hint">{{ field.hint }}</small>
    </component><div v-if="error" class="inline-error" role="alert"><AppIcon name="CircleAlert" :size="17" />{{ error }}</div></div>
    <footer class="modal-footer"><button type="button" class="btn secondary" :disabled="busy || uploading || recording" @click="closeDialog()">取消</button><button type="submit" class="btn" :class="dialog.danger ? 'danger' : 'primary'" :disabled="busy || uploading || recording"><span v-if="busy" class="spinner small"></span>{{ busy ? '正在提交…' : dialog.confirm || '保存' }}</button></footer></form>
  </section></div></Teleport>
</template>
<style scoped>
.uploaded-media{width:100%;text-align:left}
</style>
