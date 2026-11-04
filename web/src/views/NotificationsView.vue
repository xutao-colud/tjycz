<script setup lang="ts">
import {ref} from 'vue'
import {useRoute} from 'vue-router'
import {api,post,query,datetime} from '../lib/api'
import {isParent} from '../lib/state'
import {useResource,loadStudents,studentOptions,act} from '../lib/ui'
import type {Attachment} from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import SoftSelect from '../components/SoftSelect.vue'
import LoadState from '../components/LoadState.vue'
import AttachmentList from '../components/AttachmentList.vue'
interface Message{id:number;studentName:string;parentName:string;title:string;message:string;event:string;createdAt:string;readAt:string|null;deliveryStatus:string;attachments:Attachment[]}
const studentId=ref<string|number>(String(useRoute().query.studentId||'')),students=useResource(loadStudents),resource=useResource(()=>api<Message[]>('/student-notifications'+query({studentId:studentId.value})),[studentId])
async function read(id:number){if(await act(()=>post(`/student-notifications/${id}/read`),'已标记已读'))resource.refresh()}
</script>
<template><PageHeader title="到离校消息" subtitle="孩子的每一次到达与归家，都清楚留在这里。" /><section class="panel message-filter"><SoftSelect v-model="studentId" :options="[{value:'',label:'全部授权孩子'},...studentOptions(students.data.value||[])]" aria-label="消息学生" /><button class="text-btn" @click="resource.refresh">刷新</button></section><LoadState :loading="resource.loading.value" :error="resource.error.value" :empty="!resource.data.value?.length" title="暂时没有到离校消息" @retry="resource.refresh"><article v-for="message in resource.data.value" :key="message.id" class="panel message-card"><header><h2>{{message.title}}</h2><span class="badge" :class="message.readAt?'gray':'green'">{{message.readAt?'已读':'未读'}}</span></header><p>{{message.message}}</p><small>{{datetime(message.createdAt)}}<template v-if="!isParent"> · 接收家长：{{message.parentName}}</template></small><AttachmentList :files="message.attachments" /><p class="form-hint">站内消息已生成；微信订阅推送尚未配置。</p><button v-if="isParent&&!message.readAt" class="text-btn" @click="read(message.id)">我已知晓</button></article></LoadState></template>
<style scoped>.message-filter{display:flex;gap:18px;align-items:center;padding:18px;margin-bottom:20px}.message-filter .soft-select{flex:1}.message-card{padding:24px;margin-bottom:18px}.message-card header{display:flex;justify-content:space-between;gap:15px}.message-card h2{font-size:18px}.message-card p{margin:16px 0;line-height:1.9}.message-card small{color:var(--text-secondary)}.message-card .form-hint{font-size:12px;color:var(--text-secondary)}</style>
