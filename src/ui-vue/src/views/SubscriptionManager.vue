<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSubscriptionStore } from '../stores/subscriptions'
import { useNotificationStore } from '../stores/notifications'
import { useSherlockStore } from '../stores/sherlock'

const store = useSubscriptionStore()
const notificationStore = useNotificationStore()
const sherlockStore = useSherlockStore()
const router = useRouter()

const confirmDeleteId = ref<string | null>(null)
const requestDelete = (id: string) => { confirmDeleteId.value = id }
const cancelDelete  = () => { confirmDeleteId.value = null }
const executeDelete = async (id: string) => {
  confirmDeleteId.value = null
  await store.remove(id)
}

const formatDate = (ts: number) =>
  new Date(ts).toLocaleDateString('fr-FR', { dateStyle: 'medium' })

const formatDateTime = (ts: number) =>
  new Date(ts).toLocaleDateString('fr-FR', { dateStyle: 'medium' }) + ' ' +
  new Date(ts).toLocaleTimeString('fr-FR', { timeStyle: 'short' })

// The header polls the unread count every minute; poll faster while this page
// is open so a new alert shows up within seconds.
let pollInterval: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  pollInterval = setInterval(() => notificationStore.fetchUnreadCount(), 10_000)
  await Promise.all([store.fetchMine(), notificationStore.fetchNotifications()])
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})

// When the unread count goes up while this page is open, reload the list so
// the new alert shows without a page refresh.
watch(() => notificationStore.unreadCount, (count, previous) => {
  if (count > previous) {
    store.fetchMine(true)
    notificationStore.fetchNotifications(20, true)
  }
})

const hasNewResults = (subId: string) =>
  notificationStore.notifications.some(n => n.subscriptionId === subId && !n.isRead)

const sherlockNotifs = computed(() =>
  notificationStore.notifications.filter(
    n => n.notificationType === 'SHERLOCK_ANSWER' && !n.isRead
  )
)

const replaySearch = async (subscriptionId: string, rawQuery: string) => {
  await notificationStore.markReadBySubscription(subscriptionId)
  notificationStore.triggerReplay(rawQuery)
  router.push('/')
}

const viewAnswer = async (notifId: string) => {
  await notificationStore.markRead(notifId)
  sherlockStore.openPane()
}
</script>

<template>
  <div class="subs-page">
    <div class="subs-page-header">
      <h1>Mes alertes</h1>
      <button
        v-if="notificationStore.unreadCount > 0"
        class="mark-all-btn"
        @click="notificationStore.markAllRead()"
      >Tout marquer lu</button>
    </div>
    <p class="subs-intro">
      Vous recevrez une notification par e-mail et dans l'application
      lorsque de nouveaux documents correspondront à ces recherches.
    </p>

    <div v-if="store.loading || notificationStore.loading" class="subs-status">Chargement…</div>
    <div v-else-if="store.error" class="subs-error">{{ store.error }}</div>

    <template v-else>
    <!-- Sherlock answer notifications -->
    <div v-if="sherlockNotifs.length > 0" class="sherlock-section">
      <h2 class="section-heading">Réponses de Sherlock</h2>
      <div class="sherlock-card">
        <div v-for="n in sherlockNotifs" :key="n.id" class="sherlock-notif-row">
          <div class="sherlock-notif-info">
            <span class="sherlock-notif-msg">{{ n.message }}</span>
            <span class="sherlock-notif-date">{{ formatDateTime(n.createdAt) }}</span>
          </div>
          <button class="view-answer-btn" @click="viewAnswer(n.id)">Voir la réponse →</button>
        </div>
      </div>
    </div>

    <div v-if="store.subscriptions.length === 0" class="subs-status">
      Vous n'avez aucune alerte active. Lancez une recherche, puis cliquez sur
      <strong>🔔 S'abonner</strong>.
    </div>

    <div v-if="store.subscriptions.length > 0" class="subs-card">
      <table class="subs-table">
        <thead>
          <tr>
            <th class="col-notif" title="Nouveaux résultats"></th>
            <th>Recherche</th>
            <th>Sources</th>
            <th>Fréquence</th>
            <th>Dernière vérification</th>
            <th>Créée le</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="sub in store.subscriptions"
            :key="sub.id"
            :class="{ 'row-new': hasNewResults(sub.id) }"
          >
            <td class="col-notif">
              <span
                v-if="hasNewResults(sub.id)"
                class="notif-dot"
                title="Nouveaux résultats disponibles"
              >🔔</span>
            </td>
            <td>
              <button
                class="sub-query-btn"
                :title="`Cliquer pour relancer : « ${sub.query || 'tous les documents'} »`"
                @click="replaySearch(sub.id, sub.query)"
              >{{ sub.query || '(tous les documents)' }}</button>
              <span v-if="sub.filename" class="sub-detail">Fichier : {{ sub.filename }}</span>
            </td>
            <td class="col-sources">{{ sub.indices.join(', ') }}</td>
            <td>
              <select
                :value="sub.frequency"
                @change="store.updateFrequency(sub.id, ($event.target as HTMLSelectElement).value)"
                class="freq-select"
              >
                <option value="IMMEDIATE">Immédiate</option>
                <option value="DAILY">Résumé quotidien</option>
                <option value="WEEKLY">Résumé hebdomadaire</option>
              </select>
            </td>
            <td class="col-date">{{ sub.lastCheckedAt ? formatDate(sub.lastCheckedAt) : '—' }}</td>
            <td class="col-date">{{ formatDate(sub.createdAt) }}</td>
            <td class="row-actions">
              <div v-if="confirmDeleteId === sub.id" class="confirm-delete">
                <span class="confirm-delete-label">Supprimer ?</span>
                <button class="confirm-yes-btn" @click="executeDelete(sub.id)" title="Confirmer la suppression">✓</button>
                <button class="confirm-no-btn"  @click="cancelDelete"          title="Annuler">✕</button>
              </div>
              <button v-else class="delete-btn" @click="requestDelete(sub.id)" title="Supprimer cette alerte">
                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="3 6 5 6 21 6"/>
                  <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
                  <line x1="10" y1="11" x2="10" y2="17"/>
                  <line x1="14" y1="11" x2="14" y2="17"/>
                  <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                </svg>
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    </template>
  </div>
