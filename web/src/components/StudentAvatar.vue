<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import type { Attachment } from '../lib/types'
import { attachmentUrl } from '../mobile/endpoints'
import { serviceBase } from '../mobile/platform'
const props=defineProps<{student:{name:string;avatar?:Attachment|null}}>()
const failed=ref(false),src=computed(()=>props.student.avatar?attachmentUrl(serviceBase,props.student.avatar.url):'')
watch(src,()=>failed.value=false)
</script>
<template><span class="student-avatar child-photo"><img v-if="src&&!failed" :src="src" :alt="`${student.name}的头像`" @error="failed=true" /><template v-else>{{student.name.slice(-2)}}</template></span></template>
<style scoped>.child-photo{overflow:hidden;flex-shrink:0}.child-photo img{width:100%;height:100%;object-fit:cover}</style>
