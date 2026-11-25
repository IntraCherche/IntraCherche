import { defineStore } from 'pinia'
import apiClient from '../plugins/axios'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // The JWT itself lives in an httpOnly cookie managed by the browser.
    // We only track the username here so the UI can display it and know
    // that a session should be active.
    username: localStorage.getItem('user-name') || null as string | null,
    // What the header shows. The username stays the unique key (search history, quota); for a Google
    // visitor it is an id, and this is the readable "Visiteur 493C".
    displayName: localStorage.getItem('user-display-name') || null as string | null,
    // Cached so the cogwheel doesn't flash on reload before /auth/me answers.
    // Purely cosmetic: the backend re-checks group membership on every admin call.
    isAdmin: localStorage.getItem('user-is-admin') === 'true',
    // Defaults to true when uncached, mirroring the backend default where a blank
    // sherlock-group-sid opens Sherlock to everyone — so sessions predating this
    // flag keep their pane until /auth/me says otherwise.
    canUseSherlock: localStorage.getItem('user-can-sherlock') !== 'false',
  }),

  getters: {
    isAuthenticated: (state) => !!state.username,
  },

  actions: {
    setUser(user: string, isAdmin = false, canUseSherlock = true, displayName: string | null = null) {
      this.username = user;
      localStorage.setItem('user-name', user);
      this.displayName = displayName || user;
      localStorage.setItem('user-display-name', this.displayName);
      this.setPermissions(isAdmin, canUseSherlock);
    },
    setPermissions(isAdmin: boolean, canUseSherlock: boolean) {
      this.isAdmin = isAdmin;
      this.canUseSherlock = canUseSherlock;
      localStorage.setItem('user-is-admin', String(isAdmin));
      localStorage.setItem('user-can-sherlock', String(canUseSherlock));
    },
    /** Re-resolves the session server-side so group changes take effect on reload. */
    async refresh() {
      try {
        const r = await apiClient.get<{ username: string; displayName?: string; isAdmin: boolean; canUseSherlock: boolean }>('/auth/me')
        this.setUser(r.data.username, r.data.isAdmin, r.data.canUseSherlock, r.data.displayName)
      } catch {
        // Silent: a 401 is handled by the axios interceptor, and any other
        // failure just leaves the cached flags in place.
      }
    },
    /**
     * Picks up a session the store does not know about: the Google login ends on "/" with the session
     * cookie set and nothing stored here. A 401 just means there is no session, so it must not
     * trigger the interceptor's redirect (validateStatus keeps it out of the error path).
     */
    async restore(): Promise<boolean> {
      try {
        const r = await apiClient.get<{ username: string; displayName?: string; isAdmin: boolean; canUseSherlock: boolean }>(
          '/auth/me', { validateStatus: (status) => status < 500 })
        if (r.status !== 200) return false
        this.setUser(r.data.username, r.data.isAdmin, r.data.canUseSherlock, r.data.displayName)
        return true
      } catch {
        return false
      }
    },
    logout() {
      this.username = null;
      this.displayName = null;
      this.isAdmin = false;
      this.canUseSherlock = true;
      localStorage.removeItem('user-name');
      localStorage.removeItem('user-display-name');
      localStorage.removeItem('user-is-admin');
      localStorage.removeItem('user-can-sherlock');
    }
  }
})
