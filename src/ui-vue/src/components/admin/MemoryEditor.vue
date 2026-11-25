<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import apiClient from '../../plugins/axios'

interface Config {
  totalRamMb: number; runningHeapMb: number; configuredHeapMb: number | null; jvmConfAvailable: boolean
  jvmConfPath: string | null; solrHeapMb: number; solrMode: 'BLOCK' | 'LINE' | 'READONLY' | 'NOFILE'
  solrFile: string | null; reservedRamGb: number; writable: boolean; notWritableReason: string | null
  recommendedHeapMb: number; recommendedSolrMb: number
}
interface Service { key: string; name: string; workers: number; ramMb: number; auto: boolean }
interface Budget {
  totalRamMb: number; heapMb: number; solrMb: number; osMb: number; llmMb: number; reservedMb: number
  budgetMb: number; neededMb: number; minimumMb: number; servicesKnown: boolean; services: Service[]
}
interface Finding { severity: 'error' | 'warning' | 'info'; scope: string; where: string; message: string }

const emit = defineEmits<{ (e: 'saved', info: { backup: string | null; restartSupported: boolean }): void }>()

const cfg = ref<Config | null>(null)
const budget = ref<Budget | null>(null)
const findings = ref<Finding[]>([])
const restartSupported = ref(false)
const loadError = ref<string | null>(null)
const busy = ref(false)

const heap = ref(0)
const solr = ref(0)
const reserved = ref(0)
const start = ref({ heap: 0, solr: 0, reserved: 0 })

const heapMax = computed(() => Math.floor(((cfg.value?.totalRamMb ?? 0) * 0.75) / 128) * 128)
const solrMax = computed(() => Math.floor(((cfg.value?.totalRamMb ?? 0) * 0.5) / 128) * 128)
const reservedMax = computed(() => Math.max(1, Math.floor((cfg.value?.totalRamMb ?? 1024) / 1024)))
const solrEditable = computed(() => cfg.value?.solrMode === 'BLOCK' || cfg.value?.solrMode === 'LINE')
const dirty = computed(() =>
  heap.value !== start.value.heap || solr.value !== start.value.solr || reserved.value !== start.value.reserved)
const hasErrors = computed(() => findings.value.some(f => f.severity === 'error'))

function gb(mb: number) { return (mb / 1024).toFixed(1).replace('.', ',') }

async function load() {
  loadError.value = null
  try {
    const { data } = await apiClient.get<{ config: Config; preview: { budget: Budget; findings: Finding[] }; restartSupported: boolean }>('/admin/memory')
    cfg.value = data.config
    budget.value = data.preview.budget
    restartSupported.value = data.restartSupported
    heap.value = data.config.configuredHeapMb ?? data.config.runningHeapMb
    solr.value = data.config.solrHeapMb
    reserved.value = data.config.reservedRamGb
    start.value = { heap: heap.value, solr: solr.value, reserved: reserved.value }
    findings.value = []
  } catch {
    loadError.value = 'Impossible de lire la mémoire.'
  }
}
onMounted(load)

let timer: ReturnType<typeof setTimeout> | null = null
function payload() {
  return {
    appHeapMb: heap.value !== start.value.heap ? heap.value : null,
    solrHeapMb: solr.value !== start.value.solr ? solr.value : null,
    reservedRamGb: reserved.value !== start.value.reserved ? reserved.value : null,
  }
}
watch([heap, solr, reserved], () => {
  if (!cfg.value) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    try {
      const { data } = await apiClient.post<{ budget: Budget; findings: Finding[] }>('/admin/memory/preview', {
        ...payload(),
        // the preview needs the working values even for unchanged fields
        appHeapMb: heap.value, solrHeapMb: solr.value, reservedRamGb: reserved.value,
      })
      budget.value = data.budget
      findings.value = data.findings
    } catch { /* keep the last preview */ }
  }, 250)
})

async function save() {
  busy.value = true
  try {
    const { data } = await apiClient.put<{ saved: boolean; findings: Finding[]; backups: string[] }>(
      '/admin/memory', payload(), { validateStatus: s => s === 200 || s === 422 })
    findings.value = data.findings
    if (data.saved) {
      const b = data.backups.join(' ; ') || null
      await load()
      emit('saved', { backup: b, restartSupported: restartSupported.value })
    }
  } catch {
    findings.value = [{ severity: 'error', scope: '', where: '', message: "L'enregistrement a échoué." }]
  } finally { busy.value = false }
}

