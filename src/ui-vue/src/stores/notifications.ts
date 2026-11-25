import { defineStore } from 'pinia'
import apiClient from '../plugins/axios'

export interface Notification {
  id: string
  subscriptionId: string
  message: string
  querySummary: string
  createdAt: number
  isRead: boolean
  notificationType: string
}

export const useNotificationStore = defineStore('notifications', {
  state: () => ({
    notifications: [] as Notification[],
    unreadCount: 0,
    loading: false,
    replayQuery: null as string | null,
  }),

  actions: {
    async fetchUnreadCount() {
      try {
        const r = await apiClient.get<{ count: number }>('/notifications/unread-count')
        this.unreadCount = r.data.count
      } catch {
        // silent — bell just shows 0
      }
    },

    // silent: background refresh, keeps the list on screen instead of "Chargement…"
    async fetchNotifications(limit = 20, silent = false) {
      if (!silent) this.loading = true
      try {
        const [r] = await Promise.all([
          apiClient.get<Notification[]>(`/notifications?limit=${limit}`),
          // The list is capped at `limit`, so counting its unread rows would
          // under-report the badge; ask the server for the real count.
          this.fetchUnreadCount(),
        ])
        this.notifications = r.data
      } catch (e) {
        console.error('[Notifications] Failed to load:', e)
      } finally {
        this.loading = false
      }
    },

    async markRead(id: string) {
      await apiClient.patch(`/notifications/${id}/read`)
      const n = this.notifications.find(n => n.id === id)
      if (n && !n.isRead) {
        n.isRead = true
        this.unreadCount = Math.max(0, this.unreadCount - 1)
      }
    },

    async markAllRead() {
      await apiClient.patch('/notifications/read-all')
      this.notifications.forEach(n => (n.isRead = true))
      this.unreadCount = 0
    },

    async markReadBySubscription(subscriptionId: string) {
      const unread = this.notifications.filter(n => n.subscriptionId === subscriptionId && !n.isRead)
      await Promise.all(unread.map(n => this.markRead(n.id)))
    },

    triggerReplay(rawQuery: string) {
      this.replayQuery = rawQuery
    },
  },
})
