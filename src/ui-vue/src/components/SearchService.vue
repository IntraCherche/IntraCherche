<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import axios from 'axios'
import apiClient from '../plugins/axios'
import SearchResultItem from './SearchResultItem.vue';
import SearchPagination from './SearchPagination.vue';
import SearchSidebar from './SearchSidebar.vue';
import GeoCombobox from './GeoCombobox.vue';
import type { SolrDocument, SearchResponse, Gazetteer, GeoOption } from '../types/SearchTypes';
import { useSubscriptionStore } from '../stores/subscriptions';
import { useNotificationStore } from '../stores/notifications';
import { useIndicesStore } from '../stores/indices';
import { useTour } from '../composables/useTour';
import { useSearchHistory } from '../composables/useSearchHistory';
import { useSpellingSuggestion } from '../composables/useSpellingSuggestion';
import { usePrefixCompletion } from '../composables/usePrefixCompletion';

const notificationStore = useNotificationStore()
const { startTourIfFirstVisit } = useTour()

const showHelp = ref(false)

const OPERATORS = [
  { syntax: '"..."',     example: '"rapport annuel"',             desc: 'Phrase exacte — mots dans cet ordre' },
  { syntax: 'mot*',      example: 'rapport*',                     desc: 'Joker de fin — commence par…' },
  { syntax: 'mot?',      example: 'rappor?',                      desc: 'Joker d\'un caractère — remplace une lettre' },
  { syntax: 'mot~',      example: 'rapport~',                     desc: 'Flou — tolérance orthographique (~1 erreur)' },
  { syntax: 'AND',       example: 'eau AND pollution',            desc: 'Les deux termes requis — comportement par défaut' },
  { syntax: 'OR',        example: 'eau OR rivière',               desc: 'L\'un ou l\'autre suffit (élargit la recherche)' },
  { syntax: 'NOT / -',   example: 'eau NOT mer  ·  eau -mer',     desc: 'Exclure un terme — écarte tout document qui le contient, sur n\'importe quelle page' },
  { syntax: '( )',       example: '(eau OR fleuve) AND pollution', desc: 'Grouper des conditions' },
  { syntax: 'terme^n',   example: 'eau^2 pollution',              desc: 'Booster la pertinence d\'un terme' },
]

const appVersion = ref<string | null>(null)
const query = ref('')
const filenameFilter = ref('')
const geoCity = ref('')
const geoDpt = ref('')
const geoRegion = ref('')
const geoCountry = ref('')

// Place names from the NER service. Null until loaded — or for good if the service
// is down, in which case Département/Région/Pays stay plain text inputs.
const gazetteer = ref<Gazetteer | null>(null)
const countryOptions = computed<GeoOption[]>(() =>
  (gazetteer.value?.countries ?? []).map(value => ({ value })))
const regionOptions = computed<GeoOption[]>(() =>
  (gazetteer.value?.regions ?? []).map(value => ({ value })))
// Narrowed to the chosen region; without one, each department shows its region as a hint.
const departmentOptions = computed<GeoOption[]>(() =>
  (gazetteer.value?.departments ?? [])
    .filter(d => !geoRegion.value || d.region === geoRegion.value)
    .map(d => ({ value: d.name, hint: geoRegion.value ? undefined : d.region })))

// Picking a region drops a department that isn't in it — the two filters would contradict.
watch(geoRegion, (region) => {
  const dpt = gazetteer.value?.departments.find(d => d.name === geoDpt.value)
  if (region && dpt && dpt.region !== region) geoDpt.value = ''
})

const showAdvanced = ref(false)
const sortOrder = ref<'score' | 'mtime_desc' | 'mtime_asc'>('score')
const dateFrom = ref('')
const dateTo = ref('')
const results = ref<SolrDocument[]>([])
const totalResults = ref(0);
const relaxedToOr = ref(false);
const suggestion = ref<string | undefined>(undefined);
const degraded = ref<SearchResponse['degraded']>(undefined);
// Whether the search that produced the results on screen carried any advanced filter.
// Captured from the request, not read from the filter fields: those stay editable after
// the search, and the notice must describe the results shown, not what is typed now.
const filtersOnLastSearch = ref(false);
const searchTime = ref(0);
const isLoading = ref(false);
const hasSearched = ref(false);

const currentPage = ref(0);
const pageSize = 10;

const indicesStore = useIndicesStore()
const { availableIndices, selectedIndices } = storeToRefs(indicesStore)

const resultsPerIndex = ref<Record<string, number>>({});

const isGeoFilterActive = computed(() =>
  geoCity.value.trim() !== '' ||
  geoDpt.value.trim() !== '' ||
  geoRegion.value.trim() !== '' ||
  geoCountry.value.trim() !== ''
)

const {
  matches: filteredHistory,
  save: saveToHistory,
  remove: removeFromHistory,
  clear: clearHistory,
} = useSearchHistory(query)
const showHistory = ref(false)

