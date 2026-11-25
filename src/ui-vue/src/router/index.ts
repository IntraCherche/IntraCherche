import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Login from '../views/Login.vue'
import SearchService from '../components/SearchService.vue'
import SubscriptionManager from '../views/SubscriptionManager.vue'
import AdminPanel from '../views/AdminPanel.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'Login', component: Login },
    { path: '/', name: 'Search', component: SearchService },
    { path: '/abonnements', name: 'Subscriptions', component: SubscriptionManager },
    { path: '/admin', name: 'Admin', component: AdminPanel },
  ]
})

// The "Bouncer": Checks if you are logged in before every page change.
// An empty store is not proof of no session — the Google login arrives with a cookie and nothing
// stored — so the server is asked once before the visitor is sent to the login page.
router.beforeEach(async (to) => {
  const authStore = useAuthStore()

  if (to.name === 'Login') return true
  if (!authStore.isAuthenticated) await authStore.restore()
  return authStore.isAuthenticated ? true : { name: 'Login' }
})

export default router