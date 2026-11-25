<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import apiClient from '../plugins/axios'
import ConfigTab from '../components/admin/ConfigTab.vue'

// ── Types ────────────────────────────────────────────────────────────────────

interface IndexCycleSummary {
  succeeded: number
  failed: number
  skipped: number
  textPages: number
  ocrPages: number
  // The index's own time: from its first file started to its last file done, and the time
  // during which one of its pages was being OCRed
  durationMs: number
  ocrActiveMs: number
}

interface ServiceOutage {
  service: string
  label: string
  since: string
}

interface IndexerStatus {
  isRunning: boolean
  isPaused: boolean
  // Services indexing waits for: it resumes by itself once they answer again.
  waitingFor?: ServiceOutage[]
  cycleTotal: number
  cycleProcessed: number
  textPagesTotal: number
  ocrPagesTotal: number
  cycleTextPages: number
  cycleOcrPages: number
  cycleOcrActiveMs: number
  appStartedAt: string | null
  cycleStartedAt: string | null
  lastCycleCompletedAt: string | null
  activeFiles: Array<{ path: string; startedAt: string }>
  lastFileCompletedAt: string | null
  queueDepth: number
  registry: { total: number; pending: number; orphans?: number }
  // The last indexing round that processed files — kept across idle rounds and restarts.
  lastRound: LastRound | null
}

interface LastRound {
  completedAt: string
  durationMs: number
  files: number
  textPages: number
  ocrPages: number
  ocrActiveMs: number
  indexes: Record<string, IndexCycleSummary>
}

interface ServiceHealth {
  url: string
  enabled: boolean
  up: boolean
  managed: boolean
  restarting: boolean
  // Reranker, and Ollama while it holds the model: where it actually runs, and what is wrong
  // with that. gpuPercent/model are Ollama's; maxPassages/candidates the reranker's.
  device?: string | null
  gpuPercent?: number
  model?: string
  maxPassages?: number | null
  candidates?: number
  problems?: ('TOO_FEW_PASSAGES' | 'NOT_ON_GPU' | 'MODEL_OFF_GPU' | 'OCR_NOT_ON_GPU')[]
  // Ollama only: it runs on another machine (llm.ollamaUrl), where it has to be started.
  remote?: boolean
}

interface CoreInfo {
  name: string
  numFiles: number | null
  sizeInBytes: number | null
  lastModified: string | null
  // false when Solr holds no core for this index yet: nothing has ever been indexed
  // into it, so it has no size and no date and its file count is a true zero.
  created?: boolean
  // indexes.yaml's visibleToGroups; absent for a core indexes.yaml does not declare (seen by nobody).
  visibleTo?: { all: boolean; groups: { name: string; sid: string }[] }
}

interface SolrHealth {
  url: string
  up: boolean
  managed: boolean
  restarting: boolean
  cores?: CoreInfo[]
  error?: string
}

interface HealthData {
  services: {
    embedding: ServiceHealth
    ner: ServiceHealth
    // Only with a GPU node: its service, and the row without "Gpu" is then this server's fallback.
    ocrGpu?: ServiceHealth
    ocr: ServiceHealth
    rerankerGpu?: ServiceHealth
    reranker: ServiceHealth
    llmGpu?: ServiceHealth
    llm: ServiceHealth
  }
  solr: SolrHealth
}

interface ServiceAllocation {
  key: string
  name: string
  workers: number
  ramMb: number
  auto: boolean
}

interface ResourceAllocation {
  totalCores: number
  reservedCores: number
  totalRamMb: number
  budgetRamMb: number
  // Reserved = this JVM's heap + Solr's + a floor for the OS; the rest is the
  // budget the Python model services are allocated from.
  reservedRamMb?: number
  heapRamMb?: number
  // Sherlock's model, when it runs on this server: Ollama holds its weights resident
  // and is not one of the services below, so the reservation has to cover it.
  llmRamMb?: number
  services: ServiceAllocation[]
}

interface CoreSegment {
  label: string
  cores: number
  color: string
  tooltip: string
  subLabel: string
}

interface IndexingError {
  kind: 'error' | 'skipped' | 'source' | 'limited'
  timestamp: string
  path: string
  message: string
  coreName?: string
}

interface BenchmarkResult {
  type: string
  pages: number
  extractionMs: number
  languageMs: number
  nerMs: number
  embeddingMs: number
  enrichmentMs: number
  totalMs: number
  pagesPerMinute: number
  completedAt: string
}

interface BenchmarkStatus {
  phase: 'IDLE' | 'PREPARING_SCANNED' | 'RUNNING' | 'DONE' | 'FAILED'
  type: string | null
  startedAt: string | null
  scannedPdfReady: boolean
  lastResult: BenchmarkResult | null
  errorMessage: string | null
  wasStopped: boolean
  pagesDone: number
  pagesTotal: number
}


// ── State ────────────────────────────────────────────────────────────────────

const status   = ref<IndexerStatus | null>(null)
const health   = ref<HealthData | null>(null)
const errors      = ref<IndexingError[]>([])
const errorSearch = ref('')
const resources = ref<ResourceAllocation | null>(null)
const benchmark = ref<BenchmarkStatus | null>(null)
const appVersion = ref<string | null>(null)
const benchmarkLoading = ref(false)
const forbidden  = ref(false)

// ── Configuration tab (diagnosis and editors live in components/admin/ConfigTab.vue) ──
const activeTab = ref<'general' | 'config'>('general')
const configTab = ref<InstanceType<typeof ConfigTab> | null>(null)
const configProblems = ref(0)
function openTab(tab: 'general' | 'config') {
  activeTab.value = tab
  if (tab === 'config') configTab.value?.refresh()
}

const loadError  = ref<string | null>(null)
const lastRefresh = ref<Date | null>(null)
const pauseLoading = ref(false)
const pauseConfirming = ref(false)
const pauseError = ref<string | null>(null)

// Full re-index — see the confirmation block in the template for what it costs.
const REINDEX_PHRASE = 'Oui je veux tout réindexer'
const reindexConfirming = ref(false)
const reindexLoading = ref(false)
const reindexPhrase = ref('')
const reindexError = ref<string | null>(null)
const reindexResult = ref<string | null>(null)
const restartingServices = ref<Set<string>>(new Set())

const tickerNow = ref(Date.now())
let pollInterval: ReturnType<typeof setInterval> | null = null
let tickInterval: ReturnType<typeof setInterval> | null = null
let restartPolls: Record<string, ReturnType<typeof setInterval>> = {}

// ── Data fetching ─────────────────────────────────────────────────────────────

const fetchStatus = async () => {
  const r = await apiClient.get<IndexerStatus>('/admin/indexer/status')
  status.value = r.data
}

const fetchHealth = async () => {
  const r = await apiClient.get<HealthData>('/admin/health')
  health.value = r.data
}

const fetchErrors = async () => {
  const r = await apiClient.get<IndexingError[]>('/admin/indexer/errors')
  errors.value = r.data
}

const fetchBenchmark = async () => {
  const r = await apiClient.get<BenchmarkStatus>('/admin/benchmark/status')
  benchmark.value = r.data
}

const fetchResources = async () => {
  const r = await apiClient.get<ResourceAllocation>('/admin/resources')
  resources.value = r.data
}


// Sherlock questions per user today, against llm.quota; a limit of null is unlimited.
interface SherlockUsage {
  enabled: boolean
  limits: Record<'BASE' | 'PREMIUM' | 'EXTENDED', number | null>
  users: { username: string, tier: 'BASE' | 'PREMIUM' | 'EXTENDED', used: number, limit: number | null }[]
}
const sherlockUsage = ref<SherlockUsage | null>(null)
const TIER_LABELS = { BASE: 'Base', PREMIUM: 'Premium', EXTENDED: 'Étendu' } as const

const fetchSherlockUsage = async () => {
  const r = await apiClient.get<SherlockUsage>('/admin/sherlock-usage')
  sherlockUsage.value = r.data
}

const fetchVersion = async () => {
  const r = await apiClient.get<{ version: string }>('/version')
  appVersion.value = r.data.version
}

const fetchAll = async () => {
  const wasRunning = status.value?.isRunning ?? false
  const wasBenchmarkActive = benchmark.value?.phase === 'RUNNING' || benchmark.value?.phase === 'PREPARING_SCANNED'
  try {
    await Promise.all([fetchStatus(), fetchHealth(), fetchErrors(), fetchBenchmark(), fetchResources(), fetchVersion(), fetchSherlockUsage()])
    lastRefresh.value = new Date()
    loadError.value = null
    const isRunning = status.value?.isRunning ?? false
    const isBenchmarkActive = benchmark.value?.phase === 'RUNNING' || benchmark.value?.phase === 'PREPARING_SCANNED'
    // Re-sync poll interval when active state changes
    if (isRunning !== wasRunning || isBenchmarkActive !== wasBenchmarkActive) {
      startPoll()
    }
  } catch (e: any) {
    if (e.response?.status === 403) {
      forbidden.value = true
      stopPoll()
    } else {
      loadError.value = 'Erreur de connexion au serveur.'
    }
  }
}

// ── Polling ──────────────────────────────────────────────────────────────────

const startPoll = () => {
  stopPoll()
  const benchmarkActive = benchmark.value?.phase === 'RUNNING' || benchmark.value?.phase === 'PREPARING_SCANNED'
  const active = status.value?.isRunning || benchmarkActive
  // Poll every 5 s when active (indexing or benchmark), every 30 s when idle
  const interval = active ? 5_000 : 30_000
  pollInterval = setInterval(fetchAll, interval)
  if (active) {
    tickInterval = setInterval(() => { tickerNow.value = Date.now() }, 1_000)
  }
}

const stopPoll = () => {
  if (pollInterval) { clearInterval(pollInterval); pollInterval = null }
  if (tickInterval) { clearInterval(tickInterval); tickInterval = null }
}

