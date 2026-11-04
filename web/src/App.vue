<script setup lang="ts">
import SoftSelect from './components/SoftSelect.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from './components/AppIcon.vue'
import ModalHost from './components/ModalHost.vue'
import ToastHost from './components/ToastHost.vue'
import LoginView from './views/LoginView.vue'
import MobileShell from './mobile/MobileShell.vue'
import { mobileApp } from './mobile/platform'
import { user, health, initialize, demoLogin, logout, selectedDate, globalSearch, roleName, demoRole, demoRoleName, campuses, campusError, campusLoading, currentCampusId, currentCampusName, contextVersion, selectCampus, refreshCampuses, allCampusLabel, showAllCampusOption } from './lib/state'
import { connection } from './lib/api'
import { navigation } from './lib/navigation'
import { act, openDialog } from './lib/ui'
import type { Role, DemoRole } from './lib/types'
const route = useRoute(); const router = useRouter(); const sidebarOpen = ref(false)
const menus = computed(() => navigation.filter(item => user.value && (item.roles as Role[]).includes(user.value.role)))
const groups = computed(() => [...new Set(menus.value.map(item => item.group))])
const currentName = computed(() => navigation.find(item => item.path === route.path)?.name || '工作台')
const dateLabel = computed(() => new Date(`${selectedDate.value}T12:00:00`).toLocaleDateString('zh-CN', { month:'long',day:'numeric',weekday:'long' }))
watch(() => route.path, () => { sidebarOpen.value = false })
watch([user, () => route.path], () => { if (user.value && !menus.value.some(item => item.path === route.path) && !(mobileApp && (route.path === '/growth' || route.path === '/follow-ups' || route.path === '/teacher-attendance' && user.value.role !== 'PARENT'))) router.replace('/') })
watch(() => connection.unauthorized, value => { if (value) logout() })
onMounted(initialize)
async function switchRole(value: string | number) {
  const role = value as DemoRole
  const ok = await act(() => demoLogin(role), `已切换为${demoRoleName[role]}（演示）`)
  if (ok) { globalSearch.value = ''; router.replace('/') }
}
async function signOut() { if (await openDialog({ title:'退出当前账号', description:'退出后将返回登录入口，未提交的表单内容不会保存。',confirm:'退出登录' })) logout() }
watch(contextVersion, () => {
  sidebarOpen.value = false
  if (Object.keys(route.query).length) router.replace({ path: route.path, query: {} })
}, { flush: 'sync' })
function changeCampus(value: string | number) {
  selectCampus(value ? Number(value) : null)
}
function search() { router.push('/students') }
</script>
<template>
  <div v-if="!health.ready" class="boot-screen"><div class="brand-mark"><AppIcon name="Sprout" :size="32" /></div><h2>{{mobileApp ? '天健悦成长' : '晚辅智管'}}</h2><p>让陪伴更有温度，让成长清晰可见</p><span class="spinner"></span></div>
  <LoginView v-else-if="!user" />
  <MobileShell v-else-if="mobileApp" />
  <div v-else class="app-shell">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false"></div>
    <aside class="sidebar" :class="{open:sidebarOpen}">
      <RouterLink to="/" class="brand"><div class="brand-mark"><AppIcon name="Sprout" :size="27" /></div><div><strong>晚辅智管<span class="brand-dot"></span></strong><small>WANFU · SMART CAMPUS</small></div></RouterLink>
      <div class="campus-switch"><div class="campus-icon"><AppIcon name="School" :size="20" /></div><label><span class="muted small-text">当前校区</span><SoftSelect :model-value="currentCampusId ?? ''" aria-label="选择当前校区" @change="changeCampus" :options="[...(showAllCampusOption ? [{value: '' as const, label: allCampusLabel}] : []), ...campuses.map(campus => ({value: campus.id, label: campus.name}))]" /><small>{{ campusLoading ? '正在同步校区…' : '仅显示当前账号授权范围' }}</small></label></div><button v-if="campusError" class="campus-retry text-btn" @click="refreshCampuses">{{ campusError }} 点击重试</button>
      <nav aria-label="主导航"><div v-for="group in groups" :key="group" class="nav-group"><div v-if="group !== '工作空间'" class="nav-group-label">{{ group }}</div><RouterLink v-for="item in menus.filter(n => n.group === group)" :key="item.path" :to="item.path" class="nav-link" active-class="active" :exact-active-class="item.path === '/' ? 'active' : 'exact-active'"><AppIcon :name="item.icon" :size="19" /><span>{{ item.name }}</span><span v-if="item.path === '/' && route.path === '/'" class="nav-dot"></span></RouterLink></div></nav>
      <div class="sidebar-bottom"><div class="help-card"><AppIcon name="HeartHandshake" :size="23" /><strong>用心陪伴每一份成长</strong><small>把时间留给孩子，把事务交给智管。</small></div><button class="profile" @click="signOut"><span class="avatar">{{ user.name?.slice(-2) }}</span><span><strong>{{ user.name }}</strong><small>{{ roleName[user.role] }}</small></span><AppIcon name="LogOut" :size="17" /></button></div>
    </aside>
    <div class="main-shell"><header class="topbar"><div class="breadcrumbs"><button class="icon-btn mobile-menu" aria-label="打开导航菜单" @click="sidebarOpen = true"><AppIcon name="Menu" /></button><AppIcon name="PanelLeft" :size="17" class="desktop-only" /><span class="desktop-only">工作空间</span><AppIcon name="ChevronRight" :size="14" class="desktop-only" /><strong>{{ currentName }}</strong></div><div class="topbar-tools"><form class="top-search" @submit.prevent="search"><AppIcon name="Search" :size="17" /><input v-model="globalSearch" placeholder="搜索学生姓名、家长…" aria-label="搜索学生" /><kbd>↵</kbd></form><span v-if="user.demo" class="demo-label">演示环境</span><label v-if="user.demo" class="role-select"><AppIcon name="ShieldCheck" :size="16" /><SoftSelect :model-value="demoRole" aria-label="切换演示角色" @change="switchRole" :options="[{value:'ADMIN',label:'总管理员'},{value:'CAMPUS_ADMIN',label:'校区校长'},{value:'TEACHER',label:'教师'},{value:'PARENT',label:'家长'}]" /></label><span class="avatar top-avatar">{{ user.name?.slice(-2) }}</span></div></header>
    <main :key="contextVersion" class="main-content"><div class="workspace-meta"><span><i class="live-dot"></i>{{ user.demo ? '演示工作空间' : '机构工作空间' }} · {{ currentCampusName }}</span><label class="date-picker"><AppIcon name="CalendarDays" :size="15" /><span>{{ dateLabel }}</span><input v-model="selectedDate" type="date" aria-label="业务日期" /></label></div><RouterView v-slot="{ Component }"><component :is="Component" :key="`${contextVersion}-${route.path}`" /></RouterView></main><footer class="app-footer"><span>晚辅智管 <span class="footer-dot">·</span> 用心教育，温暖成长</span><span>教学与成长，一目了然</span></footer></div>
  </div><ModalHost :key="contextVersion" /><ToastHost />
</template>
