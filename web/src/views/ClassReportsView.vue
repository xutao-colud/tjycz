<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, post, query, datetime, requestId } from '../lib/api'
import { selectedDate, isAdmin, roleName } from '../lib/state'
import { openDialog, useResource } from '../lib/ui'
import type { ClassReportPage } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import SoftSelect from '../components/SoftSelect.vue'
import LoadState from '../components/LoadState.vue'

const route=useRoute(),classId=ref<string|number>(String(route.query.classId||''))
const {data,loading,error,refresh}=useResource(()=>api<ClassReportPage>('/class-reports'+query({date:selectedDate.value,classId:classId.value})),[selectedDate,classId])
const classroom=computed(()=>data.value?.classes.find(c=>c.id===data.value?.classId))
async function edit() {
  const page=data.value
  if(!page?.classId||page.readOnly)return
  const report=page.report
  if(await openDialog({title:`${classroom.value?.name||'班级'} · ${page.date}`,description:'记录本班每天的辅导、课堂表现与活动情况，校长和老板可按校区权限查看。',fields:[{key:'content',label:'班级日报',type:'textarea',required:true,hint:'可记录作业辅导、学习习惯、体能活动等，最多6000字',placeholder:'今天班级的整体情况与进展…'},{key:'followUp',label:'需跟进事项',type:'textarea',hint:'最多2000字',placeholder:'需要继续辅导或沟通的事项…'},{key:'nextPlan',label:'后续安排',type:'textarea',hint:'最多2000字',placeholder:'下一次辅导、活动或沟通安排…'}],initial:{content:report?.content||'',followUp:report?.followUp||'',nextPlan:report?.nextPlan||''},confirm:'保存班级日报',submit:body=>api(`/class-reports/${page.classId}`+query({date:page.date}),{method:'PUT',body:{...body,expectedVersion:report?.version||0}}),success:'班级日报已保存'}))await refresh()
}
async function comment() {
  const page=data.value;if(!page?.classId||page.readOnly||!isAdmin.value)return
  const id=requestId()
  if(await openDialog({title:`点评 · ${classroom.value?.name}`,description:`${page.date}。点评供对应班级老师查看。`,fields:[{key:'text',label:'班级情况点评',type:'textarea',required:true,hint:'最多2000字'}],confirm:'保存点评',submit:body=>post(`/class-reports/${page.classId}/comments`+query({date:page.date}),{...body,requestId:id}),success:'班级点评已保存'}))await refresh()
}
</script>

<template>
  <PageHeader class="class-report-heading" title="班级日报" subtitle="记录每天的陪伴，让班级的进展清晰可见。"><button v-if="data?.classId" class="btn primary" :disabled="data.readOnly" @click="edit"><AppIcon name="FilePenLine" :size="17" />{{data.report?'编辑日报':'记录本班情况'}}</button></PageHeader>
  <section class="panel class-report-filter"><SoftSelect v-model="classId" aria-label="选择日报班级" :options="(data?.classes||[]).map(c=>({value:c.id,label:`${c.name} · ${c.campusName}`}))" :placeholder="classroom?`${classroom.name} · ${classroom.campusName}`:'暂无授权班级'" /><span>{{selectedDate}}</span><button class="icon-btn" aria-label="刷新班级日报" @click="refresh"><AppIcon name="RefreshCw" :size="17" /></button></section>
  <LoadState :loading="loading" :error="error" :empty="!data?.classId" title="暂无可记录的班级" description="请联系校区管理员分配班级。" @retry="refresh">
    <template v-if="data?.classId"><p v-if="data.readOnly" class="info-banner">未来日期可查看安排，实际情况发生后再记录。</p>
      <section class="class-report-overview"><article><AppIcon name="UsersRound" /><strong>{{data.overview.studentCount}}</strong><span>本班学生</span></article><article><AppIcon name="CalendarCheck2" /><strong>{{data.overview.arrivedCount}} / {{data.overview.studentCount}}</strong><span>已到校 · 含已离校</span></article><article><AppIcon name="NotebookPen" /><strong>{{data.overview.completedHomeworkCount}} / {{data.overview.homeworkCount}}</strong><span>作业完成份数</span></article></section>
      <p class="class-report-caption">{{classroom?.campusName}} · {{classroom?.name}} · 已请假 {{data.overview.leaveCount}} 人</p>
      <article v-if="data.report" class="panel class-report-body"><header><div><span class="eyebrow">{{data.date}}</span><h2>{{classroom?.name}}的今日记录</h2></div><span class="badge green">已记录</span></header><section><h3>班级情况</h3><p>{{data.report.content}}</p></section><section v-if="data.report.followUp"><h3>需跟进事项</h3><p>{{data.report.followUp}}</p></section><section v-if="data.report.nextPlan"><h3>后续安排</h3><p>{{data.report.nextPlan}}</p></section><footer><span>记录人 {{data.report.createdName}}</span><span>最近更新 {{data.report.updatedName}} · {{datetime(data.report.updatedAt)}}</span></footer></article>
      <article v-else class="panel class-report-empty"><AppIcon name="ClipboardList" :size="34" /><h2>今天的班级故事，等你记录</h2><p>写下辅导进展、同学们的表现和需要继续关注的事。</p><button class="btn primary" :disabled="data.readOnly" @click="edit">记录本班情况</button></article>
      <section class="panel class-report-comments"><header><h2>校长点评</h2><button v-if="isAdmin" class="btn secondary" :disabled="data.readOnly" @click="comment">点评班级</button></header><article v-for="note in data.comments||[]" :key="note.id"><strong>{{note.actorName}} · {{roleName[note.actorRole]}}</strong><p>{{note.text}}</p><small>{{datetime(note.createdAt)}}</small></article><p v-if="!data.comments?.length" class="muted">暂无校长点评</p></section>
    </template>
  </LoadState>
