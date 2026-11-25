<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSherlockStore } from '../stores/sherlock'
import { useAuthStore } from '../stores/auth'
import { useIndicesStore } from '../stores/indices'
import { useNotificationStore } from '../stores/notifications'
import apiClient, { DOWNLOAD_TIMEOUT_MS } from '../plugins/axios'
import type { SherlockCitation, SherlockQuestion, SherlockQuote } from '../stores/sherlock'
import AnswerInlines, { type AnswerPart } from './AnswerInlines.vue'
import { layoutAnswer } from '../utils/answerMarkdown'


const store = useSherlockStore()
const authStore = useAuthStore()
const indicesStore = useIndicesStore()
const notificationStore = useNotificationStore()
const route = useRoute()

const questionText = ref('')
const submitting = ref(false)
const expandedIds = ref(new Set<string>())
const confirmDeleteId = ref<string | null>(null)
const inlineFollowUpId = ref<string | null>(null)
const inlineText = ref('')
const inlineInputRef = ref<HTMLTextAreaElement | null>(null)

const scopeOpen = ref(false)
const scopeRef = ref<HTMLElement | null>(null)

const noIndexSelected = computed(() => indicesStore.loaded && indicesStore.selectedIndices.length === 0)

const canSubmit = computed(() =>
  questionText.value.trim().length > 0 && !submitting.value && (!!store.document || !noIndexSelected.value))

// Every index selected is sent as [] ("all"), so an index added later is searched too
const currentScope = (): string[] =>
  indicesStore.allSelected ? [] : [...indicesStore.selectedIndices]

const scopeLabel = computed(() => {
  if (!indicesStore.loaded || indicesStore.allSelected) return 'Tous les index'
  const sel = indicesStore.selectedIndices
  if (sel.length === 0) return 'Aucun index sélectionné'
  return sel.length <= 2 ? sel.join(', ') : `${sel.slice(0, 2).join(', ')} +${sel.length - 2}`
})

const toggleScopeIndex = (name: string, checked: boolean) => {
  const sel = indicesStore.selectedIndices
  indicesStore.selectedIndices = checked
    ? (sel.includes(name) ? sel : [...sel, name])
    : sel.filter(i => i !== name)
}

const toggleAllScope = () => {
  indicesStore.selectedIndices = indicesStore.allSelected ? [] : [...indicesStore.availableIndices]
}

const onDocumentClick = (e: MouseEvent) => {
  if (scopeOpen.value && scopeRef.value && !scopeRef.value.contains(e.target as Node)) {
    scopeOpen.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick))

const scopeSummary = (indices: string[] | null | undefined) =>
  indices && indices.length > 0 ? indices.join(', ') : null

const spellingNote = (q: SherlockQuestion) => {
  const entries = Object.entries(q.spelling ?? {})
  return entries.length === 0 ? null : entries.map(([typed, fixed]) => `${typed} → ${fixed}`).join(', ')
}

const documentOf = (q: SherlockQuestion) =>
  q.documentId ? { id: q.documentId, name: q.documentName ?? q.documentId } : null

type FlatQuestion = SherlockQuestion & { depth: number }

const flattenedQuestions = computed((): FlatQuestion[] => {
  const qs = store.questions
  const result: FlatQuestion[] = []
  const addWithChildren = (q: SherlockQuestion, depth: number) => {
    result.push({ ...q, depth })
    qs.filter(c => c.parentId === q.id)
      .sort((a, b) => a.createdAt - b.createdAt)
      .forEach(c => addWithChildren(c, depth + 1))
  }
  qs.filter(q => !q.parentId)
    .sort((a, b) => b.createdAt - a.createdAt)
    .forEach(r => addWithChildren(r, 0))
  return result
})

// Auto-expand the most recently answered question when it is new to the list,
// so the background refresh below does not reopen one the user collapsed
watch(() => store.questions, (questions, previous) => {
  if (questions.length === 0) return
  const latest = [...questions]
    .filter(q => q.status === 'ANSWERED')
    .sort((a, b) => (b.answeredAt ?? 0) - (a.answeredAt ?? 0))[0]
  const wasAnswered = previous?.some(p => p.id === latest?.id && p.status === 'ANSWERED')
  if (latest && !wasAnswered) expandedIds.value = new Set([...expandedIds.value, latest.id])
})

// While the pane is open and a question is waiting, refresh the list so the
// answer shows up in the pane as soon as it is ready
const hasPending = computed(() =>
  store.questions.some(q => q.status === 'QUEUED' || q.status === 'PROCESSING'))
