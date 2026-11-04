<script setup lang="ts">
import { computed } from 'vue'
import { label } from '../lib/ui'
const props = defineProps<{ value?: string | null }>()
const tone = computed(() => ['COMPLETED','MASTERED','CONFIRMED','PUBLISHED','PRESENT','SETTLED','DONE','APPROVED','VERIFIED','ACTIVE','USED'].includes(props.value || '') ? 'green' : ['CORRECTION','UNCORRECTED','FAILED','REJECTED','VERIFY_REJECTED'].includes(props.value || '') ? 'red' : ['PENDING_REVIEW','PENDING_RECHECK','IN_PROGRESS','PENDING','REQUESTED','IN_REVIEW','ON_LEAVE'].includes(props.value || '') ? 'amber' : 'gray')
</script>
<template><span class="badge" :class="tone"><i></i>{{ label(value) }}</span></template>
