<script setup lang="ts">
import AppIcon from './AppIcon.vue'
withDefaults(defineProps<{ loading?: boolean; error?: string; empty?: boolean; title?: string; description?: string }>(), { title: '这里还没有记录', description: '调整筛选条件，或添加第一条记录。' })
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="loading" class="state-box" aria-live="polite"><span class="spinner"></span><p>正在加载数据…</p></div>
  <div v-else-if="error" class="state-box error-state" role="alert"><div class="empty-icon"><AppIcon name="CloudOff" :size="28" /></div><h3>数据暂时未能加载</h3><p>{{ error }}</p><button class="btn secondary" @click="$emit('retry')"><AppIcon name="RefreshCw" :size="15" />重新加载</button></div>
  <div v-else-if="empty" class="state-box"><div class="empty-icon"><AppIcon name="Inbox" :size="28" /></div><h3>{{ title }}</h3><p>{{ description }}</p></div>
  <slot v-else />
</template>
