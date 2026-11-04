<script setup lang="ts">
import { toasts } from '../lib/ui'
import AppIcon from './AppIcon.vue'
</script>
<template><Teleport to="body"><div class="toast-stack" aria-live="polite"><div v-for="toast in toasts" :key="toast.id" class="toast" :class="toast.tone"><AppIcon :name="toast.tone === 'error' ? 'CircleAlert' : 'CircleCheck'" :size="19" />{{ toast.text }}<button class="icon-btn" aria-label="关闭通知" @click="toasts.splice(toasts.indexOf(toast), 1)"><AppIcon name="X" :size="15" /></button></div></div></Teleport></template>
