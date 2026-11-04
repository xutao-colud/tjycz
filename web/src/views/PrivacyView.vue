<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { api, post, datetime, contextVersion } from '../lib/api'
import { isAdmin, isParent } from '../lib/state'
import { act, openDialog, useResource } from '../lib/ui'
import type { Student } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import SoftSelect from '../components/SoftSelect.vue'
import LoadState from '../components/LoadState.vue'

interface Policy { version:string; consentRequired?:boolean; serviceText:string; mediaText:string; retentionText:string; externalConfiguration:string[] }
interface Consent { status:string; policyVersion?:string; createdAt?:string }
interface ConsentState { service:Consent; media:Consent; effectiveService:boolean; effectiveMedia:boolean; history:{id:number;scope:string;granted:boolean;createdAt:string;policyVersion:string}[] }
interface PrivacyRequest { id:number; studentId?:number; studentName:string; type:string; status:string; reason?:string; reviewNote?:string; createdAt:string; completedAt?:string; canExport?:boolean }
const selected=ref<string|number>(''), exportText=ref(''), blockers=ref<Record<number,string[]>>({})
let generation=0, disposed=false
watch([contextVersion,selected],()=>{ generation++; exportText.value=''; blockers.value={} },{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;generation++})
const current=(value:number)=>!disposed&&value===generation
const students=useResource(()=>api<Student[]>('/students'))
const policy=useResource(()=>api<Policy>('/privacy/policy'))
const requests=useResource(()=>isParent.value||isAdmin.value?api<PrivacyRequest[]>('/privacy/requests'):Promise.resolve([]))
watch(students.data,rows=>{ if(!rows?.some(s=>s.id===Number(selected.value))) selected.value=rows?.[0]?.id??'' })
const consent=useResource(()=>selected.value?api<ConsentState>(`/privacy/consents?studentId=${selected.value}`):Promise.resolve(null),[selected])
const child=computed(()=>students.data.value?.find(s=>s.id===Number(selected.value)))
const captions:Record<string,string>={OPEN:'待审核',APPROVED:'已审核，待处理',REJECTED:'未通过',COMPLETED:'已完成',GRANTED:'已同意',WITHDRAWN:'已撤回',UNSET:'尚未选择'}
const caption=(value:string)=>captions[value]||value
async function grant(scope:string,granted:boolean) {
  const studentId=Number(selected.value),name=child.value?.name
  if(!studentId||!policy.data.value||policy.data.value.consentRequired===false) return
  const title=granted?'确认授权':'撤回授权'
  if(await openDialog({title,description:`${name} · ${scope==='MEDIA'?'图片、录音与视频':'托管服务信息处理'}\n${granted?'我已阅读当前版本说明，并以该孩子绑定监护人的身份作出选择。':'撤回后将暂停该孩子的相关媒体记录与查看；必要的账目与申请审计仍会保留。'}`,confirm:granted?'同意并记录授权':'确认撤回',danger:!granted,submit:()=>post('/privacy/consents',{studentId,scope,policyVersion:policy.data.value!.version,granted}),success:granted?'授权已记录':'授权已撤回'})) consent.refresh()
}
async function requestData(type:string) {
  const studentId=Number(selected.value)
  if(!studentId) return
  if(await openDialog({title:type==='EXPORT'?'申请孩子的资料副本':'申请资料脱敏',description:`${child.value?.name} · ${type==='EXPORT'?'副本仅包含该孩子的资料，管理员审核后可以查看和下载。':'提交申请不会立即处理资料。管理员核对离校、费用和安全事项，审核后还须再次确认执行。'}`,fields:[{key:'reason',label:'申请说明',type:'textarea',required:true}],confirm:'提交申请',submit:v=>post('/privacy/requests',{studentId,type,reason:v.reason}),success:'申请已提交'})) requests.refresh()
}
async function review(row:PrivacyRequest) {
  if(await openDialog({title:'审核资料申请',description:`${row.studentName} · ${row.type==='EXPORT'?'资料副本':'资料脱敏'}\n${row.reason}`,fields:[{key:'decision',label:'审核结果',type:'select',required:true,options:[{value:'APPROVE',label:'同意申请'},{value:'REJECT',label:'不予通过'}]},{key:'note',label:'核对说明',type:'textarea',required:true}],initial:{decision:'APPROVE'},submit:v=>post(`/privacy/requests/${row.id}/review`,v),success:'审核结果已保存'})) requests.refresh()
}
async function viewExport(row:PrivacyRequest) {
  const started=generation
  let result:unknown
  if(await act(async()=>{result=await post(`/privacy/requests/${row.id}/export`)},'资料副本已生成，仅供当前授权账号查看')&&current(started)) {exportText.value=JSON.stringify(result,null,2);await requests.refresh()}
}
function downloadExport() {
  const url=URL.createObjectURL(new Blob([exportText.value],{type:'application/json;charset=utf-8'}))
  const anchor=document.createElement('a'); anchor.href=url; anchor.download='孩子的资料副本.json'; anchor.click(); setTimeout(()=>URL.revokeObjectURL(url),0)
}
async function erase(row:PrivacyRequest) {
  const started=generation
  await act(async()=>{
    const check=await api<{blocks:string[]}>(`/privacy/requests/${row.id}/erasure-check`)
    if(!current(started)) return
    blockers.value[row.id]=check.blocks
    if(check.blocks.length) throw new Error('当前不能执行脱敏，请先处理卡片中的待办事项。')
    if(await openDialog({title:'确认执行资料脱敏',description:`${row.studentName}\n执行后将移除孩子的姓名、联系方式和学习正文，关闭相关媒体访问。必要的脱敏账目与申请审计保留，共用附件不连带删除。请完成最终核对。`,danger:true,fields:[{key:'confirmation',label:'请输入“确认脱敏”',required:true}],confirm:'执行脱敏',submit:v=>{if(v.confirmation!=='确认脱敏') throw new Error('请输入“确认脱敏”'); return post(`/privacy/requests/${row.id}/erase`,{confirmed:true,note:'管理员已完成离校、费用与安全核对，并明确确认脱敏'})},success:'资料脱敏已完成'})&&current(started)) { exportText.value=''; await Promise.all([requests.refresh(),students.refresh()]) }
  },'保护条件已核对')
}
</script>