onMounted(async () => { await fetchAll(); startPoll() })
onUnmounted(() => {
  stopPoll()
  Object.values(restartPolls).forEach(clearInterval)
  restartPolls = {}
})

// ── Actions ───────────────────────────────────────────────────────────────────

const restartService = async (name: string) => {
  if (restartingServices.value.has(name)) return
  restartingServices.value = new Set([...restartingServices.value, name])
  try {
    await apiClient.post(`/admin/services/${name}/restart`)
  } catch {
    restartingServices.value.delete(name)
    restartingServices.value = new Set(restartingServices.value)
    return
  }
  // Poll health until the service reports up (or server confirms it's no longer restarting)
  if (restartPolls[name]) clearInterval(restartPolls[name])
  restartPolls[name] = setInterval(async () => {
    await fetchHealth()
    const svc = name === 'solr'
      ? health.value?.solr
      : health.value?.services[name as keyof typeof health.value.services]
    if (svc && !svc.restarting && (svc.up || !svc.managed)) {
      clearInterval(restartPolls[name])
      delete restartPolls[name]
      restartingServices.value.delete(name)
      restartingServices.value = new Set(restartingServices.value)
    }
  }, 3_000)
}

const requestPause = () => {
  if (!status.value || pauseLoading.value) return
  pauseError.value = null
  pauseConfirming.value = true
}

const cancelPause = () => {
  pauseConfirming.value = false
}

const togglePause = async (confirmed = false) => {
  if (!status.value || pauseLoading.value) return
  if (!status.value.isPaused && !confirmed) {
    requestPause()
    return
  }
  pauseConfirming.value = false
  pauseError.value = null
  pauseLoading.value = true
  try {
    if (status.value.isPaused) {
      await apiClient.post('/admin/indexer/resume')
    } else {
      await apiClient.post('/admin/indexer/pause')
    }
    await fetchStatus()
    startPoll()
  } catch {
    pauseError.value = status.value?.isPaused
      ? 'Impossible de reprendre l\'indexation. Veuillez réessayer.'
      : 'Impossible de mettre l\'indexation en pause. Veuillez réessayer.'
  } finally {
    pauseLoading.value = false
  }
}

const requestReindexAll = () => {
  if (!canReindex.value || reindexLoading.value) return
  reindexError.value = null
  reindexResult.value = null
  reindexPhrase.value = ''
  reindexConfirming.value = true
}

const cancelReindexAll = () => {
  reindexConfirming.value = false
  reindexPhrase.value = ''
}

const confirmReindexAll = async () => {
  if (!reindexPhraseOk.value || reindexLoading.value) return
  reindexLoading.value = true
  reindexError.value = null
  try {
    const r = await apiClient.post<{ coresReloaded: number; markedForReindex: number }>(
      '/admin/reindex-all',
      { confirmation: reindexPhrase.value }
    )
    reindexResult.value =
      `Ré-indexage complet lancé : ${r.data.markedForReindex.toLocaleString('fr-FR')} fichier(s) à réindexer, `
      + `${r.data.coresReloaded} index rechargé(s). Le traitement démarre au prochain cycle.`
    reindexConfirming.value = false
    reindexPhrase.value = ''
    await fetchStatus()
    startPoll()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { error?: string } } })?.response?.data?.error
    reindexError.value = msg ?? "Le ré-indexage complet n'a pas pu être lancé. Veuillez réessayer."
  } finally {
    reindexLoading.value = false
  }
}

const startBenchmark = async (type: 'text' | 'scanned') => {
  if (benchmarkLoading.value) return
  benchmarkLoading.value = true
  try {
    await apiClient.post(`/admin/benchmark/start?type=${type}`)
    await fetchBenchmark()
    // Poll faster while benchmark is running
    startPoll()
  } finally {
    benchmarkLoading.value = false
  }
}

const prepareScanned = async () => {
  if (benchmarkLoading.value) return
  benchmarkLoading.value = true
  try {
    await apiClient.post('/admin/benchmark/prepare-scanned')
    await fetchBenchmark()
  } finally {
    benchmarkLoading.value = false
  }
}

const stopBenchmark = async () => {
  const b = benchmark.value
  const elapsedMin = b?.startedAt
    ? Math.round((Date.now() - new Date(b.startedAt).getTime()) / 60_000)
    : 0
  const msg = elapsedMin > 0
    ? `Le banc d'essai tourne depuis ${elapsedMin} min. Arrêter maintenant perdra toute la progression. Continuer ?`
    : 'Arrêter le banc d\'essai en cours ?'
  if (!confirm(msg)) return
  await apiClient.post('/admin/benchmark/stop')
  await fetchBenchmark()
  startPoll()
}

const resetBenchmark = async () => {
  await apiClient.post('/admin/benchmark/reset')
  await fetchBenchmark()
}

const fmtMs = (ms: number) => {
  if (ms >= 60_000) {
    const m = Math.floor(ms / 60_000)
    const s = Math.floor((ms % 60_000) / 1000)
    return `${m}m ${s.toString().padStart(2, '0')}s`
  }
  return ms >= 1_000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

// ── Computed helpers ──────────────────────────────────────────────────────────

const progressPct = computed(() => {
  if (!status.value || status.value.cycleTotal === 0) return 0
  return Math.min(100, Math.round((status.value.cycleProcessed / status.value.cycleTotal) * 100))
})

const isPausing = computed(() =>
  !!status.value?.isPaused && !!status.value?.isRunning
)

// Accents, case and punctuation are forgiven — the server normalises the same way.
const normalizePhrase = (value: string) =>
  value.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z]/g, '')

const reindexPhraseOk = computed(() =>
  normalizePhrase(reindexPhrase.value) === normalizePhrase(REINDEX_PHRASE)
)

// A full re-index deletes and rewrites the Solr configset on disk, so it may only run
// while the indexer is idle. Pending files are blocked too: they would be swallowed by
// the re-index and their own progress lost.
const reindexBlockedReason = computed(() => {
  const s = status.value
  if (!s) return "État de l'indexeur inconnu."
  if (s.isRunning) return "Indisponible : un cycle d'indexation est en cours."
  if (s.isPaused) return "Indisponible : reprenez l'indexation — le ré-indexage la met lui-même en pause."
  if (s.registry.pending > 0) {
    return `Indisponible : ${s.registry.pending.toLocaleString('fr-FR')} fichier(s) restent à indexer.`
  }
  return null
})

const canReindex = computed(() => reindexBlockedReason.value === null)

const waitingFor = computed(() => status.value?.waitingFor ?? [])

const outageSince = (iso: string) =>
  new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })

const indexerLabel = computed(() => {
  if (!status.value) return ''
  if (isPausing.value) return 'Mise en pause…'
  if (status.value.isPaused) return 'En pause'
  if (waitingFor.value.length) return "En attente d'un service"
  if (status.value.isRunning) return 'En cours'
  return 'Inactif'
})

const indexerLabelClass = computed(() => {
  if (!status.value) return ''
  if (isPausing.value) return 'badge-pausing'
  if (status.value.isPaused) return 'badge-paused'
  if (waitingFor.value.length) return 'badge-down'
  if (status.value.isRunning) return 'badge-running'
  return 'badge-idle'
})

const serviceRows = computed(() => {
  if (!health.value) return []
  const labels: Record<string, string> = {
    embedding: 'Embedding',
    ner: 'NER',
    ocr: 'OCR',
    reranker: 'Reranker',
    llm: 'Ollama (Sherlock)',
  }
  const services = health.value.services
  const rows = Object.entries(services)
    .filter(([key]) => !key.endsWith('Gpu'))
    .map(([key, svc]) => ({
      key,
      label: labels[key] ?? key,
      url: svc.url,
      enabled: svc.enabled,
      up: svc.up,
      managed: svc.managed,
      restarting: svc.restarting || restartingServices.value.has(key),
      device: svc.device ?? null,
      gpuPercent: svc.gpuPercent ?? null,
      // With a GPU node: the other of its two services, shown under the one in use.
      secondary: null as { label: string, url: string, up: boolean } | null,
    }))
  // With a GPU node, a service is one row showing where it runs now: the node while it
  // answers, else this server's service. The restart button stays this server's service.
  const nodes = { ocr: services.ocrGpu, reranker: services.rerankerGpu, llm: services.llmGpu }
  for (const row of rows) {
    const node = nodes[row.key as keyof typeof nodes]
    if (!node) continue
    const local = { url: row.url, up: row.up }
    if (node.up) {
      row.url = node.url
      row.up = true
      row.device = node.device ?? null
      row.gpuPercent = node.gpuPercent ?? null
      row.secondary = { label: 'secours (ce serveur)', ...local }
    } else {
      row.device = local.up ? 'cpu' : null
      row.gpuPercent = null
      row.secondary = { label: 'serveur GPU', url: node.url, up: false }
    }
  }
  // Append Solr as a row
  rows.push({
    key: 'solr',
    label: 'Solr',
    url: health.value.solr.url,
    enabled: true,
    up: health.value.solr.up,
    managed: health.value.solr.managed,
    restarting: health.value.solr.restarting || restartingServices.value.has('solr'),
    device: null,
    gpuPercent: null,
    secondary: null,
  })
  return rows
})

// The reranker's deployment problems, in the admin's words. The checks themselves are made
// on the server (RerankerService.deployment); only the wording lives here.
// "GPU", "CPU", or "GPU 60 %" when Ollama split the model between the card and the processor.
function deviceLabel(device: string, gpuPercent: number | null): string {
  if (!device.startsWith('cuda')) return 'CPU'
  return gpuPercent != null && gpuPercent < 100 ? `GPU ${gpuPercent} %` : 'GPU'
}

