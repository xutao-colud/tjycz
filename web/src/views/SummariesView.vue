<script setup lang="ts">
import SoftSelect from '../components/SoftSelect.vue'
import { ref } from 'vue'
import { api, post, query, datetime } from '../lib/api'
import { isParent, selectedDate } from '../lib/state'
import { useResource, loadStudents, openDialog, studentOptions, label } from '../lib/ui'
import type { Summary } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import LoadState from '../components/LoadState.vue'
import StatusBadge from '../components/StatusBadge.vue'
const studentId=ref<string | number>(''); const students=useResource(loadStudents)
const {data,loading,error,refresh}=useResource(()=>api<Summary[]>(`/summaries${query({date:selectedDate.value,studentId:studentId.value})}`),[selectedDate,studentId])
const historyActions:Record<string,string>={GENERATE:'生成草稿',REFRESH:'刷新草稿',EDIT:'编辑内容',SUBMIT:'提交审核',WITHDRAW:'撤回待审',APPROVE:'审核通过',REJECT:'退回修改',PUBLISH:'发布给家长'}
function content(value:unknown) { return typeof value==='string'?value:JSON.stringify(value,null,2) }
async function generate() {
  if(await openDialog({title:'生成今日成长小结',description:'汇总选中学生当日记录。仅草稿和退回的小结可刷新；待审、已通过和已发布的内容保持不变。',fields:[{key:'date',label:'小结日期',type:'date',required:true},{key:'studentIds',label:'选择学生',type:'multi',required:true,options:studentOptions(students.data.value||[])}],initial:{date:selectedDate.value,studentIds:studentId.value?[Number(studentId.value)]:(students.data.value||[]).map(s=>s.id)},submit:v=>post('/summaries/generate',v),success:'草稿生成操作已完成，请编辑后提交独立审核'})) refresh()
}
async function edit(item:Summary) {
  if(await openDialog({title:`编辑 ${item.studentName} 的小结`,description:'保存后请提交独立审核。待审、通过审核和已发布的内容不能直接修改。',fields:[{key:'content',label:'小结正文',type:'textarea',required:true,hint:'最多12000字'},{key:'teacherComment',label:'老师寄语',type:'textarea'}],initial:{content:content(item.content),teacherComment:item.teacherComment||''},submit:v=>{if(String(v.content).trim().length>12000) throw new Error('小结正文最多12000字');return api(`/summaries/${item.id}`,{method:'PATCH',body:{...v,expectedVersion:item.version}})},success:'小结已保存，可提交独立审核'})) refresh()
}
async function submitReview(item:Summary) {
  if(await openDialog({title:'提交独立审核',description:`${item.studentName} · ${item.date}\n提交后正文和寄语将冻结，请由另一位校长或老板审核。需要修改时，提交者可撤回待审稿。`,confirm:'提交审核',submit:()=>post(`/summaries/${item.id}/submit-review`,{expectedVersion:item.version}),success:'已提交，请等待校长或老板审核'})) refresh()
}
async function withdrawReview(item:Summary) {
  if(await openDialog({title:'撤回待审小结',description:'撤回后可继续编辑，再次提交时将保留本次提交和撤回记录。',confirm:'确认撤回',submit:()=>post(`/summaries/${item.id}/withdraw-review`,{expectedVersion:item.version}),success:'已撤回，可继续编辑'})) refresh()
}
async function review(item:Summary) {
  if(await openDialog({title:`审核 ${item.studentName} 的小结`,description:`请核对页面所示正文和教师寄语。提交者：${item.submittedName || '—'} · 稿件版本 ${item.reviewVersion ?? item.version}`,fields:[{key:'approved',label:'审核结论',type:'select',required:true,options:[{value:'yes',label:'审核通过'},{value:'no',label:'退回修改'}]},{key:'comment',label:'审核意见',type:'textarea',required:true,placeholder:'说明核对结果或需要修改的内容'}],initial:{approved:'yes',comment:''},confirm:'保存审核结论',submit:v=>post(`/summaries/${item.id}/review`,{expectedVersion:item.version,approved:v.approved==='yes',comment:v.comment}),success:'审核结论已保存'})) refresh()
}
async function publish(item:Summary) {
  const issues=(await api<{status:string;title:string;reason:string;measures:string}[]>(`/follow-ups${query({date:item.date,studentId:item.studentId,kind:'ISSUE'})}`)).filter(i=>['OPEN','IN_PROGRESS'].includes(i.status))
  if(issues.length&&!await openDialog({title:`核对 ${item.studentName} 的关注事项`,description:issues.map(i=>`${i.title}：${i.reason}\n已采取措施：${i.measures}`).join('\n\n'),confirm:'已逐项核对',submit:async()=>undefined})) return
  if(await openDialog({title:'发布审核通过的小结',description:`${item.studentName} · ${item.date}\n将发布 ${item.reviewedName || '审核人'} 已通过的固定内容，家长即可查看和反馈。`,confirm:'确认发布',submit:()=>post(`/summaries/${item.id}/publish`,{confirmedConcerns:issues.length>0}),success:'小结已发布，可在小程序内查看'})) refresh()
}
async function feedback(item:Summary) {
  if(await openDialog({title:'给今天的陪伴一个回应',description:`${item.studentName} · ${item.date}\n不满意时请填写原因，帮助老师有针对性地跟进。`,fields:[{key:'satisfaction',label:'您的感受',type:'select',required:true,options:[{value:'SATISFIED',label:'满意'},{value:'DISSATISFIED',label:'不满意'}]},{key:'text',label:'反馈原因（不满意时必填）',type:'textarea'}],initial:{satisfaction:item.feedbackSatisfaction||'SATISFIED',text:item.feedbackText||''},submit:v=>{if(v.satisfaction==='DISSATISFIED'&&!String(v.text||'').trim())throw new Error('请填写不满意的原因');return post(`/summaries/${item.id}/feedback`,v)},success:'反馈已提交，感谢您的回应'})) refresh()
}