interface Seg { label: string; mb: number; cls: string }
const segments = computed<Seg[]>(() => {
  const b = budget.value
  if (!b) return []
  const base = b.heapMb + b.solrMb + b.osMb + b.llmMb
  const out: Seg[] = [
    { label: 'Application (Java)', mb: b.heapMb, cls: 'm-java' },
    { label: 'Solr', mb: b.solrMb, cls: 'm-solr' },
    { label: 'Système', mb: b.osMb, cls: 'm-os' },
  ]
  if (b.llmMb > 0) out.push({ label: 'Modèle Sherlock', mb: b.llmMb, cls: 'm-llm' })
  if (b.reservedMb > base) out.push({ label: 'Réserve supplémentaire', mb: b.reservedMb - base, cls: 'm-extra' })
  if (b.servicesKnown) out.push({ label: 'Services Python', mb: b.neededMb, cls: 'm-py' })
  return out
})
const used = computed(() => segments.value.reduce((a, s) => a + s.mb, 0))
const freeMb = computed(() => (budget.value?.totalRamMb ?? 0) - used.value)
function pct(mb: number) { return Math.max(0, Math.min(100, (mb / (budget.value?.totalRamMb || 1)) * 100)) }
const severityLabel: Record<string, string> = { error: 'Erreur', warning: 'Attention', info: 'Info' }
</script>

