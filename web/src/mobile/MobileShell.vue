<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { App as DeviceApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import AppIcon from '../components/AppIcon.vue'
import SoftSelect from '../components/SoftSelect.vue'
import { navigation } from '../lib/navigation'
import { user, selectedDate, contextVersion, campuses, currentCampusId, currentCampusName, selectCampus, logout, roleName, demoLogin, demoRole, demoRoleName, campusError, refreshCampuses, allCampusLabel } from '../lib/state'
import { act, openDialog } from '../lib/ui'
import { nativeApp } from './platform'
import type { DemoRole } from '../lib/types'
const router=useRouter(),route=useRoute(),menuOpen=ref(false)
const menus=computed(()=>[...navigation.filter(item=>user.value&&(item.roles as string[]).includes(user.value.role)),{path:'/growth',name:'今日成长',icon:'Sprout',group:'教学管理',roles:[]},{path:'/follow-ups',name:'关注与沟通',icon:'MessageCircle',group:'教学管理',roles:[]},...(user.value?.role!=='PARENT'?[{path:'/teacher-attendance',name:'老师签到',icon:'UserCheck',group:'教学管理',roles:[]}]:[])])
const title=computed(()=>route.path==='/'?'天健悦成长':menus.value.find(m=>m.path===route.path)?.name||'天健悦成长')
const tabs=computed(()=>[{path:'/',name:user.value?.role==='PARENT'?'今日成长':'工作台',icon:'House'},{path:'/homework',name:'作业',icon:'NotebookPen'},{path:'/follow-ups?kind=MESSAGE',name:'消息',icon:'MessageCircle'}])
watch([()=>route.path,contextVersion],()=>{menuOpen.value=false})
async function signOut(){if(await openDialog({title:'退出登录',description:'确认退出当前账号？',confirm:'退出'}))await logout()}
async function role(value:string|number){if(await act(()=>demoLogin(value as DemoRole),'已切换演示身份'))await router.replace('/')}
let back:PluginListenerHandle|undefined,alive=true
onMounted(async()=>{
  if(!nativeApp)return
  const handle=await DeviceApp.addListener('backButton',()=>{
    if(menuOpen.value){menuOpen.value=false;return}
    const overlay=document.querySelector('.modal-overlay')
    if(overlay){
      document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}))
      if(document.contains(overlay))overlay.querySelector<HTMLButtonElement>('button[aria-label^="关闭"]:not(:disabled)')?.click()
      return
    }
    if(route.path!=='/'){if(router.options.history.state.back)router.back();else void router.replace('/');return}
    void DeviceApp.exitApp()
  })
  if(alive)back=handle;else await handle.remove()
})
onBeforeUnmount(()=>{alive=false;void back?.remove()})
</script>
<template>
  <div class="mobile-shell">
    <header class="mobile-top"><button v-if="route.path!=='/'" class="icon-btn" aria-label="返回上一页" @click="router.options.history.state.back?router.back():router.replace('/')"><AppIcon name="ChevronLeft" /></button><span v-else class="mobile-brand"><AppIcon name="Sprout" :size="23" /></span><strong>{{title}}</strong><button class="icon-btn" aria-label="账号与全部功能" @click="menuOpen=true"><AppIcon name="CircleUserRound" /></button></header>
    <div class="mobile-context"><SoftSelect :model-value="currentCampusId??''" aria-label="当前校区" :options="[{value:'',label:allCampusLabel},...campuses.map(c=>({value:c.id,label:c.name}))]" @change="value=>selectCampus(value?Number(value):null)" /><input v-model="selectedDate" type="date" aria-label="业务日期" /></div>
    <button v-if="campusError" class="text-btn" @click="refreshCampuses">{{campusError}} · 重试</button>
    <main :key="contextVersion" class="mobile-main"><RouterView v-slot="{Component}"><component :is="Component" :key="`${contextVersion}-${route.path}`" /></RouterView></main>
    <nav class="mobile-tabs" aria-label="App主导航"><RouterLink v-for="tab in tabs" :key="tab.path" :to="tab.path" :class="{selected:route.path===tab.path.split('?')[0]}"><AppIcon :name="tab.icon" :size="23" /><span>{{tab.name}}</span></RouterLink><button :class="{selected:menuOpen}" @click="menuOpen=true"><AppIcon name="LayoutGrid" :size="23" /><span>全部</span></button></nav>
    <Teleport to="body"><div v-if="menuOpen" class="mobile-menu-overlay" @click.self="menuOpen=false"><section class="mobile-menu-sheet" role="dialog" aria-modal="true" aria-label="账号与全部功能"><header><div><strong>{{user?.name}}</strong><p>{{user&&roleName[user.role]}} · {{currentCampusName}}</p></div><button class="icon-btn" aria-label="关闭全部功能" @click="menuOpen=false"><AppIcon name="X" /></button></header><label v-if="user?.demo" class="mobile-demo">演示身份<SoftSelect :model-value="demoRole" aria-label="演示身份" :options="Object.entries(demoRoleName).map(([value,label])=>({value,label}))" @change="role" /></label><div class="mobile-menu-grid"><RouterLink v-for="item in menus" :key="item.path" :to="item.path" @click="menuOpen=false"><AppIcon :name="item.icon" :size="22" /><span>{{item.name}}</span></RouterLink></div><button class="btn secondary full" @click="signOut">退出登录</button></section></div></Teleport>
  </div>
</template>
