<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  currentPage: number;
  totalResults: number;
  pageSize: number;
}>();

const emit = defineEmits<{
  (e: 'changePage', page: number): void;
}>();

const totalPages = computed(() => Math.ceil(props.totalResults / props.pageSize));

// Logic to show a window of pages (e.g., current +/- 2)
const visiblePages = computed((): number[] => {
  const range: number[] = [];
  const start = Math.max(0, props.currentPage - 2);
  const end = Math.min(totalPages.value - 1, props.currentPage + 2);

  if (totalPages.value <= 0) return range; // Return empty array if no pages

  for (let i = start; i <= end; i++) {
    range.push(i);
  }
  return range;
});
</script>

<template>
  <footer v-if="totalPages > 1" class="pagination-footer">
    <button
      :disabled="currentPage === 0"
      @click="emit('changePage', currentPage - 1)"
      class="nav-btn"
    >
      Précédent
    </button>

    <div class="pages">
      <span v-if="visiblePages.length > 0 && (visiblePages[0] ?? 0) > 0" @click="emit('changePage', 0)">
        1 ...
      </span>

      <span
        v-for="p in visiblePages"
        :key="p"
        :class="{ 'active': p === currentPage }"
        @click="emit('changePage', p)"
      >
        {{ p + 1 }}
      </span>

      <span v-if="visiblePages.length > 0 && ((visiblePages[visiblePages.length - 1] ?? 0) < totalPages - 1)"
            @click="emit('changePage', totalPages - 1)">
        ... {{ totalPages }}
      </span>
    </div>

    <button
      :disabled="currentPage >= totalPages - 1"
      @click="emit('changePage', currentPage + 1)"
      class="nav-btn"
    >
      Suivant
    </button>
  </footer>
</template>

<style scoped>
.pagination-footer {
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 40px 0;
  gap: 15px;
  font-family: Arial, sans-serif;
}

.pages span {
  padding: 8px 14px;
  cursor: pointer;
  color: #1a0dab;
  font-size: 14px;
}

.pages span.active {
  color: #202124;
  font-weight: bold;
  cursor: default;
}

.nav-btn {
  background: white;
  border: 1px solid #dfe1e5;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.nav-btn:disabled {
  color: #bdc1c6;
  border-color: #f1f1f1;
  cursor: not-allowed;
}
</style>