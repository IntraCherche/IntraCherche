<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import apiClient from '../plugins/axios';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const loading = ref(false);
const errorMessage = ref('');
const form = reactive({ username: '', password: '' });

// What this server offers: the Google button (OpenID Connect, public demo) and the password form.
// The form is only hidden, never removed: /login?password brings it back for an administrator.
const options = reactive({ oidc: false, oidcUrl: '/oauth2/authorization/google', oidcLabel: 'Se connecter avec Google', passwordEnabled: true, passwordForm: true });
// When the administrator turned the password login off the server refuses it, so the form is never offered.
const showPasswordForm = computed(() => options.passwordEnabled && (options.passwordForm || route.query.password !== undefined));

onMounted(async () => {
  if (route.query.logoutError !== undefined) {
    errorMessage.value = "La déconnexion n'a pas pu être confirmée par le serveur : votre session est peut-être encore ouverte. Fermez le navigateur ou réessayez.";
  }
  if (route.query.oidcError !== undefined) {
    // A refused single sign-on must not leave the previous user signed in behind this page: end that session
    // on the server (this request carries its cookie, the refused callback did not) and forget it here.
    authStore.logout();
    try { await apiClient.post('/auth/logout'); } catch { /* no session to end */ }
    errorMessage.value = "La connexion unique a échoué ou n'est pas autorisée. Veuillez réessayer ou contacter votre administrateur.";
  }
  try {
    const r = await apiClient.get<{ oidc: boolean; oidcUrl?: string; oidcLabel?: string; passwordEnabled?: boolean; passwordForm: boolean }>('/auth/options');
    options.passwordEnabled = r.data.passwordEnabled !== false;
    options.oidc = r.data.oidc === true;
    if (r.data.oidcUrl) options.oidcUrl = r.data.oidcUrl;
    if (r.data.oidcLabel) options.oidcLabel = r.data.oidcLabel;
    options.passwordForm = r.data.passwordForm !== false;
  } catch {
    // An older server has no such route: the password form is all it has.
  }
});

// Login is the one call that may legitimately outlast the 10 s default: resolving a user's
// AD group SIDs is a cache miss by definition on the first login after a restart. The
// default timeout aborted the request while the server was still succeeding, and the user
// saw a generic error with nothing in the log to explain it.
const LOGIN_TIMEOUT_MS = 30000;

const onLogin = async () => {
  loading.value = true;
  errorMessage.value = '';

  try {
    // 1. Call your Spring Boot API
    const response = await apiClient.post('/auth/login', form, { timeout: LOGIN_TIMEOUT_MS });

    // 2. Update Global State (Pinia)
    // Backend sets the JWT as an httpOnly cookie; we only store the username here.
    authStore.setUser(
      response.data.username,
      response.data.isAdmin === true,
      response.data.canUseSherlock !== false,
      response.data.displayName,
    );

    // 3. Redirect to the Search page
    router.push('/');
  } catch (err: any) {
    // Say which of the three went wrong — wrong password, too many tries, or a server that
    // never answered. They call for different actions, and lumping them under one message
    // leaves the user and the administrator with nothing to go on.
    if (err.response?.status === 401) {
      errorMessage.value = "Identifiants invalides. Veuillez réessayer.";
    } else if (err.response?.status === 403) {
      errorMessage.value = err.response.data?.message || "La connexion par mot de passe n'est pas autorisée.";
    } else if (err.response?.status === 429) {
      errorMessage.value =
        err.response.data?.message ||
        "Trop de tentatives de connexion. Veuillez réessayer dans une minute.";
    } else if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
      errorMessage.value =
        "Le serveur met trop de temps à répondre. Réessayez dans un instant ; " +
        "si cela se reproduit, signalez-le à votre administrateur.";
    } else if (!err.response) {
      errorMessage.value = "Serveur injoignable. Vérifiez votre connexion au réseau.";
    } else {
      errorMessage.value = "Une erreur inattendue s'est produite.";
    }
  } finally {
    loading.value = false;
  }
};
</script>


<template>
  <div class="login-wrapper">
    <div class="login-card">
      <div class="login-brand">
        <img src="/Logo-IntraCherche-HighDef.png" alt="IntraCherche" class="login-logo" />
        <span class="login-name">IntraCherche</span>
      </div>
      <a v-if="options.oidc" class="sso-button" :href="options.oidcUrl">
        {{ options.oidcLabel }}
      </a>
      <form v-if="showPasswordForm" @submit.prevent="onLogin">
        <div class="form-group">
          <label>Nom d'utilisateur</label>
          <input v-model="form.username" type="text" required placeholder="ex. jdoe" />
        </div>

        <div class="form-group">
          <label>Mot de passe</label>
          <input v-model="form.password" type="password" required />
        </div>

        <button type="submit" :disabled="loading" title="Utilisez vos identifiants de connexion Windows habituels">
          {{ loading ? 'Connexion en cours…' : 'Se connecter' }}
        </button>
      </form>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      <p v-if="showPasswordForm" class="hint">Utilisez vos identifiants de connexion Windows habituels</p>
    </div>
  </div>
</template>


<style scoped>
.login-wrapper { height: 80vh; display: flex; align-items: center; justify-content: center; }
.login-card { width: 100%; max-width: 400px; padding: 2rem; border: 1px solid #eee; box-shadow: 0 4px 6px rgba(0,0,0,0.1); border-radius: 8px; }
.login-brand { display: flex; align-items: center; justify-content: center; gap: 12px; margin-bottom: 1.5rem; }
.login-logo { height: 48px; width: auto; }
.login-name { font-size: 28px; font-weight: 800; background: linear-gradient(to right, #4285F4, #EA4335, #FBBC05, #34A853); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
.form-group { margin-bottom: 1rem; }
label { display: block; margin-bottom: 0.5rem; font-weight: bold; }
input { width: 100%; padding: 0.75rem; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box; }
button { width: 100%; padding: 0.75rem; background: #007bff; color: white; border: none; border-radius: 4px; cursor: pointer; }
button:disabled { background: #ccc; }
.sso-button { display: block; box-sizing: border-box; width: 100%; padding: 0.75rem; margin-bottom: 1rem; text-align: center; text-decoration: none; color: #3c4043; background: #fff; border: 1px solid #dadce0; border-radius: 4px; font-weight: 600; }
.sso-button:hover { background: #f8f9fa; }
.error { color: #d9534f; margin-top: 1rem; font-size: 0.9rem; }
.hint { margin-top: 1.25rem; font-size: 0.78rem; color: #aaa; text-align: center; font-style: italic; }
</style>