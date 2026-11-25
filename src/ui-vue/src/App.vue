<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import SherlockPane from './components/SherlockPane.vue'
import { RouterView, RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useNotificationStore } from './stores/notifications'
import apiClient from './plugins/axios'
import { useTour } from './composables/useTour'

const authStore = useAuthStore()
const notificationStore = useNotificationStore()
const route = useRoute()
const { startTour } = useTour()

const appVersion = ref<string | null>(null)

let pollInterval: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  if (authStore.isAuthenticated) {
    // Resolve admin status server-side so the cogwheel matches the user's
    // current group membership, not whatever was cached at login time.
    authStore.refresh()
    notificationStore.fetchUnreadCount()
    pollInterval = setInterval(() => notificationStore.fetchUnreadCount(), 60_000)
    try {
      const { data } = await apiClient.get('/version')
      appVersion.value = data.version
    } catch {
      // silently ignore — tooltip simply won't show the version if unavailable
    }
  }
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})

const logout = async () => {
  // The session lives in an httpOnly cookie that only the server can end. If it did not answer, the
  // session is still open: say so on the login page instead of pretending to be signed out.
  let confirmed = true
  try { await apiClient.post('/auth/logout') } catch { confirmed = false }
  authStore.logout()
  // A full page load, not router.push: the stores (indexes, Sherlock questions, alerts) and the
  // search page hold the previous user's data, and the next person to log in on this tab would
  // see it until a refresh — an index they may not see among them.
  window.location.assign(confirmed ? '/login' : '/login?logoutError=1')
}
</script>

<template>
  <div class="app-root">
    <header v-if="authStore.isAuthenticated" class="nav-header">
      <RouterLink to="/" class="logo" :title="appVersion ? `IntraCherche v${appVersion}` : 'IntraCherche'"><img src="/Logo-IntraCherche-HighDef.png" alt="IntraCherche" class="logo-img" /> IntraCherche</RouterLink>
      <div class="user-info">
        <span>{{ authStore.displayName || authStore.username }}</span>

        <RouterLink to="/abonnements" class="notif-bell-btn" title="Gérer mes alertes">
          🔔
          <span v-if="notificationStore.unreadCount > 0" class="notif-badge">
            {{ notificationStore.unreadCount > 99 ? '99+' : notificationStore.unreadCount }}
          </span>
        </RouterLink>

        <RouterLink v-if="authStore.isAdmin" to="/admin" class="admin-link" title="Panneau d'administration">⚙</RouterLink>

        <button
          v-if="route.path === '/'"
          class="tour-btn"
          @click="startTour"
          title="Visite guidée — découvrir les fonctionnalités"
          aria-label="Lancer la visite guidée"
        >?</button>

        <button @click="logout" class="logout-btn">Se déconnecter</button>
      </div>
    </header>

    <RouterView />
    <SherlockPane />
  </div>
</template>

<style>
body {
  margin: 0;
  padding: 0;
  background-color: #fff;
  display: block;
  min-height: unset;
}

#app {
  max-width: 1920px;
  margin: 0 auto;
  padding: 2rem;
  text-align: left;
}

.nav-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 2rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid #eee;
}
.logo {
  text-decoration: none;
  color: inherit;
  cursor: pointer;
}
.logo-img {
  height: 24px;
  width: auto;
  vertical-align: middle;
  margin-right: 4px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.logout-btn {
  cursor: pointer;
  background: none;
  border: 1px solid #ccc;
  padding: 4px 8px;
}

/* Notification bell */
.notif-bell-btn {
  display: inline-block;
  position: relative;
  font-size: 20px;
  padding: 4px 8px;
  line-height: 1;
  text-decoration: none;
  cursor: pointer;
}

.notif-badge {
  position: absolute;
  top: 0;
  right: 0;
  background: #d93025;
  color: white;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 4px;
  min-width: 16px;
  text-align: center;
  line-height: 16px;
}

.admin-link {
  font-size: 18px;
  padding: 4px 6px;
  text-decoration: none;
  color: #5f6368;
  border-radius: 4px;
  transition: background 0.12s, color 0.12s;
}
.admin-link:hover { background: #f1f3f4; color: #202124; }

.tour-btn {
  cursor: pointer;
  background: none;
  border: 1px solid #dadce0;
  border-radius: 50%;
  width: 28px;
  height: 28px;
  font-size: 14px;
  font-weight: 700;
  color: #5f6368;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.12s, color 0.12s, border-color 0.12s;
}
.tour-btn:hover {
  background: #e8f0fe;
  color: #1a73e8;
  border-color: #1a73e8;
}

</style>
