<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, post, query, datetime, requestId } from '../lib/api'
import { selectedDate, currentCampusId } from '../lib/state'
import { openDialog, useResource } from '../lib/ui'
import type { CampusReportPage } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import SoftSelect from '../components/SoftSelect.vue'
import LoadState from '../components/LoadState.vue'

const route=useRoute(),campusId=ref<string|number>(String(route.query.campusId||currentCampusId.value||''))
const {data,loading,error,refresh}=useResource(()=>api<CampusReportPage>('/campus-reports'+query({date:selectedDate.value,campusId:campusId.value})),[selectedDate,campusId])
const campus=computed(()=>data.value?.campuses.find(c=>c.id===data.value?.campusId))
async function edit() {
  const page=data.value;if(!page?.campusId||page.readOnly)return
  const summary=page.summary
  if(await openDialog({title:`${campus.value?.name||'校区'} · ${page.date}`,description:'记录所负责校区的整体运营、教学进展及重点安排。',fields:[{key:'content',label:'校区总结',type:'textarea',required:true,hint:'最多6000字',placeholder:'今天校区的整体情况、教学进展与服务情况…'},{key:'followUp',label:'重点跟进',type:'textarea',hint:'最多2000字'},{key:'nextPlan',label:'后续安排',type:'textarea',hint:'最多2000字'}],initial:{content:summary?.content||'',followUp:summary?.followUp||'',nextPlan:summary?.nextPlan||''},confirm:'保存校区总结',submit:body=>api(`/campus-reports/${page.campusId}`+query({date:page.date}),{method:'PUT',body:{...body,expectedVersion:summary?.version||0}}),success:'校区总结已保存'}))await refresh()
}
async function comment(classId:number,className:string) {
  const page=data.value;if(!page||page.readOnly)return
  const id=requestId()
  if(await openDialog({title:`点评 · ${className}`,description:`${campus.value?.name} · ${page.date}。点评供对应班级老师查看。`,fields:[{key:'text',label:'班级情况点评',type:'textarea',required:true,hint:'最多2000字',placeholder:'肯定做得好的地方，写下需要改进的方向与建议…'}],confirm:'保存点评',submit:body=>post(`/class-reports/${classId}/comments`+query({date:page.date}),{...body,requestId:id}),success:'班级点评已保存'}))await refresh()
}
</script>

<template>
  <PageHeader class="campus-report-heading" title="校区总结" subtitle="看见整体进展，回应每个班级的努力。"><button v-if="data?.campusId" class="btn primary" :disabled="data.readOnly" @click="edit"><AppIcon name="FilePenLine" :size="17" />{{data.summary?'编辑总结':'记录校区总结'}}</button></PageHeader>
  <section class="panel campus-report-filter"><SoftSelect v-model="campusId" aria-label="选择总结校区" :options="(data?.campuses||[]).map(c=>({value:c.id,label:c.name}))" :placeholder="campus?.name||'暂无授权校区'" /><span>{{selectedDate}}</span><button class="icon-btn" aria-label="刷新校区总结" @click="refresh"><AppIcon name="RefreshCw" :size="17" /></button></section>
  <LoadState :loading="loading" :error="error" :empty="!data?.campusId" title="暂无授权校区" description="请联系老板分配校区管理权限。" @retry="refresh">
    <template v-if="data?.campusId"><p v-if="data.readOnly" class="info-banner">实际情况发生后再记录，未来日期仅供查看。</p>
      <section class="campus-report-metrics"><article><strong>{{data.overview.arrivedCount}} / {{data.overview.studentCount}}</strong><span>学生到校 · 含已离校</span></article><article><strong>{{data.overview.checkedInTeacherCount}} / {{data.overview.teacherCount}}</strong><span>老师签到</span></article><article><strong>{{data.overview.completedHomeworkCount}} / {{data.overview.homeworkCount}}</strong><span>作业完成份数</span></article></section>
      <article class="panel campus-report-summary"><header><div><small>{{campus?.name}} · {{data.date}}</small><h2>校区今日总结</h2></div><span class="badge" :class="data.summary?'green':'gray'">{{data.summary?'已记录':'待记录'}}</span></header><template v-if="data.summary"><p>{{data.summary.content}}</p><h3 v-if="data.summary.followUp">重点跟进</h3><p v-if="data.summary.followUp">{{data.summary.followUp}}</p><h3 v-if="data.summary.nextPlan">后续安排</h3><p v-if="data.summary.nextPlan">{{data.summary.nextPlan}}</p><footer>记录人 {{data.summary.createdName}} · 最近更新 {{data.summary.updatedName}}<br />{{datetime(data.summary.updatedAt)}}</footer></template><template v-else><p>写下校区的整体进展、重点事项与后续安排。</p><button class="btn secondary" :disabled="data.readOnly" @click="edit">记录校区总结</button></template></article>
      <h2 class="campus-report-section-title">班级情况与点评 <small>{{data.classes.length}} 个班级</small></h2>
      <p v-if="!data.classes.length" class="compact-empty">当前校区暂无班级</p>
      <article v-for="item in data.classes" :key="item.id" class="panel campus-report-class"><header><div><h3>{{item.name}}</h3><small>{{item.teacherName}} · {{item.overview.studentCount}} 位学生</small></div><button class="btn secondary" :disabled="data.readOnly" @click="comment(item.id,item.name)">点评班级</button></header><div class="campus-report-class-stats"><span>到校 {{item.overview.arrivedCount}}/{{item.overview.studentCount}}</span><span>请假 {{item.overview.leaveCount}}</span><span>作业 {{item.overview.completedHomeworkCount}}/{{item.overview.homeworkCount}}</span></div><div v-if="item.report" class="campus-report-teacher-note"><strong>老师日报 · {{item.report.updatedName}}</strong><p>{{item.report.content}}</p></div><p v-else class="muted">老师尚未记录当日班级情况，可先根据现场情况点评。</p><section v-for="note in item.comments" :key="note.id" class="campus-report-comment"><strong>{{note.actorName}} · {{datetime(note.createdAt)}}</strong><p>{{note.text}}</p></section><RouterLink :to="`/class-reports?classId=${item.id}`" class="text-btn">查看班级日报与点评<AppIcon name="ChevronRight" :size="15" /></RouterLink></article>
    </template>
  </LoadState>
