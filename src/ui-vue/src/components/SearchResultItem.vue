<script setup lang="ts">
import { computed, ref } from 'vue'
import apiClient, { DOWNLOAD_TIMEOUT_MS } from '../plugins/axios'
import DocTypeIcon from './DocTypeIcon.vue'
import type { SolrDocument } from '../types/SearchTypes'
import { useAuthStore } from '../stores/auth'
import { useSherlockStore } from '../stores/sherlock'

const props = defineProps<{ doc: SolrDocument; showGeo?: boolean }>()

const authStore = useAuthStore()
const sherlockStore = useSherlockStore()

const askSherlock = () => sherlockStore.askAbout({
  id: props.doc.parentId,
  name: props.doc.filename,
  source: props.doc.source,
})

const hasGeolocations = computed(() =>
  (props.doc.geolocationCities?.length ?? 0) +
  (props.doc.geolocationDepts?.length ?? 0) +
  (props.doc.geolocationRegions?.length ?? 0) +
  (props.doc.geolocationCountries?.length ?? 0) > 0
)

const geoExpanded = ref(false)
const toggleGeo = () => { geoExpanded.value = !geoExpanded.value }
const geoVisible = computed(() => props.showGeo || geoExpanded.value)

/**
 * Opens the document.
 * @param page - Optional page number to jump to. If null, opens at start.
 */
const openFile = async (targetId: string, page: number | null = null) => {
  try {
    const response = await apiClient.get(`download`, {
      params: { id: targetId },
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT_MS
    })

    const contentType = String(response.headers['content-type'] ?? '')
    const blob = new Blob([response.data], { type: contentType })
    let url = window.URL.createObjectURL(blob)

    const targetPage = page || 1
    if (contentType.includes('pdf')) {
      url += `#page=${targetPage}`
    }

    const viewableTypes = [
      'application/pdf',
      'text/plain',
      'image/jpeg',
      'image/png'
    ]

    const isViewable = viewableTypes.some(type =>
      contentType.startsWith(type)
    )

    if (isViewable) {
      const newTab = window.open('', '_blank')

      if (newTab) {
        const pageSuffix = page ? ` - Page ${page}` : ''
        newTab.document.title = `${props.doc.filename}${pageSuffix}`

        newTab.document.body.style.margin = '0'
        newTab.document.body.style.height = '100vh'

        const iframe = newTab.document.createElement('iframe')
        iframe.src = url
        iframe.style.width = '100%'
        iframe.style.height = '100%'
        iframe.style.border = 'none'

        newTab.document.body.appendChild(iframe)
      }
    } else {
      const link = document.createElement('a')
      link.href = url
      link.download = props.doc.filename
      document.body.appendChild(link)
      link.click()
      link.remove()
    }

    // cleanup (important!)
    setTimeout(() => URL.revokeObjectURL(url), 10000)
  } catch (error) {
    console.error('Download failed:', error)
    alert("Erreur lors de l'ouverture du fichier")
  }
}

// Safely render a Solr highlight snippet.
// Solr wraps matched terms in <b>...</b>. We need those tags but nothing else.
// Strategy: escape ALL HTML via textContent, then selectively restore bare <b> and <em>.
// This prevents any injected HTML in indexed document content from executing.
function sanitizeHighlight(raw: string): string {
  const div = document.createElement('div')
  div.textContent = raw
  return div.innerHTML.replace(/&lt;(\/?(?:b|em))&gt;/gi, '<$1>')
}

// proper URL builder (for semantics)
const getFileUrl = (id: string, page: number | null = null) => {
  let url = `/download?id=${encodeURIComponent(id)}`
  if (page) url += `#page=${page}`
  return url
}

// smart click handler
const handleLinkClick = (
  event: MouseEvent,
  id: string,
  page: number | null = null
) => {
  // Allow native browser behavior
  if (
    event.ctrlKey ||
    event.metaKey ||
    event.shiftKey ||
    event.button === 1
  ) {
    return
  }

  event.preventDefault()
  openFile(id, page)
}

