import { createApp } from 'vue'
import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import { mobileApp } from './mobile/platform'
import App from './App.vue'
import './style.css'
if (mobileApp) { document.documentElement.classList.add('native-app'); void import('./mobile/mobile.css'); document.title = '天健悦成长' }
const router = createRouter({ history: mobileApp ? createWebHashHistory() : createWebHistory(), routes: [
  {path:'/',component:mobileApp ? ()=>import('./mobile/MobileHome.vue') : ()=>import('./views/DashboardView.vue')},
  ...(mobileApp ? [{path:'/growth',component:()=>import('./mobile/MobileHome.vue')},{path:'/follow-ups',component:()=>import('./mobile/FollowUpsView.vue')},{path:'/teacher-attendance',component:()=>import('./mobile/TeacherAttendance.vue')}] : []),
  {path:'/students',component:()=>import('./views/StudentsView.vue')},
  {path:'/safety',component:()=>import('./views/SafetyView.vue')},
  {path:'/privacy',component:()=>import('./views/PrivacyView.vue')},
  {path:'/capture',component:()=>import('./views/CaptureView.vue')},
  {path:'/rewards',component:()=>import('./views/RewardsView.vue')},
  {path:'/student-notifications',component:()=>import('./views/NotificationsView.vue')},
  {path:'/attendance',component:()=>import('./views/AttendanceView.vue')},
  {path:'/homework',component:()=>import('./views/HomeworkView.vue')},
  {path:'/mistakes',component:()=>import('./views/MistakesView.vue')},
  {path:'/fitness',component:()=>import('./views/FitnessView.vue')},
  {path:'/schedules',component:()=>import('./views/SchedulesView.vue')},
  {path:'/ledger',component:()=>import('./views/FinanceView.vue'),props:{mode:'ledger'}},
  {path:'/renewals',component:()=>import('./views/FinanceView.vue'),props:{mode:'renewals'}},
  {path:'/campus-reports',component:()=>import('./views/CampusReportsView.vue')},
  {path:'/family-services',component:()=>import('./views/FamilyServicesView.vue')},
  {path:'/class-reports',component:()=>import('./views/ClassReportsView.vue')},
  {path:'/summaries',component:()=>import('./views/SummariesView.vue')},
  {path:'/campuses',component:()=>import('./views/CampusesView.vue')},
  {path:'/settings',component:()=>import('./views/SettingsView.vue')},
  {path:'/:pathMatch(.*)*',redirect:'/'}
], scrollBehavior:()=>({top:0}) })
createApp(App).use(router).mount('#app')
