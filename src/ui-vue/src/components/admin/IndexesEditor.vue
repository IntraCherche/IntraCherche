<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import apiClient from '../../plugins/axios'

interface Dir { smbServer: string; smbShare: string; smbUser: string; smbDomain: string | null; smbPassword: string | null; passwordSet: boolean }
interface Index {
  coreName: string; status: string; confirmDelete: boolean; ocr: boolean
  allowedExtensions: string[] | null; visibleToAll: boolean; visibleToGroups: string[]; indexedDirs: Dir[]
}
interface Finding { severity: 'error' | 'warning' | 'info'; scope: string; where: string; message: string }
interface Editable {
  file: string; writable: boolean; notWritableReason: string | null
  indexes: Index[]; defaultExtensions: string[]; ignoredKeys: string[]
}

const emit = defineEmits<{ (e: 'saved', info: { backup: string | null; restartSupported: boolean }): void }>()

const loaded = ref<Editable | null>(null)
const restartSupported = ref(false)
const indexes = ref<Index[]>([])
const existingNames = ref<Set<string>>(new Set())
const original = ref('')
const loadError = ref<string | null>(null)
const findings = ref<Finding[]>([])
const busy = ref(false)
const shareTests = ref<Record<string, { ok: boolean; message: string; busy?: boolean }>>({})

const dirty = computed(() => JSON.stringify(indexes.value) !== original.value)
const hasErrors = computed(() => findings.value.some(f => f.severity === 'error'))
const defaultExts = computed(() => (loaded.value?.defaultExtensions ?? []).join(', '))

async function load() {
  loadError.value = null
  try {
    const { data } = await apiClient.get<{ config: Editable; restartSupported: boolean }>('/admin/indexes-config')
    loaded.value = data.config
    restartSupported.value = data.restartSupported
    indexes.value = JSON.parse(JSON.stringify(data.config.indexes))
    existingNames.value = new Set(data.config.indexes.map(i => i.coreName))
    original.value = JSON.stringify(indexes.value)
    findings.value = []
  } catch {
    loadError.value = 'Impossible de lire les index.'
  }
}
onMounted(load)

function emptyDir(): Dir {
  return { smbServer: '', smbShare: '', smbUser: '', smbDomain: null, smbPassword: null, passwordSet: false }
}
function addIndex() {
  indexes.value.push({
    coreName: '', status: 'ACTIVE', confirmDelete: false, ocr: true, allowedExtensions: null,
    visibleToAll: true, visibleToGroups: [], indexedDirs: [emptyDir()],
  })
}
function removeNewIndex(i: number) { indexes.value.splice(i, 1) }
function isNew(ix: Index) { return !existingNames.value.has(ix.coreName) || ix.coreName === '' }

function groupsText(ix: Index) { return ix.visibleToGroups.join(', ') }
function setGroups(ix: Index, text: string) {
  ix.visibleToGroups = text.split(',').map(s => s.trim()).filter(Boolean)
}
function extsText(ix: Index) { return (ix.allowedExtensions ?? []).join(', ') }
function setExts(ix: Index, text: string) {
  ix.allowedExtensions = text.split(/[,\s]+/).map(s => s.trim().replace(/^\./, '')).filter(Boolean)
}
function setCustomExts(ix: Index, on: boolean) {
  ix.allowedExtensions = on ? [...(loaded.value?.defaultExtensions ?? [])] : null
}

function payload(): Index[] {
  return indexes.value.map(ix => ({
    ...ix,
    confirmDelete: ix.status === 'DELETE' && ix.confirmDelete,
    indexedDirs: ix.indexedDirs.map(d => ({ ...d, smbPassword: d.smbPassword ? d.smbPassword : null })),
  }))
}

async function check() {
  busy.value = true
  try {
    const { data } = await apiClient.post<Finding[]>('/admin/indexes-config/validate', payload())
    findings.value = data
    if (!data.length) findings.value = [{ severity: 'info', scope: '', where: '', message: 'Aucun problème détecté.' }]
  } catch {
    findings.value = [{ severity: 'error', scope: '', where: '', message: 'La vérification a échoué.' }]
  } finally { busy.value = false }
}

async function save() {
  busy.value = true
  try {
    const { data } = await apiClient.put<{ saved: boolean; findings: Finding[]; backup: string | null }>(
      '/admin/indexes-config', payload(), { validateStatus: s => s === 200 || s === 422 })
    findings.value = data.findings
    if (data.saved) {
      await load()
      findings.value = data.findings
      emit('saved', { backup: data.backup, restartSupported: restartSupported.value })
    }
  } catch {
    findings.value = [{ severity: 'error', scope: '', where: '', message: "L'enregistrement a échoué." }]
  } finally { busy.value = false }
}

async function testShare(ix: Index, d: Dir, key: string) {
  shareTests.value[key] = { ok: false, message: 'Test en cours…', busy: true }
  try {
    const { data } = await apiClient.post<{ ok: boolean; message: string }>('/admin/indexes-config/test-share',
      { coreName: ix.coreName, dir: d })
    shareTests.value[key] = data
  } catch {
    shareTests.value[key] = { ok: false, message: 'Le test a échoué.' }
  }
}

