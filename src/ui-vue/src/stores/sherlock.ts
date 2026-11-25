import { defineStore } from 'pinia'
import apiClient from '../plugins/axios'
import { useNotificationStore } from './notifications'

export interface SherlockCitation {
  id: string | null
  path: string
  filename: string
  page: number | null
}

/** A quotation in the answer; offsets index the answer string. */
export interface SherlockQuote {
  start: number
  end: number
  /** 1-based passage the answer cites for it; null when no citation follows it */
  document: number | null
  /** 1-based passage that contains it word for word; null when none does */
  foundIn: number | null
  verified: boolean
}

export interface SherlockQuestion {
  id: string
  question: string
  status: 'QUEUED' | 'PROCESSING' | 'ANSWERED' | 'FAILED'
  answer: string | null
  citations: SherlockCitation[]
  createdAt: number
  answeredAt: number | null
  parentId: string | null
  /** Indexes searched; empty means every index */
  indices: string[]
  /** Quotations checked against the passages; null for answers from before the check */
  quotes: SherlockQuote[] | null
  /** The one file the question is about (Solr parent_id); null for the whole scope */
  documentId: string | null
  documentName: string | null
  /** Misspelled words corrected before searching: typed word → correction */
  spelling: Record<string, string> | null
  /** How the question was read, when the user should know: a long document read slowly, or in part */
  notice: string | null
}

/** The user's questions today against their daily quota (llm.quota) */
export interface SherlockQuota {
  tier: 'BASE' | 'PREMIUM' | 'EXTENDED'
  used: number
  /** null: unlimited */
  limit: number | null
}

/** A file picked from the results with "Interroger ce document" */
export interface SherlockDocument {
  id: string
  name: string
  /** The index it came from: the question searches that index only */
  source: string
}

export const useSherlockStore = defineStore('sherlock', {
  state: () => ({
    questions: [] as SherlockQuestion[],
    loading: false,
    paneOpen: false,
    submitConfirmation: false,
    submitError: null as string | null,
    deleteError: null as string | null,
    /** Set while the pane asks about one document instead of the selected indexes */
    document: null as SherlockDocument | null,
    quota: null as SherlockQuota | null,
  }),

  actions: {
    // silent: background refresh, keeps the list on screen instead of the loading state
    async fetchQuestions(silent = false) {
      if (!silent) this.loading = true
      try {
        const r = await apiClient.get<SherlockQuestion[]>('/sherlock/questions?limit=20')
        this.questions = r.data
      } catch (e) {
        console.error('[Sherlock] Failed to fetch questions:', e)
      } finally {
        this.loading = false
      }
      // With the list: a failed question gives its place back, and midnight resets the count.
      await this.fetchQuota()
    },

    async fetchQuota() {
      try {
        const r = await apiClient.get<SherlockQuota>('/sherlock/quota')
        this.quota = r.data
      } catch {
        // non-blocking: the pane simply shows no count
      }
    },

    /**
     * @param indices indexes to search; [] means every index, omitted follows the parent question
     */
    async submitQuestion(text: string, parentId?: string | null, indices?: string[],
                         document?: { id: string; name: string } | null) {
      this.submitError = null
      try {
        await apiClient.post('/sherlock/questions', {
          question: text, parentId: parentId ?? null, indices: indices ?? null,
          documentId: document?.id ?? null, documentName: document?.name ?? null,
        })
        await this.fetchQuestions()
        this.submitConfirmation = true
        setTimeout(() => { this.submitConfirmation = false }, 6000)
      } catch (e: any) {
        const msg = e?.response?.data?.error ?? 'Une erreur est survenue. Veuillez réessayer.'
        this.submitError = msg
        setTimeout(() => { this.submitError = null }, 10000)
      }
    },

    async openPane() {
      this.paneOpen = true
      await this.fetchQuestions()
    },

    /** Opens the pane on one document: the next questions are about it until the chip is cleared */
    async askAbout(document: SherlockDocument) {
      this.document = document
      await this.openPane()
    },

    clearDocument() {
      this.document = null
    },

    closePane() {
      this.paneOpen = false
    },

    async deleteQuestion(id: string) {
      this.deleteError = null
      try {
        await apiClient.delete(`/sherlock/questions/${id}`)
        // Remove the deleted question and any orphaned children
        this.questions = this.questions.filter(q => q.id !== id && q.parentId !== id)
      } catch (e: any) {
        const status = e?.response?.status
        if (status === 404) {
          // Question no longer exists or its status changed — sync with server
          await this.fetchQuestions()
        } else {
          this.deleteError = 'La suppression a échoué. Veuillez réessayer.'
          setTimeout(() => { this.deleteError = null }, 8000)
        }
      }
    },

    async markSherlockNotificationsRead() {
      try {
        await apiClient.patch('/notifications/sherlock/read-all')
        const notifStore = useNotificationStore()
        notifStore.notifications.forEach(n => {
          if (n.notificationType === 'SHERLOCK_ANSWER') n.isRead = true
        })
        await notifStore.fetchUnreadCount()
      } catch {
        // non-blocking
      }
    },
  },
})
