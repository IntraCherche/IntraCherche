import { defineStore } from 'pinia'
import apiClient from '../plugins/axios'

export interface Subscription {
  id: string
  username: string
  query: string
  indices: string[]
  filename?: string
  geoCity?: string
  geoDpt?: string
  geoRegion?: string
  geoCountry?: string
  dateOffsetFrom?: number
  dateOffsetTo?: number
  sort: string
  frequency: 'IMMEDIATE' | 'DAILY' | 'WEEKLY'
  lastCheckedAt?: number
  createdAt: number
  isActive: boolean
}

export interface CreateSubscriptionPayload {
  query: string
  indices: string[]
  filename?: string
  geoCity?: string
  geoDpt?: string
  geoRegion?: string
  geoCountry?: string
  dateOffsetFrom?: number
  dateOffsetTo?: number
  sort: string
  frequency: 'IMMEDIATE' | 'DAILY' | 'WEEKLY'
}

export const useSubscriptionStore = defineStore('subscriptions', {
  state: () => ({
    subscriptions: [] as Subscription[],
    loading: false,
    error: null as string | null,
  }),

  actions: {
    async fetchMine(silent = false) {
      if (!silent) this.loading = true
      this.error = null
      try {
        const r = await apiClient.get<Subscription[]>('/subscriptions')
        this.subscriptions = r.data
      } catch (e) {
        this.error = 'Impossible de charger les alertes.'
      } finally {
        this.loading = false
      }
    },

    async create(payload: CreateSubscriptionPayload) {
      const r = await apiClient.post<Subscription>('/subscriptions', payload)
      this.subscriptions.unshift(r.data)
      return r.data
    },

    async remove(id: string) {
      await apiClient.delete(`/subscriptions/${id}`)
      this.subscriptions = this.subscriptions.filter(s => s.id !== id)
    },

    async updateFrequency(id: string, frequency: string) {
      const r = await apiClient.patch<Subscription>(`/subscriptions/${id}/frequency`, { frequency })
      const idx = this.subscriptions.findIndex(s => s.id === id)
      if (idx !== -1) this.subscriptions[idx] = r.data
    },
  },
})