<template>
  <PageHeader title="隐私与数据" subtitle="查看孩子的信息说明，按需申请资料副本" />
  <div v-if="!isParent&&!isAdmin" class="info-banner">孩子资料按任教班级提供，资料申请由校区管理员处理。</div>
  <LoadState :loading="policy.loading.value||students.loading.value" :error="policy.error.value||students.error.value" @retry="() => {policy.refresh();students.refresh()}">
    <section v-if="policy.data.value" class="panel privacy-panel">
      <div class="panel-header"><div><h2>孩子的信息处理说明</h2><p>当前版本 {{ policy.data.value.version }}</p></div><SoftSelect v-model="selected" aria-label="选择孩子" :options="(students.data.value||[]).map(s=>({value:s.id,label:`${s.name} · ${s.className}`}))" placeholder="暂无可查看的孩子" /></div>
      <div class="privacy-policy"><p>{{ policy.data.value.serviceText }}</p><p>{{ policy.data.value.mediaText }}</p><p>{{ policy.data.value.retentionText }}</p></div>
      <div v-if="selected" class="privacy-grants">
        <div v-if="policy.data.value.consentRequired!==false&&consent.error.value" class="inline-error">{{ consent.error.value }}</div>
        <template v-if="policy.data.value.consentRequired!==false"><article v-for="scope in ['SERVICE','MEDIA']" :key="scope" class="privacy-grant">
          <div><h3>{{ scope==='SERVICE'?'托管服务信息处理':'图片、录音与视频' }}</h3><p>我的选择：{{ caption((scope==='SERVICE'?consent.data.value?.service:consent.data.value?.media)?.status||'UNSET') }}</p><p>孩子当前状态：{{ (scope==='SERVICE'?consent.data.value?.effectiveService:consent.data.value?.effectiveMedia)?'授权有效':'尚未授权或已暂停' }}</p></div>
          <div v-if="isParent" class="heading-actions"><button class="btn secondary" @click="grant(scope,true)">阅读后同意</button><button class="text-btn" @click="grant(scope,false)">撤回授权</button></div>
        </article></template>
        <div v-if="isParent" class="heading-actions privacy-request-actions"><button class="btn secondary" @click="requestData('EXPORT')">申请资料副本</button><button class="btn secondary" @click="requestData('ERASURE')">申请资料脱敏</button></div>
        <details v-if="policy.data.value.consentRequired!==false&&isParent&&consent.data.value?.history.length" class="privacy-history"><summary>查看我的授权记录（最近 100 次）</summary><p v-for="item in consent.data.value.history" :key="item.id">{{ datetime(item.createdAt) }} · {{ item.scope==='MEDIA'?'媒体记录':'服务信息' }} · {{ item.granted?'同意':'撤回' }} · {{ item.policyVersion }}</p></details>
      </div>
    </section>
  </LoadState>
  <section v-if="isParent||isAdmin" class="panel privacy-panel">
    <div class="panel-header"><div><h2>{{ isAdmin?'资料申请处理':'我的资料申请' }}</h2><p>审核通过后仍须单独执行，脱敏前会再次核对保护条件</p></div><button class="text-btn" @click="requests.refresh">刷新</button></div>
    <LoadState :loading="requests.loading.value" :error="requests.error.value" :empty="!requests.data.value?.length" @retry="requests.refresh">
      <article v-for="row in requests.data.value" :key="row.id" class="privacy-request">
        <div class="privacy-request-title"><h3>{{ row.studentName }} · {{ row.type==='EXPORT'?'资料副本':'资料脱敏' }}</h3><span class="badge" :class="row.status==='COMPLETED'?'green':'gray'">{{ caption(row.status) }}</span></div>
        <p>{{ row.reason }}</p><p v-if="row.reviewNote">核对说明：{{ row.reviewNote }}</p><small>{{ datetime(row.createdAt) }}</small>
        <ul v-if="blockers[row.id]?.length"><li v-for="block in blockers[row.id]" :key="block">{{ block }}</li></ul>
        <div class="heading-actions privacy-row-actions"><button v-if="isAdmin&&row.status==='OPEN'" class="btn secondary" @click="review(row)">审核申请</button><button v-if="row.canExport!==false&&row.type==='EXPORT'&&['APPROVED','COMPLETED'].includes(row.status)" class="btn primary" @click="viewExport(row)">查看资料副本</button><button v-if="isAdmin&&row.type==='ERASURE'&&row.status==='APPROVED'" class="btn danger" @click="erase(row)">核对并执行脱敏</button></div>
      </article>
    </LoadState>
  </section>
  <section v-if="exportText" class="panel privacy-panel"><div class="panel-header"><h2>孩子的资料副本</h2><div class="heading-actions"><button class="btn secondary" @click="downloadExport">下载资料文件</button><button class="text-btn" @click="exportText=''">收起</button></div></div><pre class="privacy-export">{{ exportText }}</pre></section>
  <div v-if="policy.data.value" class="privacy-external"><strong>机构正式启用前需完成</strong><p v-for="item in policy.data.value.externalConfiguration" :key="item">{{ item }}</p></div>
