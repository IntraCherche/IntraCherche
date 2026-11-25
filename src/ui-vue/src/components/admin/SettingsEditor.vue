<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import apiClient from '../../plugins/axios'

interface Setting {
  key: string; tier: string; group: string; label: string; type: string; help: string
  unit: string | null; min: number | null; max: number | null; required: boolean; allowBlank: boolean; slider: boolean
  value: string | number | boolean | null; defaultValue: string | number | boolean | null
  modified: boolean; running: string | null; secretSet: boolean
}
interface Finding { severity: 'error' | 'warning' | 'info'; scope: string; where: string; message: string }
interface View { file: string | null; writable: boolean; notWritableReason: string | null; settings: Setting[] }

const props = defineProps<{ tier: 'common' | 'advanced' | 'directory' }>()
const emit = defineEmits<{ (e: 'saved', info: { backup: string | null; restartSupported: boolean }): void }>()

const view = ref<View | null>(null)
const values = ref<Record<string, string | number | boolean | null>>({})
const orig = ref<Record<string, string | number | boolean | null>>({})
const restartSupported = ref(false)
const loadError = ref<string | null>(null)
const findings = ref<Finding[]>([])
const busy = ref(false)
const unlocked = ref(props.tier === 'common')
const groupLookup = ref<Record<string, { name: string; msg: string; ok?: boolean }>>({})

const mine = computed(() => (view.value?.settings ?? []).filter(s => s.tier === props.tier))
const groups = computed(() => {
  const out: { name: string; items: Setting[] }[] = []
  for (const s of mine.value) {
    let g = out.find(x => x.name === s.group)
    if (!g) { g = { name: s.group, items: [] }; out.push(g) }
    g.items.push(s)
  }
  return out
})
function norm(v: unknown) { return v === null || v === undefined ? '' : String(v) }
function changed(s: Setting) { return norm(values.value[s.key]) !== norm(orig.value[s.key]) }
const changedKeys = computed(() => mine.value.filter(changed).map(s => s.key))
const dirty = computed(() => changedKeys.value.length > 0)
const hasErrors = computed(() => findings.value.some(f => f.severity === 'error'))
function isDefault(s: Setting) { return norm(values.value[s.key]) === norm(s.defaultValue) }
function reset(s: Setting) { values.value[s.key] = s.defaultValue }

async function load() {
  loadError.value = null
  try {
    const { data } = await apiClient.get<{ config: View; restartSupported: boolean }>('/admin/settings')
    view.value = data.config
    restartSupported.value = data.restartSupported
    const v: Record<string, string | number | boolean | null> = {}
    for (const s of data.config.settings) v[s.key] = s.type === 'secret' ? '' : s.value
    values.value = { ...v }
    orig.value = { ...v }
    findings.value = []
  } catch {
    loadError.value = 'Impossible de lire les réglages.'
  }
}
onMounted(load)

function payload() {
  const out: Record<string, unknown> = {}
  for (const s of mine.value) {
    if (!changed(s)) continue
    out[s.key] = isDefault(s) && !s.required ? null : values.value[s.key]
  }
  return out
}

async function check() {
  busy.value = true
  try {
    const { data } = await apiClient.post<Finding[]>('/admin/settings/validate', payload())
    findings.value = data.length ? data : [{ severity: 'info', scope: '', where: '', message: 'Aucun problème détecté.' }]
  } catch {
    findings.value = [{ severity: 'error', scope: '', where: '', message: 'La vérification a échoué.' }]
  } finally { busy.value = false }
}

async function save() {
  busy.value = true
  try {
    const { data } = await apiClient.put<{ saved: boolean; findings: Finding[]; backup: string | null }>(
      '/admin/settings', payload(), { validateStatus: s => s === 200 || s === 422 })
    findings.value = data.findings
    if (data.saved) {
      await load()
      findings.value = []
      emit('saved', { backup: data.backup, restartSupported: restartSupported.value })
    }
  } catch {
    findings.value = [{ severity: 'error', scope: '', where: '', message: "L'enregistrement a échoué." }]
  } finally { busy.value = false }
}

