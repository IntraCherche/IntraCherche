import { ref, computed, watch, type Ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '../stores/auth'

/** Before 3.3 every user of a browser shared this key, and saw the searches of whoever came before. */
const LEGACY_KEY = 'searchHistory'
const KEY_PREFIX = 'searchHistory:'
const HISTORY_MAX = 50
const SHOWN_MAX = 8

/** Lower case without accents, as the index compares words. */
const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

/**
 * Whether a past search completes what is being typed: every word typed starts a word of the
 * entry, so "tav aff" offers "suspect affaire Tavenna".
 */
export const completes = (entry: string, typed: string): boolean => {
  const entryWords = fold(entry).split(/[\s'’"()+-]+/).filter(Boolean)
  return fold(typed).split(/\s+/).filter(Boolean)
    .every(word => entryWords.some(entryWord => entryWord.startsWith(word)))
}

/**
 * The user's own past searches, offered as completions under the search bar.
 *
 * Kept per user: the key names the logged-in user, so whoever logs in next on the same
 * browser does not see what the previous user searched for. The searches never leave the
 * browser, and only ever offer what this user typed, never a word from the index.
 */
export function useSearchHistory(query: Ref<string>) {
  const { username } = storeToRefs(useAuthStore())
  const history = ref<string[]>([])

  const key = () => (username.value ? KEY_PREFIX + username.value : null)

  const load = () => {
    // Nobody can tell whose searches the shared key holds: drop it.
    localStorage.removeItem(LEGACY_KEY)
    const k = key()
    history.value = []
    if (!k) return
    try { history.value = JSON.parse(localStorage.getItem(k) || '[]') } catch { /* ignore */ }
  }

  const persist = () => {
    const k = key()
    if (!k) return
    if (history.value.length) localStorage.setItem(k, JSON.stringify(history.value))
    else localStorage.removeItem(k)
  }

  watch(username, load, { immediate: true })

  const matches = computed(() => {
    const typed = query.value.trim()
    const found = typed ? history.value.filter(h => h !== typed && completes(h, typed)) : history.value
    return found.slice(0, SHOWN_MAX)
  })

  const save = (q: string) => {
    const trimmed = q.trim()
    if (!trimmed || trimmed === '*:*') return
    history.value = [trimmed, ...history.value.filter(h => h !== trimmed)].slice(0, HISTORY_MAX)
    persist()
  }

  const remove = (item: string) => {
    history.value = history.value.filter(h => h !== item)
    persist()
  }

  const clear = () => {
    history.value = []
    persist()
  }

  return { matches, save, remove, clear }
}