</template>

<style scoped>
.subs-page {
  max-width: 1000px;
  margin: 40px auto;
  padding: 0 24px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.subs-page-header {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 8px;
}

h1 { font-size: 24px; color: #202124; margin: 0; }

.mark-all-btn {
  background: none;
  border: none;
  color: #1a73e8;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}
.mark-all-btn:hover { text-decoration: underline; }

.subs-intro { color: #5f6368; font-size: 14px; margin-bottom: 24px; }

/* Sherlock answer notifications */
.sherlock-section { margin-bottom: 32px; }
.section-heading { font-size: 16px; font-weight: 600; color: #202124; margin: 0 0 12px; }
.sherlock-card {
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 6px rgba(60,64,67,.10);
}
.sherlock-notif-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  border-bottom: 1px solid #f0f0f0;
  background: #fff;
}
.sherlock-notif-row:last-child { border-bottom: none; }
.sherlock-notif-row:nth-child(even) { background: #f8f9fa; }
.sherlock-notif-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.sherlock-notif-msg { font-size: 14px; color: #202124; font-weight: 500; }
.sherlock-notif-date { font-size: 11px; color: #70757a; }
.view-answer-btn {
  flex-shrink: 0;
  background: #1a73e8;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.12s;
}
.view-answer-btn:hover { background: #1558b0; }

.subs-status { color: #70757a; font-style: italic; }
.subs-error  { color: #d93025; font-size: 14px; }

/* Card container */
.subs-card {
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 6px rgba(60,64,67,.10), 0 2px 10px rgba(60,64,67,.06);
}

.subs-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.subs-table thead {
  background: #f8f9fa;
}

.subs-table th {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 2px solid #e0e0e0;
  color: #5f6368;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: .4px;
  white-space: nowrap;
}

.subs-table td {
  padding: 12px 14px;
  border-bottom: 1px solid #f0f0f0;
  vertical-align: middle;
}

/* Zebra striping */
.subs-table tbody tr:nth-child(even) td {
  background: #f8f9fa;
}

/* Row hover */
.subs-table tbody tr {
  transition: background 0.12s;
}
.subs-table tbody tr:hover td {
  background: #e8f0fe !important;
}

/* Last row — no bottom border */
.subs-table tbody tr:last-child td {
  border-bottom: none;
}

/* Highlight row with new results */
.row-new td {
  border-left: 3px solid transparent;
}
.row-new td:first-child {
  border-left: 3px solid #1a73e8;
}

/* Notification icon column */
.col-notif {
  width: 40px;
  text-align: center;
  padding-left: 10px !important;
  padding-right: 4px !important;
}

.notif-dot {
  display: inline-block;
  font-size: 16px;
  cursor: default;
  animation: bell-ring 2s ease-in-out infinite;
  transform-origin: 50% 0%;
}

@keyframes bell-ring {
  0%, 80%, 100% { transform: rotate(0deg); }
  85%            { transform: rotate(12deg); }
  90%            { transform: rotate(-10deg); }
  95%            { transform: rotate(8deg); }
}

/* Clickable query */
.sub-query-btn {
  background: none;
  border: none;
  padding: 0;
  font-weight: 500;
  font-size: 14px;
  color: #1a73e8;
  cursor: pointer;
  text-align: left;
  display: block;
  text-decoration: none;
}
.sub-query-btn:hover {
  text-decoration: underline;
  color: #1558b0;
}

.sub-detail { color: #70757a; font-size: 12px; display: block; margin-top: 2px; }

.col-sources { color: #5f6368; max-width: 180px; }
.col-date    { color: #70757a; white-space: nowrap; }

.freq-select {
  border: 1px solid #dfe1e5;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 13px;
  color: #202124;
  cursor: pointer;
  background: white;
}
.freq-select:focus {
  outline: 2px solid #1a73e8;
  outline-offset: 1px;
}

.row-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: flex-end;
  white-space: nowrap;
}

.delete-btn {
  background: none;
  border: none;
  color: #9aa0a6;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  transition: color 0.12s, background 0.12s;
}
.delete-btn:hover { color: #c5221f; background: #fce8e6; }

.confirm-delete {
  display: flex;
  align-items: center;
  gap: 4px;
}
.confirm-delete-label {
  font-size: 11px;
  color: #c5221f;
  font-weight: 600;
  white-space: nowrap;
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
</style>
