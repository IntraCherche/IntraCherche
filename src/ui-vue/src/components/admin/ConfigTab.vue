<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import apiClient from '../../plugins/axios'
import IndexesEditor from './IndexesEditor.vue'
import SettingsEditor from './SettingsEditor.vue'
import MemoryEditor from './MemoryEditor.vue'

interface ConfigFinding { severity: 'error' | 'warning' | 'info'; scope: string; where: string; message: string }
interface ConfigSetting { key: string; value: string; defaultValue: string; modified: boolean; secret: boolean }
interface ConfigIndex { coreName: string; status: string; visibleTo: string; directories: number; ocr: boolean; loaded: boolean }
interface ConfigReport {
  checkedAt: string
  applicationFile: string | null
  indexesFile: string | null
  findings: ConfigFinding[]
  settings: ConfigSetting[]
  indexes: ConfigIndex[]
}

const emit = defineEmits<{ (e: 'problems', count: number): void }>()

const sub = ref<'diagnostic' | 'indexes' | 'common' | 'directory' | 'memory' | 'advanced'>('diagnostic')
const restartNeeded = ref(false)
const restartSupported = ref(false)
const savedBackup = ref<string | null>(null)
const restartConfirming = ref(false)
const restarting = ref(false)
const restartError = ref<string | null>(null)

function onSaved(info: { backup: string | null; restartSupported: boolean }) {
  restartNeeded.value = true
  restartSupported.value = info.restartSupported
  savedBackup.value = info.backup
  refresh()
}

async function restart() {
  restartError.value = null
  try {
    await apiClient.post('/admin/restart')
    restarting.value = true
    restartConfirming.value = false
    const wait = setInterval(async () => {
      try {
        await apiClient.get('/admin/config-check')
        clearInterval(wait)
        window.location.reload()
      } catch { /* still down */ }
    }, 4000)
  } catch (e: any) {
    restartError.value = e?.response?.data?.error ?? 'Le redémarrage a échoué.'
  }
}
const report = ref<ConfigReport | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const showAllSettings = ref(false)

const problems = computed(() => (report.value?.findings ?? []).filter(f => f.severity !== 'info').length)
const visibleSettings = computed(() =>
  (report.value?.settings ?? []).filter(s => showAllSettings.value || s.modified))

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const { data } = await apiClient.get<ConfigReport>('/admin/config-check')
    report.value = data
    emit('problems', problems.value)
  } catch {
    error.value = 'Impossible de lire la configuration.'
  } finally {
    loading.value = false
  }
}
defineExpose({ refresh })
onMounted(refresh)

const severityLabel: Record<string, string> = { error: 'Erreur', warning: 'Attention', info: 'Info' }
</script>

