<script setup lang="ts">
import { mobileApp } from '../mobile/platform'
defineProps<{ title: string; subtitle: string; eyebrow?: string }>()
</script>
<template><header class="page-heading"><div><div class="eyebrow">{{ eyebrow || (mobileApp ? '天健悦成长' : '晚辅智管 · 机构工作空间') }}</div><h1>{{ title }}</h1><p>{{ subtitle }}</p></div><div class="heading-actions"><slot /></div></header></template>
