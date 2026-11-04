<script setup lang="ts" generic="T extends string | number">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import AppIcon from './AppIcon.vue'

defineOptions({ inheritAttrs: false })
const props = defineProps<{
  modelValue?: T | null
  options: { value: T; label: string; disabled?: boolean }[]
  placeholder?: string
  disabled?: boolean
  required?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: T]; change: [value: T] }>()
const id = useId()
const trigger = ref<HTMLButtonElement>()
const menu = ref<HTMLElement>()
const portal = ref<HTMLElement | string>('body')
const open = ref(false)
const active = ref(-1)
const position = ref({ left: '0px', top: '0px', width: '0px', maxHeight: '280px' })
const selected = computed(() => props.options.findIndex(option => String(option.value) === String(props.modelValue ?? '')))
const caption = computed(() => props.options[selected.value]?.label || props.placeholder || '请选择')
let typed = ''
let typedAt = 0

function close() { open.value = false; typed = '' }
function reveal() {
  if (props.disabled || !props.options.some(option => !option.disabled)) return
  const rect = trigger.value!.getBoundingClientRect()
  portal.value = trigger.value!.closest<HTMLElement>('[role="dialog"]') || 'body'
  const below = window.innerHeight - rect.bottom - 12
  const above = rect.top - 12
  const upwards = below < 180 && above > below
  const height = Math.min(280, Math.max(80, upwards ? above : below))
  const width = Math.min(Math.max(rect.width, 180), window.innerWidth - 24)
  position.value = {
    left: `${Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))}px`,
    top: `${upwards ? rect.top - 6 : rect.bottom + 6}px`,
    width: `${width}px`, maxHeight: `${height}px`
  }
  open.value = true
  active.value = selected.value >= 0 && !props.options[selected.value]?.disabled ? selected.value : props.options.findIndex(option => !option.disabled)
  nextTick(() => {
    if (menu.value) menu.value.style.transform = upwards ? 'translateY(-100%)' : ''
    scrollActive()
  })
}
function scrollActive() { menu.value?.querySelector(`[data-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' }) }
function choose(index: number) {
  const option = props.options[index]
  if (!open.value || props.disabled || !option || option.disabled) return
  close()
  if (String(option.value) !== String(props.modelValue ?? '')) {
    emit('update:modelValue', option.value)
    emit('change', option.value)
  }
  trigger.value?.focus()
}
function keydown(event: KeyboardEvent) {
  const { key } = event
  if (key === 'Tab') { close(); return }
  if (key === 'Escape' && open.value) { event.preventDefault(); event.stopPropagation(); close(); return }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(key)) {
    event.preventDefault(); event.stopPropagation()
    if (!open.value) { reveal(); return }
    if (key === 'Enter' || key === ' ') { choose(active.value); return }
    const enabled = props.options.map((option, index) => option.disabled ? -1 : index).filter(index => index >= 0)
    const cursor = enabled.indexOf(active.value)
    active.value = key === 'Home' ? enabled[0]! : key === 'End' ? enabled.at(-1)! : enabled[(cursor + (key === 'ArrowDown' ? 1 : -1) + enabled.length) % enabled.length]!
    nextTick(scrollActive)
  } else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    event.preventDefault()
    if (!open.value) reveal()
    const now = Date.now()
    typed = now - typedAt > 700 ? key : typed + key
    typedAt = now
    const index = props.options.findIndex(option => !option.disabled && option.label.toLocaleLowerCase().startsWith(typed.toLocaleLowerCase()))
    if (index >= 0) { active.value = index; nextTick(scrollActive) }
  }
}
function outside(event: PointerEvent) {
  if (!trigger.value?.contains(event.target as Node) && !menu.value?.contains(event.target as Node)) close()
}
function viewport(event: Event) { if (!menu.value?.contains(event.target as Node)) close() }
watch(open, value => {
  if (value) {
    document.addEventListener('pointerdown', outside)
    window.addEventListener('resize', close)
    document.addEventListener('scroll', viewport, true)
  } else removeListeners()
})
function removeListeners() {
  document.removeEventListener('pointerdown', outside)
  window.removeEventListener('resize', close)
  document.removeEventListener('scroll', viewport, true)
}
watch(() => [props.options, props.disabled, props.modelValue], close)
onBeforeUnmount(removeListeners)
</script>

<template>
  <button v-bind="$attrs" ref="trigger" type="button" class="soft-select" :class="{ 'select-open': open }" role="combobox" aria-haspopup="listbox" :aria-expanded="open" :aria-controls="open ? `${id}-list` : undefined" :aria-activedescendant="open && active >= 0 ? `${id}-${active}` : undefined" :aria-required="required || undefined" :disabled="disabled || !options.some(option => !option.disabled)" @click="open ? close() : reveal()" @keydown="keydown" @blur="close">
    <span :class="{ 'select-placeholder': selected < 0 }">{{ caption }}</span><AppIcon name="ChevronDown" :size="15" />
  </button>
  <Teleport :to="portal">
    <div v-if="open" :id="`${id}-list`" ref="menu" class="soft-select-menu" role="listbox" :style="position" :aria-label="caption" @mousedown.prevent>
      <div v-for="(option, index) in options" :id="`${id}-${index}`" :key="option.value" class="soft-select-option" :class="{ 'option-active': index === active, 'option-selected': index === selected, 'option-disabled': option.disabled }" role="option" :aria-selected="index === selected" :aria-disabled="option.disabled || undefined" :data-index="index" @pointermove="!option.disabled && (active = index)" @click="choose(index)">
        <span>{{ option.label }}</span><AppIcon v-if="index === selected" name="Check" :size="16" />
      </div>
    </div>
  </Teleport>
</template>
