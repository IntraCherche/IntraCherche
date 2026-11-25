import { driver } from 'driver.js'
import 'driver.js/dist/driver.css'
import { useAuthStore } from '../stores/auth'

const TOUR_KEY = 'intracherche_tour_seen'

export function useTour() {
  const startTour = () => {
    const authStore = useAuthStore()
    const d = driver({
      showProgress: true,
      progressText: '{{current}} / {{total}}',
      nextBtnText: 'Suivant →',
      prevBtnText: '← Précédent',
      doneBtnText: 'Terminer',
      onDestroyed: () => {
        localStorage.setItem(TOUR_KEY, '1')
      },
      steps: [
        {
          element: '#tour-search-input',
          popover: {
            title: 'Barre de recherche',
            description:
              'Entrez vos termes et appuyez sur <strong>Entrée</strong>. ' +
              'Cliquez sur le bouton <strong>ℹ</strong> pour afficher tous les opérateurs disponibles : ' +
              'recherche exacte, joker, flou, exclusion de termes… Par défaut, plusieurs termes sont tous requis (ET).',
            side: 'bottom',
            align: 'start',
          },
        },
        {
          element: '#tour-advanced-toggle',
          popover: {
            title: 'Recherche avancée',
            description:
              'Affinez votre recherche par <strong>nom de fichier</strong>, ' +
              '<strong>localisation géographique</strong> (ville, département, région, pays) ' +
              'ou <strong>plage de dates</strong> avec des raccourcis pratiques.',
            side: 'bottom',
            align: 'start',
          },
        },
        {
          element: '#tour-sidebar',
          popover: {
            title: 'Sources',
            description:
              'Choisissez dans quels <strong>index</strong> chercher. ' +
              'Cochez ou décochez des sources, ou cliquez sur un compteur de résultats ' +
              'pour isoler une source et relancer la recherche automatiquement.',
            side: 'right',
            align: 'start',
          },
        },
        // Sherlock's step is dropped for users outside the Sherlock group: the FAB
        // isn't rendered for them, so there is nothing to point at and no reason to
        // advertise a feature they cannot use.
        ...(authStore.canUseSherlock
          ? [
              {
                element: '#tour-sherlock-fab',
                popover: {
                  title: 'Sherlock — Bibliothécaire virtuel',
                  description:
                    'Posez une question en <strong>langage naturel</strong> et obtenez une réponse ' +
                    'synthétique générée à partir de vos documents, avec les sources citées.',
                  side: 'left' as const,
                  align: 'end' as const,
                },
              },
            ]
          : []),
      ],
    })
    d.drive()
  }

  const startTourIfFirstVisit = () => {
    if (!localStorage.getItem(TOUR_KEY)) {
      setTimeout(startTour, 800)
    }
  }

  return { startTour, startTourIfFirstVisit }
}
