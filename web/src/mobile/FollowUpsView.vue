<script setup lang="ts">
import { nextTick,ref,watch } from 'vue'
import { useRoute } from 'vue-router'
import { api,post,query,requestId,contextVersion,businessDate } from '../lib/api'
import { selectedDate,isParent } from '../lib/state'
import { useResource,loadStudents,studentOptions,openDialog,notify,type Field } from '../lib/ui'
import SoftSelect from '../components/SoftSelect.vue'
import LoadState from '../components/LoadState.vue'
import AttachmentList from '../components/AttachmentList.vue'
import type { FollowUp } from './types'
const route=useRoute(),studentId=ref<string|number>(String(route.query.studentId||'')),kind=ref(route.query.kind==='MESSAGE'?'MESSAGE':'ISSUE'),detailId=ref<number|null>(Number(route.query.id)||null)
const students=useResource(loadStudents)
watch([selectedDate,studentId,kind,contextVersion],()=>{detailId.value=null},{flush:'sync'})
const list=useResource(()=>api<FollowUp[]>('/follow-ups'+query({date:selectedDate.value,studentId:studentId.value,kind:kind.value})),[selectedDate,studentId,kind])
const detail=useResource(()=>detailId.value?api<FollowUp>(`/follow-ups/${detailId.value}`):Promise.resolve(null),[detailId])
watch(()=>route.query,value=>{studentId.value=String(value.studentId||'');kind.value=value.kind==='MESSAGE'?'MESSAGE':'ISSUE';detailId.value=Number(value.id)||null})
let composeHandled=false
watch(()=>students.data.value,rows=>{if(!composeHandled&&route.query.compose==='1'&&rows?.length&&selectedDate.value<=businessDate()){composeHandled=true;void create()}})
const captions:Record<string,string>={OPEN:'待跟进',IN_PROGRESS:'跟进中',RESOLVED:'已解决',CANCELLED:'已撤销'}
watch(detail.data,async row=>{if(!row?.logs?.length)return;const version=contextVersion.value;await nextTick();if(version!==contextVersion.value||detailId.value!==row.id)return;try{await post(`/follow-ups/${row.id}/read`,{lastLogId:row.logs.at(-1)!.id})}catch{if(version===contextVersion.value&&detailId.value===row.id)notify('阅读回执未同步，请刷新后重试。','error')}})
async function create(){
  const message=isParent.value||kind.value==='MESSAGE',key=requestId()
  const fields:Field[]=[{key:'studentId',label:'学生',type:'select',required:true,options:studentOptions(students.data.value||[])},{key:'reason',label:message?'留言内容':'具体情况',type:'textarea',required:true}]
  if(!message)fields.push({key:'title',label:'关注事项',required:true},{key:'type',label:'事项类型',type:'select',required:true,options:[{value:'LEARNING',label:'学习'},{value:'HEALTH',label:'身体状况'},{value:'ATTENDANCE',label:'考勤'},{value:'OTHER',label:'其他'}]},{key:'measures',label:'已采取的措施',type:'textarea',required:true})
  if(await openDialog({title:message?(isParent.value?'给老师留言':'发作业消息'):'登记关注事项',fields,initial:{studentId:studentId.value||students.data.value?.[0]?.id||'',type:'LEARNING'},submit:v=>post('/follow-ups',{...v,date:selectedDate.value,kind:message?'MESSAGE':'ISSUE',needsParent:false,requestId:key}),success:'记录已提交'})){if(message)kind.value='MESSAGE';await list.refresh()}
}
async function reply(row:FollowUp,action='REPLY'){
  const key=requestId()
  if(await openDialog({title:action==='ACK'?'确认已了解':action==='PARENT_DONE'?'反馈配合情况':'补充回复',fields:action==='ACK'?[]:[{key:'text',label:'回复内容',type:'textarea',required:true},{key:'attachmentIds',label:'补充材料',type:'file'}],submit:v=>post(`/follow-ups/${row.id}/reply`,{...v,action,requestId:key}),success:'回复已提交'})){await detail.refresh();await list.refresh()}
}
async function status(row:FollowUp){
  const next=row.status==='OPEN'?'IN_PROGRESS':row.status==='IN_PROGRESS'?'RESOLVED':'IN_PROGRESS',key=requestId()
  if(await openDialog({title:next==='RESOLVED'?'记录处理结果':'开始继续跟进',fields:[{key:'text',label:'处理说明',type:'textarea',required:true}],submit:v=>api(`/follow-ups/${row.id}/status`,{method:'PUT',body:{...v,status:next,expectedVersion:row.version,requestId:key}}),success:'跟进状态已更新'})){await detail.refresh();await list.refresh()}
}
</script>
<template><h1 class="mobile-page-title">关注与沟通</h1><div class="mobile-filter"><SoftSelect v-model="studentId" aria-label="沟通学生" :options="[{value:'',label:'全部学生'},...studentOptions(students.data.value||[])]" /><SoftSelect v-model="kind" aria-label="记录类型" :options="[{value:'ISSUE',label:'关注事项'},{value:'MESSAGE',label:'家校留言'}]" /></div><button class="btn primary full" :disabled="selectedDate>businessDate()||students.loading.value" @click="create">{{isParent?'给老师留言':kind==='MESSAGE'?'发作业消息':'登记关注事项'}}</button><LoadState :loading="list.loading.value" :error="list.error.value" :empty="!list.data.value?.length" @retry="list.refresh"><article v-for="row in list.data.value" :key="row.id" class="mobile-conversation panel"><button class="mobile-conversation-title" @click="detailId=detailId===row.id?null:row.id"><strong>{{row.studentName}} · {{row.title}}</strong><span>{{captions[row.status]||row.status}} ›</span></button><p>{{row.reason}}</p><p v-if="row.measures">已采取措施：{{row.measures}}</p><p v-if="row.needsParent">请家长配合：{{row.parentAction}}</p><small>负责老师：{{row.ownerName}}</small><LoadState v-if="detailId===row.id" :loading="detail.loading.value" :error="detail.error.value" @retry="detail.refresh"><template v-if="detail.data.value"><article v-for="log in detail.data.value.logs" :key="log.id" class="mobile-conversation-log"><small>{{log.actorName}} · {{log.createdAt.replace('T',' ').slice(0,16)}}</small><p>{{log.text}}</p><AttachmentList :files="log.attachments" /></article><div v-if="row.status!=='CANCELLED'" class="row-actions"><button class="btn secondary" @click="reply(detail.data.value)">回复</button><button v-if="isParent" class="btn secondary" @click="reply(detail.data.value,'ACK')">已了解</button><button v-if="isParent&&row.needsParent" class="btn secondary" @click="reply(detail.data.value,'PARENT_DONE')">已配合</button><button v-if="detail.data.value.canManage" class="btn primary" @click="status(detail.data.value)">{{row.status==='IN_PROGRESS'?'记录解决结果':'开始跟进'}}</button></div></template></LoadState></article></LoadState></template>