const ldapResult = ref<{ ok: boolean; message: string } | null>(null)
async function testLdap() {
  ldapResult.value = { ok: true, message: 'Test en cours…' }
  try {
    const { data } = await apiClient.post<{ ok: boolean; message: string }>('/admin/settings/test-ldap', payload())
    ldapResult.value = data
  } catch {
    ldapResult.value = { ok: false, message: 'Le test a échoué.' }
  }
}

const mailTo = ref('')
const mailResult = ref<{ ok: boolean; message: string } | null>(null)
async function testMail() {
  mailResult.value = { ok: true, message: 'Envoi en cours…' }
  try {
    const { data } = await apiClient.post<{ ok: boolean; message: string }>(
      '/admin/settings/test-mail', { settings: payload(), to: mailTo.value })
    mailResult.value = data
  } catch {
    mailResult.value = { ok: false, message: "L'essai a échoué." }
  }
}

async function findGroup(s: Setting) {
  const entry = groupLookup.value[s.key]
  if (!entry || !entry.name.trim()) return
  try {
    const { data } = await apiClient.post<{ name: string; sid: string }>('/admin/settings/resolve-group', { name: entry.name })
    values.value[s.key] = data.sid
    groupLookup.value[s.key] = { name: entry.name, msg: `Groupe « ${data.name} » trouvé.`, ok: true }
  } catch (e: any) {
    groupLookup.value[s.key] = { name: entry.name, msg: e?.response?.data?.error ?? 'Recherche impossible.', ok: false }
  }
}
function lookup(s: Setting) {
  if (!groupLookup.value[s.key]) groupLookup.value[s.key] = { name: '', msg: '' }
  return groupLookup.value[s.key]!
}
function boolValue(s: Setting) { return values.value[s.key] === true }
function displayDefault(s: Setting) {
  const d = s.defaultValue
  if (s.type === 'bool') return d ? 'oui' : 'non'
  if (d === '' || d === null) return 'vide'
  const unit = s.unit && Number(d) === 1 && s.unit.endsWith('s') ? s.unit.slice(0, -1) : s.unit
  return String(d) + (unit ? ' ' + unit : '')
}
const severityLabel: Record<string, string> = { error: 'Erreur', warning: 'Attention', info: 'Info' }
</script>

