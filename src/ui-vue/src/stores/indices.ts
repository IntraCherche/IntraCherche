import { defineStore } from 'pinia'
import apiClient from '../plugins/axios'

// The index selection is shared: the search sidebar and Sherlock's scope chip edit the same
// list, so a question asked from the pane searches what the user sees selected for search.
export const useIndicesStore = defineStore('indices', {
  state: () => ({
    availableIndices: [] as string[],
    selectedIndices: [] as string[],
    loaded: false,
  }),

  getters: {
    allSelected: (state) =>
      state.availableIndices.length > 0 &&
      state.availableIndices.every(i => state.selectedIndices.includes(i)),
  },

  actions: {
    async load() {
      if (this.loaded) return
      try {
        const response = await apiClient.get<string[]>('/indices')
        this.availableIndices = response.data
        // Default to selecting all indices; only on first load, so returning to the search
        // page doesn't wipe a selection made meanwhile (e.g. from the Sherlock pane)
        this.selectedIndices = [...response.data]
        this.loaded = true
      } catch (error) {
        console.error('Failed to load indices', error)
      }
    },
  },
})