// Deployment problems of the reranker and of Ollama, in the admin's words. The checks themselves
// are made on the server (RerankerService.deployment, LlmService.ModelPlacement); only the
// wording lives here.
const serviceProblems = computed(() => {
  const services = health.value?.services
  const rows = [services?.rerankerGpu, services?.reranker, services?.llmGpu, services?.llm, services?.ocrGpu]
  return rows.flatMap(svc => (svc?.problems ?? []).map(p => {
    switch (p) {
      case 'TOO_FEW_PASSAGES':
        return `Le reranker accepte au plus ${svc?.maxPassages} passages, mais reranker.candidates vaut ${svc?.candidates} : `
          + `il refuse chaque question et Sherlock se rabat sur l'ordre de la recherche. Réglez `
          + `RERANKER_MAX_PASSAGES à ${svc?.candidates} au moins là où tourne le reranker, ou baissez reranker.candidates.`
      case 'NOT_ON_GPU':
        return `Le reranker de la machine GPU tourne sur son processeur (${svc?.device}) : `
          + `une minute par question au lieu d'une seconde. PyTorch pour CUDA manque ou le pilote NVIDIA `
          + `n'est pas chargé — refaites l'installation de la machine GPU là où tourne le reranker.`
      case 'MODEL_OFF_GPU':
        return `Le modèle de Sherlock (${svc?.model}) tourne sur le processeur (${svc?.gpuPercent ?? 0} % sur la carte) : `
          + `des minutes par réponse au lieu de secondes. Ollama garde un modèle là où il l'a chargé ; `
          + `un autre programme occupait sans doute la carte à ce moment. Libérez-la (nvidia-smi), `
          + `puis là où tourne Ollama : sudo systemctl restart ollama (ou intracherche-ollama).`
      case 'OCR_NOT_ON_GPU':
        return `L'OCR du serveur GPU tourne sur son processeur (${svc?.device}) : les pages numérisées y `
          + `sont lues sans accélération. onnxruntime pour CUDA ne se charge pas — refaites l'installation `
          + `de la machine GPU.`
    }
  }))
})

const SERVICE_COLORS: Record<string, string> = {
  ocr:       '#fb8c00',
  ner:       '#1e88e5',
  embedding: '#8e24aa',
  reranker:  '#00897b',
}

const coreSegments = computed<CoreSegment[]>(() => {
  const r = resources.value
  if (!r || !r.totalCores) return []
  const segments: CoreSegment[] = [
    {
      label:    'Java / Solr',
      cores:    r.reservedCores,
      color:    '#78909c',
      tooltip:  `Java / Solr · ${r.reservedCores} cœur${r.reservedCores > 1 ? 's' : ''} réservés`,
      subLabel: `${r.reservedCores} cœur${r.reservedCores > 1 ? 's' : ''}`,
    },
    ...r.services.map(s => {
      const ram = s.ramMb >= 1024
        ? `~${(s.ramMb / 1024).toFixed(1)} Go/worker`
        : `~${s.ramMb} Mo/worker`
      return {
        label:    s.name,
        cores:    s.workers,
        color:    SERVICE_COLORS[s.key] ?? '#9aa0a6',
        tooltip:  `${s.name} · ${s.workers} worker${s.workers > 1 ? 's' : ''} · ${ram}${s.auto ? ' (auto)' : ''}`,
        subLabel: `${s.workers}w · ${ram}${s.auto ? ' · auto' : ''}`,
      }
    }),
  ]
  const spare = r.totalCores - segments.reduce((sum, s) => sum + s.cores, 0)
  if (spare > 0) {
    segments.push({
      label:    'Libre',
      cores:    spare,
      color:    '#e0e4ea',
      tooltip:  `${spare} cœur${spare > 1 ? 's' : ''} non alloués`,
      subLabel: `${spare} cœur${spare > 1 ? 's' : ''}`,
    })
  }
  return segments
})

// Sources (shares, local directories) that could not be read at all during the last
// attempt. They are not file errors: the indexer never got as far as a file, so the
// registry stays empty and every other figure on the page looks healthy.
const sourceErrors = computed(() => errors.value.filter(e => e.kind === 'source'))

const filteredErrors = computed(() => {
  const q = errorSearch.value.trim().toLowerCase()
  if (!q) return errors.value
  return errors.value.filter(e =>
    e.path.toLowerCase().includes(q) ||
    e.message.toLowerCase().includes(q) ||
    (e.coreName ?? '').toLowerCase().includes(q)
  )
})

// ── Formatting ────────────────────────────────────────────────────────────────

const fmt = (iso: string | null) => {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('fr-FR', {
    dateStyle: 'short', timeStyle: 'short',
  })
}

/** Megabytes as the panel says them elsewhere: one decimal, French separator. */
const fmtGb = (mb: number) => (mb / 1024).toLocaleString('fr-FR', {
  minimumFractionDigits: 1, maximumFractionDigits: 1,
}) + ' Go'

const fmtBytes = (b: number | null) => {
  if (b == null) return '—'
  if (b < 1024) return b + ' o'
  if (b < 1_048_576) return (b / 1024).toFixed(1) + ' Ko'
  return (b / 1_048_576).toFixed(1) + ' Mo'
}

const fmtRefresh = computed(() =>
  lastRefresh.value
    ? lastRefresh.value.toLocaleTimeString('fr-FR', { timeStyle: 'medium' })
    : '—'
)

const shortPath = (p: string) =>
  p.length > 60 ? '…' + p.slice(-60) : p

// Active files sorted slowest-first so the potential stall is always on top
const activeFilesSorted = computed(() =>
  [...(status.value?.activeFiles ?? [])].sort(
    (a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()
  )
)

const elapsedSeconds = (startedAt: string) =>
  Math.round((Date.now() - new Date(startedAt).getTime()) / 1000)

const fmtDuration = (totalSeconds: number): string => {
  const s = Math.max(0, Math.round(totalSeconds))
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d > 0) return `${d}j ${h}h ${m}m`
  if (h > 0) return `${h}h ${m}m ${sec}s`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}

// Seconds since the app started — drives "En cours depuis" and page rate denominators
const uptimeSeconds = computed(() => {
  if (!status.value?.appStartedAt) return 0
  return Math.max(1, (tickerNow.value - new Date(status.value.appStartedAt).getTime()) / 1000)
})

// Seconds elapsed in the current cycle — used only for ETA
const cycleElapsedSeconds = computed(() => {
  if (!status.value?.isRunning || !status.value.cycleStartedAt) return 0
  return Math.max(1, (tickerNow.value - new Date(status.value.cycleStartedAt).getTime()) / 1000)
})

const textPpm = (pages: number, durationMs: number): string | null =>
  pages < 1 || durationMs < 1 ? null : (pages / (durationMs / 60000)).toFixed(1)

// OCR rate uses the OCR workers' busy time — not diluted by text-only file processing
const ocrPpm = (pages: number, ocrActiveMs: number): string | null =>
  pages < 1 || ocrActiveMs < 1 ? null : (pages / (ocrActiveMs / 60000)).toFixed(1)

// While a cycle runs: its own rate once it has enough to measure. Otherwise — idle, or a
// cycle that has just started — the last round that processed files.
const textPagesPerMinute = computed<string | null>(() => {
  const s = status.value
  if (!s) return null
  if (s.isRunning && cycleElapsedSeconds.value >= 10) {
    const live = textPpm(s.cycleTextPages, cycleElapsedSeconds.value * 1000)
    if (live) return live
  }
  return s.lastRound ? textPpm(s.lastRound.textPages, s.lastRound.durationMs) : null
})

const ocrPagesPerMinute = computed<string | null>(() => {
  const s = status.value
  if (!s) return null
  if (s.isRunning && s.cycleOcrActiveMs >= 1000) {
    const live = ocrPpm(s.cycleOcrPages, s.cycleOcrActiveMs)
    if (live) return live
  }
  return s.lastRound ? ocrPpm(s.lastRound.ocrPages, s.lastRound.ocrActiveMs) : null
})

// Per-index pages/min for the last round table, each over the index's own time
const summaryTextPpm = (s: IndexCycleSummary): string | null => textPpm(s.textPages, s.durationMs)

const summaryOcrPpm = (s: IndexCycleSummary): string | null => ocrPpm(s.ocrPages, s.ocrActiveMs)


const etaSeconds = computed<number | null>(() => {
  const s = status.value
  if (!s?.isRunning || cycleElapsedSeconds.value < 10 || s.cycleProcessed < 2) return null
  const rate = s.cycleProcessed / cycleElapsedSeconds.value
  if (rate <= 0) return null
  return Math.round((s.cycleTotal - s.cycleProcessed) / rate)
})

const benchmarkElapsedMs = computed(() => {
  const b = benchmark.value
  if (!b?.startedAt || b.phase !== 'RUNNING') return 0
  return Math.max(1, tickerNow.value - new Date(b.startedAt).getTime())
})

const benchmarkLiveRate = computed<number | null>(() => {
  const b = benchmark.value
  if (!b || b.phase !== 'RUNNING' || b.pagesDone < 1) return null
  const elapsedMin = benchmarkElapsedMs.value / 60_000
  if (elapsedMin < 0.05) return null
  return b.pagesDone / elapsedMin
})

const benchmarkEtaMin = computed<number | null>(() => {
  const b = benchmark.value
  const rate = benchmarkLiveRate.value
  if (!b || !rate || b.pagesTotal < 1) return null
  return (b.pagesTotal - b.pagesDone) / rate
})
</script>