let pollInterval: ReturnType<typeof setInterval> | null = null
const stopPolling = () => {
  if (pollInterval) clearInterval(pollInterval)
  pollInterval = null
}
watch(() => store.paneOpen && hasPending.value, (poll, wasPolling) => {
  stopPolling()
  if (poll) {
    pollInterval = setInterval(() => store.fetchQuestions(true), 5_000)
  } else if (wasPolling && store.paneOpen) {
    // The answer arrived: its notification is already on the server, update the bell
    notificationStore.fetchUnreadCount()
  }
})
onBeforeUnmount(stopPolling)

const togglePane = () => {
  if (store.paneOpen) {
    store.closePane()
  } else {
    store.openPane()
    // The search page loads the indexes too, but the pane is also reachable from other pages
    indicesStore.load()
  }
}

const scrollToQuestion = async (id: string) => {
  await nextTick()
  document.querySelector(`[data-question-id="${id}"]`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

const submit = async () => {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const doc = store.document
    await store.submitQuestion(questionText.value.trim(), null,
      doc ? [doc.source] : currentScope(), doc)
    if (!store.submitError) {
      questionText.value = ''
      const newest = flattenedQuestions.value[0]
      if (newest) scrollToQuestion(newest.id)
    }
  } finally {
    submitting.value = false
  }
}

const submitInline = async () => {
  const parentId = inlineFollowUpId.value
  if (!parentId || !inlineText.value.trim() || submitting.value) return
  submitting.value = true
  try {
    await store.submitQuestion(inlineText.value.trim(), parentId)
    if (!store.submitError) {
      inlineText.value = ''
      inlineFollowUpId.value = null
      const newReply = store.questions
        .filter(q => q.parentId === parentId)
        .sort((a, b) => b.createdAt - a.createdAt)[0]
      if (newReply) scrollToQuestion(newReply.id)
    }
  } finally {
    submitting.value = false
  }
}

const retry = async (q: SherlockQuestion) => {
  if (submitting.value) return
  submitting.value = true
  try {
    await store.submitQuestion(q.question, null, q.indices ?? [], documentOf(q))
  } finally {
    submitting.value = false
  }
}

// Same question, same conversation, but no index restriction
const relaunchOnAllIndices = async (q: SherlockQuestion) => {
  if (submitting.value) return
  submitting.value = true
  try {
    await store.submitQuestion(q.question, q.parentId, [])
  } finally {
    submitting.value = false
  }
}

const startFollowUp = async (parentId: string) => {
  inlineFollowUpId.value = parentId
  inlineText.value = ''
  await nextTick()
  inlineInputRef.value?.focus()
  inlineInputRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

const cancelFollowUp = () => {
  inlineFollowUpId.value = null
  inlineText.value = ''
}

const requestDelete = (id: string) => { confirmDeleteId.value = id }
const cancelDelete  = () => { confirmDeleteId.value = null }
const executeDelete = async (id: string) => {
  confirmDeleteId.value = null
  await store.deleteQuestion(id)
}

const toggleExpand = (id: string) => {
  const next = new Set(expandedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
    const q = store.questions.find(q => q.id === id)
    if (q?.status === 'ANSWERED') store.markSherlockNotificationsRead()
  }
  expandedIds.value = next
}

const statusLabel = (status: string) => {
  switch (status) {
    case 'QUEUED':     return 'En attente'
    case 'PROCESSING': return 'En cours…'
    case 'ANSWERED':   return 'Répondu'
    case 'FAILED':     return 'Échec'
    default:           return status
  }
}

const formatDate = (ts: number) =>
  new Date(ts).toLocaleDateString('fr-FR', { dateStyle: 'short' }) + ' ' +
  new Date(ts).toLocaleTimeString('fr-FR', { timeStyle: 'short' })

const formatDuration = (from: number, to: number) => {
  const s = Math.round((to - from) / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  const r = s % 60
  return r > 0 ? `${m} min ${r} s` : `${m} min`
}

const citationLabel = (c: SherlockCitation) =>
  c.page != null ? `${c.filename} — page ${c.page}` : c.filename

const parseCitations = (text: string, citations: SherlockCitation[] | null | undefined, parts: AnswerPart[]) => {
  const pattern = /\[DOCUMENT (\d+) - ([^\]]+)\]/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) parts.push({ type: 'text', content: text.slice(lastIndex, match.index) })
    const docNum = match[1] as string
    const label = match[2] as string
    const c = citations?.[parseInt(docNum) - 1]
    if (c) parts.push({ type: 'citation', label, citation: c })
    else   parts.push({ type: 'citation-dead', label })
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < text.length) parts.push({ type: 'text', content: text.slice(lastIndex) })
}