const severityLabel: Record<string, string> = { error: 'Erreur', warning: 'Attention', info: 'Info' }
const statusHelp: Record<string, string> = {
  ACTIVE: 'Les fichiers sont lus et indexés en continu.',
  PAUSED: "L'index n'est plus mis à jour mais reste consultable.",
  DELETE: "L'index et ses documents seront supprimés après confirmation.",
}
</script>

<template>
  <div>
    <div v-if="loadError" class="error-banner">{{ loadError }}</div>
    <div v-else-if="!loaded" class="loading">Chargement…</div>
    <template v-else>
      <p class="ie-note">
        Les index sont enregistrés dans <code>{{ loaded.file }}</code>. Ce fichier est généré par cette page
        (<em>ne pas le modifier à la main</em>) ; les explications des réglages se trouvent ici, pas dans le fichier.
      </p>
      <div v-if="!loaded.writable" class="ie-banner ie-warn">
        {{ loaded.notWritableReason }} La modification n'est pas possible tant que ce droit n'est pas accordé.
      </div>
      <div v-if="loaded.ignoredKeys.length" class="ie-banner ie-warn">
        Réglages inconnus, ignorés par l'application, qui disparaîtront à l'enregistrement :
        <code v-for="k in loaded.ignoredKeys" :key="k" class="ie-chip">{{ k }}</code>
      </div>

      <section v-for="(ix, i) in indexes" :key="i" class="ie-index">
        <header class="ie-index-head">
          <template v-if="isNew(ix)">
            <input v-model="ix.coreName" class="ie-input ie-name" placeholder="Nom de l'index (ex. Juridique)"
                   title="Lettres, chiffres, _ et - ; définitif une fois enregistré." />
            <button class="ie-link ie-danger" @click="removeNewIndex(i)">Retirer ce nouvel index</button>
          </template>
          <h3 v-else>{{ ix.coreName }}</h3>
        </header>

        <div class="ie-grid">
          <label class="ie-field">
            <span class="ie-label">Statut</span>
            <select v-model="ix.status" class="ie-input">
              <option value="ACTIVE">Actif</option>
              <option value="PAUSED">En pause</option>
              <option value="DELETE">À supprimer</option>
            </select>
            <span class="ie-help">{{ statusHelp[ix.status] }}</span>
            <label v-if="ix.status === 'DELETE'" class="ie-check ie-danger">
              <input type="checkbox" v-model="ix.confirmDelete" /> Je confirme la suppression de cet index
            </label>
          </label>

          <div class="ie-field">
            <span class="ie-label">Visible par</span>
            <label class="ie-check"><input type="radio" :value="true" v-model="ix.visibleToAll" /> Tous les utilisateurs</label>
            <label class="ie-check"><input type="radio" :value="false" v-model="ix.visibleToAll" /> Seulement ces groupes de l'annuaire</label>
            <input v-if="!ix.visibleToAll" class="ie-input" :value="groupsText(ix)"
                   @input="setGroups(ix, ($event.target as HTMLInputElement).value)"
                   placeholder="Medical, Direction" />
            <span class="ie-help">
              Restreint, n'accorde jamais : dans un index visible, les droits NTFS de chaque fichier s'appliquent toujours.
              Noms de groupe antérieurs à Windows 2000, séparés par des virgules.
            </span>
          </div>

          <div class="ie-field">
            <span class="ie-label">Reconnaissance de texte (OCR)</span>
            <label class="ie-check"><input type="checkbox" v-model="ix.ocr" /> Lire les PDF numérisés</label>
            <span class="ie-help">Désactiver accélère l'indexation, mais les documents scannés ne seront pas trouvables par leur contenu.</span>
          </div>

          <div class="ie-field">
            <span class="ie-label">Types de fichiers</span>
            <label class="ie-check"><input type="radio" :checked="ix.allowedExtensions === null" @change="setCustomExts(ix, false)" /> Ceux par défaut</label>
            <label class="ie-check"><input type="radio" :checked="ix.allowedExtensions !== null" @change="setCustomExts(ix, true)" /> Une liste personnalisée</label>
            <input v-if="ix.allowedExtensions !== null" class="ie-input" :value="extsText(ix)"
                   @input="setExts(ix, ($event.target as HTMLInputElement).value)" placeholder="pdf, docx, txt" />
            <span class="ie-help">Par défaut : {{ defaultExts }}.</span>
          </div>
        </div>

        <h4 class="ie-sub">Dossiers indexés (partages réseau SMB)</h4>
        <table class="ie-dirs">
          <thead>
            <tr><th>Serveur</th><th>Partage</th><th>Compte</th><th>Domaine</th><th>Mot de passe</th><th></th></tr>
          </thead>
          <tbody>
            <template v-for="(d, j) in ix.indexedDirs" :key="j">
              <tr>
                <td><input v-model="d.smbServer" class="ie-input" placeholder="serveur1" /></td>
                <td><input v-model="d.smbShare" class="ie-input" placeholder="RH" /></td>
                <td><input v-model="d.smbUser" class="ie-input" placeholder="compte_service" autocomplete="off" /></td>
                <td><input v-model="d.smbDomain" class="ie-input" placeholder="facultatif" /></td>
                <td><input v-model="d.smbPassword" type="password" class="ie-input" autocomplete="new-password"
                           :placeholder="d.passwordSet ? '•••••• (inchangé)' : 'obligatoire'" /></td>
                <td class="ie-actions">
                  <button class="ie-link" @click="testShare(ix, d, i + '-' + j)">Tester</button>
                  <button class="ie-link ie-danger" @click="ix.indexedDirs.splice(j, 1)">Retirer</button>
                </td>
              </tr>
              <tr v-if="shareTests[i + '-' + j]">
                <td colspan="6" :class="shareTests[i + '-' + j]?.ok ? 'ie-ok' : 'ie-bad'">
                  {{ shareTests[i + '-' + j]?.message }}
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <button class="ie-link" @click="ix.indexedDirs.push(emptyDir())">+ Ajouter un dossier</button>
      </section>

      <button class="ie-btn ie-add" :disabled="!loaded.writable" @click="addIndex">+ Ajouter un index</button>

      <div class="ie-bar">
        <button class="ie-btn" :disabled="busy || !dirty" @click="check">Vérifier</button>
        <button class="ie-btn ie-primary" :disabled="busy || !dirty || !loaded.writable" @click="save">Enregistrer</button>
        <span v-if="dirty" class="ie-help">Modifications non enregistrées.</span>
      </div>

      <table v-if="findings.length" class="ie-findings">
        <tbody>
          <tr v-for="(f, k) in findings" :key="k">
            <td><span :class="['ie-sev', 'ie-sev-' + f.severity]">{{ severityLabel[f.severity] }}</span></td>
            <td><code v-if="f.where">{{ f.where }}</code></td>
            <td>{{ f.message }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="hasErrors" class="ie-help">Corrigez les erreurs pour pouvoir enregistrer.</div>

    </template>
  </div>
</template>

<style scoped>
.ie-note { font-size: 13px; color: #5f6368; margin: 0 0 12px; }
.ie-banner { border-radius: 8px; padding: 10px 14px; margin: 0 0 14px; font-size: 13px; }
.ie-warn { background: #fef7e0; border: 1px solid #f9d77e; color: #7a4f01; }
.ie-restart { background: #e6f4ea; border: 1px solid #a8dab5; color: #137333; margin-top: 16px; }
.ie-restart .ie-btn { margin: 6px 8px 0 0; }
.ie-chip { margin-left: 6px; }
.ie-index { border: 1px solid #e0e0e0; border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.ie-index-head { display: flex; align-items: center; gap: 16px; margin-bottom: 8px; }
.ie-index-head h3 { margin: 0; font-size: 17px; }
.ie-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; }
.ie-field { display: flex; flex-direction: column; gap: 4px; }
.ie-label { font-weight: 600; font-size: 13px; color: #202124; }
.ie-help { font-size: 12px; color: #5f6368; }
.ie-input { padding: 6px 8px; border: 1px solid #c4c7c5; border-radius: 6px; font-size: 13px; width: 100%; box-sizing: border-box; }
.ie-name { max-width: 320px; font-weight: 600; }
.ie-check { font-size: 13px; display: flex; gap: 6px; align-items: center; }
.ie-sub { margin: 16px 0 6px; font-size: 13px; }
.ie-dirs { width: 100%; border-collapse: collapse; margin-bottom: 6px; }
.ie-dirs th { text-align: left; font-size: 12px; color: #5f6368; padding: 2px 6px; }
.ie-dirs td { padding: 3px 6px; }
.ie-actions { white-space: nowrap; }
.ie-link { background: none; border: none; color: #1a73e8; cursor: pointer; font-size: 13px; padding: 2px 6px; }
.ie-danger { color: #c5221f; }
.ie-ok { color: #137333; font-size: 12px; }
.ie-bad { color: #c5221f; font-size: 12px; }
.ie-btn { border: 1px solid #c4c7c5; background: #fff; border-radius: 6px; padding: 7px 16px; font-size: 14px; cursor: pointer; }
.ie-btn:disabled { opacity: .5; cursor: default; }
.ie-primary { background: #1a73e8; color: #fff; border-color: #1a73e8; }
.ie-add { margin-bottom: 16px; }
.ie-bar { display: flex; gap: 10px; align-items: center; position: sticky; bottom: 0; background: #fff; padding: 10px 0; border-top: 1px solid #e0e0e0; }
.ie-findings { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
.ie-findings td { padding: 5px 8px; border-bottom: 1px solid #eee; vertical-align: top; }
.ie-sev { font-weight: 700; font-size: 12px; text-transform: uppercase; }
.ie-sev-error { color: #c5221f; } .ie-sev-warning { color: #b06000; } .ie-sev-info { color: #5f6368; }
.error-banner { color: #c5221f; }
</style>
