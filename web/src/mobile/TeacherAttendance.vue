<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { api,post,query,time,businessDate } from '../lib/api'
import { selectedDate,user,campuses,refreshCampuses } from '../lib/state'
import { useResource,openDialog } from '../lib/ui'
import { currentLocation } from '../lib/location'
const locationController=new AbortController()
onBeforeUnmount(()=>locationController.abort())
import LoadState from '../components/LoadState.vue'
import type { TeacherAttendance } from './types'
const {data,loading,error,refresh}=useResource(async()=>{await refreshCampuses();return api<TeacherAttendance[]>('/teacher-attendance'+query({date:selectedDate.value}))},[selectedDate])
const campusFor=(row:TeacherAttendance)=>campuses.value.find(c=>c.id===row.campusId)
async function check(row:TeacherAttendance){if(await openDialog({title:'确认本人签到',description:`在${row.campusName}登记今天的到校时间。签到地点：${campusFor(row)?.checkInAddress||'尚未设置'}，允许范围${campusFor(row)?.checkInRadius||0}米。确认后获取当前位置。`,submit:async()=>post('/teacher-attendance/check-in',{campusId:row.campusId,location:await currentLocation(locationController.signal)}),success:'签到成功'}))await refresh()}
</script>
<template><h1 class="mobile-page-title">老师签到</h1><LoadState :loading="loading" :error="error" :empty="!data?.length" @retry="refresh"><article v-for="row in data" :key="`${row.teacherId}-${row.campusId}`" class="mobile-roster panel"><header><h3>{{row.teacherName}}</h3><span>{{row.checkInTime?'已签到':'未签到'}}</span></header><p>{{row.campusName}} · {{time(row.checkInTime)}}</p><p>{{campusFor(row)?.checkInAddress||'未设置签到地点，请联系校区管理员'}}<template v-if="campusFor(row)?.checkInRadius"> · 周围 {{campusFor(row)?.checkInRadius}} 米</template></p><button v-if="user?.role==='TEACHER'&&row.teacherId===user.id&&!row.checkInTime&&selectedDate===businessDate()" class="btn primary full" :disabled="!campusFor(row)?.checkInRadius" @click="check(row)">本人签到</button></article></LoadState></template>