/** The sources and advanced-panel filters, as the search and the spelling suggestion send them. */
const filterParams = (): Record<string, string> => {
  const params: Record<string, string> = { indices: selectedIndices.value.join(',') }
  if (filenameFilter.value.trim()) params.filename = filenameFilter.value.trim()
  if (geoCity.value.trim()) params.geoCity = geoCity.value.trim()
  if (geoDpt.value.trim()) params.geoDpt = geoDpt.value.trim()
  if (geoRegion.value.trim()) params.geoRegion = geoRegion.value.trim()
  if (geoCountry.value.trim()) params.geoCountry = geoCountry.value.trim()
  if (dateFrom.value) params.dateFrom = dateFrom.value
  if (dateTo.value) params.dateTo = dateTo.value
  return params
}

const { suggestion: typedSuggestion, cancel: cancelTypedSuggestion } =
  useSpellingSuggestion(query, filterParams, showHistory)
const { completions: wordCompletions, cancel: cancelWordCompletions } =
  usePrefixCompletion(query, filterParams, showHistory)

/** Rows under the search bar, at most. */
const COMPLETIONS_SHOWN = 10

/**
 * Under the search bar: the spelling correction of what is typed, the past searches it
 * completes, then the words of readable pages that complete the word being typed.
 */
const completions = computed(() => {
  const rows = [
    ...(typedSuggestion.value ? [{ kind: 'spelling' as const, text: typedSuggestion.value }] : []),
    ...filteredHistory.value.map(text => ({ kind: 'history' as const, text })),
    ...wordCompletions.value.map(text => ({ kind: 'word' as const, text })),
  ]
  return rows
    .filter((row, i) => rows.findIndex(other => other.text === row.text) === i)
    .slice(0, COMPLETIONS_SHOWN)
})
// The completion the arrow keys are on, -1 for none: Enter then searches what is typed.
const highlightedHistory = ref(-1)
watch(completions, () => { highlightedHistory.value = -1 })

const selectFromHistory = (item: string) => {
  query.value = item
  showHistory.value = false
  handleSearch(0)
}

/** A completed word goes into the bar, ready for the next one: searching is still the user's call. */
const selectCompletion = (item: { kind: 'spelling' | 'history' | 'word', text: string }) => {
  if (item.kind === 'word') query.value = item.text + ' '
  else selectFromHistory(item.text)
}

const onSearchKeydown = (event: KeyboardEvent) => {
  const count = showHistory.value ? completions.value.length : 0
  if (event.key === 'ArrowDown' && count) {
    event.preventDefault()
    highlightedHistory.value = (highlightedHistory.value + 1) % count
  } else if (event.key === 'ArrowUp' && count) {
    event.preventDefault()
    highlightedHistory.value = highlightedHistory.value <= 0 ? count - 1 : highlightedHistory.value - 1
  } else if (event.key === 'Escape') {
    showHistory.value = false
  } else if (event.key === 'Enter') {
    const item = completions.value[highlightedHistory.value]
    if (showHistory.value && item) selectCompletion(item)
    else handleSearch(0)
  } else {
    showHistory.value = true
  }
}

// Fetch indices from backend on startup
onMounted(async () => {
  apiClient.get<Gazetteer>('/geo/gazetteer')
    .then(({ data }) => { gazetteer.value = data })
    .catch(() => { /* NER service down: keep the free-text inputs */ })

  try {
    const { data } = await apiClient.get('/version')
    appVersion.value = data.version
  } catch { /* ignore */ }

  await indicesStore.load()

  startTourIfFirstVisit()

  // Replay a saved-search alert if one was requested while on another page
  if (notificationStore.replayQuery !== null) {
    query.value = notificationStore.replayQuery
    notificationStore.replayQuery = null
    if (query.value.trim()) handleSearch(0)
  }
})

// Replay a saved-search alert when the component is already mounted (panel stays on '/')
watch(() => notificationStore.replayQuery, (rawQuery) => {
  if (rawQuery !== null) {
    query.value = rawQuery
    notificationStore.replayQuery = null
    if (query.value.trim()) handleSearch(0)
  }
})

const errorMessage = ref('');

/** User-facing reason a search request failed. 401 never gets here: the API client redirects to login. */
const searchErrorMessage = (error: unknown): string => {
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return "Le serveur IntraCherche met trop de temps à répondre. Merci de réessayer dans quelques instants."
    }
    if (!error.response) {
      return "Le serveur IntraCherche est injoignable. Vérifiez votre connexion puis réessayez."
    }
    if (error.response.status === 429) {
      return error.response.data?.error
        ?? "Vous avez lancé trop de recherches en peu de temps. Merci de patienter une minute avant de relancer une recherche."
    }
  }
  return "La recherche a échoué à cause d'une erreur du serveur. Merci de réessayer ; si le problème persiste, contactez votre administrateur."
}