</template>

<style scoped>
.campus-report-filter{display:flex;align-items:center;gap:14px;padding:16px;margin-bottom:22px}.campus-report-filter .soft-select{flex:1;min-width:0}.campus-report-filter>span{font-size:12px;color:var(--muted)}.campus-report-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:22px}.campus-report-metrics article{padding:24px 18px;border-radius:22px;background:#edf4fd}.campus-report-metrics article:nth-child(2){background:#edf8f2}.campus-report-metrics article:nth-child(3){background:#f6f0fc}.campus-report-metrics strong,.campus-report-metrics span{display:block}.campus-report-metrics strong{font-size:24px;color:#2a3b53}.campus-report-metrics span{font-size:12px;color:#8191a7;margin-top:12px;line-height:1.7}.campus-report-summary,.campus-report-class{padding:26px;margin-bottom:20px}.campus-report-summary header,.campus-report-class header{display:flex;align-items:center;justify-content:space-between;gap:14px}.campus-report-summary h2{font-size:21px;margin-top:7px}.campus-report-summary small,.campus-report-class small{font-size:12px;color:var(--muted);display:block;margin-top:6px}.campus-report-summary p,.campus-report-class p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;color:var(--text-secondary);margin:15px 0}.campus-report-summary h3{margin-top:24px;font-size:14px;color:#557091}.campus-report-summary footer{font-size:12px;line-height:1.9;color:var(--muted);border-top:1px solid var(--border);margin-top:22px;padding-top:18px}.campus-report-section-title{font-size:21px;margin:30px 0 18px}.campus-report-section-title small{font-size:12px;font-weight:400;color:var(--muted);margin-left:8px}.campus-report-class h3{font-size:18px}.campus-report-class header .btn{flex-shrink:0}.campus-report-class-stats{display:flex;flex-wrap:wrap;gap:8px 18px;font-size:12px;color:#7c8da5;margin:18px 0}.campus-report-teacher-note{background:#f6f8fb;padding:18px;border-radius:17px;margin:18px 0}.campus-report-teacher-note strong,.campus-report-comment strong{font-size:12px;color:#6a7f9c}.campus-report-teacher-note p{margin-bottom:0}.campus-report-comment{padding:16px 0;border-top:1px solid var(--border)}.campus-report-comment p{margin-bottom:0}.campus-report-class>.text-btn{margin-top:17px}@media(max-width:600px){.campus-report-heading{flex-direction:row;align-items:center;gap:12px}.campus-report-heading :deep(.eyebrow),.campus-report-heading :deep(p){display:none}.campus-report-heading :deep(.heading-actions){width:auto;flex-shrink:0}.campus-report-heading :deep(h1){margin:0}.campus-report-filter>span{display:none}.campus-report-metrics{gap:8px}.campus-report-metrics article{padding:20px 10px;border-radius:18px}.campus-report-metrics strong{font-size:19px}.campus-report-metrics span{font-size:10px}.campus-report-summary,.campus-report-class{padding:22px}.campus-report-class h3{font-size:16px}}
</style>
