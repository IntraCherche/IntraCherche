import { ref, watch, onBeforeUnmount, type Ref } from 'vue'
import apiClient from '../plugins/axios'

/** Completions follow the typing closely: a short pause is enough. */
const PAUSE_MS = 150
/** The server completes no shorter word (PrefixCompletion.MIN_PREFIX_LENGTH). */
const MIN_PREFIX_LENGTH = 3

/**
 * Completions of the word being typed at the end of the search bar, drawn by the server from
 * the pages the user can read, under the filters the search would run with.
 *
 * Asked on a pause only, and one request at a time: a new keystroke cancels the one in
 * flight. The answer is kept only if the bar still holds what was asked about. Nothing is
 * remembered: whoever logs in next in this page must not be shown a word drawn from documents
 * only the previous user could read.
 */
export function usePrefixCompletion(
  query: Ref<string>,
  filterParams: () => Record<string, string>,
  /** Whether the user is typing in the bar: a query set by a search or a replay is not asked about. */
  typing: Ref<boolean>,
) {
  const completions = ref<string[]>([])
  let timer: ReturnType<typeof setTimeout> | undefined
  let inFlight: AbortController | undefined

  const cancel = () => {
    clearTimeout(timer)
    inFlight?.abort()
    inFlight = undefined
  }

  const ask = async (q: string) => {
    inFlight = new AbortController()
    try {
      const { data } = await apiClient.get<{ completions?: string[] }>('/complete', {
        params: { ...filterParams(), q },
        signal: inFlight.signal,
      })
      if (query.value === q) completions.value = data.completions ?? []
    } catch {
      // Cancelled, or the server failed: no completion is the right answer either way.
    }
  }

  watch(query, (typed) => {
    cancel()
    completions.value = []
    // Only a word still being typed: once a space follows it, it is finished.
    const word = typed.match(/\S+$/)?.[0].replace(/^[+-]/, '') ?? ''
    if (word.length < MIN_PREFIX_LENGTH || !typing.value) return
    timer = setTimeout(() => ask(typed), PAUSE_MS)
  })

  onBeforeUnmount(cancel)

  return { completions, cancel }
}