<template>
  <section class="card">
    <div class="card-header">
      <h2>Configuration</h2>
    </div>
    <nav class="sub-bar" role="tablist">
      <button role="tab" :class="{ active: sub === 'diagnostic' }" @click="sub = 'diagnostic'">
        Diagnostic<span v-if="problems" class="sub-badge">{{ problems }}</span>
      </button>
      <button role="tab" :class="{ active: sub === 'indexes' }" @click="sub = 'indexes'">Index</button>
      <button role="tab" :class="{ active: sub === 'common' }" @click="sub = 'common'">Réglages courants</button>
      <button role="tab" :class="{ active: sub === 'directory' }" @click="sub = 'directory'">Annuaire</button>
      <button role="tab" :class="{ active: sub === 'memory' }" @click="sub = 'memory'">Mémoire</button>
      <button role="tab" :class="{ active: sub === 'advanced' }" @click="sub = 'advanced'">Réglages avancés</button>
    </nav>

    <div class="card-body">
      <div v-if="restartNeeded" class="restart-banner">
        <strong>✓ Enregistré.</strong> Les modifications seront prises en compte au prochain démarrage de l'application.
        <span v-if="savedBackup" class="restart-help">Ancienne version conservée : <code>{{ savedBackup }}</code></span>
        <div v-if="restarting">Redémarrage en cours… la page se rechargera d'elle-même.</div>
        <div v-else-if="restartSupported">
          <template v-if="restartConfirming">
            L'application sera indisponible environ une minute ; l'indexation reprendra seule.
            <button class="rb-btn rb-primary" @click="restart">Redémarrer maintenant</button>
            <button class="rb-btn" @click="restartConfirming = false">Annuler</button>
          </template>
          <button v-else class="rb-btn" @click="restartConfirming = true">Redémarrer l'application…</button>
          <div v-if="restartError" class="restart-bad">{{ restartError }}</div>
        </div>
        <div v-else class="restart-help">Redémarrez le service (<code>sudo systemctl restart intracherche</code>).</div>
      </div>

      <IndexesEditor v-show="sub === 'indexes'" @saved="onSaved" />
      <SettingsEditor v-show="sub === 'common'" tier="common" @saved="onSaved" />
      <SettingsEditor v-show="sub === 'directory'" tier="directory" @saved="onSaved" />
      <MemoryEditor v-show="sub === 'memory'" @saved="onSaved" />
      <SettingsEditor v-show="sub === 'advanced'" tier="advanced" @saved="onSaved" />

      <div v-show="sub === 'diagnostic'">
        <div v-if="error" class="error-banner">{{ error }}</div>
        <div v-else-if="!report" class="loading">Chargement…</div>
        <template v-else>
          <p class="cfg-note">
            Les fichiers <code>{{ report.applicationFile ?? 'application.yaml' }}</code> et
            <code>{{ report.indexesFile ?? 'indexes.yaml' }}</code> sont relus depuis le disque pour repérer ce qui
            serait ignoré ou refusé.
            <button class="btn-link" :disabled="loading" @click="refresh">Vérifier à nouveau</button>
          </p>

          <h3 class="cfg-title">Diagnostic</h3>
          <div v-if="!report.findings.length" class="cfg-ok">✓ Aucun problème détecté.</div>
          <table v-else class="errors-table">
            <thead><tr><th>Niveau</th><th>Fichier</th><th>Où</th><th>Constat</th></tr></thead>
            <tbody>
              <tr v-for="(f, i) in report.findings" :key="i">
                <td><span :class="['sev', 'sev-' + f.severity]">{{ severityLabel[f.severity] }}</span></td>
                <td>{{ f.scope }}</td>
                <td><code>{{ f.where }}</code></td>
                <td>{{ f.message }}</td>
              </tr>
            </tbody>
          </table>

          <h3 class="cfg-title">Index lus dans indexes.yaml</h3>
          <table v-if="report.indexes.length" class="errors-table">
            <thead><tr><th>Index</th><th>Statut</th><th>Visible par</th><th>Dossiers</th><th>OCR</th><th>Pris en compte</th></tr></thead>
            <tbody>
              <tr v-for="ix in report.indexes" :key="ix.coreName">
                <td><strong>{{ ix.coreName }}</strong></td>
                <td>{{ ix.status }}</td>
                <td>{{ ix.visibleTo || '—' }}</td>
                <td>{{ ix.directories }}</td>
                <td>{{ ix.ocr ? 'oui' : 'non' }}</td>
                <td>{{ ix.loaded ? 'oui' : 'non — redémarrage nécessaire' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="cfg-note">Aucun index.</div>

          <h3 class="cfg-title">Réglages en vigueur</h3>
          <label class="cfg-check">
            <input type="checkbox" v-model="showAllSettings"> Afficher aussi les réglages restés à leur valeur par défaut
          </label>
          <table class="errors-table">
            <thead><tr><th>Réglage</th><th>Valeur</th><th>Par défaut</th></tr></thead>
            <tbody>
              <tr v-for="s in visibleSettings" :key="s.key">
                <td><code>{{ s.key }}</code></td>
                <td :class="{ 'cfg-modified': s.modified }">{{ s.value === '' ? '—' : s.value }}</td>
                <td>{{ s.secret ? '' : (s.defaultValue === '' ? '—' : s.defaultValue) }}</td>
              </tr>
              <tr v-if="!visibleSettings.length"><td colspan="3">Aucun réglage modifié.</td></tr>
            </tbody>
          </table>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.card { border: 1px solid #e0e0e0; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 6px rgba(60,64,67,.10); margin-bottom: 24px; }
.card-header { padding: 14px 20px; border-bottom: 1px solid #e0e0e0; }
.card-header h2 { margin: 0; font-size: 17px; }
.card-body { padding: 16px 20px; }
.sub-bar { display: flex; gap: 4px; padding: 0 12px; border-bottom: 1px solid #e0e0e0; background: #fafafa; }
.sub-bar button { background: none; border: none; border-bottom: 3px solid transparent; padding: 10px 16px; font-size: 14px; color: #5f6368; cursor: pointer; }
.sub-bar button.active { color: #1a73e8; border-bottom-color: #1a73e8; font-weight: 600; }
.sub-badge { display: inline-block; margin-left: 8px; min-width: 18px; padding: 0 6px; border-radius: 9px; background: #c5221f; color: #fff; font-size: 12px; line-height: 18px; text-align: center; }
.cfg-title { font-size: 15px; margin: 24px 0 8px; color: #202124; }
.cfg-note { font-size: 13px; color: #5f6368; margin: 0 0 8px; }
.cfg-ok { color: #137333; font-weight: 600; }
.cfg-check { display: block; font-size: 13px; color: #5f6368; margin-bottom: 8px; }
.cfg-modified { font-weight: 600; }
.sev { font-weight: 700; font-size: 12px; text-transform: uppercase; }
.sev-error { color: #c5221f; } .sev-warning { color: #b06000; } .sev-info { color: #5f6368; }
.btn-link { background: none; border: none; color: #1a73e8; cursor: pointer; font-size: 13px; margin-left: 8px; }
.errors-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.errors-table th { text-align: left; padding: 6px 8px; color: #5f6368; border-bottom: 1px solid #e0e0e0; }
.errors-table td { padding: 6px 8px; border-bottom: 1px solid #f0f0f0; vertical-align: top; }
.error-banner { color: #c5221f; }
.restart-banner { background: #e6f4ea; border: 1px solid #a8dab5; color: #137333; border-radius: 8px; padding: 10px 14px; margin-bottom: 16px; font-size: 13px; }
.restart-help { display: block; font-size: 12px; color: #5f6368; }
.restart-bad { color: #c5221f; }
.rb-btn { border: 1px solid #c4c7c5; background: #fff; border-radius: 6px; padding: 7px 16px; font-size: 14px; cursor: pointer; margin: 6px 8px 0 0; }
.rb-primary { background: #1a73e8; color: #fff; border-color: #1a73e8; }
</style>
