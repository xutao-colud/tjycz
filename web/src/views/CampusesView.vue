<script setup lang="ts">
import SoftSelect from '../components/SoftSelect.vue'
import { computed, onBeforeUnmount } from 'vue'
import { api, post } from '../lib/api'
import { campuses, currentCampusId, currentCampusName, isSuperAdmin, selectCampus, refreshCampuses, allCampusLabel, showAllCampusOption } from '../lib/state'
import { openDialog, useResource, act } from '../lib/ui'
import { currentLocation } from '../lib/location'
const locationController=new AbortController()
onBeforeUnmount(()=>locationController.abort())
import type { Campus, ClassRoom, ManagedUser, Student } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import LoadState from '../components/LoadState.vue'
const {data,loading,error,refresh} = useResource(async () => {
  const [list,users,students,classes] = await Promise.all([api<Campus[]>('/campuses'),api<ManagedUser[]>('/users'),api<Student[]>('/students'),api<ClassRoom[]>('/classes')])
  return {campuses:list,teachers:users.filter(u=>u.role==='TEACHER'),students,classes}
})
const visibleCampuses = computed(() => data.value?.campuses.filter(c=>currentCampusId.value===null || c.id===currentCampusId.value) || [])
async function create() {
  if (!isSuperAdmin.value) return
  if (await openDialog({title:'创建校区',description:'建立独立的教学校区，再为校长分配管理权限、创建班级与课程。',fields:[{key:'name',label:'校区名称',required:true},{key:'address',label:'校区地址',type:'textarea'}],submit:v=>post('/campuses',v),success:'校区已创建'})) { await refreshCampuses(); refresh() }
}
function changeCampus(value: string | number) { selectCampus(value ? Number(value) : null) }
async function configureLocation(campus:Campus, useCurrent=false) {
  let latitude=campus.checkInLatitude,longitude=campus.checkInLongitude
  if(useCurrent&&!await act(async()=>{const point=await currentLocation(locationController.signal);latitude=point.latitude;longitude=point.longitude},'已获取当前位置，请核对地址后保存')) return
  if(await openDialog({title:`老师签到地点 · ${campus.name}`,description:'按校区设置地址、中心点与允许半径，用于老师本人签到的服务端范围核验。经纬度使用WGS84坐标；可在校区现场点击“使用当前位置”获取。',fields:[{key:'address',label:'签到地址',required:true,type:'textarea'},{key:'latitude',label:'中心点纬度',type:'number',min:-90,max:90,step:0.0000001,required:true},{key:'longitude',label:'中心点经度',type:'number',min:-180,max:180,step:0.0000001,required:true},{key:'radiusMeters',label:'允许签到范围',type:'select',required:true,options:[...new Set([50,100,200,300,500,1000,2000,3000,campus.checkInRadius||200])].sort((a,b)=>a-b).map(value=>({value,label:`中心点周围 ${value} 米`}))}],initial:{address:campus.checkInAddress||campus.address||'',latitude:latitude??'',longitude:longitude??'',radiusMeters:campus.checkInRadius||200},submit:body=>api(`/campuses/${campus.id}/check-in-location`,{method:'PUT',body}),success:'签到地点已保存'})) {await refreshCampuses();await refresh()}
}
function teacherCampuses(member: ManagedUser) { return member.campusNames?.join('、') || member.campusIds?.map(id=>campuses.value.find(c=>c.id===id)?.name||`校区 #${id}`).join('、') || '—' }
function count(campus: Campus, kind: 'studentCount'|'teacherCount'|'classCount') {
  if (campus[kind] !== undefined) return campus[kind]
  if (kind==='studentCount') return data.value?.students.filter(s=>s.campusId===campus.id).length || 0
  if (kind==='classCount') return data.value?.classes.filter(c=>c.campusId===campus.id).length || 0
  return data.value?.teachers.filter(t=>t.campusIds?.includes(campus.id)).length || 0
}
</script>
<template>
  <PageHeader title="校区管理" subtitle="每个校区各有秩序，每一份成长都被看见。"><button v-if="isSuperAdmin" class="btn primary" @click="create"><AppIcon name="Plus" :size="17" />创建校区</button></PageHeader>
  <section class="panel">
    <div class="filter-bar"><AppIcon name="School" :size="19" /><SoftSelect :model-value="currentCampusId ?? ''" aria-label="查看校区师生" @change="changeCampus" :options="[...(showAllCampusOption ? [{value: '' as const, label: allCampusLabel}] : []), ...campuses.map(campus => ({value: campus.id, label: campus.name}))]" /><span class="filter-count">{{ currentCampusName }} · {{ visibleCampuses.length }} 个校区</span><button class="icon-btn" aria-label="刷新校区" @click="refresh();refreshCampuses()"><AppIcon name="RefreshCw" :size="17" /></button></div>
    <LoadState :loading="loading" :error="error" :empty="!visibleCampuses.length" title="暂无可访问的校区" description="由总管理员创建校区并分配管理权限。" @retry="refresh"><div class="campus-grid"><article v-for="campus in visibleCampuses" :key="campus.id" class="campus-card"><div class="card-top"><span class="kpi-icon mint"><AppIcon name="School" :size="21" /></span><span class="badge" :class="campus.enabled===false?'gray':'green'">{{ campus.enabled===false?'已停用':'运营中' }}</span></div><h2>{{ campus.name }}</h2><p class="muted small-text">{{ campus.address||'尚未填写地址' }}</p><div class="campus-counts"><div><strong>{{ count(campus,'teacherCount') }}</strong><small>老师</small></div><div><strong>{{ count(campus,'studentCount') }}</strong><small>学生</small></div><div><strong>{{ count(campus,'classCount') }}</strong><small>班级</small></div></div><div class="campus-check-in"><strong>签到地点</strong><p>{{campus.checkInAddress||'尚未设置签到地点'}}</p><small v-if="campus.checkInRadius">中心点周围 {{campus.checkInRadius}} 米</small><div class="row-actions"><button class="text-btn" @click="configureLocation(campus)">设置老师签到范围</button><button class="text-btn" @click="configureLocation(campus,true)">使用当前位置</button></div></div><button class="text-btn" @click="selectCampus(campus.id)">查看本校区师生<AppIcon name="ArrowRight" :size="14" /></button></article></div></LoadState>
  </section>
  <div v-if="data&&!loading&&!error" class="campus-members">
    <section class="panel"><div class="panel-header"><h2>校区老师 <span class="count-pill">{{ data.teachers.length }}</span></h2><RouterLink to="/settings" class="text-btn">成员与授权<AppIcon name="ArrowUpRight" :size="14" /></RouterLink></div><LoadState :empty="!data.teachers.length" title="当前校区暂无老师"><div class="table-wrap"><table><thead><tr><th>老师</th><th>所属校区</th><th>授权班级</th></tr></thead><tbody><tr v-for="teacher in data.teachers" :key="teacher.id"><td><strong>{{ teacher.name }}</strong></td><td>{{ teacherCampuses(teacher) }}</td><td>{{ teacher.classIds?.map(id=>data!.classes.find(c=>c.id===id)?.name||`班级 #${id}`).join('、')||'—' }}</td></tr></tbody></table></div></LoadState></section>
    <section class="panel"><div class="panel-header"><h2>校区学生 <span class="count-pill">{{ data.students.length }}</span></h2><RouterLink to="/students" class="text-btn">学生档案<AppIcon name="ArrowUpRight" :size="14" /></RouterLink></div><LoadState :empty="!data.students.length" title="当前校区暂无学生"><div class="table-wrap"><table><thead><tr><th>学生</th><th>所属校区</th><th>班级</th><th>年级</th></tr></thead><tbody><tr v-for="student in data.students" :key="student.id"><td><RouterLink :to="`/students?studentId=${student.id}`" class="text-btn">{{ student.name }}</RouterLink></td><td>{{ student.campusName||'—' }}</td><td>{{ student.className }}</td><td>{{ student.grade }}</td></tr></tbody></table></div></LoadState></section>
  </div>
</template>

<style scoped>
.campus-check-in{padding:18px 0;border-top:1px solid var(--border);margin-bottom:15px}.campus-check-in strong{font-size:13px}.campus-check-in p{font-size:13px;line-height:1.8;color:var(--text-secondary);margin:8px 0}.campus-check-in small{display:block;color:var(--muted);margin-bottom:12px}.campus-check-in .row-actions{gap:18px;margin-top:12px}
</style>
