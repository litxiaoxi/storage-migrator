<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

interface SelectOption {
  value: string
  label: string
  hint?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: SelectOption[]
    placeholder?: string
    disabled?: boolean
  }>(),
  {
    placeholder: '请选择',
    disabled: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const root = ref<HTMLElement | null>(null)
const open = ref(false)
const highlight = ref(-1)

const current = computed(() => props.options.find((option) => option.value === props.modelValue))

function openPanel() {
  if (props.disabled || open.value) {
    return
  }
  open.value = true
  const index = props.options.findIndex((option) => option.value === props.modelValue)
  highlight.value = index >= 0 ? index : 0
}

function closePanel() {
  open.value = false
  highlight.value = -1
}

function toggle() {
  if (open.value) {
    closePanel()
    return
  }
  openPanel()
}

function choose(index: number) {
  const option = props.options[index]
  if (!option) {
    return
  }
  emit('update:modelValue', option.value)
  closePanel()
}

function onKeydown(event: KeyboardEvent) {
  switch (event.key) {
    case 'Enter':
    case ' ':
      event.preventDefault()
      toggle()
      break
    case 'Escape':
      event.preventDefault()
      closePanel()
      break
    case 'ArrowDown':
      event.preventDefault()
      if (!open.value) {
        openPanel()
      } else {
        highlight.value = Math.min(props.options.length - 1, highlight.value + 1)
      }
      break
    case 'ArrowUp':
      event.preventDefault()
      if (!open.value) {
        openPanel()
      } else {
        highlight.value = Math.max(0, highlight.value - 1)
      }
      break
    case 'Tab':
      closePanel()
      break
    default:
      break
  }
}

function onDocumentPointerDown(event: MouseEvent) {
  if (!open.value) {
    return
  }
  const element = root.value
  if (element && !element.contains(event.target as Node)) {
    closePanel()
  }
}

onMounted(() => document.addEventListener('mousedown', onDocumentPointerDown))
onUnmounted(() => document.removeEventListener('mousedown', onDocumentPointerDown))
</script>

<template>
  <div ref="root" class="ps" :class="{ 'ps--open': open, 'ps--disabled': disabled }">
    <button
      type="button"
      class="ps__trigger"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="listbox"
      @click="toggle"
      @keydown="onKeydown"
    >
      <span class="ps__value" :class="{ 'ps__value--empty': !current }">
        {{ current ? current.label : placeholder }}
      </span>
      <svg class="ps__arrow" viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">
        <polyline
          points="6 9 12 15 18 9"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>

    <transition name="ps-pop">
      <ul v-if="open" class="ps__panel" role="listbox">
        <li
          v-for="(option, index) in options"
          :key="option.value"
          class="ps__option"
          :class="{
            'is-selected': option.value === modelValue,
            'is-highlight': index === highlight,
          }"
          role="option"
          :aria-selected="option.value === modelValue"
          @click="choose(index)"
          @mouseenter="highlight = index"
        >
          <span class="ps__option-main">
            <span class="ps__option-label">{{ option.label }}</span>
            <span v-if="option.hint" class="ps__option-hint">{{ option.hint }}</span>
          </span>
          <svg
            v-if="option.value === modelValue"
            class="ps__check"
            viewBox="0 0 24 24"
            width="14"
            height="14"
            aria-hidden="true"
          >
            <polyline
              points="20 6 9 17 4 12"
              fill="none"
              stroke="currentColor"
              stroke-width="2.4"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </li>
        <li v-if="!options.length" class="ps__empty">没有可选项</li>
      </ul>
    </transition>
  </div>
</template>

<style scoped>
.ps {
  position: relative;
  margin-bottom: 14px;
}

.ps__trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 12px;
  border: 1px solid #d9e1ea;
  border-radius: 10px;
  background: #fff;
  color: #0f172a;
  font-size: 13px;
  line-height: 1.4;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background-color 0.15s ease;
}

.ps__trigger:hover:not(:disabled) {
  border-color: #b7c4d4;
}

.ps__trigger:focus-visible {
  outline: none;
  border-color: #0f172a;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}

.ps--open .ps__trigger {
  border-color: #0f172a;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}

.ps__value {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ps__value--empty {
  color: #94a3b8;
}

.ps__arrow {
  flex: none;
  color: #64748b;
  transition: transform 0.18s ease;
}

.ps--open .ps__arrow {
  transform: rotate(180deg);
}

.ps--disabled .ps__trigger {
  background: #f8fafc;
  color: #94a3b8;
  cursor: not-allowed;
}

.ps__panel {
  position: absolute;
  z-index: 20;
  top: calc(100% + 6px);
  right: 0;
  left: 0;
  max-height: 264px;
  margin: 0;
  padding: 6px;
  overflow: auto;
  list-style: none;
  border: 1px solid #e4eaf2;
  border-radius: 12px;
  background: #fff;
  box-shadow:
    0 12px 28px rgba(15, 23, 42, 0.14),
    0 2px 6px rgba(15, 23, 42, 0.06);
}

.ps__option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #0f172a;
  font-size: 13px;
  cursor: pointer;
}

.ps__option.is-highlight {
  background: #f3f6fa;
}

.ps__option.is-selected {
  background: #eef3fb;
}

.ps__option-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ps__option-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ps__option-hint {
  color: #8494a8;
  font-size: 11px;
}

.ps__check {
  flex: none;
  color: #2563eb;
}

.ps__empty {
  padding: 10px;
  color: #94a3b8;
  font-size: 12px;
  text-align: center;
}

.ps-pop-enter-active,
.ps-pop-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}

.ps-pop-enter-from,
.ps-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