const displayMtime = computed(() => {
  if (props.doc.mtime) {
    const date = new Date(props.doc.mtime)
    return date.toLocaleDateString('fr-FR', {
      day: '2-digit', month: 'short', year: 'numeric'
    }).replace('.', '')
  }
  return ''
})

/**
 * An .eml/.msg filename is usually the subject mangled by the mail client, or a bare
 * GUID when it was exported in bulk. The subject is what the operator recognises, so it
 * takes the title slot; the real filename stays visible on the path line below.
 */
const displayTitle = computed(() =>
  props.doc.emailSubject?.trim() || props.doc.filename || 'Document sans nom'
)

const displaySender = computed(() => props.doc.emailFrom?.join(', ') ?? '')

/** The sent date, with the time of day — for a message that is often the point. */
const displayEmailDate = computed(() => {
  if (!props.doc.emailDate) return ''
  return new Date(props.doc.emailDate).toLocaleString('fr-FR', {
    day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit'
  }).replace('.', '')
})

/** Only worth a header line if we actually got something out of the message. */
const hasEmailHeaders = computed(() =>
  Boolean(displaySender.value || displayEmailDate.value)
)

const isEmail = computed(() => props.doc.docType === 'mail')

/**
 * Translate the section labels EmailExtractor writes ("Headers", "Message 2",
 * "Attachment: devis.txt") for display. Anything else is shown as-is.
 */
const emailPartLabel = (label: string) => {
  if (label === 'Headers') return 'En-tête'
  const attachment = label.match(/^Attachment: (.+)$/)
  if (attachment) return `PJ : ${attachment[1]}`
  const message = label.match(/^Message(?: (\d+))?$/)
  if (message) return message[1] ? `Message ${message[1]}` : 'Message'
  return label
}
</script>

<template>
  <div class="result-card">
    <div class="result-header">
      <DocTypeIcon :type="doc.docType" />
      <a
        :href="getFileUrl(doc.id)"
        @click="handleLinkClick($event, doc.id)"
        class="doc-title-link"
      >
        {{ displayTitle }}
      </a>
      <span class="source-badge" v-if="doc.source">{{ doc.source }}</span>
      <span class="mtime" v-if="displayMtime">{{ displayMtime }}</span>
      <button
        v-if="authStore.canUseSherlock && doc.parentId"
        class="ask-document-btn"
        title="Poser des questions à Sherlock sur ce document uniquement"
        @click="askSherlock"
      >Interroger ce document</button>
    </div>

    <!-- Who sent it and when: for a message this identifies the hit far better than the path -->
    <div v-if="hasEmailHeaders" class="email-meta">
      <span v-if="displaySender" class="email-from" :title="displaySender">
        De : {{ displaySender }}
      </span>
      <span v-if="displaySender && displayEmailDate" class="email-sep">·</span>
      <span v-if="displayEmailDate" class="email-date">{{ displayEmailDate }}</span>
    </div>

    <div class="doc-path" :title="doc.path">{{ doc.path }}</div>

    <button
      v-if="hasGeolocations && !showGeo"
      class="geo-toggle-btn"
      @click="toggleGeo"
      :title="geoExpanded ? 'Masquer les lieux détectés' : 'Afficher les lieux détectés'"
    >lieux {{ geoExpanded ? '▴' : '▾' }}</button>

    <div v-if="hasGeolocations && geoVisible" class="geo-tags">
      <span v-for="city in doc.geolocationCities" :key="'city-' + city" class="geo-tag geo-city" title="Ville">{{ city }}</span>
      <span v-for="dept in doc.geolocationDepts" :key="'dept-' + dept" class="geo-tag geo-dept" title="Département">{{ dept }}</span>
      <span v-for="region in doc.geolocationRegions" :key="'region-' + region" class="geo-tag geo-region" title="Région">{{ region }}</span>
      <span v-for="country in doc.geolocationCountries" :key="'country-' + country" class="geo-tag geo-country" title="Pays">{{ country }}</span>
    </div>

    <div v-for="(snippet, index) in doc.snippets" :key="index" class="snippet-item">
      <div class="snippet-content">
        <a
          class="page-link-inline"
          :href="getFileUrl(snippet.id, snippet.page)"
          @click="handleLinkClick($event, snippet.id, snippet.page)"
          :title="
            doc.isSpreadsheet
              ? 'Feuille : ' + (snippet.sheetName || snippet.page)
              : isEmail && snippet.sheetName
                ? 'Partie du message : ' + snippet.sheetName
                : (doc.isPageApprox ? 'Environ page : ' : 'Page : ') + snippet.page
          "
        >
          <!-- Priority display: sheet name for Excel, message part for mail, page number otherwise.
               A page number means nothing for an email, but "Headers" or the attachment
               filename tells the operator where in the message the match sits. -->
          <template v-if="doc.isSpreadsheet && snippet.sheetName">
            [{{ snippet.sheetName }}]
          </template>
          <template v-else-if="isEmail && snippet.sheetName">
            [{{ emailPartLabel(snippet.sheetName) }}]
          </template>
          <template v-else>
            ({{ doc.isPageApprox ? '~' : '' }}p.{{ snippet.page }})
          </template>
        </a>
        <span class="snippet-text" v-html="sanitizeHighlight(snippet.text)"></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.result-card {
  margin-bottom: 32px;
  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
  text-align: left;
  max-width: 800px;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}

