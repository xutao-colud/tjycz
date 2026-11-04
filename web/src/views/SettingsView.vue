<script setup lang="ts">
import { ref } from 'vue'
import { api, post, datetime } from '../lib/api'
import { encryptPassword } from '../lib/password'
import { isAdmin, isSuperAdmin, user, roleName, campuses, currentCampusId, currentCampusName, refreshCampuses } from '../lib/state'
import { useResource, openDialog, label, studentOptions, type Field, type FormValues, type Option } from '../lib/ui'
import type { Audit, ClassRoom, Settings, Student, ManagedUser, Role } from '../lib/types'
import PageHeader from '../components/PageHeader.vue'
import AppIcon from '../components/AppIcon.vue'
import LoadState from '../components/LoadState.vue'
const tab = ref(isAdmin.value && !isSuperAdmin.value ? 'users' : 'institution')
const {data,loading,error,refresh} = useResource(async () => {
  const [settings,audit,users,classes,students] = await Promise.all([
    api<Settings>('/settings'), isAdmin.value ? api<Audit[]>('/audit-logs') : Promise.resolve([]),
    isAdmin.value ? api<ManagedUser[]>('/users') : Promise.resolve([]), api<ClassRoom[]>('/classes'), api<Student[]>('/students'),
  ])
  return {settings,audit,users,classes,students}
})
async function edit() {
  const s = data.value?.settings
  if (!s || !isSuperAdmin.value) return
  if (await openDialog({title:'机构信息与积分规则',description:'积分规则保存后由后端在后续奖励事件中实际使用，历史流水保留。',fields:[{key:'institutionName',label:'机构名称',required:true},{key:'campusName',label:'校区名称',required:true},{key:'teacherName',label:'负责人姓名',required:true},{key:'homework',label:'完成作业奖励积分',type:'number',min:0,max:100,required:true},{key:'mistake',label:'掌握错题奖励积分',type:'number',min:0,max:100,required:true},{key:'summary',label:'日小结发布奖励积分',type:'number',min:0,max:100,required:true},{key:'fitness',label:'体能打卡奖励积分',type:'number',min:0,max:100,required:true}],initial:{institutionName:s.institutionName,campusName:s.campusName,teacherName:s.teacherName,summary:s.pointsRules.find(r=>r.event==='SUMMARY_PUBLISHED')?.points??2,homework:s.pointsRules.find(r=>r.event==='HOMEWORK_COMPLETED')?.points??5,mistake:s.pointsRules.find(r=>r.event==='MISTAKE_MASTERED')?.points??2,fitness:s.pointsRules.find(r=>r.event==='FITNESS')?.points??2},submit:({homework,mistake,fitness,summary,...v})=>api('/settings',{method:'PATCH',body:{...v,pointsRules:[{event:'SUMMARY_PUBLISHED',points:Number(summary)},{event:'HOMEWORK_COMPLETED',points:homework},{event:'MISTAKE_MASTERED',points:mistake},{event:'FITNESS',points:fitness}]}}),success:'机构设置与积分规则已保存'})) refresh()
}
function accessField(role: Role): Field {
  if (role === 'ADMIN') return {key:'campusIds',label:'授权管理校区',type:'multi',required:true,options:campuses.value.map(c=>({value:c.id,label:c.name})),hint:'校长仅可管理所选校区；至少授权一个校区。'}
  if (role === 'TEACHER') return {key:'classIds',label:'教师授权班级',type:'multi',required:true,options:(data.value?.classes||[]).map(c=>({value:c.id,label:`${c.name}${c.campusName ? ` · ${c.campusName}` : ''}`})),hint:'教师仅可访问已授权班级及其学生。'}
  return {key:'studentIds',label:'家长绑定学生',type:'multi',required:true,options:studentOptions(data.value?.students||[]),hint:'家长仅可访问绑定的孩子，可以绑定不同校区的孩子。'}
}
async function createUser() {
  let role = 'TEACHER' as Role
  const roles: Option[] = [{value:'TEACHER',label:'授课老师'},{value:'PARENT',label:'学生家长'}]
  if (isSuperAdmin.value) roles.unshift({value:'ADMIN',label:'校区校长'})
  if (!await openDialog({title:'选择账号角色',description:'根据角色配置校区、班级或孩子的访问范围。',fields:[{key:'role',label:'账号角色',type:'select',required:true,options:roles}],initial:{role:'TEACHER'},confirm:'下一步',submit:async v=>{role=v.role}})) return
  if (await openDialog({title:`创建${roleName[role]}账号`,description:'密码通过 RSA 加密与一次性凭据传输，创建后可使用账号登录。',fields:[{key:'name',label:'姓名',required:true},{key:'username',label:'登录账号',required:true},{key:'password',label:'初始密码',type:'password',required:true,min:12,max:72,hint:'密码须 12–72 位，包含大小写字母、数字和符号。'},accessField(role)],initial:role==='ADMIN'&&currentCampusId.value ? {campusIds:[currentCampusId.value]} : {},submit:async v=>{
    if (typeof v.password !== 'string' || v.password.length < 12 || v.password.length > 72 || !/[A-Z]/.test(v.password) || !/[a-z]/.test(v.password) || !/[0-9]/.test(v.password) || !/[^A-Za-z0-9]/.test(v.password)) throw new Error('密码须12至72位，包含大小写字母、数字和符号')
    if (role === 'ADMIN' && !(Array.isArray(v.campusIds) && v.campusIds.length)) throw new Error('请为校长至少授权一个校区')
    return post('/users',await encryptPassword('CREATE_USER',{...v,role,campusIds:role==='ADMIN'?v.campusIds:[],classIds:role==='TEACHER'?v.classIds:[],studentIds:role==='PARENT'?v.studentIds:[]}))
  },success:'账号已创建并保存授权关系'})) { refresh(); refreshCampuses() }
}
function canEdit(member: ManagedUser) {
  if (!isAdmin.value || member.manageable === false || member.role === 'SUPER_ADMIN') return false
  if (isSuperAdmin.value) return ['ADMIN','TEACHER','PARENT'].includes(member.role)
  // 跨校区账号不能由只管理其中部分校区的校长接管。
  return ['TEACHER','PARENT'].includes(member.role) && !!member.campusIds?.length && member.campusIds.every(id=>user.value?.campusIds.includes(id))
}
async function editAccess(member: ManagedUser) {
  if (!canEdit(member)) return
  const field = accessField(member.role)
  const existing = (member[field.key as keyof ManagedUser] || []) as number[]
  // 当前筛选外的既有关系原样保留；切到全部校区后可完整调整。
  const visible = new Set((field.options || []).map(o=>Number(o.value)))
  const preserved = existing.filter(id=>!visible.has(id))
  const initial: FormValues = {[field.key]:existing.filter(id=>visible.has(id))}
  const description = preserved.length ? `本次仅调整当前校区可见的授权，另有 ${preserved.length} 项既有授权将保留。选择“全部校区”可查看完整范围。` : '调整后立即按新的授权范围访问数据，请核对所选范围。'
  if (await openDialog({title:`调整授权 · ${member.name}`,description,fields:[{...field,required:member.role==='ADMIN'&&!preserved.length}],initial,submit:v=>api(`/users/${member.id}/access`,{method:'PUT',body:{[field.key]:[...new Set([...preserved,...v[field.key]])]}}),success:'账号访问权限已更新'})) { refresh(); refreshCampuses() }
}
function memberCampuses(member: ManagedUser) { return member.campusNames?.join('、') || member.campusIds?.map(id=>campuses.value.find(c=>c.id===id)?.name||`校区 #${id}`).join('、') || (member.role==='SUPER_ADMIN'?'全部校区':'—') }
</script>
<template>
  <PageHeader title="系统设置" subtitle="让机构有序运行，让教学专注成长。"><button v-if="isSuperAdmin" class="btn primary" @click="edit"><AppIcon name="SlidersHorizontal" :size="17" />编辑机构与规则</button></PageHeader>
  <section class="panel">
    <div class="filter-bar"><div class="tabs"><button :class="{active:tab==='institution'}" @click="tab='institution'">机构与规则</button><button v-if="isAdmin" :class="{active:tab==='users'}" @click="tab='users'">成员与授权</button><button v-if="isAdmin" :class="{active:tab==='audit'}" @click="tab='audit'">操作审计</button></div><button v-if="isAdmin&&tab==='users'" class="btn primary small-btn" @click="createUser"><AppIcon name="UserRoundPlus" :size="16" />创建账号</button></div>
    <LoadState :loading="loading" :error="error" @retry="refresh"><template v-if="data">
      <div v-if="tab==='institution'" class="settings-content">
        <section class="settings-section"><h2><AppIcon name="Building2" :size="20" />机构信息</h2><div class="detail-grid"><div><span>机构名称</span><strong>{{ data.settings.institutionName }}</strong></div><div><span>当前校区范围</span><strong>{{ currentCampusName }}</strong></div><div><span>机构负责人</span><strong>{{ data.settings.teacherName }}</strong></div><div><span>当前账号</span><strong>{{ user?.name }} · {{ user&&roleName[user.role] }}</strong></div></div><p v-if="!isSuperAdmin" class="muted small-text settings-notice">机构信息与全局积分规则由总管理员维护。</p></section>
        <section class="settings-section"><h2><AppIcon name="Trophy" :size="20" />成长积分规则</h2><div class="rules-grid"><div v-for="rule in data.settings.pointsRules" :key="rule.event" class="rule-card"><AppIcon :name="rule.event==='FITNESS'?'Dumbbell':rule.event==='MISTAKE_MASTERED'?'BookOpenCheck':'NotebookPen'" :size="22" /><span>{{ label(rule.event) }}</span><strong>+{{ rule.points }} <small>分</small></strong></div></div><p class="muted small-text">规则由服务端执行；重复的完成事件不会重复奖励。</p></section>
        <section class="settings-section"><h2><AppIcon name="PlugZap" :size="20" />服务连接</h2><div class="service-row"><div><strong>微信订阅通知</strong><p>{{ data.settings.wechatConfigured?'已配置消息服务，请以每条小结通知状态为准。':'尚未配置微信凭据，发布小结不会声称已推送。' }}</p></div><span class="badge" :class="data.settings.wechatConfigured?'green':'gray'">{{ data.settings.wechatConfigured?'已配置':'待配置' }}</span></div><div class="service-row"><div><strong>学生接送确认</strong><p>老师人工确认到校，离校核对接送授权与接送码。</p></div><span class="badge green">现场核验</span></div><div class="service-row"><div><strong>运行环境</strong><p>{{ data.settings.demo?'本地演示环境，包含虚构学生数据与角色切换。':'生产环境，使用机构账号登录与后端权限校验。' }}</p></div><span class="badge" :class="data.settings.demo?'amber':'green'">{{ data.settings.demo?'本地演示':'生产环境' }}</span></div></section>
      </div>
      <LoadState v-else-if="tab==='users'" :empty="!data.users.length" title="当前范围暂无可管理账号" description="添加校长、教师或家长，并配置对应访问范围。"><div class="table-wrap"><table><thead><tr><th>姓名</th><th>账号</th><th>角色</th><th>所属 / 授权校区</th><th>授权班级</th><th>绑定学生</th><th>操作</th></tr></thead><tbody><tr v-for="member in data.users" :key="member.id"><td><strong>{{ member.name }}</strong></td><td>{{ member.username }}</td><td>{{ roleName[member.role] }}</td><td>{{ memberCampuses(member) }}</td><td>{{ member.classIds?.map(id=>data!.classes.find(c=>c.id===id)?.name||`班级 #${id}`).join('、')||'—' }}</td><td>{{ member.studentIds?.map(id=>data!.students.find(s=>s.id===id)?.name||`学生 #${id}`).join('、')||'—' }}</td><td><button v-if="canEdit(member)" class="text-btn" @click="editAccess(member)">调整授权</button><span v-else class="muted">—</span></td></tr></tbody></table></div></LoadState>
      <LoadState v-else :empty="!data.audit.length" title="操作轨迹将留在这里"><div class="table-wrap"><table><thead><tr><th>操作人</th><th>所属校区</th><th>操作类型</th><th>操作详情</th><th>时间</th></tr></thead><tbody><tr v-for="log in data.audit" :key="log.id"><td><strong>{{ log.actorName }}</strong></td><td>{{ log.campusName||'—' }}</td><td>{{ log.action }}</td><td>{{ log.detail }}</td><td>{{ datetime(log.createdAt) }}</td></tr></tbody></table></div></LoadState>
    </template></LoadState>
  </section>
</template>
