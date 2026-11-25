import { ref, watch, onBeforeUnmount, type Ref } from 'vue'
import apiClient from '../plugins/axios'

/** After a space the words before it are finished: ask soon. */
const AFTER_SPACE_MS = 300
/** Mid-word, wait for a real pause, or "march" would be corrected to "marche" on the way to "marchiha". */
const MID_WORD_MS = 800

/**
 * "Vouliez-vous dire…" while typing: the server's correction of what is in the search bar,
 * under the filters the search would run with.
 *
 * Asked on a pause only, and one request at a time: a new keystroke cancels the one in
 * flight. The answer is kept only if the bar still holds what was asked about. Nothing is
 * remembered: whoever logs in next in this page must not be shown a correction drawn from
 * documents only the previous user could read.
 */
export function useSpellingSuggestion(
  query: Ref<string>,
  filterParams: () => Record<string, string>,
  /** Whether the user is typing in the bar: a query set by a search or a replay is not asked about. */
  typing: Ref<boolean>,
) {
  const suggestion = ref<string | undefined>(undefined)
  let timer: ReturnType<typeof setTimeout> | undefined
  let inFlight: AbortController | undefined

  const cancel = () => {
    clearTimeout(timer)
    inFlight?.abort()
    inFlight = undefined
  }

  const ask = async (q: string) => {
    const params = { ...filterParams(), q }
    inFlight = new AbortController()
    try {
      const { data } = await apiClient.get<{ suggestion?: string }>('/spell', { params, signal: inFlight.signal })
      if (query.value.trim() === q) suggestion.value = data.suggestion
    } catch {
      // Cancelled, or the server failed: no suggestion is the right answer either way.
    }
  }

  watch(query, (typed) => {
    cancel()
    suggestion.value = undefined
    const q = typed.trim()
    if (!q || !typing.value) return
    timer = setTimeout(() => ask(q), /\s$/.test(typed) ? AFTER_SPACE_MS : MID_WORD_MS)
  })

  onBeforeUnmount(cancel)

  return { suggestion, cancel }
}