<template>
  <div class="admin-page">
    <div class="admin-header">
      <h1>Panneau d'administration</h1>
      <span class="refresh-time">Actualisé à {{ fmtRefresh }}</span>
    </div>

    <!-- Forbidden -->
    <div v-if="forbidden" class="forbidden-box">
      <span class="forbidden-icon">🔒</span>
      <div>
        <strong>Accès refusé</strong>
        <p>Votre compte n'appartient pas au groupe <code>IntraCherche-Admin</code>.<br>
        Contactez l'administrateur système pour y être ajouté.</p>
      </div>
    </div>

    <!-- Connection error -->
    <div v-else-if="loadError && !status" class="error-banner">{{ loadError }}</div>

    <template v-else>

      <!-- A service indexing needs is down: indexing waits for it rather than index files
           without it, and resumes by itself. First thing on the page, as nothing moves. -->
      <div v-if="waitingFor.length" class="outage-banner" role="status">
        <div v-for="o in waitingFor" :key="o.service" class="outage-banner-title">
          ⚠ {{ o.label }} indisponible depuis {{ outageSince(o.since) }} — l'indexation est en attente.
        </div>
        <div class="outage-banner-note">
          Elle reprendra d'elle-même dès que le service répondra (vérifié toutes les 10 s).
          Aucun fichier n'est indexé sans lui ; la recherche reste disponible.
        </div>
      </div>

      <nav class="tab-bar" role="tablist">
        <button role="tab" :aria-selected="activeTab === 'general'" :class="{ active: activeTab === 'general' }"
                @click="openTab('general')">Général</button>
        <button role="tab" :aria-selected="activeTab === 'config'" :class="{ active: activeTab === 'config' }"
                @click="openTab('config')">
          Configuration<span v-if="configProblems" class="tab-badge">{{ configProblems }}</span>
        </button>
      </nav>

      <ConfigTab v-show="activeTab === 'config'" ref="configTab" @problems="n => (configProblems = n)" />

      <div v-show="activeTab === 'general'">

      <!-- ── Indexeur ────────────────────────────────────────────── -->
      <section class="card">
        <div class="card-header">
          <h2>Indexeur</h2>
          <span v-if="status" :class="['badge', indexerLabelClass]">{{ indexerLabel }}</span>
        </div>

        <div v-if="status" class="card-body">

          <!-- A source the last cycle could not open: nothing of it was read, so the
               figures below say nothing about it. Shown before them, and in place of
               the up-to-date note, which would otherwise be the only thing on screen. -->
          <div v-if="sourceErrors.length" class="source-error">
            <div class="source-error-title">
              {{ sourceErrors.length > 1 ? 'Sources inaccessibles' : 'Source inaccessible' }} —
              aucun fichier n'en a été lu lors du dernier passage.
            </div>
            <div v-for="(err, i) in sourceErrors" :key="i" class="source-error-row">
              <span class="source-error-path">{{ err.path }}</span>
              <span class="source-error-index">{{ err.coreName ?? '—' }}</span>
              <span class="source-error-msg">{{ err.message }}</span>
            </div>
          </div>

          <!-- Up-to-date indicator when the last cycle found nothing to do -->
          <div v-if="!sourceErrors.length && !status.isRunning && status.cycleTotal === 0 && status.lastCycleCompletedAt" class="uptodate-note">
            Tous les fichiers sont à jour.
          </div>

          <!-- Progress bar (only when a cycle has files to process) -->
          <div v-if="status.cycleTotal > 0" class="progress-section">
            <div class="progress-track">
              <div class="progress-fill" :style="{ width: progressPct + '%' }"></div>
            </div>
            <div class="progress-label">
              {{ status.cycleProcessed.toLocaleString('fr-FR') }} /
              {{ status.cycleTotal.toLocaleString('fr-FR') }} fichiers
              <span class="progress-pct">({{ progressPct }} %)</span>
            </div>
            <div v-if="status.activeFiles?.length" class="active-files">
              <div class="active-files-header">
                {{ status.activeFiles.length > 1 ? status.activeFiles.length + ' fichiers en cours' : 'Fichier en cours' }}
              </div>
              <div
                v-for="f in activeFilesSorted"
                :key="f.path"
                class="active-file-row"
                :class="{ 'active-file-stalled': elapsedSeconds(f.startedAt) > 60 }"
              >
                <span class="active-file-elapsed">({{ elapsedSeconds(f.startedAt) }}s)</span>
                <span class="active-file-path" :title="f.path">{{ shortPath(f.path) }}</span>
              </div>
            </div>
          </div>

          <!-- Performance metrics (lifetime throughput since app start) -->
          <div v-if="status.appStartedAt" class="perf-section">
            <div class="perf-title">Performance</div>
            <dl class="perf-grid">
              <div class="perf-stat">
                <dt :title="`Durée de fonctionnement d'IntraCherche depuis son démarrage, le ${fmt(status.appStartedAt)}`">En cours depuis</dt>
                <dd>{{ fmtDuration(uptimeSeconds) }}</dd>
              </div>
              <div class="perf-stat">
                <dt title="Débit de pages avec texte natif (Word, PDF natif, présentations, tableurs) du cycle en cours, sinon de la dernière indexation.">Pages texte/min</dt>
                <dd>{{ textPagesPerMinute ?? '—' }}</dd>
              </div>
              <div class="perf-stat">
                <dt title="Débit de pages OCR (PDF scannés, images) du cycle en cours, sinon de la dernière indexation. Basé sur le temps pendant lequel l'OCR lit au moins une page, non dilué par les fichiers texte.">Pages scan/min</dt>
                <dd>{{ ocrPagesPerMinute ?? '—' }}</dd>
              </div>

              <div v-if="etaSeconds !== null" class="perf-stat">
                <dt title="Estimation de la fin du cycle d'indexation en cours, basée sur la vitesse de traitement des fichiers du cycle actuel.">Fin du cycle estimée</dt>
                <dd>~{{ fmtDuration(etaSeconds) }}</dd>
              </div>
            </dl>
          </div>

          <!-- Stats grid -->
          <dl class="stats-grid">
            <div class="stat">
              <dt>File d'attente Solr</dt>
              <dd>{{ status.queueDepth.toLocaleString('fr-FR') }} tâche(s)</dd>
            </div>
            <div class="stat">
              <dt>Fichiers dans le registre</dt>
              <dd>{{ status.registry.total.toLocaleString('fr-FR') }}</dd>
            </div>
            <div class="stat">
              <dt>Fichiers en attente</dt>
              <dd>{{ status.registry.pending.toLocaleString('fr-FR') }}</dd>
            </div>
            <!-- Files belonging to no index: leftovers of a deleted index. Shown only when
                 there are any, so a healthy registry does not display a zero. -->
            <div v-if="(status.registry.orphans ?? 0) > 0" class="stat">
              <dt>Fichiers sans index</dt>
              <dd><span class="summary-fail">{{ (status.registry.orphans ?? 0).toLocaleString('fr-FR') }}</span></dd>
            </div>
            <div class="stat">
              <dt>Dernier cycle terminé</dt>
              <dd>{{ fmt(status.lastCycleCompletedAt) }}</dd>
            </div>
            <div v-if="status.isRunning && status.cycleStartedAt" class="stat">
              <dt>Cycle démarré à</dt>
              <dd>{{ fmt(status.cycleStartedAt) }}</dd>
            </div>
          </dl>

          <!-- Per-index cycle summary -->
          <!-- Last round that processed files: kept across idle rounds and restarts -->
          <div v-if="status.lastRound" class="index-summary">
            <div class="index-summary-title">
              Dernière indexation
              <span class="index-summary-meta">
                · terminée le {{ fmt(status.lastRound.completedAt) }}
                · {{ status.lastRound.files.toLocaleString('fr-FR') }} fichier(s)
                en {{ fmtDuration(status.lastRound.durationMs / 1000) }}
              </span>
            </div>
            <table class="summary-table">
              <thead>
                <tr>
                  <th rowspan="2">Index</th>
                  <th class="col-num" rowspan="2">Indexés</th>
                  <th class="col-num" rowspan="2">Erreurs</th>
                  <th class="col-num" rowspan="2">Ignorés</th>
                  <th class="col-num col-group" colspan="2" title="Pages traitées par minute par chaque index, sur son propre temps : texte natif du premier au dernier fichier de l'index, OCR pendant la lecture de ses pages. Des index traités en même temps se partagent la machine.">Pages/min</th>
                </tr>
                <tr>
                  <th class="col-num col-sub">Texte</th>
                  <th class="col-num col-sub">Scan</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(s, name) in status.lastRound.indexes" :key="name">
                  <td class="idx-name">{{ name }}</td>
                  <td class="col-num summary-ok">{{ s.succeeded.toLocaleString('fr-FR') }}</td>
                  <td class="col-num">
                    <span v-if="s.failed > 0" class="summary-fail">{{ s.failed.toLocaleString('fr-FR') }}</span>
                    <span v-else class="summary-zero">0</span>
                  </td>
                  <td class="col-num summary-skip">{{ s.skipped.toLocaleString('fr-FR') }}</td>
                  <td class="col-num summary-ppm">{{ summaryTextPpm(s) ?? '—' }}</td>
                  <td class="col-num summary-ppm">{{ summaryOcrPpm(s) ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Pause / Resume -->
          <div class="action-row">
            <template v-if="pauseConfirming">
              <span class="action-confirm-msg">Mettre l'indexeur en pause ?</span>
              <button class="action-btn btn-pause-confirm" @click="togglePause(true)">Confirmer</button>
              <button class="action-btn btn-cancel" @click="cancelPause">Annuler</button>
            </template>
            <template v-else>
              <button
                class="action-btn"
                :class="(status.isPaused && !isPausing) ? 'btn-resume' : 'btn-pause'"
                :disabled="pauseLoading || isPausing"
                @click="togglePause(false)"
              >
                <span v-if="pauseLoading">…</span>
                <span v-else-if="isPausing">⏸ Mise en pause…</span>
                <span v-else-if="status.isPaused">▶ Reprendre l'indexation</span>
                <span v-else>⏸ Mettre en pause</span>
              </button>
              <span class="action-note" v-if="isPausing">
                Mise en pause en cours…
              </span>
            </template>
          </div>
          <div v-if="pauseError" class="pause-error">{{ pauseError }}</div>

          <!-- Ré-indexage complet -->
          <div class="reindex-block">
            <template v-if="reindexConfirming">
              <p class="reindex-warning">
                <strong>Tout sera refait depuis le début :</strong> chaque fichier sera
                relu sur son partage, ré-analysé et ré-océrisé. L'opération dure aussi
                longtemps que l'indexation initiale — comptez le même temps qu'à
                l'installation. La recherche reste disponible pendant toute la durée,
                sur l'index actuel.
              </p>
              <p class="reindex-warning">
                Elle n'est nécessaire qu'après certaines mises à jour d'IntraCherche, et
                seulement quand la mise à jour le précise. <strong>En cas de doute,
                demandez à IntraCherche avant de lancer.</strong>
              </p>
              <label class="reindex-label" for="reindex-phrase">
                Pour confirmer, saisissez : <strong>{{ REINDEX_PHRASE }}</strong>
              </label>
              <input
                id="reindex-phrase"
                v-model="reindexPhrase"
                class="reindex-input"
                type="text"
                autocomplete="off"
                spellcheck="false"
                :placeholder="REINDEX_PHRASE"
                @keyup.enter="confirmReindexAll"
              />
              <div class="action-row">
                <button
                  class="action-btn btn-pause-confirm"
                  :disabled="!reindexPhraseOk || reindexLoading"
                  @click="confirmReindexAll"
                >
                  <span v-if="reindexLoading">…</span>
                  <span v-else>Lancer le ré-indexage complet</span>
                </button>
                <button class="action-btn btn-cancel" @click="cancelReindexAll">Annuler</button>
              </div>
            </template>
            <template v-else>
              <div class="action-row">
                <button
                  class="action-btn btn-reindex"
                  :disabled="!canReindex || reindexLoading"
                  title="Relit, ré-analyse et ré-océrise tous les fichiers. À ne lancer qu'après une mise à jour d'IntraCherche qui le demande — en cas de doute, demandez à IntraCherche."
                  @click="requestReindexAll"
                >
                  ↻ Ré-indexer tout
                </button>
                <span class="action-note">
                  {{ reindexBlockedReason ?? "Après certaines mises à jour seulement : tout est relu et ré-océrisé." }}
                </span>
              </div>
            </template>
          </div>
          <div v-if="reindexError" class="pause-error">{{ reindexError }}</div>
          <div v-if="reindexResult" class="reindex-ok">{{ reindexResult }}</div>
        </div>

        <div v-else class="card-body loading">Chargement…</div>
      </section>

      <!-- ── Santé des services ──────────────────────────────────── -->
      <section class="card">
        <div class="card-header">
          <h2>Services</h2>
        </div>

        <div v-if="health" class="card-body">

          <!-- Services + Solr -->
          <table class="health-table">
            <thead>
              <tr>
                <th>Service</th>
                <th>URL</th>
                <th>État</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="svc in serviceRows" :key="svc.key">
                <td class="svc-name">{{ svc.label }}</td>
                <td class="svc-url">
                  {{ svc.url }}
                  <div v-if="svc.secondary" class="svc-secondary">
                    {{ svc.secondary.label }} : {{ svc.secondary.url }},
                    {{ svc.secondary.up ? 'en ligne' : 'hors ligne' }}
                  </div>
                </td>
                <td>
                  <span v-if="svc.restarting"  class="badge badge-restarting">Redémarrage…</span>
                  <span v-else-if="!svc.enabled" class="badge badge-idle">Désactivé</span>
                  <span v-else-if="svc.up"       class="badge badge-up">En ligne</span>
                  <span v-else                   class="badge badge-down">Hors ligne</span>
                  <span v-if="svc.device" class="svc-device" :title="svc.device">
                    {{ deviceLabel(svc.device, svc.gpuPercent) }}
                  </span>
                </td>
                <td class="svc-actions">
                  <button
                    v-if="svc.managed"
                    class="restart-btn"
                    :disabled="svc.restarting"
                    :title="svc.secondary ? 'Redémarrer le service ' + svc.label + ' de ce serveur' : 'Redémarrer ' + svc.label"
                    @click="restartService(svc.key)"
                  >↺</button>
                </td>
              </tr>
            </tbody>
          </table>

          <div v-for="problem in serviceProblems" :key="problem" class="solr-error">{{ problem }}</div>

          <div v-if="health.services.ocrGpu && !health.services.ocrGpu.up" class="solr-error">
            L'OCR du serveur GPU ne répond pas : les pages numérisées sont lues sur le processeur de ce
            serveur, bien plus lentement, jusqu'à ce qu'il réponde de nouveau. Vérifiez que le serveur GPU
            est allumé (et ne s'est pas mis en veille), puis, sur celui-ci :
            <code>sudo systemctl start intracherche-ocr</code>.
          </div>

          <div v-if="health.services.rerankerGpu && !health.services.rerankerGpu.up" class="solr-error">
            Le reranker du serveur GPU ne répond pas : les questions de Sherlock sont classées sur le
            processeur de ce serveur, bien plus lentement, jusqu'à ce qu'il réponde de nouveau. S'il a été
            arrêté pour libérer la carte, c'est attendu ; sinon, sur le serveur GPU :
            <code>sudo systemctl start intracherche-reranker</code>.
          </div>

          <div v-if="health.services.llmGpu && !health.services.llmGpu.up && health.services.llm.up" class="solr-error">
            L'Ollama du serveur GPU ne répond pas : Sherlock répond avec le modèle de ce serveur, en
            quelques minutes au lieu de quelques secondes, jusqu'à ce qu'il réponde de nouveau. S'il a été
            arrêté pour libérer la carte, c'est attendu ; sinon, sur le serveur GPU :
            <code>sudo systemctl start ollama</code>.
          </div>

          <!-- Ollama is systemd-managed, not restartable from here: tell the admin what to do -->
          <div v-if="health.services.llm?.enabled && !health.services.llm.up && !health.services.llmGpu?.up" class="solr-error">
            Ollama est hors ligne — Sherlock refusera les questions tant qu'il n'est pas démarré.
            <template v-if="health.services.llm.remote">
              Vérifiez que le serveur GPU est allumé (et ne s'est pas mis en veille), puis, sur celui-ci :
              <code>sudo systemctl start ollama</code>.
            </template>
            <template v-else>
              Sur le serveur : <code>sudo systemctl start intracherche-ollama</code>
              (ou <code>sudo systemctl start ollama</code> si Ollama était déjà installé avant IntraCherche).
            </template>
          </div>

          <!-- CPU core allocation bar -->
          <div v-if="resources?.totalCores" class="alloc-section">
            <div class="alloc-title">Répartition des cœurs CPU — {{ resources.totalCores }} cœurs logiques</div>
            <div class="alloc-bar">
              <div
                v-for="seg in coreSegments"
                :key="seg.label"
                class="alloc-seg"
                :style="{ flex: seg.cores, background: seg.color }"
                :title="seg.tooltip"
              ></div>
            </div>
            <div class="alloc-legend">
              <div v-for="seg in coreSegments" :key="seg.label" class="alloc-legend-item">
                <span class="alloc-dot" :style="{ background: seg.color }"></span>
                <span class="alloc-leg-name">{{ seg.label }}</span>
                <span class="alloc-leg-sub">{{ seg.subLabel }}</span>
              </div>
            </div>

            <!-- How the RAM is divided. The heap is set by the installer from the
                 server's RAM, and the services are allocated from what is left. -->
            <div v-if="resources.reservedRamMb" class="alloc-ram">
              Mémoire — {{ fmtGb(resources.totalRamMb) }} au total :
              <strong>{{ fmtGb(resources.heapRamMb ?? 0) }}</strong> de tas Java,
              {{ fmtGb(resources.reservedRamMb) }} réservés (tas Java, Solr, système<template
                v-if="resources.llmRamMb">, dont {{ fmtGb(resources.llmRamMb) }} pour le modèle de Sherlock</template>),
              {{ fmtGb(resources.budgetRamMb) }} pour les services d'analyse.
            </div>
          </div>

          <!-- Solr core details -->
          <div v-if="health.solr.up && health.solr.cores?.length" class="solr-cores">
            <table class="core-table">
              <thead>
                <tr>
                  <th>Index (core)</th>
                  <th class="col-num">Fichiers</th>
                  <th class="col-num">Taille</th>
                  <th>Dernière modification</th>
                  <th>Visible par</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="core in health.solr.cores" :key="core.name">
                  <td>
                    {{ core.name }}
                    <span v-if="core.created === false" class="core-never" title="Aucun fichier n'a encore été indexé dans cet index.">jamais indexé</span>
                  </td>
                  <td class="col-num">{{ core.numFiles?.toLocaleString('fr-FR') ?? '—' }}</td>
                  <td class="col-num">{{ fmtBytes(core.sizeInBytes) }}</td>
                  <td>{{ fmt(core.lastModified) }}</td>
                  <td>
                    <template v-if="!core.visibleTo">
                      <span title="Cet index n'est pas déclaré dans indexes.yaml : personne ne le voit.">personne</span>
                    </template>
                    <template v-else-if="core.visibleTo.all">tous les utilisateurs</template>
                    <template v-else>
                      <span v-for="(g, i) in core.visibleTo.groups" :key="g.sid" :title="g.sid">{{ i ? ', ' : '' }}{{ g.name }}</span>
                    </template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="!health.solr.up && health.solr.error" class="solr-error">
            {{ health.solr.error }}
          </div>
        </div>

        <div v-else class="card-body loading">Chargement…</div>
      </section>

      <!-- ── Banc d'essai ──────────────────────────────────────────── -->
      <section class="card" v-if="status">
        <div class="card-header">
          <h2>Banc d'essai d'indexation</h2>
          <span v-if="benchmark?.phase === 'RUNNING' || benchmark?.phase === 'PREPARING_SCANNED'"
                class="badge badge-running">En cours…</span>
        </div>

        <div class="card-body">
          <p class="bench-note">
            Mesure le processus d'indexation complet sur une PDF de 117 pages de test
            (donc sans lecture réseau) : extraction de texte, NER et embedding en parallèle.
            <strong>L'indexeur doit être en pause pour lancer ce banc d'essai.</strong>
          </p>

          <!-- Error state -->
          <div v-if="benchmark?.phase === 'FAILED'" class="bench-error">
            Échec : {{ benchmark.errorMessage }}
            <button class="btn-small" @click="resetBenchmark">Réinitialiser</button>
          </div>

          <!-- Action buttons -->
          <div class="bench-actions">
            <button
              class="btn btn-benchmark"
              :disabled="!status.isPaused || benchmark?.phase === 'RUNNING' || benchmark?.phase === 'PREPARING_SCANNED'"
              @click="startBenchmark('text')"
            >
              ▶ PDF textuel
            </button>

            <button
              class="btn btn-benchmark"
              :disabled="!status.isPaused || !benchmark?.scannedPdfReady || benchmark?.phase === 'RUNNING' || benchmark?.phase === 'PREPARING_SCANNED'"
              @click="startBenchmark('scanned')"
              :title="benchmark?.scannedPdfReady ? '' : 'Préparez d\'abord le PDF scanné'"
            >
              ▶ PDF scanné
            </button>

            <button
              class="btn btn-secondary"
              :disabled="benchmark?.scannedPdfReady || benchmark?.phase === 'RUNNING' || benchmark?.phase === 'PREPARING_SCANNED'"
              @click="prepareScanned"
            >
              <span v-if="benchmark?.phase === 'PREPARING_SCANNED'">Génération en cours…</span>
              <span v-else-if="benchmark?.scannedPdfReady">✓ PDF scanné prêt</span>
              <span v-else>Préparer le PDF scanné</span>
            </button>

            <button
              v-if="benchmark?.phase === 'RUNNING' || benchmark?.phase === 'PREPARING_SCANNED'"
              class="btn btn-stop"
              @click="stopBenchmark"
            >
              ⏹ Arrêter
            </button>
          </div>

          <!-- Elapsed time — always visible while benchmark runs -->
          <div v-if="benchmark?.phase === 'RUNNING'" class="bench-elapsed">
            Durée : {{ fmtDuration(Math.floor(benchmarkElapsedMs / 1000)) }}
          </div>

          <!-- Live progress during extraction phase -->
          <div v-if="benchmark?.phase === 'RUNNING' && benchmark.pagesTotal > 0" class="bench-live">
            <div class="bench-live-bar-track">
              <div class="bench-live-bar-fill"
                   :style="{ width: Math.min(100, benchmark.pagesDone / benchmark.pagesTotal * 100) + '%' }">
              </div>
            </div>
            <div class="bench-live-info">
              <span>{{ benchmark.pagesDone }} / {{ benchmark.pagesTotal }} pages extraites</span>
              <span v-if="benchmarkLiveRate"> · {{ benchmarkLiveRate.toFixed(1) }} pages/min</span>
              <span v-if="benchmarkEtaMin !== null">
                · ETA {{ benchmarkEtaMin < 1 ? '< 1 min' : Math.round(benchmarkEtaMin) + ' min' }}
              </span>
            </div>
          </div>

          <div v-if="!status.isPaused" class="bench-hint">
            Mettez l'indexeur en pause pour activer le banc d'essai.
          </div>

          <!-- Completion banner (shown until dismissed) -->
          <div v-if="benchmark?.phase === 'DONE' && benchmark.lastResult" class="bench-done">
            <div class="bench-done-header">
              <span class="bench-done-icon">✓</span>
              <span class="bench-done-title">
                Banc d'essai terminé —
                PDF {{ benchmark.lastResult.type === 'TEXT' ? 'textuel' : 'scanné' }}
              </span>
              <button class="bench-done-close" @click="resetBenchmark" title="Fermer">✕</button>
            </div>
            <div class="bench-done-throughput">
              {{ benchmark.lastResult.pagesPerMinute.toFixed(1) }}
              <span class="bench-done-unit">pages/min</span>
            </div>
            <table class="bench-table bench-done-table">
              <tbody>
                <tr>
                  <td>Pages traitées</td>
                  <td class="bench-val">{{ benchmark.lastResult.pages }}</td>
                </tr>
                <tr>
                  <td>Extraction ({{ benchmark.lastResult.type === 'TEXT' ? 'PDFBox' : 'PDFBox + OCR' }})</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.extractionMs) }}</td>
                </tr>
                <tr>
                  <td>Détection de langue</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.languageMs) }}</td>
                </tr>
                <tr class="bench-sub">
                  <td>NER (entités nommées) <span class="bench-parallel-tag">↳ parallèle</span></td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.nerMs) }}</td>
                </tr>
                <tr class="bench-sub">
                  <td>Vectorisation (embedding) <span class="bench-parallel-tag">↳ parallèle</span></td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.embeddingMs) }}</td>
                </tr>
                <tr class="bench-total">
                  <td>Total</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.totalMs) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Previous result (shown when idle, after dismissal or after stop) -->
          <div v-else-if="benchmark?.lastResult" class="bench-result">
            <div class="bench-result-title">
              Dernier résultat — PDF {{ benchmark.lastResult.type === 'TEXT' ? 'textuel' : 'scanné' }}
              <span class="bench-result-date">{{ fmt(benchmark.lastResult.completedAt) }}</span>
            </div>
            <table class="bench-table">
              <tbody>
                <tr>
                  <td>Extraction</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.extractionMs) }}</td>
                </tr>
                <tr>
                  <td>Détection de langue</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.languageMs) }}</td>
                </tr>
                <tr class="bench-sub">
                  <td>NER (entités nommées) <span class="bench-parallel-tag">↳ parallèle</span></td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.nerMs) }}</td>
                </tr>
                <tr class="bench-sub">
                  <td>Vectorisation (embedding) <span class="bench-parallel-tag">↳ parallèle</span></td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.embeddingMs) }}</td>
                </tr>
                <tr class="bench-total">
                  <td>Total ({{ benchmark.lastResult.pages }} pages)</td>
                  <td class="bench-val">{{ fmtMs(benchmark.lastResult.totalMs) }}</td>
                </tr>
                <tr class="bench-throughput">
                  <td>Débit</td>
                  <td class="bench-val">{{ benchmark.lastResult.pagesPerMinute.toFixed(1) }} pages/min</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Stopped before any result -->
          <div v-else-if="benchmark?.wasStopped" class="bench-stopped">
            Banc d'essai arrêté avant la fin — aucun résultat disponible.
            <button class="btn-small" @click="resetBenchmark">Effacer</button>
          </div>
        </div>
      </section>

      <!-- ── Sherlock aujourd'hui ───────────────────────────────────── -->
      <section class="card" v-if="sherlockUsage?.enabled">
        <div class="card-header">
          <h2>Sherlock aujourd'hui</h2>
        </div>
        <div class="card-body">
          <p v-if="Object.values(sherlockUsage.limits).every(l => l == null)" class="usage-limits">
            Questions par jour : sans limite.
            <span class="usage-note">Réglez llm.quota pour en fixer une.</span>
          </p>
          <p v-else class="usage-limits">
            Questions par jour :
            {{ Object.entries(TIER_LABELS)
                 .map(([tier, label]) => label + ' ' + (sherlockUsage!.limits[tier as keyof typeof TIER_LABELS] ?? 'illimité'))
                 .join(' · ') }}
            <span class="usage-note">— remis à zéro à minuit (llm.quota)</span>
          </p>
          <p v-if="!sherlockUsage.users.length" class="usage-note">Aucune question aujourd'hui.</p>
          <table v-else class="errors-table">
            <thead>
              <tr><th>Utilisateur</th><th>Quota</th><th>Questions</th></tr>
            </thead>
            <tbody>
              <tr v-for="u in sherlockUsage.users" :key="u.username">
                <td>{{ u.username }}</td>
                <td>{{ TIER_LABELS[u.tier] }}</td>
                <td :class="{ 'quota-exhausted': u.limit != null && u.used >= u.limit }">
                  {{ u.used }}{{ u.limit != null ? ' / ' + u.limit : '' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card" v-if="appVersion">
        <div class="card-header">
          <h2>Version</h2>
        </div>
        <div class="card-body">
          <table class="license-table">
            <tbody>
              <tr>
                <th>IntraCherche</th>
                <td>v{{ appVersion }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- ── Erreurs d'indexation ────────────────────────────────── -->
      <section class="card">
        <div class="card-header">
          <h2>Problèmes d'indexation</h2>
          <span v-if="errors.length" class="error-count">{{ errors.length }}</span>
        </div>

        <div class="card-body">
          <div v-if="errors.length === 0" class="no-errors">
            Aucun problème d'indexation enregistré.
          </div>

          <template v-else>
            <div class="errors-toolbar">
              <input
                v-model="errorSearch"
                class="errors-search"
                type="search"
                placeholder="Filtrer par fichier, message, index…"
              />
              <span class="errors-count-note">
                <template v-if="errorSearch.trim()">
                  {{ filteredErrors.length }} / {{ errors.length }} résultat{{ filteredErrors.length > 1 ? 's' : '' }}
                </template>
                <template v-else>
                  {{ errors.length }} entrée{{ errors.length > 1 ? 's' : '' }}
                </template>
              </span>
            </div>

            <div v-if="filteredErrors.length === 0" class="no-errors">
              Aucun résultat pour « {{ errorSearch }} ».
            </div>

            <div v-else class="errors-scroll-wrap">
              <table class="errors-table">
                <thead>
                  <tr>
                    <th>Horodatage</th>
                    <th>Index</th>
                    <th>Fichier / source</th>
                    <th>Message</th>
                    <th>Statut</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(err, i) in filteredErrors" :key="i">
                    <td class="col-ts">{{ fmt(err.timestamp) }}</td>
                    <td class="col-index">{{ err.coreName ?? '—' }}</td>
                    <td class="col-path" :title="err.path">{{ shortPath(err.path) }}</td>
                    <td class="col-msg">{{ err.message }}</td>
                    <td>
                      <span v-if="err.kind === 'skipped'" class="badge badge-skip">Ignoré</span>
                      <span v-else-if="err.kind === 'source'" class="badge badge-down">Source</span>
                      <span v-else-if="err.kind === 'limited'" class="badge badge-limit" title="Indexé en partie (taille, volume de texte ou mémoire insuffisante) : le message indique le réglage à modifier">Limité</span>
                      <span v-else class="badge badge-down">Erreur</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </section>

      </div>

    </template>
  </div>
</template>

<style scoped>
.admin-page {
  max-width: 1600px;
  margin: 40px auto;
  padding: 0 24px 60px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

/* ── Page header ──────────────────────────────────────────────────────────── */
.admin-header {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 24px;
}
.admin-header h1 {
  font-size: 24px;
  color: #202124;
  margin: 0;
}
.refresh-time {
  font-size: 12px;
  color: #9aa0a6;
}

/* ── Cards ────────────────────────────────────────────────────────────────── */
.card {
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 6px rgba(60,64,67,.10);
  margin-bottom: 24px;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  background: #f8f9fa;
  border-bottom: 1px solid #e0e0e0;
}
.card-header h2 {
  font-size: 15px;
  font-weight: 600;
  color: #202124;
  margin: 0;
}
.card-body {
  padding: 20px;
}
.card-body.loading {
  color: #9aa0a6;
  font-style: italic;
  font-size: 14px;
}

/* ── Badges ───────────────────────────────────────────────────────────────── */
.badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 10px;
  text-transform: uppercase;
  letter-spacing: .4px;
}
.badge-running     { background: #e6f4ea; color: #137333; }
.badge-pausing     { background: #fff3e0; color: #e65100; }
.badge-paused      { background: #fef7e0; color: #b06000; }
.badge-idle        { background: #f1f3f4; color: #5f6368; }
.badge-up          { background: #e6f4ea; color: #137333; }
.badge-down        { background: #fce8e6; color: #c5221f; }
.badge-skip        { background: #f1f3f4; color: #5f6368; }
.badge-limit       { background: #fef7e0; color: #b06000; }
.badge-restarting  { background: #fef7e0; color: #b06000; }

/* ── Indexer progress ─────────────────────────────────────────────────────── */
.progress-section {
  margin-bottom: 20px;
}
.progress-track {
  height: 8px;
  background: #e8eaed;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 6px;
}
.progress-fill {
  height: 100%;
  background: #1a73e8;
  border-radius: 4px;
  transition: width 0.4s ease;
}
.progress-label {
  font-size: 13px;
  color: #5f6368;
}
.progress-pct {
  color: #9aa0a6;
  margin-left: 4px;
}
.active-files {
  margin-top: 8px;
  font-size: 12px;
}
.active-files-header {
  font-weight: 600;
  color: #5f6368;
  margin-bottom: 4px;
}
.active-file-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  color: #5f6368;
  padding: 1px 0;
}
.active-file-stalled {
  color: #b06000;
}
.active-file-elapsed {
  white-space: nowrap;
  min-width: 42px;
  text-align: right;
  color: inherit;
}
.active-file-path {
  font-family: monospace;
  word-break: break-all;
  color: inherit;
}
.uptodate-note {
  font-size: 13px;
  color: #137333;
  margin-bottom: 16px;
}

/* A source that could not be opened. Red like .pause-error: nothing of that share
   was read, which is a failure of the cycle, not a file that needs attention. */
.source-error {
  margin-bottom: 16px;
  font-size: 13px;
  color: #c5221f;
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 6px;
  padding: 10px 12px;
}
.source-error-title { font-weight: 500; }
.source-error-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-top: 6px;
}
.source-error-path  { font-family: monospace; font-size: 12px; word-break: break-all; }
.source-error-index { font-size: 12px; color: #5f6368; white-space: nowrap; }
.source-error-msg   { font-size: 12px; color: #5f6368; }

/* ── Stats grid ───────────────────────────────────────────────────────────── */
.stats-grid {
  display: flex;
  gap: 24px;
  margin: 0 0 20px;
}
.stat { margin: 0; flex: 1 1 0; min-width: 0; }
.stat dt {
  font-size: 11px;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: .4px;
  margin-bottom: 2px;
}
.stat dd {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #202124;
}

/* ── Performance section ─────────────────────────────────────────────────── */
.perf-section {
  margin: 0 0 20px;
  padding: 12px 16px;
  background: #f8f9fa;
  border-radius: 6px;
  border: 1px solid #e8eaed;
}
.perf-title {
  font-size: 11px;
  font-weight: 600;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: .5px;
  margin-bottom: 10px;
}
.perf-grid {
  display: flex;
  gap: 24px;
  margin: 0;
}
.perf-stat { margin: 0; flex: 1 1 0; min-width: 0; }
.perf-stat dt {
  font-size: 11px;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: .4px;
  margin-bottom: 2px;
}
.perf-stat dd {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #202124;
}

/* ── Per-index cycle summary ──────────────────────────────────────────────── */
.index-summary {
  margin-bottom: 20px;
}
.index-summary-title {
  font-size: 11px;
  font-weight: 600;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: .4px;
  margin-bottom: 8px;
}
.index-summary-meta {
  font-weight: 400;
  text-transform: none;
  letter-spacing: 0;
}
.summary-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.summary-table th {
  text-align: left;
  padding: 5px 10px;
  color: #5f6368;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  border-bottom: 1px solid #e0e0e0;
}
.summary-table td {
  padding: 7px 10px;
  border-bottom: 1px solid #f0f0f0;
}
.summary-table tbody tr:last-child td { border-bottom: none; }
.idx-name    { font-weight: 600; color: #202124; }
.summary-ok  { color: #137333; font-variant-numeric: tabular-nums; }
.summary-fail { color: #c5221f; font-weight: 700; font-variant-numeric: tabular-nums; }
.summary-skip { color: #9aa0a6; font-variant-numeric: tabular-nums; }
.summary-zero { color: #9aa0a6; }
.summary-ppm  { color: #5f6368; font-variant-numeric: tabular-nums; }
.col-group {
  text-align: center;
  background: #f8f9fa;
  border-left: 1px solid #e0e0e0;
  font-size: 10px;
  letter-spacing: .3px;
}
.col-sub {
  font-size: 10px;
  color: #9aa0a6;
  border-left: 1px solid #e0e0e0;
  background: #fafafa;
}
.col-sub:last-child { border-left: 1px solid #ebebeb; }

/* ── Pause / resume ───────────────────────────────────────────────────────── */
.action-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.action-btn {
  border: none;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}
.action-btn:disabled { opacity: .6; cursor: default; }
.btn-pause  { background: #fef7e0; color: #b06000; }
.btn-pause:hover:not(:disabled) { background: #fde293; }
.btn-resume { background: #e6f4ea; color: #137333; }
.btn-resume:hover:not(:disabled) { background: #ceead6; }
.btn-pause-confirm { background: #fce8e6; color: #c5221f; }
.btn-pause-confirm:hover { background: #f5c6c6; }
.btn-cancel { background: #f1f3f4; color: #5f6368; }
.btn-cancel:hover { background: #e8eaed; }
.action-confirm-msg { font-size: 13px; color: #5f6368; }
.action-note { font-size: 12px; color: #9aa0a6; font-style: italic; }
.pause-error {
  margin-top: 8px;
  font-size: 13px;
  color: #c5221f;
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 6px;
  padding: 6px 12px;
}

/* ── Ré-indexage complet ──────────────────────────────────────────────────── */
.reindex-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #ebebeb;
}
.btn-reindex { background: #f1f3f4; color: #5f6368; }
.btn-reindex:hover:not(:disabled) { background: #e8eaed; }
.reindex-warning {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.5;
  color: #5f6368;
  max-width: 62ch;
}
.reindex-label {
  display: block;
  margin: 12px 0 6px;
  font-size: 13px;
  color: #3c4043;
}
.reindex-input {
  width: 100%;
  max-width: 320px;
  margin-bottom: 12px;
  padding: 7px 10px;
  font-size: 13px;
  border: 1px solid #dadce0;
  border-radius: 6px;
}
.reindex-input:focus { outline: none; border-color: #1a73e8; }
.reindex-ok {
  margin-top: 8px;
  font-size: 13px;
  color: #137333;
  background: #e6f4ea;
  border: 1px solid #ceead6;
  border-radius: 6px;
  padding: 6px 12px;
}

/* ── Service health table ─────────────────────────────────────────────────── */
.svc-device {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 0.75rem;
  background: #e8eaed;
  color: #3c4043;
}
.health-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  margin-bottom: 20px;
}
.health-table th {
  text-align: left;
  padding: 8px 12px;
  color: #5f6368;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  border-bottom: 2px solid #e0e0e0;
}
.health-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.health-table tbody tr:last-child td { border-bottom: none; }

.svc-name    { font-weight: 600; color: #202124; }
.svc-url     { font-size: 12px; color: #70757a; font-family: monospace; }
.svc-secondary { font-size: 11px; color: #9aa0a6; margin-top: 2px; }
.svc-actions { width: 36px; text-align: center; }

.restart-btn {
  background: none;
  border: 1px solid #dadce0;
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 14px;
  cursor: pointer;
  color: #5f6368;
  line-height: 1;
  transition: background 0.15s, color 0.15s;
}
.restart-btn:hover:not(:disabled) {
  background: #f1f3f4;
  color: #202124;
}
.restart-btn:disabled {
  opacity: .4;
  cursor: default;
}

/* ── Solr cores ───────────────────────────────────────────────────────────── */
.solr-cores {
  border-top: 1px solid #e8eaed;
  padding-top: 12px;
  margin-top: 4px;
}
.solr-error {
  font-size: 12px;
  color: #c5221f;
  font-family: monospace;
  margin-top: 6px;
}

.core-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.core-table th {
  text-align: left;
  padding: 6px 10px;
  color: #5f6368;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  border-bottom: 1px solid #e0e0e0;
}
.core-table td {
  padding: 8px 10px;
  border-bottom: 1px solid #f0f0f0;
  color: #202124;
}
.core-table tbody tr:last-child td { border-bottom: none; }
.col-num { text-align: right; font-variant-numeric: tabular-nums; }
/* An index whose Solr core does not exist yet: its 0 is a real zero, not a gap. */
.core-never { color: #5f6368; font-size: 11px; white-space: nowrap; margin-left: 6px; }

/* ── Errors table ─────────────────────────────────────────────────────────── */
.error-count {
  background: #fce8e6;
  color: #c5221f;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 10px;
}
.errors-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.errors-search {
  flex: 1;
  max-width: 400px;
  padding: 6px 10px;
  font-size: 13px;
  border: 1px solid #dadce0;
  border-radius: 6px;
  outline: none;
  color: #202124;
}
.errors-search:focus {
  border-color: #1a73e8;
  box-shadow: 0 0 0 2px rgba(26,115,232,.15);
}
.errors-count-note {
  font-size: 12px;
  color: #9aa0a6;
  white-space: nowrap;
}
.no-errors {
  color: #70757a;
  font-style: italic;
  font-size: 14px;
}
.errors-scroll-wrap {
  max-height: 420px;
  overflow-y: auto;
  border: 1px solid #e8eaed;
  border-radius: 4px;
}
.usage-limits { margin: 0 0 10px; font-size: 13px; }
.usage-note { color: #9aa0a6; font-size: 12px; }
.quota-exhausted { color: #c5221f; font-weight: 600; }

.errors-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.errors-table th {
  text-align: left;
  padding: 8px 10px;
  color: #5f6368;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .4px;
  border-bottom: 2px solid #e0e0e0;
}
.errors-table td {
  padding: 8px 10px;
  border-bottom: 1px solid #f0f0f0;
  vertical-align: top;
}
.errors-table tbody tr:last-child td { border-bottom: none; }
.errors-table tbody tr:nth-child(even) td { background: #fafafa; }

.col-ts    { white-space: nowrap; color: #70757a; }
.col-index { font-size: 12px; color: #5f6368; white-space: nowrap; }
.col-path  { font-family: monospace; font-size: 12px; color: #5f6368; max-width: 240px; word-break: break-all; }
.col-msg   { color: #202124; max-width: 280px; }

/* ── Forbidden / error states ─────────────────────────────────────────────── */
.forbidden-box {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 12px;
  padding: 24px;
  color: #c5221f;
}
.forbidden-icon { font-size: 32px; line-height: 1; }
.forbidden-box strong { font-size: 16px; display: block; margin-bottom: 6px; }
.forbidden-box p { margin: 0; font-size: 14px; line-height: 1.6; }
.forbidden-box code {
  background: rgba(0,0,0,.08);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 13px;
}

.outage-banner {
  margin-bottom: 16px;
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 8px;
  padding: 12px 16px;
  color: #c5221f;
  font-size: 14px;
}
.outage-banner-title { font-weight: 500; }
.outage-banner-note  { margin-top: 4px; font-size: 13px; color: #5f6368; }

.error-banner {
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 8px;
  padding: 12px 16px;
  color: #c5221f;
  font-size: 14px;
}

/* ── Benchmark ────────────────────────────────────────────────────────────── */
.bench-note {
  font-size: 13px;
  color: #5f6368;
  margin: 0 0 16px;
  line-height: 1.5;
}
.bench-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.btn-benchmark {
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background .15s;
}
.btn-benchmark:hover:not(:disabled) { background: #1558b0; }
.btn-benchmark:disabled { opacity: .45; cursor: not-allowed; }
.btn-secondary {
  background: #fff;
  color: #1a73e8;
  border: 1px solid #dadce0;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}
.btn-secondary:hover:not(:disabled) { background: #f1f3f4; }
.btn-secondary:disabled { opacity: .45; cursor: not-allowed; }
.btn-stop {
  background: #fff;
  color: #c5221f;
  border: 1px solid #f5c6c6;
  border-radius: 6px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}
.btn-stop:hover { background: #fce8e6; }
.btn-small {
  margin-left: 12px;
  padding: 3px 10px;
  font-size: 12px;
  border: 1px solid #dadce0;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
.bench-hint {
  font-size: 12px;
  color: #9aa0a6;
  margin-bottom: 8px;
}
.bench-error {
  background: #fce8e6;
  border: 1px solid #f5c6c6;
  border-radius: 6px;
  padding: 10px 14px;
  color: #c5221f;
  font-size: 13px;
  margin-bottom: 12px;
}
/* completion banner */
.bench-done {
  margin-top: 20px;
  border: 2px solid #34a853;
  border-radius: 10px;
  padding: 20px 24px;
  background: #f0faf4;
}
.bench-done-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.bench-done-icon {
  font-size: 20px;
  color: #34a853;
  font-weight: 700;
  flex-shrink: 0;
}
.bench-done-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e7e34;
  flex: 1;
}
.bench-done-close {
  background: none;
  border: none;
  cursor: pointer;
  color: #5f6368;
  font-size: 16px;
  padding: 2px 6px;
  border-radius: 4px;
  line-height: 1;
  flex-shrink: 0;
}
.bench-done-close:hover { background: #e0e0e0; }
.bench-done-throughput {
  font-size: 42px;
  font-weight: 700;
  color: #1e7e34;
  line-height: 1;
  margin-bottom: 16px;
}
.bench-done-unit {
  font-size: 18px;
  font-weight: 500;
  color: #34a853;
}
.bench-done-table td { color: #2d6a4f; }
.bench-done-table .bench-val { color: #1e7e34; }
.bench-done-table .bench-total td { font-weight: 600; }

.bench-result {
  margin-top: 20px;
  border-top: 1px solid #e0e0e0;
  padding-top: 16px;
}
.bench-result-title {
  font-size: 13px;
  font-weight: 600;
  color: #202124;
  margin-bottom: 10px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.bench-result-date {
  font-size: 12px;
  font-weight: 400;
  color: #9aa0a6;
}
.bench-table {
  border-collapse: collapse;
  font-size: 14px;
  min-width: 320px;
}
.bench-table td {
  padding: 6px 12px 6px 0;
  border-bottom: 1px solid #f0f0f0;
  color: #5f6368;
}
.bench-table tr:last-child td { border-bottom: none; }
.bench-val {
  font-family: monospace;
  font-weight: 600;
  color: #202124;
  padding-left: 24px !important;
}
.bench-total td  { color: #202124; font-weight: 600; }
.bench-throughput td { color: #1a73e8; font-weight: 700; }
.bench-sub td { color: #80868b; font-size: 13px; }
.bench-sub .bench-val { color: #5f6368; }
.bench-parallel-tag {
  font-size: 10px;
  color: #9aa0a6;
  font-weight: 400;
  margin-left: 4px;
  white-space: nowrap;
}
.bench-stopped {
  margin-top: 16px;
  font-size: 13px;
  color: #80868b;
}
.bench-elapsed {
  font-size: 13px;
  font-weight: 600;
  color: #1a73e8;
  margin: 8px 0 4px;
  font-variant-numeric: tabular-nums;
}
.bench-live {
  margin: 4px 0 12px;
}
.bench-live-bar-track {
  height: 6px;
  background: #e8eaed;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 6px;
}
.bench-live-bar-fill {
  height: 100%;
  background: #1a73e8;
  border-radius: 3px;
  transition: width 0.8s ease;
}
.bench-live-info {
  font-size: 13px;
  color: #5f6368;
}

/* ── CPU allocation bar ───────────────────────────────────────────────────── */
.alloc-section {
  border-top: 1px solid #e8eaed;
  padding-top: 16px;
  margin-top: 4px;
  margin-bottom: 20px;
}
.alloc-title {
  font-size: 11px;
  font-weight: 600;
  color: #9aa0a6;
  text-transform: uppercase;
  letter-spacing: .4px;
  margin-bottom: 10px;
}
.alloc-bar {
  display: flex;
  height: 20px;
  border-radius: 6px;
  overflow: hidden;
  gap: 2px;
  margin-bottom: 12px;
}
.alloc-seg {
  border-radius: 3px;
  transition: opacity .15s;
  cursor: default;
}
.alloc-seg:hover { opacity: .8; }
.alloc-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
}
.alloc-ram {
  margin-top: 10px;
  font-size: 12px;
  color: #5f6368;
}
.alloc-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.alloc-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.alloc-leg-name {
  font-weight: 600;
  color: #202124;
}
.alloc-leg-sub {
  color: #9aa0a6;
}

.license-table { border-collapse: collapse; width: 100%; }
.license-table th {
  text-align: left;
  padding: 6px 12px 6px 0;
  color: #5f6368;
  font-weight: 500;
  width: 160px;
}
.license-table td { padding: 6px 0; }

/* ── Tabs ─────────────────────────────────────────────────────────────────── */
.tab-bar { display: flex; gap: 4px; border-bottom: 1px solid #e0e0e0; margin-bottom: 24px; }
.tab-bar button {
  background: none; border: none; border-bottom: 3px solid transparent;
  padding: 10px 18px; font-size: 15px; color: #5f6368; cursor: pointer;
}
.tab-bar button.active { color: #1a73e8; border-bottom-color: #1a73e8; font-weight: 600; }
.tab-badge {
  display: inline-block; margin-left: 8px; min-width: 18px; padding: 0 6px; border-radius: 9px;
  background: #c5221f; color: #fff; font-size: 12px; line-height: 18px; text-align: center;
}
</style>