</template>

<style scoped>
.class-report-filter{display:flex;align-items:center;gap:14px;padding:16px;margin-bottom:22px}.class-report-filter .soft-select{flex:1;min-width:0}.class-report-filter>span{color:var(--muted);font-size:12px;white-space:nowrap}.class-report-overview{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.class-report-overview article{display:flex;align-items:flex-start;flex-direction:column;gap:13px;padding:24px;background:#edf4fd;border-radius:22px;min-width:0;color:#4284ce}.class-report-overview article:nth-child(2){background:#edf8f2;color:#479570}.class-report-overview article:nth-child(3){background:#f6f0fc;color:#9670c1}.class-report-overview strong{font-size:25px;color:#26344b}.class-report-overview span{font-size:12px;line-height:1.7}.class-report-caption{color:var(--muted);font-size:12px;margin:16px 4px 23px}.class-report-body{padding:28px}.class-report-body header{display:flex;align-items:center;justify-content:space-between;gap:16px}.class-report-body h2{font-size:21px;margin-top:7px}.class-report-body section{margin-top:26px}.class-report-body h3{font-size:14px;color:#557091}.class-report-body p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.95;margin-top:11px;color:var(--text-secondary)}.class-report-body footer{display:flex;flex-wrap:wrap;gap:10px 24px;color:var(--muted);font-size:12px;line-height:1.8;border-top:1px solid var(--border);padding-top:20px;margin-top:28px}.class-report-empty{display:flex;align-items:center;flex-direction:column;text-align:center;padding:44px 24px;gap:18px;color:#8191a7}.class-report-empty h2{font-size:19px;color:#3c4c63}.class-report-empty p{font-size:13px;line-height:1.8}@media(max-width:600px){.class-report-heading{flex-direction:row;align-items:center;gap:12px}.class-report-heading :deep(.eyebrow),.class-report-heading :deep(p){display:none}.class-report-heading :deep(.heading-actions){width:auto;flex-shrink:0}.class-report-heading :deep(h1){margin:0}.class-report-filter{flex-wrap:nowrap}.class-report-filter>span{display:none}.class-report-filter .soft-select{flex-basis:auto}.class-report-overview{gap:8px}.class-report-overview article{padding:18px 10px;gap:10px;border-radius:18px}.class-report-overview strong{font-size:20px}.class-report-overview span{font-size:10px}.class-report-body{padding:22px}.class-report-body h2{font-size:18px}}
.class-report-comments{margin-top:20px;padding:24px}.class-report-comments header{display:flex;align-items:center;justify-content:space-between;gap:14px}.class-report-comments h2{font-size:19px}.class-report-comments article{margin-top:20px;padding-top:20px;border-top:1px solid var(--border)}.class-report-comments strong{font-size:13px;color:#557091}.class-report-comments p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;margin-top:12px;color:var(--text-secondary)}.class-report-comments small{display:block;margin-top:10px;color:var(--muted);font-size:12px}
</style>
