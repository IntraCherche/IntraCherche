<script setup lang="ts">
import { ref, computed, watch, nextTick, useId } from 'vue'
import type { GeoOption } from '../types/SearchTypes'

// A text field restricted to a fixed list of values: typing filters the list,
// and leaving the field never keeps a value that isn't in it.

const props = defineProps<{
  modelValue: string
  options: GeoOption[]
  placeholder: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  enter: []
}>()

// Case-, accent- and punctuation-insensitive, so "cote d or" finds "Côte-d'Or".
const fold = (s: string) =>
  s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[-'\u2019\s]+/g, ' ').trim()

const listId = useId()
const text = ref(props.modelValue)
const open = ref(false)
const typed = ref(false) // until the user types, the whole list is shown
const active = ref(-1)
const listEl = ref<HTMLElement | null>(null)

watch(() => props.modelValue, (value) => { text.value = value })

const filtered = computed(() => {
  const q = typed.value ? fold(text.value) : ''
  return q ? props.options.filter(o => fold(o.value).includes(q)) : props.options
})

const scrollActiveIntoView = () => nextTick(() => {
  listEl.value?.querySelector<HTMLElement>(`[data-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' })
})

const openList = () => {
  if (open.value) return
  open.value = true
  active.value = filtered.value.findIndex(o => o.value === props.modelValue)
  scrollActiveIntoView()
}

const close = () => {
  open.value = false
  typed.value = false
}

const select = (option: GeoOption) => {
  text.value = option.value
  emit('update:modelValue', option.value)
  close()
}

// On leaving the field: accept an exact match or a single remaining candidate,
// otherwise revert to the last valid choice.
const commit = () => {
  const q = fold(text.value)
  if (!q) {
    text.value = ''
    emit('update:modelValue', '')
    close()
    return
  }
  const exact = props.options.find(o => fold(o.value) === q)
  const only = filtered.value.length === 1 ? filtered.value[0] : undefined
  const match = exact ?? only
  if (match) {
    select(match)
  } else {
    text.value = props.modelValue
    close()
  }
}

const onInput = () => {
  typed.value = true
  open.value = true
  active.value = filtered.value.length > 0 ? 0 : -1
}

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    if (!open.value) return openList()
    const n = filtered.value.length
    if (n === 0) return
    active.value = e.key === 'ArrowDown' ? (active.value + 1) % n : (active.value - 1 + n) % n
    scrollActiveIntoView()
  } else if (e.key === 'Enter') {
    const option = open.value ? filtered.value[active.value] : undefined
    if (option) {
      select(option)
    } else {
      commit()
      emit('enter')
    }
  } else if (e.key === 'Escape' && open.value) {
    e.preventDefault()
    text.value = props.modelValue
    close()
  }
}
</script>

<template>
  <div class="geo-combo">
    <input
      v-model="text"
      type="text"
      class="geo-combo-input"
      role="combobox"
      autocomplete="off"
      aria-autocomplete="list"
      :aria-label="placeholder"
      :aria-expanded="open"
      :aria-controls="listId"
      :aria-activedescendant="open && active >= 0 ? `${listId}-${active}` : undefined"
      :placeholder="placeholder"
      @click="openList"
      @input="onInput"
      @keydown="onKeydown"
      @blur="commit"
    />
    <span class="geo-combo-caret" aria-hidden="true">▾</span>
    <ul v-if="open && filtered.length > 0" :id="listId" ref="listEl" role="listbox" class="geo-combo-list">
      <li
        v-for="(option, i) in filtered"
        :id="`${listId}-${i}`"
        :key="option.value"
        :data-index="i"
        role="option"
        :aria-selected="option.value === modelValue"
        :class="{ active: i === active, selected: option.value === modelValue }"
        @mousedown.prevent="select(option)"
        @mouseenter="active = i"
      >
        <span>{{ option.value }}</span>
        <span v-if="option.hint" class="geo-combo-hint">{{ option.hint }}</span>
      </li>
    </ul>
    <div v-else-if="open && typed" class="geo-combo-list geo-combo-empty">Aucune correspondance</div>
  </div>
</template>

<style scoped>
.geo-combo {
  position: relative;
  min-width: 0;
}

.geo-combo-input {
  width: 100%;
  box-sizing: border-box;
  height: 34px;
  border: 1px solid #dfe1e5;
  border-radius: 8px;
  padding: 0 26px 0 12px;
  font-size: 14px;
  outline: none;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.geo-combo-input:focus {
  border-color: #4285F4;
  box-shadow: 0 0 0 2px rgba(66, 133, 244, 0.2);
}

.geo-combo-caret {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 11px;
  color: #70757a;
  pointer-events: none;
}

.geo-combo-list {
  position: absolute;
  top: calc(100% + 2px);
  left: 0;
  min-width: 100%;
  width: max-content;
  max-width: min(360px, 90vw);
  max-height: 240px;
  overflow-y: auto;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  box-sizing: border-box;
  background: white;
  border: 1px solid #dfe1e5;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(32, 33, 36, 0.2);
  z-index: 50;
}

.geo-combo-list li {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  padding: 6px 12px;
  font-size: 13px;
  color: #202124;
  cursor: pointer;
}

.geo-combo-list li.active {
  background: #f1f3f4;
}

.geo-combo-list li.selected {
  font-weight: 600;
}

.geo-combo-hint {
  font-size: 11px;
  font-weight: 400;
  color: #70757a;
  white-space: nowrap;
}

.geo-combo-empty {
  padding: 8px 12px;
  font-size: 13px;
  color: #70757a;
  font-style: italic;
}
</style>
