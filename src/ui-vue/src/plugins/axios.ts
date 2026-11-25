import axios from 'axios'
import { useAuthStore } from '../stores/auth'

// The default above is for small JSON calls. A document download is one request that carries the whole file
// (a textbook PDF of a hundred megabytes or more), and axios' timeout covers the entire transfer, not only
// the wait for the first byte: ten seconds cut off large files on any link slower than a LAN.
export const DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000

const apiClient = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
  // The anti-CSRF header is set by the interceptor below. Axios's own handling is off: it runs
  // after the interceptors and would overwrite the header with any plain XSRF-TOKEN cookie
  // left on this host (cookies ignore the port, so another app or an earlier HTTP run on
  // localhost is enough) — a stale token, and every POST got 403.
  withXSRFToken: false,
})

// Over HTTPS the anti-CSRF cookie is __Host-XSRF-TOKEN (the prefix stops another host or a
// plain-HTTP page from planting it); over plain HTTP it is XSRF-TOKEN. The __Host- one wins
// when both are present, since only it can be trusted to come from this server.
const readCookie = (name: string) =>
  document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))?.[1]

apiClient.interceptors.request.use((config) => {
  const token = readCookie('__Host-XSRF-TOKEN') ?? readCookie('XSRF-TOKEN')
  if (token) config.headers.set('X-XSRF-TOKEN', decodeURIComponent(token))
  return config
})

// The JWT is in an httpOnly cookie — the browser sends it automatically.
// No Authorization header interceptor needed.

// On 401: the session cookie has expired or is invalid. Clear local state and redirect.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const isLoginRequest = error.config?.url?.includes('/auth/login')
    if (error.response?.status === 401 && !isLoginRequest) {
      const authStore = useAuthStore()
      authStore.logout()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default apiClient