const parseAnswer = (answer: string | null, citations: SherlockCitation[] | null | undefined,
                     quotes: SherlockQuote[] | null | undefined): AnswerPart[] => {
  if (!answer) return []
  const parts: AnswerPart[] = []
  let lastIndex = 0
  for (const q of [...(quotes ?? [])].sort((a, b) => a.start - b.start)) {
    if (q.start < lastIndex || q.end > answer.length) continue
    parseCitations(answer.slice(lastIndex, q.start), citations, parts)
    parts.push({ type: 'quote', content: answer.slice(q.start, q.end), quote: q })
    lastIndex = q.end
  }
  parseCitations(answer.slice(lastIndex), citations, parts)
  return parts
}

const quoteTitle = (q: SherlockQuote, citations: SherlockCitation[] | null | undefined) => {
  if (q.verified) return 'Citation vérifiée : ce texte figure mot pour mot dans le document cité.'
  const where = q.foundIn != null ? citations?.[q.foundIn - 1] : undefined
  if (where) return `Citation mal attribuée : ce texte figure dans ${citationLabel(where)}, pas dans le document cité.`
  return 'Citation non retrouvée : ce texte ne figure pas mot pour mot dans les documents consultés. Vérifiez-le avant de vous y fier.'
}

const unverifiedCount = (q: SherlockQuestion) => q.quotes?.filter(qt => !qt.verified).length ?? 0
const quotesNothing = (q: SherlockQuestion) =>
  q.quotes != null && q.quotes.length === 0 && !!q.answer && !q.answer.startsWith('Je ne trouve pas')

const openCitation = async (c: SherlockCitation) => {
  try {
    const params = c.id ? { id: c.id } : { path: c.path }
    const response = await apiClient.get('download', {
      params,
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT_MS
    })
    const contentType = String(response.headers['content-type'] ?? '')
    const blob = new Blob([response.data], { type: contentType })
    let url = window.URL.createObjectURL(blob)

    const viewable = ['application/pdf', 'text/plain', 'image/jpeg', 'image/png']
    if (viewable.some(t => contentType.startsWith(t))) {
      if (contentType.includes('pdf') && c.page) url += `#page=${c.page}`
      const tab = window.open('', '_blank')
      if (tab) {
        tab.document.title = citationLabel(c)
        tab.document.body.style.cssText = 'margin:0;height:100vh'
        const iframe = tab.document.createElement('iframe')
        iframe.src = url
        iframe.style.cssText = 'width:100%;height:100%;border:none'
        tab.document.body.appendChild(iframe)
      }
    } else {
      const a = document.createElement('a')
      a.href = url
      a.download = c.filename
      document.body.appendChild(a)
      a.click()
      a.remove()
    }
    setTimeout(() => URL.revokeObjectURL(url), 10000)
  } catch (err) {
    console.error('Citation open failed:', err)
  }
}
</script>