</script>
<template>
  <PageHeader title="每日小结" subtitle="认真记录，独立核对，让家长安心。"><button v-if="!isParent" class="btn primary" @click="generate"><AppIcon name="Sparkles" :size="17" />生成成长小结</button></PageHeader>
  <section class="panel">
    <div class="filter-bar"><SoftSelect v-model="studentId" aria-label="小结学生" :options="[{value:'',label:'全部学生'}, ...(students.data.value || []).map(s => ({value:s.id,label:s.name}))]" /><span class="filter-count">{{ selectedDate }} · {{ data?.length||0 }} 份小结</span><button class="icon-btn" aria-label="刷新小结" @click="refresh"><AppIcon name="RefreshCw" :size="17" /></button></div>
    <LoadState :loading="loading" :error="error" :empty="!data?.length" title="今天的成长故事，等待被写下" :description="isParent?'小结经审核并发布后，您将在这里看到孩子的每日成长记录。':'生成草稿、编辑内容、提交独立审核，通过后即可发布。'" @retry="refresh">
      <div class="summary-grid">
        <article v-for="(item,index) in data" :key="item.id" class="summary-card">
          <div class="summary-card-header"><div class="person"><span class="student-avatar" :class="`avatar-${index%4}`">{{ item.studentName.slice(-2) }}</span><div><strong>{{ item.studentName }}的成长日记</strong><small>{{ item.campusName }} · {{ item.date }}</small></div></div><StatusBadge :value="item.status" /></div>
          <div class="summary-content prose">{{ content(item.content) }}</div>
          <div class="teacher-note"><AppIcon name="Quote" :size="20" /><div><small>老师寄语</small><p>{{ item.teacherComment||'老师暂未补充寄语。' }}</p></div></div>
          <div v-if="!isParent" class="summary-review-note" :class="{'review-rejected':item.status==='REJECTED'}">
            <template v-if="item.status==='IN_REVIEW'"><strong>等待独立审核</strong><p>{{ item.submittedName }} 于 {{ datetime(item.submittedAt) }} 提交。正文和寄语已冻结；提交者可撤回修改。</p></template>
            <template v-else-if="item.status==='APPROVED' || item.status==='REJECTED'"><strong>{{ item.status==='APPROVED'?'审核通过，待发布':'已退回，请修改后重新提交' }}</strong><p>{{ item.reviewedName }} · {{ datetime(item.reviewedAt) }}</p><p>{{ item.reviewComment }}</p></template>
            <p v-else-if="item.canEdit">编辑后请提交给另一位校长或老板审核，通过后再发布给家长。</p>
          </div>
          <div v-if="!isParent && item.reviewHistory?.length" class="summary-review-history">
            <details><summary>提交与审核记录 · {{ item.reviewHistory.length }}</summary><article v-for="entry in item.reviewHistory" :key="entry.id" class="summary-history-entry"><strong>{{ historyActions[entry.action] || entry.action }} · {{ entry.actorName }}</strong><small>{{ datetime(entry.createdAt) }} · 版本 {{ entry.version }} · {{ label(entry.toStatus) }}</small><p v-if="entry.comment">{{ entry.comment }}</p><details><summary>查看此版本内容</summary><p class="history-content">{{ entry.content }}</p><p v-if="entry.teacherComment" class="history-content">教师寄语：{{ entry.teacherComment }}</p></details></article></details>
          </div>
          <div v-if="item.feedbackSatisfaction" class="feedback-note"><AppIcon name="HeartHandshake" :size="17" /><div><strong>家长反馈 · {{ item.feedbackSatisfaction==='SATISFIED'?'满意':'不满意' }}</strong><p>{{ item.feedbackText || '家长已选择满意。' }}</p></div></div>
          <div class="summary-meta"><span>记录版本 {{ item.version }}<template v-if="item.status==='PUBLISHED'"> · {{ datetime(item.publishedAt) }}</template></span><span v-if="item.status==='PUBLISHED'">通知：{{ label(item.notificationStatus) }}</span></div>
          <footer class="card-bottom"><span class="muted small-text">{{ item.status==='APPROVED'?'审核内容已固定':item.status==='PUBLISHED'?'已发布内容将保留':'每一点进步，都值得认真记录' }}</span><div class="row-actions"><button v-if="item.canEdit" class="btn secondary small-btn" @click="edit(item)">编辑小结</button><button v-if="item.canSubmit" class="btn primary small-btn" @click="submitReview(item)">提交审核</button><button v-if="item.canWithdraw" class="btn secondary small-btn" @click="withdrawReview(item)">撤回修改</button><button v-if="item.canReview" class="btn primary small-btn" @click="review(item)">审核小结</button><button v-if="item.canPublish" class="btn primary small-btn" @click="publish(item)">发布小结</button><button v-if="isParent&&item.status==='PUBLISHED'" class="btn primary small-btn" @click="feedback(item)">回应老师</button></div></footer>
        </article>
      </div>
    </LoadState>
  </section>
</template>
<style scoped>
.summary-review-note{margin:12px 0;padding:14px 16px;background:#f3f5f9;border-radius:14px;color:#626570;font-size:13px;line-height:1.8}
.summary-review-note:empty{display:none}.summary-review-note strong{color:#465b76}.summary-review-note p+p{margin-top:5px}
.review-rejected{background:#fff3ef}.review-rejected strong{color:#b85b48}
.summary-review-history{margin:12px 0;color:#626570;font-size:12px;line-height:1.8}.summary-review-history summary{cursor:pointer;color:#47709d;padding:6px 0}
.summary-history-entry{padding:12px 0;border-bottom:1px solid var(--border)}.summary-history-entry strong,.summary-history-entry small{display:block}.summary-history-entry small{color:#8a8d97}
.history-content{white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:#f7f7fa;border-radius:12px;margin-top:8px}.summary-card .row-actions{flex-wrap:wrap;justify-content:flex-end}
</style>