<template>
  <div>
    <div v-if="loadError" class="se-bad">{{ loadError }}</div>
    <div v-else-if="!view" class="loading">Chargement…</div>
    <template v-else>
      <div v-if="tier === 'advanced' && !unlocked" class="se-lock">
        <strong>Réglages avancés</strong>
        <p>Ces réglages ont été ajustés pour la plupart des installations. Une valeur inadaptée peut ralentir
          l'indexation, saturer la mémoire ou rendre Sherlock inutilisable. Modifiez-les seulement si vous savez
          pourquoi, ou sur conseil.</p>
        <button class="se-btn" @click="unlocked = true">Je comprends, afficher les réglages</button>
      </div>
      <template v-else>
        <p class="se-note">
          Enregistrés dans <code>{{ view.file ?? 'application.yaml' }}</code>, fichier généré par cette page.
          Un réglage « par défaut » n'est pas écrit dans le fichier : il suivra les évolutions des prochaines versions.
          Tous ces réglages prennent effet au prochain démarrage.
        </p>
        <div v-if="!view.writable" class="se-banner">
          {{ view.notWritableReason }} La modification n'est pas possible tant que ce droit n'est pas accordé.
        </div>

        <section v-for="g in groups" :key="g.name" class="se-group">
          <h3>{{ g.name }}</h3>
          <div v-for="s in g.items" :key="s.key" :class="['se-row', { 'se-changed': changed(s) }]">
            <div class="se-label">
              <label :for="s.key">{{ s.label }}</label>
              <span v-if="s.modified && !changed(s)" class="se-chip">modifié</span>
              <span v-if="changed(s)" class="se-chip se-chip-pending">à enregistrer</span>
            </div>
            <div class="se-control">
              <label v-if="s.type === 'bool'" class="se-check">
                <input :id="s.key" type="checkbox" :checked="boolValue(s)"
                       @change="values[s.key] = ($event.target as HTMLInputElement).checked" /> Activé
              </label>
              <div v-else-if="s.type === 'int'" class="se-inline">
                <input v-if="s.slider && s.min !== null && s.max !== null" type="range" class="se-slider"
                       :min="s.min" :max="s.max" :value="values[s.key] as number"
                       @input="values[s.key] = Number(($event.target as HTMLInputElement).value)" />
                <input :id="s.key" type="number" class="se-input se-num" :min="s.min ?? undefined" :max="s.max ?? undefined"
                       :value="values[s.key] as number"
                       @input="values[s.key] = ($event.target as HTMLInputElement).value === '' ? '' : Number(($event.target as HTMLInputElement).value)" />
                <span v-if="s.unit" class="se-unit">{{ s.unit }}</span>
              </div>
              <input v-else-if="s.type === 'secret'" :id="s.key" type="password" class="se-input" autocomplete="new-password"
                     :value="values[s.key] as string" @input="values[s.key] = ($event.target as HTMLInputElement).value"
                     :placeholder="s.secretSet ? '•••••• (inchangé)' : 'non défini'" />
              <input v-else :id="s.key" type="text" class="se-input" :value="values[s.key] as string"
                     @input="values[s.key] = ($event.target as HTMLInputElement).value"
                     :placeholder="s.type === 'groupSid' ? 'S-1-5-21-…' : ''" />
              <button v-if="!isDefault(s) && s.type !== 'secret'" class="se-link" @click="reset(s)">Valeur par défaut ({{ displayDefault(s) }})</button>
              <div v-if="s.type === 'groupSid'" class="se-find">
                <input class="se-input se-find-input" placeholder="…ou nom du groupe (ex. IntraCherche-Admin)"
                       v-model="lookup(s).name" @keyup.enter="findGroup(s)" />
                <button class="se-link" @click="findGroup(s)">Trouver l'identifiant</button>
                <span v-if="groupLookup[s.key]?.msg" :class="groupLookup[s.key]?.ok ? 'se-ok' : 'se-bad'">{{ groupLookup[s.key]?.msg }}</span>
              </div>
              <div class="se-help">{{ s.help }}</div>
              <div v-if="s.running !== null && norm(s.running) !== norm(orig[s.key]) && s.type !== 'groupSid' && !changed(s)" class="se-help">
                Valeur en cours d'utilisation : <code>{{ s.running }}</code> (redémarrage nécessaire).
              </div>
            </div>
          </div>
          <div v-if="g.name === 'Courriel des alertes'" class="se-ldap">
            <div class="se-inline">
              <input type="email" class="se-input" v-model="mailTo" placeholder="adresse qui recevra l'essai" />
              <button class="se-btn" :disabled="busy" @click="testMail">Envoyer un message d'essai</button>
            </div>
            <span v-if="mailResult" :class="mailResult.ok ? 'se-ok' : 'se-bad'">{{ mailResult.message }}</span>
            <div class="se-help">Utilise les valeurs saisies ci-dessus, enregistrées ou non (le mot de passe laissé vide est celui déjà enregistré). Sans serveur de courrier, aucun courriel n'est envoyé : les alertes restent visibles dans la cloche de l'application.</div>
          </div>
        </section>

        <div v-if="tier === 'directory'" class="se-ldap">
          <button class="se-btn" :disabled="busy" @click="testLdap">Tester la connexion à l'annuaire</button>
          <span v-if="ldapResult" :class="ldapResult.ok ? 'se-ok' : 'se-bad'">{{ ldapResult.message }}</span>
          <div class="se-help">Teste les valeurs saisies ci-dessus (et, pour ce qui est laissé inchangé, les valeurs actuelles). À l'enregistrement, le test est fait automatiquement : des valeurs qui empêcheraient toute connexion ne sont pas enregistrées.</div>
        </div>

        <div class="se-bar">
          <button class="se-btn" :disabled="busy || !dirty" @click="check">Vérifier</button>
          <button class="se-btn se-primary" :disabled="busy || !dirty || !view.writable" @click="save">Enregistrer</button>
          <span v-if="dirty" class="se-help">{{ changedKeys.length }} modification(s) non enregistrée(s).</span>
        </div>
        <table v-if="findings.length" class="se-findings">
          <tbody>
            <tr v-for="(f, k) in findings" :key="k">
              <td><span :class="['se-sev', 'se-sev-' + f.severity]">{{ severityLabel[f.severity] }}</span></td>
              <td><code v-if="f.where">{{ f.where }}</code></td>
              <td>{{ f.message }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="hasErrors" class="se-help">Corrigez les erreurs pour pouvoir enregistrer.</div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.se-note { font-size: 13px; color: #5f6368; margin: 0 0 12px; }
.se-banner { background: #fef7e0; border: 1px solid #f9d77e; color: #7a4f01; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 13px; }
.se-lock { background: #fef7e0; border: 1px solid #f9d77e; border-radius: 10px; padding: 16px 20px; color: #5b3a00; }
.se-lock p { margin: 6px 0 12px; font-size: 13px; }
.se-group { margin-bottom: 22px; }
.se-group h3 { font-size: 15px; margin: 0 0 6px; padding-bottom: 4px; border-bottom: 1px solid #e0e0e0; }
.se-row { display: grid; grid-template-columns: minmax(200px, 300px) 1fr; gap: 16px; padding: 10px 6px; border-bottom: 1px solid #f3f3f3; }
.se-changed { background: #f3f8ff; }
.se-label { font-weight: 600; font-size: 13px; color: #202124; }
.se-chip { margin-left: 8px; font-size: 11px; font-weight: 600; color: #5f6368; background: #eee; border-radius: 8px; padding: 1px 7px; }
.se-chip-pending { color: #1a73e8; background: #e8f0fe; }
.se-control { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.se-input { padding: 6px 8px; border: 1px solid #c4c7c5; border-radius: 6px; font-size: 13px; width: 100%; max-width: 460px; box-sizing: border-box; }
.se-num { max-width: 140px; width: auto; }
.se-inline { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.se-slider { width: 220px; margin-right: 10px; vertical-align: middle; }
.se-unit { font-size: 12px; color: #5f6368; margin-left: 6px; }
.se-check { font-size: 13px; display: flex; gap: 6px; align-items: center; }
.se-help { font-size: 12px; color: #5f6368; max-width: 640px; }
.se-link { background: none; border: none; color: #1a73e8; cursor: pointer; font-size: 12px; padding: 0; }
.se-find { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.se-find-input { max-width: 300px; }
.se-ok { color: #137333; font-size: 12px; }
.se-ldap { margin: 6px 0 14px; display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.se-bad { color: #c5221f; font-size: 12px; }
.se-btn { border: 1px solid #c4c7c5; background: #fff; border-radius: 6px; padding: 7px 16px; font-size: 14px; cursor: pointer; }
.se-btn:disabled { opacity: .5; cursor: default; }
.se-primary { background: #1a73e8; color: #fff; border-color: #1a73e8; }
.se-bar { display: flex; gap: 10px; align-items: center; position: sticky; bottom: 0; background: #fff; padding: 10px 0; border-top: 1px solid #e0e0e0; }
.se-findings { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
.se-findings td { padding: 5px 8px; border-bottom: 1px solid #eee; vertical-align: top; }
.se-sev { font-weight: 700; font-size: 12px; text-transform: uppercase; }
.se-sev-error { color: #c5221f; } .se-sev-warning { color: #b06000; } .se-sev-info { color: #5f6368; }
@media (max-width: 800px) { .se-row { grid-template-columns: 1fr; } }
</style>
