<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { api,post,query,time,requestId,businessDate,isCancelled } from '../lib/api'
import { isParent, isAdmin, selectedDate, contextVersion } from '../lib/state'
import { useResource, label,openDialog,notify,type Field } from '../lib/ui'
import SoftSelect from '../components/SoftSelect.vue'
import AppIcon from '../components/AppIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import AttachmentList from '../components/AttachmentList.vue'
import LoadState from '../components/LoadState.vue'
import DashboardView from '../views/DashboardView.vue'
import StudentAvatar from '../components/StudentAvatar.vue'
import type { Growth,Workbench } from './types'
import type { Student,Attachment } from '../lib/types'
const route=useRoute(),showGrowth=computed(()=>isParent.value||route.path==='/growth')
const studentId=ref<string|number>(String(route.query.studentId||'')),classId=ref<string|number>('')
watch(contextVersion,()=>{studentId.value='';classId.value=''},{flush:'sync'})
const resource=useResource(async()=>showGrowth.value?api<Growth>('/growth'+query({date:selectedDate.value,studentId:studentId.value})):isAdmin.value?null:api<Workbench>('/workbench'+query({date:selectedDate.value,classId:classId.value})),[selectedDate,studentId,classId])
const growth=computed(()=>showGrowth.value?resource.data.value as Growth|null:null)
const workbench=computed(()=>!showGrowth.value?resource.data.value as Workbench|null:null)
const done=computed(()=>growth.value?.homework.filter(h=>h.status==='COMPLETED').length||0)
const classCaption=computed(()=>{const w=workbench.value;return w?.classes.find(c=>c.id===w.classId)?.name||'选择班级'})
const selected=ref<number[]>([]),results=ref<{success:boolean;message?:string;studentId?:number;id?:number}[]>([])
watch([classId,selectedDate],()=>{selected.value=[];results.value=[]})
watch(workbench,w=>{selected.value=selected.value.filter(id=>w?.students.some(s=>s.id===id))})
function selection(){if(!selected.value.length){notify('请先勾选学生','error');return null}if(selectedDate.value>businessDate()){notify('未来日期不能登记已发生事项','error');return null}return [...selected.value]}
function retryKey(){const ids=new Map<string,string>();return (body:Record<string,unknown>)=>{const key=JSON.stringify(body);if(!ids.has(key))ids.set(key,requestId());return {...body,requestId:ids.get(key)!}}}
async function record(){
  const ids=selection();if(!ids)return;const body=retryKey()
  const fields:Field[]=[{key:'type',label:'记录类型',type:'select',required:true,options:[{value:'PREPARATION',label:'课前准备'},{value:'BEHAVIOR',label:'学习表现'},{value:'COMMENT',label:'老师点评'}]},{key:'text',label:'记录内容',type:'textarea',required:true},{key:'attachmentIds',label:'图片、语音或视频',type:'file'}]
  if(await openDialog({title:`为${ids.length}位学生记录成长`,fields,initial:{type:'COMMENT'},submit:async v=>{const r=await post<{results:typeof results.value}>('/growth/notes',body({...v,date:selectedDate.value,studentIds:ids}));results.value=r.results},success:'记录结果已返回，请核对逐项结果'})){selected.value=results.value.filter(r=>!r.success).map(r=>r.studentId!).filter(Boolean);await resource.refresh()}
}
async function review(){
  const ids=selection(),w=workbench.value;if(!ids||!w)return;const body=retryKey()
  const tasks=[...new Map(w.homework.filter(h=>ids.includes(h.studentId)&&h.status!=='COMPLETED').map(h=>[h.taskId,{value:h.taskId,label:`${h.subject} · ${h.title}`}])).values()]
  if(!tasks.length){notify('所选学生没有待批改的作业','error');return}
  if(await openDialog({title:`批改所选${ids.length}位学生的同一项作业`,description:'按任务逐份处理；失败项会保留，已完成记录不会重复批改。',fields:[{key:'taskId',label:'作业任务',type:'select',options:tasks,required:true},{key:'status',label:'批改结论',type:'select',required:true,options:[{value:'COMPLETED',label:'完成作业'},{value:'CORRECTION',label:'退回订正'}]},{key:'accuracy',label:'正确率（%）',type:'number',min:0,max:100,required:true},{key:'comment',label:'共同反馈',type:'textarea',required:true}],initial:{taskId:tasks[0]!.value,status:'COMPLETED'},submit:async v=>{const items=w.homework.filter(h=>ids.includes(h.studentId)&&h.taskId===Number(v.taskId)&&h.status!=='COMPLETED').map(h=>({id:h.id,expectedVersion:h.version}));const r=await post<{results:typeof results.value}>('/homework/batch-review',body({...v,taskId:Number(v.taskId),items}));results.value=r.results.map(r=>({...r,studentId:w.homework.find(h=>h.id===r.id)?.studentId}))},success:'批改结果已返回，请核对逐项结果'})){selected.value=results.value.filter(r=>!r.success).map(r=>r.studentId!).filter(Boolean);await resource.refresh()}
}
const batchMode=ref(false),homeTab=ref('students'),historyOpen=ref(false),photoBusy=ref(false)
const photoController=new AbortController();onBeforeUnmount(()=>photoController.abort())
const roster=computed(()=>(workbench.value?.students||[]).map(s=>{
 const tasks=workbench.value!.homework.filter(h=>h.studentId===s.id),states=['CORRECTION','PENDING_RECHECK','PENDING_REVIEW','IN_PROGRESS','NOT_STARTED']
 const primary=tasks.length?(states.find(status=>tasks.some(h=>h.status===status))||'COMPLETED'):''
 return {...s,done:tasks.filter(h=>h.status==='COMPLETED').length,total:tasks.length,primary,taskSummary:tasks.map(h=>`${h.subject} ${label(h.status)}`).join(' · '),messages:workbench.value?.messages?.filter(m=>m.studentId===s.id&&['OPEN','IN_PROGRESS'].includes(m.status)).length||0}
}))
const lowBalance=computed(()=>growth.value?.accounts?.filter(a=>Number(a.availableBalance??a.balance)<=Number(a.warningThreshold)).length||0)
watch([studentId,selectedDate],()=>{historyOpen.value=false})
async function photoMark(event:Event,student:Student){
 const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file||!student.campusId)return
 if(selectedDate.value!==businessDate()){notify('拍照考勤仅支持当天','error');input.value='';return}
 const version=contextVersion.value;photoBusy.value=true
 try{const body=new FormData();body.append('file',file);const uploaded=await api<Attachment>('/files',{method:'POST',body,campusId:student.campusId,signal:photoController.signal});const result=await api<{message:string;notificationCount:number}>('/attendance/photo',{method:'POST',body:{studentId:student.id,photoFileId:uploaded.id,action:student.attendanceStatus==='PRESENT'?'CHECK_OUT':'CHECK_IN'},signal:photoController.signal});notify(`${result.message}，已生成家长站内消息`);await resource.refresh()}catch(error){if(version===contextVersion.value&&!isCancelled(error))notify((error as Error).message,'error')}finally{if(version===contextVersion.value)photoBusy.value=false;input.value=''}
}
</script>
<template>
 <DashboardView v-if="isAdmin&&!showGrowth" />
 <LoadState v-else :loading="resource.loading.value" :error="resource.error.value" @retry="resource.refresh">
  <template v-if="growth">
   <div class="home-heading"><h1 class="mobile-page-title">孩子的今天</h1>
   <SoftSelect v-model="studentId" aria-label="查看哪个孩子" :options="growth.students.map(s=>({value:s.id,label:`${s.name} · ${s.className}`}))" :placeholder="growth.student?.name||'选择孩子'" /></div>
   <template v-if="growth.student">
    <section class="mobile-student panel"><StudentAvatar :student="growth.student" /><div><h2>{{growth.student.name}}</h2><p>{{growth.student.className}} · {{growth.student.campusName}}</p></div><StatusBadge :value="growth.student.attendanceStatus" /></section>
    <p v-if="selectedDate>businessDate()" class="info-banner">未来日期仅查看安排，尚无已发生的记录。</p>
    <RouterLink v-if="growth.pickup.openAlertCount" :to="`/safety?studentId=${growth.student.id}`" class="mobile-concern">接送事项需要核对 · 请与老师联系<AppIcon name="ChevronRight" :size="16" /></RouterLink>
    <RouterLink v-if="growth.openConcernCount" :to="`/follow-ups?studentId=${growth.student.id}`" class="mobile-concern">{{growth.openConcernCount}} 项事项需要关注<AppIcon name="ChevronRight" :size="16" /></RouterLink>
    <section class="home-focus-card panel"><header><h2>当日作业</h2><span>完成 {{done}} / {{growth.homework.length}}</span></header><RouterLink v-for="h in growth.homework" :key="h.id" class="home-task" :to="`/homework?studentId=${growth.student.id}&homeworkId=${h.id}`"><div><strong>{{h.subject}} · {{h.title}}</strong><p v-if="h.comment">{{h.comment}}</p></div><StatusBadge :value="h.status" /><AppIcon name="ChevronRight" :size="14" /></RouterLink><p v-if="!growth.homework.length" class="compact-empty">当日暂未布置作业</p></section>
    <section class="home-focus-card panel"><header><h2>签到签退</h2><RouterLink :to="`/safety?studentId=${growth.student.id}`">接送安排 ›</RouterLink></header><div class="home-clocks"><div><small>到校签到</small><strong>{{growth.student.checkInTime?time(growth.student.checkInTime):growth.student.attendanceStatus==='ON_LEAVE'?'已请假':growth.student.attendanceStatus==='NOT_ARRIVED'?'未签到':'时间未记录'}}</strong></div><div><small>离校签退</small><strong>{{growth.student.checkOutTime?time(growth.student.checkOutTime):growth.student.attendanceStatus==='ON_LEAVE'?'已请假':growth.student.attendanceStatus==='LEFT'?'时间未记录':'尚未离校'}}</strong></div></div><RouterLink :to="`/student-notifications?studentId=${growth.student.id}`" class="home-record-link">到离校照片与通知 ›</RouterLink></section>
    <section class="home-focus-card panel"><header><h2>作业消息</h2><RouterLink :to="`/follow-ups?studentId=${growth.student.id}&kind=MESSAGE`">全部 ›</RouterLink></header><RouterLink v-for="m in (growth.messages||[]).slice(0,2)" :key="m.id" class="home-task" :to="`/follow-ups?studentId=${m.studentId}&kind=MESSAGE&id=${m.id}`"><div><strong>{{m.title}}</strong><p>{{m.reason}}</p></div><AppIcon name="ChevronRight" :size="14" /></RouterLink><p v-if="!growth.messages?.length" class="home-empty">当日暂无新的家校留言，批改结果可在作业中查看。</p><RouterLink :to="`/follow-ups?studentId=${growth.student.id}&kind=MESSAGE&compose=1`" class="btn secondary full home-message">给老师留言</RouterLink></section>
    <RouterLink v-if="lowBalance&&isParent" :to="`/family-services?studentId=${growth.student.id}`" class="home-balance">{{lowBalance}} 门课程余额偏低 · 查看续费 ›</RouterLink>
    <div class="home-secondary"><RouterLink :to="`/summaries?studentId=${growth.student.id}`">每日小结 ›</RouterLink><button class="text-btn" @click="historyOpen=!historyOpen">{{historyOpen?'收起成长记录':'更多成长记录 ›'}}</button></div>
    <template v-if="historyOpen"><h2 class="mobile-section-title">成长记录</h2><p v-if="!growth.timeline.length" class="compact-empty">当日暂无已发生的成长记录</p><article v-for="event in growth.timeline" :key="event.key" class="mobile-timeline panel"><header><time>{{event.occurredAt?time(event.occurredAt):'未记录时间'}}</time><h3>{{event.title}}</h3><StatusBadge :value="event.status" /></header><p>{{event.detail}}</p><small v-if="event.actorName">{{event.actorName}}</small><AttachmentList :files="event.attachments" /></article></template>
   </template><p v-else class="compact-empty">暂无已绑定的孩子，请联系校区核对账号绑定。</p>
  </template>
  <template v-else-if="workbench">
   <div class="home-heading"><h1 class="mobile-page-title">今日班级</h1><SoftSelect v-model="classId" aria-label="选择班级" :options="workbench.classes.map(c=>({value:c.id,label:`${c.name} · ${c.campusName}`}))" :placeholder="classCaption" /></div>
   <RouterLink v-for="alert in workbench.pickupAlerts" :key="alert.id" :to="`/safety?studentId=${alert.studentId}`" class="mobile-concern">{{alert.studentName}} · 接送异常需核对</RouterLink>
   <div class="home-tabs"><button :class="{active:homeTab==='students'}" @click="homeTab='students'">学生 · {{roster.length}}</button><button :class="{active:homeTab==='messages'}" @click="homeTab='messages'">作业消息{{workbench.messages?.length?` · ${workbench.messages.length}`:''}}</button></div>
   <section v-if="homeTab==='messages'" class="home-focus-card panel"><RouterLink v-for="m in workbench.messages" :key="m.id" :to="`/follow-ups?studentId=${m.studentId}&kind=MESSAGE&id=${m.id}`" class="home-task"><div><strong>{{m.studentName}}</strong><p>{{m.reason||m.title}}</p></div><AppIcon name="ChevronRight" :size="16" /></RouterLink><p v-if="!workbench.messages?.length" class="compact-empty">当日暂无家校作业消息</p><RouterLink to="/follow-ups?kind=MESSAGE" class="text-btn">查看全部沟通 ›</RouterLink></section>
   <template v-else>
    <div class="home-secondary"><span>{{workbench.scheduled?'当日排课名单':'在册学生 · 当日未配置排课'}}</span><button class="text-btn" @click="batchMode=!batchMode">{{batchMode?'退出批量':'批量处理'}}</button></div>
    <section v-for="student in roster" :key="student.id" class="home-focus-card panel home-student-card"><header><label v-if="batchMode" class="mobile-student-select"><input v-model="selected" type="checkbox" :value="student.id" :aria-label="`选择${student.name}`" /></label><StudentAvatar :student="student" /><h2>{{student.name}}</h2><StatusBadge :value="student.attendanceStatus" /></header><RouterLink class="home-task home-student-work" :to="`/homework?studentId=${student.id}`"><strong>作业 {{student.done}} / {{student.total}}</strong><span>{{student.primary?label(student.primary):'无作业'}} ›</span></RouterLink><p v-if="student.taskSummary" class="home-subjects">{{student.taskSummary}}</p><div class="home-student-times"><span>签到 {{student.checkInTime?time(student.checkInTime):'未签到'}}</span><span>签退 {{student.checkOutTime?time(student.checkOutTime):student.attendanceStatus==='LEFT'?'时间未记录':'未签退'}}</span></div><div class="home-student-actions"><RouterLink class="btn secondary" :to="`/follow-ups?studentId=${student.id}&kind=MESSAGE`">作业消息{{student.messages?` · ${student.messages}`:''}}</RouterLink><label v-if="['NOT_ARRIVED','PRESENT'].includes(student.attendanceStatus)" class="btn primary home-photo" :class="{disabled:photoBusy||selectedDate!==businessDate()}">{{student.attendanceStatus==='PRESENT'?'拍照签退':'拍照签到'}}<input type="file" accept="image/*" capture="environment" :aria-label="`${student.name}${student.attendanceStatus==='PRESENT'?'拍照签退':'拍照签到'}`" :disabled="photoBusy||selectedDate!==businessDate()" @change="photoMark($event,student)" /></label></div></section>
    <p v-if="!roster.length" class="compact-empty">当前班级和日期暂无学生</p>
    <div v-if="batchMode" class="mobile-batch panel"><p>已选择 {{selected.length}} 位学生</p><div class="row-actions"><button class="btn secondary" @click="record">记录成长</button><button class="btn primary" @click="review">批量批改</button></div><p v-for="(result,index) in results" :key="index" :class="result.success?'result-ok':'result-fail'">{{workbench.students.find(s=>s.id===result.studentId)?.name||'记录'}} · {{result.message||(result.success?'已完成':'未完成，请核对后重试')}}</p></div>
   </template>
  </template>
 </LoadState>
</template>