</template>

<style scoped>
.privacy-panel{margin-bottom:22px}.privacy-policy,.privacy-grants{padding:0 24px 24px}.privacy-policy p{color:var(--text-secondary);line-height:1.9;margin:10px 0}.privacy-grant{display:flex;align-items:center;justify-content:space-between;gap:18px;border-top:1px solid var(--border);padding:22px 0}.privacy-grant p{color:var(--muted);margin-top:7px}.privacy-request-actions{justify-content:flex-start;margin-top:12px}.privacy-history{margin-top:24px;color:var(--text-secondary)}.privacy-history summary{cursor:pointer}.privacy-history p{margin-top:12px}.privacy-request{padding:23px 25px;border-top:1px solid var(--border)}.privacy-request-title{display:flex;align-items:center;justify-content:space-between;gap:18px}.privacy-request p{color:var(--text-secondary);line-height:1.85;margin-top:12px;white-space:pre-wrap;overflow-wrap:anywhere}.privacy-request small{display:block;color:var(--muted);margin-top:10px}.privacy-row-actions{margin-top:18px;justify-content:flex-start}.privacy-request ul{color:#b34a45}.privacy-export{margin:0 24px 24px;padding:20px;border-radius:16px;max-height:500px;overflow:auto;background:#f5f5f7;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.privacy-external{color:var(--muted);font-size:12px;line-height:1.9}.privacy-external p{margin-top:5px}@media(max-width:760px){.privacy-panel .panel-header,.privacy-grant{align-items:flex-start;flex-direction:column}.privacy-panel .soft-select{width:100%}.privacy-policy,.privacy-grants{padding-left:20px;padding-right:20px}}
</style>