const handleSearch = async (page = 0) => {
    // 1. Reset error and check query
    errorMessage.value = '';
    if (!query.value.trim() && !filenameFilter.value.trim() && !geoCity.value.trim() && !geoDpt.value.trim() && !geoRegion.value.trim() && !geoCountry.value.trim() && !dateFrom.value && !dateTo.value) return;

    // 2. Validate index selection
    // Use a local constant to ensure the value doesn't change mid-execution
    const currentSelection = [...selectedIndices.value];

    if (selectedIndices.value.length === 0) {
    errorMessage.value = "Merci de sélectionner un index.";
    results.value = []; // Clear results if nothing is selected
    totalResults.value = 0;
    return; // Exit the function early
    }

  saveToHistory(query.value)
  showHistory.value = false
  cancelTypedSuggestion()
  cancelWordCompletions()
  errorMessage.value = '';
  currentPage.value = page
  isLoading.value = true

  try {
    const params: Record<string, any> = {
      ...filterParams(),
      q: query.value.trim() || '*:*',
      page: currentPage.value,
      rows: pageSize,
      indices: currentSelection.join(',')
    }
    if (sortOrder.value !== 'score') params.sort = sortOrder.value
    // The sources checkboxes are excluded on purpose: they are always present, and the
    // notice is about the advanced-panel filters the user opted into.
    const sentFilters = ['filename', 'geoCity', 'geoDpt', 'geoRegion', 'geoCountry', 'dateFrom', 'dateTo']
      .some(key => params[key] !== undefined)
    const response = await apiClient.get<SearchResponse>('/search', { params })

    // Map the envelope fields to our refs
    // Only update if this request is still relevant
    results.value = response.data.results;
    totalResults.value = response.data.totalResults;
    relaxedToOr.value = response.data.relaxedToOr === true;
    suggestion.value = response.data.suggestion;
    degraded.value = response.data.degraded;
    filtersOnLastSearch.value = sentFilters;
    searchTime.value = response.data.searchTime;
    resultsPerIndex.value = response.data.resultsPerIndex || {};
    hasSearched.value = true;
    document.title = query.value.trim() ? `${query.value.trim()} - Recherche IntraCherche` : 'Recherche IntraCherche';
    window.scrollTo(0, 0); // Scroll to top on page change
  } catch (error) {
    console.error("Search failed:", error)
    // Say so, and clear the previous results: left on screen they would pass for the
    // answer to this search.
    errorMessage.value = searchErrorMessage(error)
    results.value = []
    totalResults.value = 0
    hasSearched.value = false
  } finally {
    isLoading.value = false
  }
}

/** Reruns the search with the spelling correction the server proposed, in place of the misspelled one in the history. */
const searchSuggestion = () => {
  if (!suggestion.value) return
  removeFromHistory(query.value.trim())
  query.value = suggestion.value
  handleSearch(0)
}

const clearSearch = () => {
  document.title = 'Recherche IntraCherche'
  query.value = ''
  filenameFilter.value = ''
  geoCity.value = ''
  geoDpt.value = ''
  geoRegion.value = ''
  geoCountry.value = ''
  dateFrom.value = ''
  dateTo.value = ''
  sortOrder.value = 'score'
  showAdvanced.value = false
  results.value = []
  hasSearched.value = false
}

const setSortOrder = (order: 'score' | 'mtime_desc' | 'mtime_asc') => {
  if (sortOrder.value === order) return
  sortOrder.value = order
  handleSearch(0)
}

const toIsoDate = (d: Date): string => d.toISOString().slice(0, 10)

const subscriptionStore = useSubscriptionStore()
const showSubscribeModal = ref(false)
const subscribeFrequency = ref<'IMMEDIATE' | 'DAILY' | 'WEEKLY'>('IMMEDIATE')
const subscribeSuccess = ref(false)
const subscribeError = ref('')
const subscribeLoading = ref(false)

const canSubscribe = computed(() =>
  hasSearched.value && (query.value.trim() !== '' || filenameFilter.value.trim() !== '')
)

const openSubscribeModal = () => {
  subscribeSuccess.value = false
  subscribeError.value = ''
  subscribeFrequency.value = 'IMMEDIATE'
  showSubscribeModal.value = true
}

const computeOffsetDays = (isoDate: string): number => {
  const diffMs = new Date(isoDate).getTime() - Date.now()
  return Math.round(diffMs / 86_400_000)
}

const confirmSubscribe = async () => {
  subscribeLoading.value = true
  subscribeError.value = ''
  try {
    await subscriptionStore.create({
      query: query.value.trim() || '',
      indices: [...selectedIndices.value],
      filename: filenameFilter.value.trim() || undefined,
      geoCity: geoCity.value.trim() || undefined,
      geoDpt: geoDpt.value.trim() || undefined,
      geoRegion: geoRegion.value.trim() || undefined,
      geoCountry: geoCountry.value.trim() || undefined,
      dateOffsetFrom: dateFrom.value ? computeOffsetDays(dateFrom.value) : undefined,
      dateOffsetTo: dateTo.value ? computeOffsetDays(dateTo.value) : undefined,
      sort: sortOrder.value,
      frequency: subscribeFrequency.value,
    })
    subscribeSuccess.value = true
    setTimeout(() => { showSubscribeModal.value = false }, 1500)
  } catch {
    subscribeError.value = "Impossible de créer l'alerte. Réessayez."
  } finally {
    subscribeLoading.value = false
  }
}