<template>
  <div v-if="authStore.isAuthenticated && authStore.canUseSherlock && route.path !== '/admin'">
    <!-- Floating toggle button -->
    <button
      id="tour-sherlock-fab"
      class="sherlock-fab"
      :class="{ open: store.paneOpen }"
      @click="togglePane"
      title="Poser une question à Sherlock"
      aria-label="Ouvrir Sherlock"
    >
      <img src="/detective_fab_bold.svg" width="48" height="48" alt="" />
    </button>

    <!-- Side pane -->
    <Transition name="pane-slide">
      <div v-if="store.paneOpen" class="sherlock-pane" role="dialog" aria-label="Sherlock">

        <!-- Header -->
        <div class="pane-header">
          <div class="pane-title-group">
            <span class="pane-title">Sherlock</span>
            <span class="experimental-badge">expérimental</span>
            <span class="lang-badge">français uniquement</span>
          </div>
          <button class="pane-close" @click="store.closePane()" aria-label="Fermer">✕</button>
        </div>

        <!-- Question history -->
        <div class="pane-body">
          <div v-if="store.loading && flattenedQuestions.length === 0" class="pane-empty">
            Chargement…
          </div>
          <div v-else-if="flattenedQuestions.length === 0" class="pane-empty">
            Posez votre première question ci-dessous.
          </div>

          <div v-else class="question-list">
            <div
              v-for="q in flattenedQuestions"
              :key="q.id"
              :data-question-id="q.id"
              class="question-item"
              :class="{
                answered: q.status === 'ANSWERED',
                failed: q.status === 'FAILED',
                'is-reply': q.depth > 0
              }"
              :style="q.depth > 0 ? { marginLeft: (Math.min(q.depth, 2) * 16) + 'px' } : {}"
            >
              <div class="question-meta">
                <span v-if="q.depth > 0" class="reply-label">↩ Suite</span>
                <span class="status-badge" :class="'status-' + q.status.toLowerCase()">
                  {{ statusLabel(q.status) }}
                </span>
                <span v-if="q.notice" class="notice-icon" :title="q.notice" :aria-label="q.notice">⚠</span>
                <span class="info-tip">
                  <span class="info-icon">ⓘ</span>
                  <span class="info-tooltip">
                    <span>Posée le {{ formatDate(q.createdAt) }}</span>
                    <span v-if="q.answeredAt">Répondue le {{ formatDate(q.answeredAt) }}</span>
                    <span v-if="q.answeredAt">Durée : {{ formatDuration(q.createdAt, q.answeredAt) }}</span>
                  </span>
                </span>
                <template v-if="q.status === 'ANSWERED' || q.status === 'FAILED'">
                  <div v-if="confirmDeleteId === q.id" class="confirm-delete">
                    <span class="confirm-delete-label">Supprimer ?</span>
                    <button class="confirm-yes-btn" @click="executeDelete(q.id)" title="Confirmer la suppression">✓</button>
                    <button class="confirm-no-btn"  @click="cancelDelete"        title="Annuler">✕</button>
                  </div>
                  <button v-else class="delete-btn" @click="requestDelete(q.id)" title="Supprimer cette question">
                    <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <polyline points="3 6 5 6 21 6"/>
                      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                      <line x1="10" y1="11" x2="10" y2="17"/>
                      <line x1="14" y1="11" x2="14" y2="17"/>
                      <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                    </svg>
                  </button>
                </template>
              </div>
              <div class="question-text">{{ q.question }}</div>
              <div
                v-if="spellingNote(q)"
                class="question-spelling"
                title="Ces mots ne figurent dans aucun document que vous pouvez lire : Sherlock a cherché leur orthographe la plus proche"
              >Orthographe corrigée : {{ spellingNote(q) }}</div>
              <div v-if="q.documentId" class="question-scope" :title="q.documentName ?? ''">
                📄 {{ q.documentName }}
              </div>
              <div v-else-if="scopeSummary(q.indices)" class="question-scope" :title="scopeSummary(q.indices) ?? ''">
                Dans : {{ scopeSummary(q.indices) }}
              </div>

              <div v-if="q.status === 'ANSWERED'" class="answer-section">
                <button class="answer-toggle" @click="toggleExpand(q.id)">
                  {{ expandedIds.has(q.id) ? '▲ Masquer la réponse' : '▼ Voir la réponse' }}
                </button>
                <Transition name="fade">
                  <div v-if="expandedIds.has(q.id)" class="answer-body">
                    <div class="answer-text">
                      <template v-for="(block, bi) in layoutAnswer(parseAnswer(q.answer, q.citations, q.quotes))" :key="bi">
                        <p v-if="block.kind === 'p'">
                          <template v-for="(line, li) in block.lines" :key="li">
                            <br v-if="li > 0"><AnswerInlines :inlines="line" :quote-title="qt => quoteTitle(qt, q.citations)" @open="openCitation" />
                          </template>
                        </p>
                        <p v-else-if="block.kind === 'h'" class="answer-heading">
                          <AnswerInlines :inlines="block.inlines" :quote-title="qt => quoteTitle(qt, q.citations)" @open="openCitation" />
                        </p>
                        <component v-else :is="block.kind">
                          <li v-for="(item, ii) in block.items" :key="ii">
                            <AnswerInlines :inlines="item" :quote-title="qt => quoteTitle(qt, q.citations)" @open="openCitation" />
                          </li>
                        </component>
                      </template>
                    </div>
                    <p v-if="unverifiedCount(q) > 0" class="quote-warning">
                      ⚠ {{ unverifiedCount(q) === 1 ? '1 citation ne figure pas' : `${unverifiedCount(q)} citations ne figurent pas` }}
                      mot pour mot dans le document cité : vérifiez-les avant de vous y fier.
                    </p>
                    <p v-else-if="quotesNothing(q)" class="quote-warning">
                      ⚠ Cette réponse ne cite aucun passage : vérifiez-la dans les documents consultés.
                    </p>
                    <div v-if="q.citations && q.citations.length > 0" class="citations">
                      <p class="citations-label">Documents consultés :</p>
                      <ul>
                        <li v-for="(c, i) in q.citations" :key="i" class="citation-item">
                          <button
                            class="citation-link"
                            :title="c.path"
                            @click="openCitation(c)"
                          >{{ citationLabel(c) }}</button>
                        </li>
                      </ul>
                    </div>

                    <div v-if="inlineFollowUpId !== q.id" class="answer-actions">
                      <button
                        class="followup-btn"
                        :disabled="submitting"
                        @click="startFollowUp(q.id)"
                      >↩ Poursuivre la conversation</button>
                      <button
                        v-if="scopeSummary(q.indices) && !q.documentId"
                        class="followup-btn"
                        :disabled="submitting"
                        title="Poser la même question sans restriction d'index"
                        @click="relaunchOnAllIndices(q)"
                      >Relancer sur tous les index</button>
                    </div>

                    <Transition name="fade">
                      <div v-if="inlineFollowUpId === q.id" class="inline-followup">
                        <textarea
                          ref="inlineInputRef"
                          v-model="inlineText"
                          class="inline-input"
                          placeholder="Votre question de suivi…"
                          rows="2"
                          @keydown.ctrl.enter="submitInline"
                        ></textarea>
                        <div class="inline-actions">
                          <span class="hint">Ctrl+Entrée</span>
                          <button class="inline-cancel-btn" @click="cancelFollowUp">Annuler</button>
                          <button
                            class="inline-send-btn"
                            :disabled="!inlineText.trim() || submitting"
                            @click="submitInline"
                          >{{ submitting ? 'Envoi…' : 'Envoyer' }}</button>
                        </div>
                      </div>
                    </Transition>
                  </div>
                </Transition>
              </div>

              <div v-else-if="q.status === 'FAILED'" class="answer-failed">
                <span>Sherlock n'a pas pu traiter cette question.</span>
                <button
                  class="retry-btn"
                  :disabled="submitting"
                  @click="retry(q)"
                >Réessayer</button>
              </div>
            </div>
          </div>
        </div>

        <!-- Confirmation message -->
        <Transition name="fade">
          <div v-if="store.submitConfirmation" class="submit-confirmation">
            Votre question a été transmise à Sherlock. Vous serez notifié(e) par e-mail et dans l'application dès qu'une réponse sera disponible.
          </div>
        </Transition>

        <!-- Error message -->
        <Transition name="fade">
          <div v-if="store.submitError || store.deleteError" class="submit-error">
            {{ store.submitError || store.deleteError }}
          </div>
        </Transition>

        <!-- Input area -->
        <div class="pane-footer">
          <p class="nl-hint">Nouvelle question en langage naturel <span class="lang-only">(français uniquement)</span></p>
          <div v-if="store.document" class="scope">
            <span class="scope-chip restricted document-chip" :title="store.document.name">
              <span class="scope-prefix">Interroger :</span>
              <span class="scope-value">📄 {{ store.document.name }}</span>
              <button
                type="button"
                class="document-clear"
                title="Ne plus interroger ce document : revenir aux index sélectionnés"
                aria-label="Ne plus interroger ce document"
                @click="store.clearDocument()"
              >✕</button>
            </span>
          </div>
          <div v-else ref="scopeRef" class="scope">
            <button
              type="button"
              class="scope-chip"
              :class="{ restricted: !indicesStore.allSelected, empty: noIndexSelected }"
              :aria-expanded="scopeOpen"
              title="Index dans lesquels Sherlock cherche — la même sélection que la recherche"
              @click="scopeOpen = !scopeOpen"
            >
              <span class="scope-prefix">Recherche dans :</span>
              <span class="scope-value">{{ scopeLabel }}</span>
              <span class="scope-caret">{{ scopeOpen ? '▴' : '▾' }}</span>
            </button>
            <div v-if="scopeOpen" class="scope-popover">
              <div class="scope-popover-header">
                <span>Sources</span>
                <button type="button" class="scope-all" @click="toggleAllScope">
                  {{ indicesStore.allSelected ? 'Désélectionner tout' : 'Sélectionner tout' }}
                </button>
              </div>
              <div v-if="indicesStore.availableIndices.length === 0" class="scope-none">Aucun index disponible</div>
              <label v-for="name in indicesStore.availableIndices" :key="name" class="scope-item">
                <input
                  type="checkbox"
                  :checked="indicesStore.selectedIndices.includes(name)"
                  @change="toggleScopeIndex(name, ($event.target as HTMLInputElement).checked)"
                />
                <span>{{ name }}</span>
              </label>
              <p class="scope-note">Cette sélection s'applique aussi à la recherche.</p>
            </div>
          </div>
          <textarea
            v-model="questionText"
            class="question-input"
            :placeholder="store.document
              ? 'Ex : Qui a signé ce document ? Quelles sont ses conclusions ?'
              : 'Ex : Quels fournisseurs livrent des roulements en titane en Europe ?'"
            rows="3"
            @keydown.ctrl.enter="submit"
          ></textarea>
          <div class="footer-actions">
            <span class="hint">
              Ctrl+Entrée pour envoyer
              <template v-if="store.quota?.limit != null">
                · <span :class="{ 'quota-exhausted': store.quota.used >= store.quota.limit }"
                        :title="'Le compteur repart à zéro à minuit' + (store.quota.tier === 'PREMIUM' ? ' — quota premium' : store.quota.tier === 'EXTENDED' ? ' — quota étendu' : '')">
                  {{ store.quota.used }} / {{ store.quota.limit }} questions aujourd'hui
                </span>
              </template>
            </span>
            <button
              class="send-btn"
              :disabled="!canSubmit"
              @click="submit"
            >
              {{ submitting ? 'Envoi…' : 'Envoyer' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Backdrop -->
    <Transition name="fade">
      <div v-if="store.paneOpen" class="sherlock-backdrop" @click="store.closePane()" />
    </Transition>
  </div>
</template>

<style scoped>
/* ── Floating button ── */
.sherlock-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #2563eb;
  border: none;
  padding: 0;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(0,0,0,0.30);
  z-index: 950;
  transition: transform 0.15s, filter 0.15s;
  overflow: hidden;
}
.sherlock-fab img { display: block; width: 64px; height: 64px; }
.sherlock-fab:hover { transform: scale(1.07); filter: brightness(1.12); }
.sherlock-fab.open  { display: none; }

/* ── Side pane ── */
.sherlock-pane {
  position: fixed;
  top: 0;
  right: 0;
  width: 400px;
  height: 100vh;
  background: #fff;
  box-shadow: -4px 0 20px rgba(0,0,0,0.15);
  z-index: 900;
  display: flex;
  flex-direction: column;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  font-size: 14px;
}

/* ── Header ── */
.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-bottom: 1px solid #e0e0e0;
  background: #f8f9fa;
  flex-shrink: 0;
}
.pane-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pane-title { font-weight: 600; font-size: 15px; color: #202124; }
.experimental-badge {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  color: #b06000;
  background: #fef3e0;
  border: 1px solid #f9ab00;
  border-radius: 4px;
  padding: 2px 6px;
}
.lang-badge {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  color: #1967d2;
  background: #e8f0fe;
  border: 1px solid #aecbfa;
  border-radius: 4px;
  padding: 2px 6px;
}
.lang-only { color: #9aa0a6; font-weight: 400; }
.pane-close {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #5f6368;
  padding: 4px 6px;
  border-radius: 4px;
}
.pane-close:hover { background: #e8eaed; }

/* ── Body ── */
.pane-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.pane-empty { color: #70757a; font-style: italic; text-align: center; margin-top: 40px; }

/* ── Question list ── */
.question-list { display: flex; flex-direction: column; gap: 10px; }
.question-item {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 12px;
  background: #fafafa;
}
.question-item.answered { border-left: 3px solid #34a853; }
.question-item.failed   { border-left: 3px solid #ea4335; }
.question-item.is-reply {
  border-left: 3px solid #aecbfa;
  background: #f0f4ff;
}
.question-item.is-reply.answered { border-left: 3px solid #34a853; }
.question-item.is-reply.failed   { border-left: 3px solid #ea4335; }

.reply-label {
  font-size: 10px;
  font-weight: 600;
  color: #1967d2;
  background: #e8f0fe;
  border: 1px solid #aecbfa;
  border-radius: 4px;
  padding: 1px 5px;
  letter-spacing: .2px;
}

.question-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.info-tip {
  position: relative;
  display: inline-flex;
  align-items: center;
}
.info-icon {
  font-size: 13px;
  color: #9aa0a6;
  cursor: default;
  user-select: none;
  line-height: 1;
}
.info-tip:hover .info-tooltip { display: flex; }
.info-tooltip {
  display: none;
  flex-direction: column;
  gap: 2px;
  position: absolute;
  bottom: calc(100% + 7px);
  left: 50%;
  transform: translateX(-50%);
  background: #3c4043;
  color: #fff;
  font-size: 11px;
  padding: 7px 10px;
  border-radius: 5px;
  white-space: nowrap;
  z-index: 999;
  line-height: 1.6;
  pointer-events: none;
  box-shadow: 0 2px 8px rgba(0,0,0,0.22);
}
.info-tooltip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: #3c4043;
}

.status-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 10px;
}
.status-queued     { background: #f1f3f4; color: #5f6368; }
.status-processing { background: #e8f0fe; color: #1967d2; }
.status-answered   { background: #e6f4ea; color: #137333; }
.status-failed     { background: #fce8e6; color: #c5221f; }

.question-text {
  font-weight: 500;
  color: #202124;
  line-height: 1.4;
  margin-bottom: 6px;
}

/* ── Answer section ── */
.answer-toggle {
  background: none;
  border: none;
  color: #1a73e8;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  margin-top: 4px;
}
.answer-toggle:hover { text-decoration: underline; }

.answer-body {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #e8eaed;
}
.answer-text {
  color: #202124;
  line-height: 1.6;
  overflow-wrap: break-word;
  margin: 0 0 10px;
}
.answer-text p { margin: 0 0 8px; }
.answer-text .answer-heading { font-weight: 600; margin: 10px 0 4px; }
.answer-text ul, .answer-text ol { margin: 0 0 8px; padding-left: 20px; }
.answer-text li { margin-bottom: 4px; }
.answer-text > :last-child { margin-bottom: 0; }
.citations-label { font-size: 12px; font-weight: 600; color: #5f6368; margin-bottom: 4px; }
.citations ul { margin: 0; padding-left: 16px; }
.citation-item { font-size: 11px; margin-bottom: 3px; list-style: disc; }
.citation-link {
  background: none;
  border: none;
  padding: 0;
  font-size: 11px;
  color: #1a73e8;
  cursor: pointer;
  text-align: left;
  text-decoration: underline;
  word-break: break-word;
}
.citation-link:hover { color: #1558b0; }

.answer-text :deep(.inline-citation) {
  background: none;
  border: none;
  padding: 0;
  font-size: inherit;
  font-family: inherit;
  line-height: inherit;
  color: #1a73e8;
  cursor: pointer;
  text-decoration: underline;
}
.answer-text :deep(.inline-citation:hover) { color: #1558b0; }
.answer-text :deep(.inline-citation-dead) { color: #70757a; }

.answer-text :deep(.quote-verified .quote-mark) { color: #188038; font-size: 0.85em; margin-left: 2px; }
.answer-text :deep(.quote-unverified) {
  background: #fef7e0;
  text-decoration: underline dotted #b06000;
  cursor: help;
}
.answer-text :deep(.quote-unverified .quote-mark) { color: #b06000; font-size: 0.85em; margin-left: 2px; }
.quote-warning { font-size: 12px; color: #b06000; margin: 6px 0 0; }

.followup-btn {
  margin-top: 10px;
  background: none;
  border: 1px solid #1a73e8;
  border-radius: 4px;
  color: #1a73e8;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.followup-btn:hover:not(:disabled) { background: #1a73e8; color: #fff; }
.followup-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.inline-followup {
  margin-top: 10px;
  border-top: 1px solid #e8eaed;
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.inline-input {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid #aecbfa;
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 13px;
  font-family: inherit;
  color: #202124;
  background: #f8fbff;
  outline: none;
  transition: border-color 0.15s;
}
.inline-input:focus { border-color: #1a73e8; background: #fff; }
.inline-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.inline-actions .hint {
  font-size: 11px;
  color: #9aa0a6;
  flex: 1;
}
.inline-cancel-btn {
  background: none;
  border: 1px solid #dadce0;
  border-radius: 6px;
  color: #5f6368;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  cursor: pointer;
  transition: background 0.15s;
}
.inline-cancel-btn:hover { background: #f1f3f4; }
.inline-send-btn {
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 5px 14px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}
.inline-send-btn:hover:not(:disabled) { background: #1558b0; }
.inline-send-btn:disabled { background: #bdc1c6; cursor: not-allowed; }

.answer-actions { display: flex; flex-wrap: wrap; gap: 6px; }

.notice-icon {
  color: #b06000;
  font-size: 13px;
  cursor: help;
}

.question-spelling {
  font-size: 11px;
  color: #1967d2;
  margin: -2px 0 4px;
  cursor: help;
}

.question-scope {
  font-size: 11px;
  color: #5f6368;
  margin: -2px 0 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── Scope chip ── */
.scope { position: relative; margin-bottom: 6px; }
.scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #dadce0;
  border-radius: 12px;
  padding: 3px 10px;
  font-size: 12px;
  font-family: inherit;
  color: #3c4043;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.scope-chip:hover { background: #f1f3f4; }
.scope-chip.restricted { background: #e8f0fe; border-color: #aecbfa; color: #1967d2; }
.scope-chip.empty { background: #fce8e6; border-color: #f5c6c4; color: #c5221f; }
.scope-prefix { color: inherit; opacity: 0.75; flex-shrink: 0; }
.scope-value { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope-caret { font-size: 10px; flex-shrink: 0; }
.document-chip { cursor: default; }
.document-clear {
  background: none;
  border: none;
  padding: 0 0 0 4px;
  font-size: 11px;
  color: inherit;
  cursor: pointer;
  flex-shrink: 0;
}
.document-clear:hover { color: #c5221f; }
.scope-popover {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  min-width: 220px;
  max-width: 100%;
  max-height: 260px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #dadce0;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.18);
  padding: 8px 10px;
  z-index: 10;
}
.scope-popover-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  font-weight: 600;
  color: #5f6368;
  margin-bottom: 4px;
}
.scope-all {
  background: none;
  border: none;
  padding: 0;
  font-size: 11px;
  color: #1a73e8;
  cursor: pointer;
}
.scope-all:hover { text-decoration: underline; }
.scope-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #202124;
  padding: 3px 0;
  cursor: pointer;
}
.scope-none { font-size: 12px; color: #70757a; font-style: italic; }
.scope-note { margin: 6px 0 0; font-size: 11px; color: #9aa0a6; }

/* ── Failed state ── */
.answer-failed {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #c5221f;
  margin-top: 4px;
}
.retry-btn {
  flex-shrink: 0;
  background: none;
  border: 1px solid #c5221f;
  border-radius: 4px;
  color: #c5221f;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.retry-btn:hover:not(:disabled) { background: #c5221f; color: #fff; }
.retry-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.delete-btn {
  margin-left: auto;
  background: none;
  border: none;
  color: #9aa0a6;
  cursor: pointer;
  padding: 3px 4px;
  border-radius: 3px;
  line-height: 1;
  display: flex;
  align-items: center;
  transition: color 0.15s, background 0.15s;
}
.delete-btn:hover { color: #c5221f; background: #fce8e6; }

.confirm-delete {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}
.confirm-delete-label {
  font-size: 11px;
  color: #c5221f;
  font-weight: 600;
}
.confirm-yes-btn,
.confirm-no-btn {
  background: none;
  border: 1px solid;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  padding: 1px 5px;
  line-height: 1.4;
  transition: background 0.12s, color 0.12s;
}
.confirm-yes-btn { border-color: #c5221f; color: #c5221f; }
.confirm-yes-btn:hover { background: #c5221f; color: #fff; }
.confirm-no-btn  { border-color: #dadce0; color: #5f6368; }
.confirm-no-btn:hover  { background: #f1f3f4; }

/* ── Confirmation banner ── */
.submit-confirmation {
  margin: 8px 12px;
  padding: 10px 14px;
  background: #e6f4ea;
  border: 1px solid #ceead6;
  border-radius: 6px;
  color: #137333;
  font-size: 13px;
  line-height: 1.4;
  flex-shrink: 0;
}

/* ── Error banner ── */
.quota-exhausted {
  color: #c5221f;
  font-weight: 600;
}

.submit-error {
  margin: 8px 12px;
  padding: 10px 14px;
  background: #fce8e6;
  border: 1px solid #f5c6c4;
  border-radius: 6px;
  color: #c5221f;
  font-size: 13px;
  line-height: 1.4;
  flex-shrink: 0;
}

/* ── Footer ── */
.pane-footer {
  padding: 12px;
  border-top: 1px solid #e0e0e0;
  background: #f8f9fa;
  flex-shrink: 0;
}
.nl-hint {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: #5f6368;
}
.question-input {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid #dfe1e5;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  color: #202124;
  background: #fff;
  outline: none;
  transition: border-color 0.15s;
}
.question-input:focus { border-color: #1a73e8; }

.footer-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  margin-top: 8px;
}
.hint { font-size: 11px; color: #9aa0a6; }

.send-btn {
  width: 100%;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}
.send-btn:hover:not(:disabled) { background: #1558b0; }
.send-btn:disabled { background: #bdc1c6; cursor: not-allowed; }

/* ── Backdrop ── */
.sherlock-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.15);
  z-index: 850;
}

/* ── Transitions ── */
.pane-slide-enter-active,
.pane-slide-leave-active { transition: transform 0.25s ease; }
.pane-slide-enter-from,
.pane-slide-leave-to    { transform: translateX(100%); }

.fade-enter-active,
.fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from,
.fade-leave-to     { opacity: 0; }
</style>
