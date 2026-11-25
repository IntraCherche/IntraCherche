<script setup lang="ts">
import { computed, nextTick } from 'vue';

const props = defineProps<{
  availableIndices: string[];
  modelValue: string[];
  indexCounts: Record<string, number>;
}>();

const emit = defineEmits(['update:modelValue', 'change']);

const isAllSelected = computed(() =>
  props.modelValue.length === props.availableIndices.length && props.availableIndices.length > 0
);

const toggleAll = () => {
  const newValue = isAllSelected.value ? [] : [...props.availableIndices];
  emit('update:modelValue', newValue);
  emit('change');
};

const isolateIndex = (name: string) => {
  // 1. Set the selection to ONLY this index
  const newValue = [name];

  // 2. Emit the update
  emit('update:modelValue', newValue);

  // 3. Trigger the search immediately
  nextTick(() => {
    emit('change');
  });
};
</script>

<template>
  <aside id="tour-sidebar" class="search-sidebar">
    <div class="filter-group">
      <div class="filter-header">
        <h3 title="Index dans lesquels chercher">Sources</h3>
        <button @click="toggleAll" class="text-link">
          {{ isAllSelected ? 'Désélectionner tout' : 'Sélectionner tout' }}
        </button>
      </div>

      <div v-for="name in availableIndices" :key="name" class="filter-item">
        <input
          type="checkbox"
          :id="'idx-' + name"
          :value="name"
          :checked="modelValue.includes(name)"
          @change="(e) => {
              const isChecked = (e.target as HTMLInputElement).checked;
              let newSelection = [...modelValue];

              if (isChecked) {
                if (!newSelection.includes(name)) newSelection.push(name);
              } else {
                newSelection = newSelection.filter(item => item !== name);
              }

               // ONLY update the list, DO NOT trigger the search
              emit('update:modelValue', newSelection);

          }"
        />
        <label :for="'idx-' + name" class="index-label">
          <span class="index-name">{{ name }}</span>
        </label>
        <span
            v-if="indexCounts && indexCounts[name] !== undefined"
            class="count-badge clickable"
                title="Cliquer pour afficher seulement les résultats de cet index"
                @click.stop="isolateIndex(name)"
        >
                        {{ indexCounts[name] || 0}}
        </span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.search-sidebar {
  position: sticky;
  top: 20px;
  padding-right: 20px;
  border-right: 1px solid #ebebeb;
  min-width: 200px; /* Ensures sidebar doesn't collapse */
}

.filter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid #f8f9fa;
}

.text-link {
  background: none;
  border: none;
  color: #1a0dab;
  font-size: 11px;
  cursor: pointer;
  text-decoration: underline;
  padding: 0;
}

.filter-group h3 {
  font-size: 13px;
  text-transform: uppercase;
  color: #202124;
  margin: 0;
  letter-spacing: 0.5px;
  font-weight: 700;
}

.filter-item {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  width: 100%;
}

.filter-item input {
  margin-right: 12px;
  cursor: pointer;
  width: 16px;
  height: 16px;
}


.index-label {
  flex: 1; /* This pushes the badge to the right */
  cursor: pointer;
  font-size: 14px;
  color: #4d5156;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: 10px; /* Space between name and badge */
}

/* The name inside the label */
.index-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: 8px;
}

/* Styling the count badge */
.count-badge {
  background-color: #f1f3f4;
  color: #70757a;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid #f1f3f4;
  user-select: none; /* Prevents text selection on accidental clicks */
  flex-shrink: 0;
}

.count-badge.clickable {
  cursor: pointer;
  pointer-events: auto;
  transition: all 0.2s ease;
}

.count-badge.clickable:hover {
  background-color: #1a73e8; /* Blue background on hover */
  color: white;
  border-color: #1a73e8;
}

/* Optional: add a tooltip-like feel */
.count-badge.clickable:active {
  transform: scale(0.95);
}

/* Subtle hover effect for the whole row */
.filter-item:hover .count-badge {
  background-color: #e8eaed;
  color: #202124;
}
</style>