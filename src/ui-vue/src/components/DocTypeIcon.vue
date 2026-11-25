<script setup lang="ts">
import { computed } from 'vue'
import type { DocType } from '../types/SearchTypes'

const props = defineProps<{ type?: DocType }>()

const kind = computed<DocType>(() => props.type ?? 'other')

// French label shown on hover and read out by screen readers.
const LABELS: Record<DocType, string> = {
  pdf: 'Document PDF',
  doc: 'Document texte (Word, ODT)',
  sheet: 'Feuille de calcul',
  slides: 'Présentation',
  image: 'Image',
  mail: 'Message électronique',
  text: 'Fichier texte',
  other: 'Fichier'
}

const label = computed(() => LABELS[kind.value])
</script>

<template>
  <span class="doc-type-icon" :class="'dt-' + kind" :title="label" role="img" :aria-label="label">
    <!-- All glyphs share a 24x24 box and a 1.8 stroke so they weigh the same on the line. -->
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">

      <!-- Sheet of paper with a folded corner: shared by the page-like types -->
      <template v-if="kind === 'pdf' || kind === 'doc' || kind === 'text' || kind === 'other'">
        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
        <path d="M14 3v5h5" />
        <!-- PDF gets a filled band, plain text a couple of rules, Word a full block -->
        <template v-if="kind === 'pdf'">
          <rect x="8" y="13.5" width="8" height="4" rx="0.8" fill="currentColor" stroke="none" />
        </template>
        <template v-else-if="kind === 'doc'">
          <path d="M8.5 12.5h7M8.5 15.5h7M8.5 18h4" />
        </template>
        <template v-else-if="kind === 'text'">
          <path d="M8.5 13.5h7M8.5 17h5" />
        </template>
      </template>

      <!-- Grid: spreadsheet -->
      <template v-else-if="kind === 'sheet'">
        <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
        <path d="M3.5 9.5h17M3.5 14.5h17M9.5 4.5v15M15 4.5v15" />
      </template>

      <!-- Screen on a stand: presentation -->
      <template v-else-if="kind === 'slides'">
        <rect x="3.5" y="4" width="17" height="11" rx="2" />
        <path d="M12 15v4M9 21l3-2 3 2" />
      </template>

      <!-- Frame with a hill and a sun: image -->
      <template v-else-if="kind === 'image'">
        <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
        <circle cx="9" cy="10" r="1.6" />
        <path d="M4 17l4.5-4.5 3.5 3.5 2.5-2.5L20 18" />
      </template>

      <!-- Envelope: mail -->
      <template v-else-if="kind === 'mail'">
        <rect x="3" y="5.5" width="18" height="13" rx="2" />
        <path d="M3.5 7.5 12 13.5l8.5-6" />
      </template>
    </svg>
  </span>
</template>

<style scoped>
.doc-type-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.doc-type-icon svg {
  width: 18px;
  height: 18px;
  display: block;
}

/* One accent per family. Muted enough that a page of one single type
   does not turn into a column of colour. */
.dt-pdf    { color: #c5221f; }
.dt-doc    { color: #1967d2; }
.dt-sheet  { color: #1e7e34; }
.dt-slides { color: #b06000; }
.dt-image  { color: #8430ce; }
.dt-mail   { color: #0b7b83; }
.dt-text   { color: #5f6368; }
.dt-other  { color: #80868b; }
</style>