const applyDatePreset = (preset: 'today' | 'week' | 'month' | 'year') => {
  const today = new Date()
  dateTo.value = toIsoDate(today)
  if (preset === 'today') {
    dateFrom.value = toIsoDate(today)
  } else if (preset === 'week') {
    const from = new Date(today)
    from.setDate(today.getDate() - 7)
    dateFrom.value = toIsoDate(from)
  } else if (preset === 'month') {
    const from = new Date(today)
    from.setDate(today.getDate() - 30)
    dateFrom.value = toIsoDate(from)
  } else {
    const from = new Date(today)
    from.setFullYear(today.getFullYear() - 1)
    dateFrom.value = toIsoDate(from)
  }
  handleSearch(0)
}
</script>

<template>
  <div class="main-wrapper" :class="{ 'has-results': hasSearched }">
    <header class="header-section" :class="{ 'minimized': hasSearched }">
        <h1 class="brand-logo" @click="clearSearch" style="cursor: pointer" :title="appVersion ? `IntraCherche v${appVersion}` : 'IntraCherche'">
            IntraCherche
          </h1>
      <div class="search-bar-container">
        <div class="search-input-area">
          <div id="tour-search-input" class="input-wrapper" :class="{ 'input-error': errorMessage }">
            <input
              v-model="query"
              @keydown="onSearchKeydown"
              @focus="showHistory = true"
              @blur="showHistory = false"
              type="text"
              class="modern-input"
              placeholder="Rechercher dans les documents..."
            />
            <button
              class="help-btn"
              :class="{ active: showHelp }"
              @click.stop="showHelp = !showHelp"
              title="Aide sur les opérateurs de recherche"
              aria-label="Aide sur les opérateurs de recherche"
            >ℹ</button>
            <button v-if="query" class="clear-btn" @click="clearSearch">✕</button>
              <button
                class="search-btn"
                @click="() => handleSearch(0)"
                :disabled="isLoading"
                :class="{ 'btn-loading': isLoading }"
              >
                <span v-if="!isLoading">🔍</span>
                <span v-else class="spinner"></span>
              </button>
          </div>

          <!-- Operator help popover -->
          <div v-if="showHelp" class="help-backdrop" @click="showHelp = false"></div>
          <div v-if="showHelp" class="help-popover" role="tooltip">
            <div class="help-popover-header">
              <span class="help-popover-title">Opérateurs de recherche</span>
              <button class="help-popover-close" @click="showHelp = false" aria-label="Fermer">✕</button>
            </div>
            <table class="help-table">
              <thead>
                <tr>
                  <th>Syntaxe</th>
                  <th>Exemple</th>
                  <th>Description</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="op in OPERATORS" :key="op.syntax">
                  <td><code>{{ op.syntax }}</code></td>
                  <td><code>{{ op.example }}</code></td>
                  <td>{{ op.desc }}</td>
                </tr>
              </tbody>
            </table>
            <p class="help-note">Ces opérateurs ne s'appliquent pas aux filtres Localisation du panneau de recherche avancée.
              Si aucune page ne contient tous les mots, la recherche les cherche d'abord répartis sur les pages
              d'un même document, puis bascule automatiquement en <code>OR</code> —
              mais les filtres, eux, restent toujours obligatoires.</p>
          </div>

          <div v-if="showHistory && completions.length > 0" class="history-dropdown">
            <ul>
              <li v-for="(item, i) in completions" :key="item.kind + item.text" :class="{ highlighted: i === highlightedHistory }"
                  @mousedown.prevent="selectCompletion(item)">
                <template v-if="item.kind === 'spelling'">
                  <span class="history-icon">&#x270E;</span>
                  <span class="history-text">Vouliez-vous dire : <em class="spelling-text">{{ item.text }}</em> ?</span>
                </template>
                <template v-else-if="item.kind === 'word'">
                  <span class="history-icon">&#x1F50D;&#xFE0E;</span>
                  <span class="history-text">{{ item.text }}</span>
                </template>
                <template v-else>
                  <span class="history-icon">&#x1F551;</span>
                  <span class="history-text">{{ item.text }}</span>
                  <button class="history-remove" @mousedown.stop.prevent="removeFromHistory(item.text)" title="Supprimer">✕</button>
                </template>
              </li>
            </ul>
            <div v-if="filteredHistory.length > 0" class="history-footer">
              <button @mousedown.prevent="clearHistory">Effacer l'historique</button>
            </div>
          </div>
        </div>

        <transition name="fade">
            <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>
          </transition>

        <div id="tour-advanced-toggle" class="advanced-link" @click="showAdvanced = !showAdvanced">
          Recherche avancée {{ showAdvanced ? '−' : '+' }}
        </div>

        <transition name="fade">
          <div v-if="showAdvanced" class="advanced-panel">
            <div class="advanced-row">
              <label class="advanced-label">Nom du fichier contient</label>
              <input
                v-model="filenameFilter"
                @keyup.enter="handleSearch(0)"
                type="text"
                class="advanced-input"
                placeholder="ex : rapport, contrat 2024, .pdf"
              />
            </div>
            <div class="advanced-row">
              <label class="advanced-label">
                Localisation
                <span class="experimental-badge" title="La détection des lieux est automatique et peut manquer certains documents ou produire des résultats inexacts.">expérimental</span>
              </label>
              <div class="geo-inputs-wrapper">
                <div class="geo-inputs">
                  <input v-model="geoCity" @keyup.enter="handleSearch(0)" type="text" class="advanced-input" placeholder="Ville" />
                  <template v-if="gazetteer">
                    <GeoCombobox v-model="geoDpt" :options="departmentOptions" placeholder="Département" @enter="handleSearch(0)" />
                    <GeoCombobox v-model="geoRegion" :options="regionOptions" placeholder="Région" @enter="handleSearch(0)" />
                    <GeoCombobox v-model="geoCountry" :options="countryOptions" placeholder="Pays" @enter="handleSearch(0)" />
                  </template>
                  <template v-else>
                    <input v-model="geoDpt" @keyup.enter="handleSearch(0)" type="text" class="advanced-input" placeholder="Département" />
                    <input v-model="geoRegion" @keyup.enter="handleSearch(0)" type="text" class="advanced-input" placeholder="Région" />
                    <input v-model="geoCountry" @keyup.enter="handleSearch(0)" type="text" class="advanced-input" placeholder="Pays" />
                  </template>
                </div>
                <span class="geo-hint">Majuscules et accents non requis · ⚠ les opérateurs ~, *, "" ne s'appliquent pas</span>
              </div>
            </div>
            <div class="advanced-row">
              <label class="advanced-label">Date de modification</label>
              <div class="date-filter-wrapper">
                <div class="date-inputs">
                  <div class="date-field">
                    <span class="date-label-inline">Du</span>
                    <input v-model="dateFrom" @change="handleSearch(0)" type="date" class="advanced-input date-input" />
                  </div>
                  <div class="date-field">
                    <span class="date-label-inline">Au</span>
                    <input v-model="dateTo" @change="handleSearch(0)" type="date" class="advanced-input date-input" />
                  </div>
                </div>
                <div class="date-presets">
                  <button class="date-preset-btn" @click="applyDatePreset('today')" type="button" title="Fichiers modifiés aujourd'hui">Aujourd'hui</button>
                  <button class="date-preset-btn" @click="applyDatePreset('week')" type="button" title="Fichiers modifiés dans les 7 derniers jours">Depuis une semaine</button>
                  <button class="date-preset-btn" @click="applyDatePreset('month')" type="button" title="Fichiers modifiés dans les 30 derniers jours">Depuis un mois</button>
                  <button class="date-preset-btn" @click="applyDatePreset('year')" type="button" title="Fichiers modifiés dans les 12 derniers mois">Depuis un an</button>
                </div>
              </div>
            </div>
            <button class="advanced-search-btn" @click="handleSearch(0)" :disabled="isLoading">
              Rechercher
            </button>
          </div>
        </transition>
      </div>
    </header>

       <main class="results-layout">
             <div class="content-grid">

               <SearchSidebar
                 v-if="availableIndices.length > 0"
                 v-model="selectedIndices"
                 :available-indices="availableIndices"
                 :index-counts="resultsPerIndex"
                 @change="handleSearch(0)"
               />

          <div class="results-container">
            <div v-if="isLoading" class="loading-overlay">
              <div class="loading-content">
                <div class="main-spinner"></div>
                <p>Recherche en cours ...</p>
                <span class="loading-subtext">Merci de patienter</span>
              </div>
            </div>

            <div :class="{ 'results-dimmed': isLoading }">
              <div v-if="hasSearched">
                <div class="meta-info">
                  <span>{{ totalResults }} résultats ({{ searchTime.toFixed(3) }} secondes)</span>
                  <button v-if="canSubscribe" class="subscribe-btn" @click="openSubscribeModal" title="Créer une alerte pour cette recherche">
                    🔔 S'abonner
                  </button>
                  <div class="sort-toggle">
                    <span :class="['sort-option', { active: sortOrder === 'score' }]" @click="setSortOrder('score')" title="Trier par pertinence">Pertinence</span>
                    <span :class="['sort-option', { active: sortOrder === 'mtime_desc' }]" @click="setSortOrder('mtime_desc')" title="Du plus récemment modifié au plus ancien">Plus récent</span>
                    <span :class="['sort-option', { active: sortOrder === 'mtime_asc' }]" @click="setSortOrder('mtime_asc')" title="Du plus ancien au plus récemment modifié">Plus ancien</span>
                  </div>
                </div>

                <div v-if="degraded" class="degraded-notice" role="status">
                  <template v-if="degraded === 'HIGH_LOAD'">
                    Le serveur IntraCherche est actuellement très sollicité : la recherche est
                    simplifiée jusqu'à ce que la charge diminue. Les résultats peuvent différer
                    de ceux que vous obtenez habituellement.
                  </template>
                  <template v-else>
                    La recherche par le sens est momentanément indisponible : seule la recherche
                    par mots-clés est utilisée. Les résultats peuvent différer de ceux que vous
                    obtenez habituellement.
                  </template>
                </div>

                <div v-if="suggestion" class="suggestion-notice">
                  Vouliez-vous dire :
                  <a href="#" @click.prevent="searchSuggestion">{{ suggestion }}</a> ?
                </div>

                <div v-if="relaxedToOr && results.length > 0" class="relaxed-notice">
                  Aucun document ne contient <strong>tous</strong> les termes recherchés —
                  voici ceux qui en contiennent <strong>certains</strong>.
                  <template v-if="filtersOnLastSearch">Vos filtres, eux, restent appliqués.</template>
                </div>

                <div v-if="results.length > 0" class="results-list">
                  <SearchResultItem v-for="doc in results" :key="doc.id" :doc="doc" :show-geo="isGeoFilterActive" />
                </div>

                <div v-else class="empty-state">
                  Aucun résultat trouvé pour cette recherche.
                </div>
              </div>

              <div v-else class="empty-state">
                Sélectionnez vos sources et lancez une recherche.
              </div>
            </div>
          </div>
        </div>
    </main>
  </div>

  <SearchPagination
      v-if="hasSearched"
      :current-page="currentPage"
      :total-results="totalResults"
      :page-size="pageSize"
      @change-page="(p) => handleSearch(p)"
    />

  <Teleport to="body">
    <div v-if="showSubscribeModal" class="modal-overlay" @click.self="showSubscribeModal = false">
      <div class="modal-box" role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <h2 id="modal-title">Créer une alerte</h2>
        <p class="modal-query-preview">
          Recherche : <strong>{{ query.trim() || '(tous les documents)' }}</strong>
        </p>
        <div class="modal-field">
          <label for="modal-freq">Fréquence de notification</label>
          <select id="modal-freq" v-model="subscribeFrequency">
            <option value="IMMEDIATE">Dès qu'un nouveau document est indexé</option>
            <option value="DAILY">Résumé quotidien (08h00)</option>
            <option value="WEEKLY">Résumé hebdomadaire (lundi 08h00)</option>
          </select>
        </div>
        <p v-if="subscribeSuccess" class="modal-success">Alerte créée avec succès !</p>
        <p v-if="subscribeError" class="modal-error">{{ subscribeError }}</p>
        <div class="modal-actions">
          <button @click="showSubscribeModal = false" class="btn-secondary">Annuler</button>
          <button @click="confirmSubscribe" class="btn-primary" :disabled="subscribeLoading">
            {{ subscribeLoading ? 'Création…' : 'Confirmer' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.main-wrapper {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 15vh;
  transition: padding 0.4s ease;
}

.main-wrapper.has-results {
  padding-top: 40px;
}

/* Base Logo State (Big and Centered) */
.brand-logo {
  font-size: 80px;
  font-weight: 800;
  margin-bottom: 30px;
  background: linear-gradient(to right, #4285F4, #EA4335, #FBBC05, #34A853);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  transition: all 0.4s ease; /* Smooth transition */
}

/* Minimized State (Small and Sleek) */
.header-section.minimized .brand-logo {
  font-size: 32px; /* Much smaller */
  margin-bottom: 15px;
  text-align: center;
}

.search-bar-container {
  width: 100%;
  max-width: 650px;
}

.search-input-area {
  position: relative;
}

.history-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: white;
  border: 1px solid #dfe1e5;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(32,33,36,0.2);
  z-index: 100;
  overflow: hidden;
}

.history-dropdown ul {
  list-style: none;
  margin: 0;
  padding: 6px 0;
}

.history-dropdown li {
  display: flex;
  align-items: center;
  padding: 9px 16px;
  cursor: pointer;
  font-size: 14px;
  gap: 10px;
  color: #202124;
}

.history-dropdown li:hover,
.history-dropdown li.highlighted {
  background: #f8f9fa;
}

.history-icon {
  color: #70757a;
  font-size: 13px;
  flex-shrink: 0;
}

.history-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-remove {
  background: none;
  border: none;
  color: #70757a;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 4px;
  flex-shrink: 0;
  visibility: hidden;
}

.history-dropdown li:hover .history-remove {
  visibility: visible;
}

.history-remove:hover {
  background: #ebebeb;
  color: #202124;
}

.spelling-text {
  color: #1a0dab;
  font-weight: 600;
}

.history-footer {
  border-top: 1px solid #f0f0f0;
  padding: 6px 16px;
  text-align: right;
}

.history-footer button {
  background: none;
  border: none;
  color: #1a0dab;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 0;
}

.history-footer button:hover {
  text-decoration: underline;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  background: white;
  border: 1px solid #dfe1e5;
  border-radius: 28px;
  padding: 5px 10px 5px 20px;
  transition: box-shadow 0.2s;
}

.input-wrapper:hover, .input-wrapper:focus-within {
  box-shadow: 0 1px 6px rgba(32,33,36,0.28);
  border-color: rgba(223,225,229,0);
}

.modern-input {
  flex: 1;
  height: 44px;
  border: none;
  outline: none;
  font-size: 16px;
}

.clear-btn {
  background: none;
  border: none;
  color: #70757a;
  font-size: 20px;
  cursor: pointer;
  margin-right: 10px;
}

.search-btn {
  background: #4285F4;
  color: white;
  border: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.advanced-link {
  text-align: right;
  color: #1a0dab;
  font-size: 13px;
  margin-top: 10px;
  cursor: pointer;
}

.results-layout {
  width: 100%;
  max-width: 1200px; /* Slightly wider to accommodate sidebar */
  margin-top: 40px;
  padding: 0 20px;
  opacity: 0;
  transition: opacity 0.5s ease;
}

/* Show the layout once indices are loaded, even if not searched yet */
.main-wrapper.has-results .results-layout,
.results-layout {
  opacity: 1;
}

.empty-state {
  text-align: center;
  color: #70757a;
  margin-top: 100px;
  font-style: italic;
}

.meta-info {
  display: flex;
  justify-content: space-between;
  color: #70757a;
  font-size: 13px;
  padding-bottom: 10px;
  border-bottom: 1px solid #ebebeb;
  margin-bottom: 25px;
}

.sort-toggle {
  display: flex;
  gap: 4px;
}

.sort-option {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 12px;
  cursor: pointer;
  color: #5f6368;
  border: 1px solid transparent;
  transition: all 0.15s;
}

.sort-option:hover {
  background: #f1f3f4;
}

.sort-option.active {
  color: #1a73e8;
  border-color: #1a73e8;
  background: #e8f0fe;
  font-weight: 500;
}

.dropdowns span { margin-left: 20px; cursor: pointer; }

/* Pagination stuff */
.pagination-footer {
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 40px 0;
  gap: 20px;
}

.page-numbers span {
  padding: 8px 12px;
  cursor: pointer;
  color: #1a0dab;
}

.page-numbers span.active-page {
  color: #202124;
  font-weight: bold;
  cursor: default;
}

.page-nav {
  background: none;
  border: 1px solid #dfe1e5;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}

.page-nav:disabled {
  color: #bdc1c6;
  cursor: not-allowed;
}
/* End of pagination stuff */

/* Loading overlay */
/* Ensure the container can hold the absolute overlay */
.results-container {
  position: relative;
  min-height: 400px;
  width: 100%;
}

/* The "Frosted Glass" Overlay */
.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.8);
  display: flex;
  justify-content: center;
  align-items: flex-start; /* Keeps the spinner visible even on long pages */
  padding-top: 100px;
  z-index: 20;
  backdrop-filter: blur(3px); /* Makes the background results blurry */
}

.loading-content {
  text-align: center;
  background: white;
  padding: 40px;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.12);
  border: 1px solid #eee;
}

.main-spinner {
  width: 50px;
  height: 50px;
  border: 5px solid #f3f3f3;
  border-top: 5px solid #1a73e8; /* Standard Search Blue */
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 20px;
}

/* Visual feedback that results are "stale" */
.results-dimmed {
  opacity: 0.4;
  filter: grayscale(0.5);
  pointer-events: none; /* Prevents clicking links while loading */
  user-select: none;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
/* End of loading overlay */

/* Warning message */
.error-text {
  color: #d93025;
  font-size: 13px;
  margin-top: 8px;
  margin-left: 20px;
  font-weight: 500;
}

.input-error {
  border-color: #d93025 !important;
  box-shadow: 0 0 0 1px #d93025;
}

/* Smooth transition for the error message */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
/* End of warning message */

/* Container for Results */
.content-grid {
  display: grid;
  grid-template-columns: 200px 1fr; /* Sidebar is 200px, results take rest */
  gap: 40px;
  align-items: start;
}



/* Mobile Responsiveness */
@media (max-width: 768px) {
  .content-grid {
    grid-template-columns: 1fr; /* Stack sidebar on top of results on small screens */
  }
  .search-sidebar {
    border-right: none;
    border-bottom: 1px solid #ebebeb;
    padding-bottom: 20px;
    margin-bottom: 20px;
  }
}

/* End of container for Results */

/* Advanced search panel */
.advanced-panel {
  margin-top: 12px;
  padding: 14px 18px;
  background: #f8f9fa;
  border: 1px solid #dfe1e5;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.advanced-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.advanced-label {
  font-size: 13px;
  color: #5f6368;
  white-space: nowrap;
  min-width: 140px;
}

.advanced-input {
  flex: 1;
  height: 34px;
  border: 1px solid #dfe1e5;
  border-radius: 8px;
  padding: 0 12px;
  font-size: 14px;
  outline: none;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.advanced-input:focus {
  border-color: #4285F4;
  box-shadow: 0 0 0 2px rgba(66, 133, 244, 0.2);
}

.geo-inputs-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.geo-inputs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.geo-hint {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
}

.advanced-search-btn {
  align-self: flex-end;
  background: #4285F4;
  color: white;
  border: none;
  padding: 0 16px;
  height: 34px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}

.advanced-search-btn:hover {
  background: #3367d6;
}

.advanced-search-btn:disabled {
  background: #a8c7fa;
  cursor: not-allowed;
}
.experimental-badge {
  display: inline-block;
  margin-left: 6px;
  font-size: 10px;
  font-weight: 600;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fcd34d;
  border-radius: 4px;
  padding: 1px 5px;
  vertical-align: middle;
  cursor: help;
  letter-spacing: 0.02em;
}
.date-filter-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 6px;
}

.date-inputs {
  display: flex;
  gap: 12px;
}

.date-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.date-label-inline {
  font-size: 12px;
  color: #5f6368;
  white-space: nowrap;
}

.date-input {
  flex: unset;
  width: 150px;
}

.date-presets {
  display: flex;
  gap: 6px;
}

.date-preset-btn {
  font-size: 11px;
  padding: 2px 9px;
  border: 1px solid #dfe1e5;
  border-radius: 10px;
  background: white;
  color: #5f6368;
  cursor: pointer;
  transition: all 0.15s;
}

.date-preset-btn:hover {
  background: #e8f0fe;
  border-color: #1a73e8;
  color: #1a73e8;
}
/* End of advanced search panel */

.subscribe-btn {
  font-size: 12px;
  padding: 3px 12px;
  border: 1px solid #1a73e8;
  border-radius: 12px;
  background: #e8f0fe;
  color: #1a73e8;
  cursor: pointer;
  transition: all 0.15s;
  margin-left: 10px;
}
.subscribe-btn:hover { background: #1a73e8; color: white; }

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: white;
  border-radius: 12px;
  padding: 28px 32px;
  width: 100%;
  max-width: 440px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.18);
}
.modal-box h2 { margin: 0 0 12px; font-size: 18px; color: #202124; }
.modal-query-preview { font-size: 14px; color: #5f6368; margin-bottom: 18px; }
.modal-field { margin-bottom: 18px; }
.modal-field label { font-size: 13px; color: #5f6368; display: block; margin-bottom: 6px; }
.modal-field select {
  width: 100%;
  height: 36px;
  border: 1px solid #dfe1e5;
  border-radius: 8px;
  padding: 0 10px;
  font-size: 14px;
}
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary {
  background: #1a73e8;
  color: white;
  border: none;
  padding: 8px 18px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}
.btn-primary:disabled { background: #a8c7fa; cursor: not-allowed; }
.btn-secondary {
  background: none;
  border: 1px solid #dfe1e5;
  padding: 8px 18px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: #5f6368;
}
.modal-success { color: #34A853; font-size: 13px; margin: 8px 0 0; }
.modal-error   { color: #d93025; font-size: 13px; margin: 8px 0 0; }

/* ── Help button ── */
.help-btn {
  background: none;
  border: none;
  color: #70757a;
  font-size: 16px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 50%;
  line-height: 1;
  transition: color 0.15s, background 0.15s;
  margin-right: 4px;
  flex-shrink: 0;
}
.help-btn:hover,
.help-btn.active {
  color: #1a73e8;
  background: #e8f0fe;
}

/* ── Help popover backdrop (closes on outside click) ── */
.help-backdrop {
  position: fixed;
  inset: 0;
  z-index: 110;
}

/* ── Help popover panel ── */
.help-popover {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  /* Decoupled from the search-bar width: wide enough for the operator table,
     but capped to the viewport so it never overflows on small screens. */
  width: min(640px, 94vw);
  box-sizing: border-box;
  background: white;
  border: 1px solid #dfe1e5;
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(32, 33, 36, 0.22);
  z-index: 120;
  padding: 14px 18px 10px;
}

.help-popover-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.help-popover-title {
  font-size: 13px;
  font-weight: 700;
  color: #202124;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.help-popover-close {
  background: none;
  border: none;
  color: #70757a;
  font-size: 14px;
  cursor: pointer;
  padding: 2px 5px;
  border-radius: 4px;
}
.help-popover-close:hover { background: #f1f3f4; }

.help-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  table-layout: fixed;
}

.help-table th:nth-child(1),
.help-table td:nth-child(1) { width: 16%; }
.help-table th:nth-child(2),
.help-table td:nth-child(2) { width: 32%; }
.help-table th:nth-child(3),
.help-table td:nth-child(3) { width: 52%; }

.help-table th {
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: #70757a;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 0 8px 6px 0;
  border-bottom: 1px solid #f0f0f0;
}

.help-table td {
  padding: 5px 8px 5px 0;
  vertical-align: top;
  color: #202124;
  border-bottom: 1px solid #f8f9fa;
}

.help-table tr:last-child td {
  border-bottom: none;
}

.help-table code {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  background: #f1f3f4;
  padding: 1px 5px;
  border-radius: 4px;
  /* Wrap long examples (e.g. "(eau OR fleuve) AND pollution") instead of
     spilling past the panel background. */
  white-space: normal;
  overflow-wrap: anywhere;
  color: #1a73e8;
}

.help-note {
  font-size: 11px;
  color: #70757a;
  margin: 8px 0 0;
  font-style: italic;
}

/* The note is italic; keep the operator upright so it reads as a literal. */
.help-note code {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  font-style: normal;
  color: #1a73e8;
}

/* ── Notice shown when the search fell back to keywords only (server busy or embedding down) ── */
.degraded-notice {
  font-size: 13px;
  color: #174ea6;
  background: #e8f0fe;
  border: 1px solid #aecbfa;
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

/* ── "Vouliez-vous dire" line shown when a spelling correction finds what the query did not ── */
.suggestion-notice {
  font-size: 14px;
  color: #3c4043;
  margin-bottom: 12px;
}

.suggestion-notice a {
  color: #1a0dab;
  font-weight: 600;
  font-style: italic;
}

/* ── "Relaxed to OR" notice shown above results when strict AND found nothing ── */
.relaxed-notice {
  font-size: 13px;
  color: #5f4300;
  background: #fef7e0;
  border: 1px solid #fde293;
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

</style>