<template>
  <div>
    <div v-if="loadError" class="me-bad">{{ loadError }}</div>
    <div v-else-if="!cfg || !budget" class="loading">Chargement…</div>
    <template v-else>
      <p class="me-note">
        Le serveur dispose de <strong>{{ gb(cfg.totalRamMb) }} Go</strong> de mémoire. Le tas Java et celui de Solr sont lus
        au démarrage ; un redémarrage de l'application applique les deux. Les curseurs s'arrêtent à ce que le serveur peut
        raisonnablement donner.
      </p>
      <div v-if="!cfg.writable" class="me-banner">{{ cfg.notWritableReason }}</div>

      <div class="me-bar" role="img" aria-label="Répartition de la mémoire">
        <div v-for="s in segments" :key="s.label" :class="['me-seg', s.cls]" :style="{ width: pct(s.mb) + '%' }"
             :title="s.label + ' : ' + gb(s.mb) + ' Go'"></div>
        <div v-if="freeMb > 0" class="me-seg m-free" :style="{ width: pct(freeMb) + '%' }" title="Libre"></div>
      </div>
      <ul class="me-legend">
        <li v-for="s in segments" :key="s.label"><span :class="['me-dot', s.cls]"></span>{{ s.label }} {{ gb(s.mb) }} Go</li>
        <li><span class="me-dot m-free"></span>{{ freeMb >= 0 ? 'Libre ' + gb(freeMb) + ' Go' : 'Dépassement ' + gb(-freeMb) + ' Go' }}</li>
      </ul>

      <section class="me-row">
        <div class="me-label">Mémoire de l'application (Java)</div>
        <div class="me-control">
          <div class="me-inline">
            <input type="range" class="me-slider" min="512" :max="heapMax" step="128" v-model.number="heap" />
            <input type="number" class="me-num" min="512" :max="heapMax" step="128" v-model.number="heap" /> Mo
          </div>
          <div class="me-help">
            Mémoire qu'IntraCherche peut utiliser pour lire et traiter les fichiers. À augmenter si de gros fichiers échouent faute de
            mémoire. Recommandé pour ce serveur : {{ cfg.recommendedHeapMb }} Mo. Plafond du curseur : les trois quarts de la mémoire du serveur.
            <button v-if="heap !== cfg.recommendedHeapMb" class="me-link" @click="heap = Math.min(cfg.recommendedHeapMb, heapMax)">Utiliser la valeur recommandée</button>
          </div>
          <div class="me-help">
            En cours d'utilisation : {{ cfg.runningHeapMb }} Mo
            <span v-if="cfg.configuredHeapMb === null">(valeur du service, calculée à l'installation)</span>.
          </div>
        </div>
      </section>

      <section class="me-row">
        <div class="me-label">Mémoire de Solr</div>
        <div class="me-control">
          <div class="me-inline">
            <input type="range" class="me-slider" min="512" :max="solrMax" step="128" v-model.number="solr" :disabled="!solrEditable" />
            <input type="number" class="me-num" min="512" :max="solrMax" step="128" v-model.number="solr" :disabled="!solrEditable" /> Mo
          </div>
          <div class="me-help">
            Mémoire du moteur de recherche. Solr garde son index dans le cache du système, pas dans son tas : plus n'est pas toujours mieux.
            Recommandé : {{ cfg.recommendedSolrMb }} Mo. Plafond du curseur : la moitié de la mémoire du serveur.
            <button v-if="solrEditable && solr !== cfg.recommendedSolrMb" class="me-link" @click="solr = Math.min(cfg.recommendedSolrMb, solrMax)">Utiliser la valeur recommandée</button>
          </div>
          <div v-if="cfg.solrMode === 'READONLY'" class="me-bad">
            <code>{{ cfg.solrFile }}</code> règle la mémoire par <code>SOLR_JAVA_MEM</code> : modifiez-la à la main, ou remplacez-la par <code>SOLR_HEAP</code>.
          </div>
          <div v-if="cfg.solrMode === 'NOFILE'" class="me-bad">Fichier solr.in.sh introuvable : la mémoire de Solr ne peut pas être modifiée ici.</div>
        </div>
      </section>

      <section class="me-row">
        <div class="me-label">Réserve minimale (Go)</div>
        <div class="me-control">
          <div class="me-inline">
            <input type="range" class="me-slider" min="1" :max="reservedMax" step="1" v-model.number="reserved" />
            <input type="number" class="me-num" min="1" :max="reservedMax" step="1" v-model.number="reserved" /> Go
          </div>
          <div class="me-help">
            Plancher de mémoire gardée pour Java, Solr, le système et Sherlock : ce qui reste est partagé entre les services d'analyse
            (OCR, lieux, embeddings, reranker). L'application prend le plus grand de ce plancher et de la somme des postes ci-dessus.
            À augmenter si le serveur fait aussi autre chose.
          </div>
        </div>
      </section>

      <div class="me-bar-actions">
        <button class="me-btn me-primary" :disabled="busy || !dirty || !cfg.writable || hasErrors" @click="save">Enregistrer</button>
        <button class="me-btn" :disabled="!dirty" @click="heap = start.heap; solr = start.solr; reserved = start.reserved">Annuler les modifications</button>
        <span v-if="dirty" class="me-help">Modifications non enregistrées.</span>
      </div>
      <table v-if="findings.length" class="me-findings">
        <tbody>
          <tr v-for="(f, k) in findings" :key="k">
            <td><span :class="['me-sev', 'me-sev-' + f.severity]">{{ severityLabel[f.severity] }}</span></td>
            <td><code v-if="f.where">{{ f.where }}</code></td>
            <td>{{ f.message }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.me-note { font-size: 13px; color: #5f6368; margin: 0 0 12px; }
.me-banner { background: #fef7e0; border: 1px solid #f9d77e; color: #7a4f01; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 13px; }
.me-bar { display: flex; height: 26px; border-radius: 6px; overflow: hidden; border: 1px solid #dadce0; background: #f1f3f4; }
.me-seg { height: 100%; transition: width .15s; }
.m-java { background: #1a73e8; } .m-solr { background: #e37400; } .m-os { background: #80868b; }
.m-llm { background: #9334e6; } .m-extra { background: #bdc1c6; } .m-py { background: #188038; } .m-free { background: #f1f3f4; }
.me-legend { display: flex; flex-wrap: wrap; gap: 6px 18px; list-style: none; padding: 0; margin: 8px 0 20px; font-size: 12px; color: #3c4043; }
.me-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; border: 1px solid #dadce0; }
.me-row { display: grid; grid-template-columns: minmax(200px, 300px) 1fr; gap: 16px; padding: 12px 6px; border-bottom: 1px solid #f3f3f3; }
.me-label { font-weight: 600; font-size: 13px; }
.me-control { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; font-size: 13px; }
.me-inline { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.me-slider { width: min(420px, 100%); }
.me-num { width: 100px; padding: 5px 8px; border: 1px solid #c4c7c5; border-radius: 6px; font-size: 13px; }
.me-help { font-size: 12px; color: #5f6368; max-width: 680px; }
.me-link { background: none; border: none; color: #1a73e8; cursor: pointer; font-size: 12px; padding: 0 0 0 6px; }
.me-bad { color: #c5221f; font-size: 12px; }
.me-bar-actions { display: flex; gap: 10px; align-items: center; padding: 14px 0; }
.me-btn { border: 1px solid #c4c7c5; background: #fff; border-radius: 6px; padding: 7px 16px; font-size: 14px; cursor: pointer; }
.me-btn:disabled { opacity: .5; cursor: default; }
.me-primary { background: #1a73e8; color: #fff; border-color: #1a73e8; }
.me-findings { width: 100%; border-collapse: collapse; font-size: 13px; }
.me-findings td { padding: 5px 8px; border-bottom: 1px solid #eee; vertical-align: top; }
.me-sev { font-weight: 700; font-size: 12px; text-transform: uppercase; }
.me-sev-error { color: #c5221f; } .me-sev-warning { color: #b06000; } .me-sev-info { color: #5f6368; }
@media (max-width: 800px) { .me-row { grid-template-columns: 1fr; } }
</style>
