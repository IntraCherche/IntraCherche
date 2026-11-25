<script setup lang="ts">
import type { SherlockCitation, SherlockQuote } from '../stores/sherlock'
import type { Inline } from '../utils/answerMarkdown'

export type AnswerPart =
  | { type: 'text'; content: string }
  | { type: 'quote'; content: string; quote: SherlockQuote }
  | { type: 'citation'; label: string; citation: SherlockCitation }
  | { type: 'citation-dead'; label: string }

defineProps<{
  inlines: Inline<AnswerPart>[]
  quoteTitle: (q: SherlockQuote) => string
}>()
defineEmits<{ open: [c: SherlockCitation] }>()
</script>

<template>
  <!-- citations are links, not buttons: a long file name then wraps like the text around it -->
  <template v-for="(x, i) in inlines" :key="i">
    <component :is="x.bold ? 'strong' : 'span'">
      <template v-if="x.part.type === 'text'">{{ x.part.content }}</template>
      <span
        v-else-if="x.part.type === 'quote'"
        :class="x.part.quote.verified ? 'quote-verified' : 'quote-unverified'"
        :title="quoteTitle(x.part.quote)"
      >{{ x.part.content }}<span v-if="x.last" class="quote-mark" aria-hidden="true">{{ x.part.quote.verified ? '✓' : '⚠' }}</span></span>
      <a
        v-else-if="x.part.type === 'citation'"
        href="#"
        class="inline-citation"
        :title="x.part.citation.path"
        @click.prevent="$emit('open', x.part.citation)"
      >[{{ x.part.label }}]</a>
      <span v-else class="inline-citation-dead">[{{ x.part.label }}]</span>
    </component>
  </template>
</template>