/* The type icon belongs to the title, not to the badges after it */
.result-header > .doc-type-icon {
  margin-right: -6px;
}

/* The clickable title */
.doc-title-link {
  color: #1a0dab; /* Classic search blue */
  font-size: 20px;
  font-weight: 400;
  text-decoration: none;
  display: inline-block;
  line-height: 1.2;
    cursor: pointer;
}

.doc-title-link:hover {
  text-decoration: underline;
  cursor: pointer;
}

.doc-title-link:visited {
  color: #609; /* Standard visited link color */
}

.doc-title-link:active {
  opacity: 0.85;
}

/* Sender and date, between the subject and the path */
.email-meta {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: #5f6368;
  margin-bottom: 2px;
  min-width: 0;
}

.email-from {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.email-sep {
  color: #bdc1c6;
}

.email-date {
  flex-shrink: 0;
  color: #70757a;
}

.doc-path {
  color: #006621; /* Green path color like old Google */
  font-size: 14px;
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.snippet-item {
  margin-top: 6px;
  font-size: 14px;
  color: #4d5156;
  line-height: 1.5;
  padding-left: 12px;
  border-left: 3px solid #eee; /* Subtle visual break for snippets */
}

.page-link-inline {
  color: #1a73e8;
  font-weight: 600;
  cursor: pointer;
  margin-right: 8px;
  background: #f1f3f4;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}

.page-link-inline:hover {
  background-color: #e8f0fe;
  color: #174ea6;
}

.source-badge {
  padding: 2px 8px;
  background-color: #e8f0fe;
  color: #1967d2;
  border-radius: 12px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
}

.mtime {
  color: #70757a;
  font-size: 13px;
}

/* Make sure the bold highlights from Solr look good */
:deep(b), :deep(em) {
  font-weight: 700;
  color: #202124;
  font-style: normal;
}

.geo-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.geo-tag {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 10px;
  font-weight: 500;
}

.geo-city    { background: #e6f4ea; color: #1e7e34; }
.geo-dept    { background: #e8f0fe; color: #1967d2; }
.geo-region  { background: #fce8e6; color: #c5221f; }
.geo-country { background: #fef3e2; color: #b06000; }

.geo-toggle-btn {
  display: inline-block;
  margin-bottom: 6px;
  background: none;
  border: 1px solid #dadce0;
  border-radius: 8px;
  padding: 1px 7px;
  font-size: 11px;
  color: #5f6368;
  cursor: pointer;
  line-height: 1.6;
}

.ask-document-btn {
  margin-left: auto;
  flex-shrink: 0;
  background: none;
  border: 1px solid #dadce0;
  border-radius: 12px;
  padding: 2px 10px;
  font-size: 12px;
  color: #1a73e8;
  cursor: pointer;
  white-space: nowrap;
}
.ask-document-btn:hover {
  background: #e8f0fe;
  border-color: #aecbfa;
}

.geo-toggle-btn:hover {
  background: #f1f3f4;
  border-color: #bdc1c6;
}
</style>
