# Changelog

Toutes les modifications notables d'IntraCherche sont consignées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
projet applique le [versionnage sémantique](https://semver.org/lang/fr/).

## [3.4.4] — 2026-11-16

### Ajouté

- Le panneau d'administration a un onglet Configuration : un diagnostic des fichiers `application.yaml` et `indexes.yaml` (réglage inconnu, indentation, valeur invalide), un éditeur des index avec test des partages, vérification des groupes et sauvegarde de l'ancienne version, des réglages courants et avancés expliqués, vérifiés avant enregistrement, un onglet Mémoire (tas Java, tas Solr, réserve) dont les curseurs sont bornés par la mémoire du serveur, un courrier d'alerte (serveur SMTP, compte, mot de passe) réglable dans les réglages courants, avec un bouton d'envoi d'un message d'essai, et un onglet Annuaire dont la connexion est testée avant tout enregistrement. Des tests vérifient que chaque réglage proposé est bien lu par l'application et relu tel quel après enregistrement.

### Corrigé

- Sans serveur de courrier configuré, l'application démarre et n'envoie simplement pas de courriel : les alertes restent visibles dans la cloche.
- Un dépassement de mémoire Java arrête le service, que systemd relance aussitôt, au lieu de le laisser actif mais coupé de Solr.
- Un fichier dont la lecture arrête deux fois l'application faute de mémoire n'est plus relu à chaque redémarrage : il est lu seul après un arrêt, puis indexé par son nom seulement, avec dans le panneau d'administration la mémoire conseillée ; relevée, elle le fait relire.
- Le nettoyage des fichiers disparus, quand ils sont plus de 1 024, ne fait plus échouer le cycle d'indexation (Solr refusait la requête de suppression : trop de clauses).

### Modifié

- Le `application.yaml` généré commence par un bloc « à renseigner d'abord » (annuaire et groupe d'administration, en clés à plat sans indentation) ; le panneau d'administration le réécrit de la même façon.
- L'installateur donne au compte du service le droit d'écrire dans `/etc/intracherche` (fichiers toujours en `640`) pour que le panneau d'administration enregistre la configuration ; chaque fichier réécrit garde les droits de l'ancien et une sauvegarde est conservée.
- IntraCherche est publié sous licence EUPL 1.2 : le fichier de licence `license.jwt`, la limite de trois résultats et les bandeaux d'expiration disparaissent.
- La connexion OpenID Connect est en mode `directory` par défaut ; le mode de démonstration doit être demandé explicitement.

## [3.4.3] — 2026-11-08

### Corrigé

- L'ouverture d'un document volumineux n'échoue plus au bout de dix secondes.

## [3.4.2] — 2026-11-06

### Corrigé

- Un index actif apparaît dans la liste dès le début du cycle d'indexation, même si un index précédent est encore en cours de traitement.

### Modifié

- `/api/auth/me` indique si un groupe d'administration est configuré et si la session le porte, pour diagnostiquer l'absence du panneau d'administration.

## [3.4.1] — 2026-11-06

### Corrigé

- La recherche ne renvoie plus d'erreur pour un utilisateur membre de très nombreux groupes.

### Modifié

- La connexion consigne dans le journal le nombre de groupes de l'utilisateur, sans son nom.
- La notice des licences tierces est à jour (versions de Solr et de Qwen, JRE, bibliothèques LGPL/CDDL, junrar, CUDA).

## [3.4.0] — 2026-10-17

### Ajouté

- Le guide d'installation retrouve la JVM du service en deux commandes : la version sur plusieurs lignes provoquait une erreur de syntaxe affichée à chaque collage dans un terminal.
- Le guide d'installation dit quelle méthode d'import du certificat de l'AC convient à un Active Directory Microsoft et laquelle à un Samba AD.
- La documentation d'installation est désormais fournie en PDF (et en ODT), à côté de `INSTALL.md`, qui reste la source à copier pour les commandes.
- L'archive livre aussi `CHANGELOG.md`, l'historique des changements de chaque version.
- Le guide d'installation regroupe les valeurs à adapter (contrôleur de domaine, DN de base, compte de liaison) dans un bloc de variables, et les commandes LDAPS et de dépannage s'y appuient.
- L'installateur avertit quand la mémoire du serveur ne suffit pas pour Sherlock en local, avec les trois remèdes.
- Connexion avec Google pour la démo publique (`auth.oidc`, désactivée par défaut, réservée à la licence de démo) : chaque visiteur a son propre quota Sherlock et son propre historique de questions.
- Connexion unique (OpenID Connect) pour une entreprise : l'identité donnée par le fournisseur (courriel, UPN…) est retrouvée dans un attribut de l'annuaire et ouvre la session du compte correspondant (`auth.oidc.mode: directory`, désactivée par défaut).
- `auth.password.enabled: false` supprime la connexion par mot de passe ; l'application refuse de démarrer si la connexion unique n'est pas activée, et il n'y a pas de compte de secours.
- Le guide d'installation décrit la connexion unique, ses messages de refus et la reprise en cas de panne du fournisseur.

### Corrigé

- Le service de reranking passe à `fsspec` 2026.6.0, qui corrige une faille d'injection de gabarit.
- Un `application.yaml` recopié d'un autre serveur dont le mot de passe ne correspond pas au certificat HTTPS de celui-ci donne un message d'arrêt qui explique la cause et la commande à lancer, au lieu d'une trace.
- Quand personne ne peut se connecter parce que l'annuaire est injoignable ou que son certificat LDAPS n'est pas approuvé, le journal le dit en clair et renvoie au guide, au lieu d'une simple erreur de connexion fermée.
- Le message d'arrêt « mémoire insuffisante » dit quand la réservation seule dépasse la mémoire (Sherlock sur un petit serveur) et propose de le désactiver, au lieu de conseils sur le nombre de workers qui ne changent rien.
- Le modèle `indexes.yaml` et le guide ne présentent plus que les partages SMB, avec leur compte de service, et plus les champs `windowsPath` et `linuxPath`, sans effet.
- Un chemin d'API inconnu répond 404 au lieu de 500, et n'écrit plus de trace d'erreur dans le journal.
- Un fichier illisible (vide, corrompu) n'est plus relu à chaque cycle : il attend que sa taille ou sa date change.
- Le journal n'écrit plus une ligne par fichier à chaque cycle d'indexation.

## [3.3.1] — 2026-10-07

### Corrigé

- Un très gros document n'est plus envoyé à Solr en une seule requête, mais par lots de 100 pages, et l'indexation a son propre client Solr : un Solr lent ne bloque plus la recherche ni la liste des index.
- Une panne passagère de Solr pendant l'indexation est retentée deux fois avant que le fichier soit déclaré en échec.
- Un document dont le texte met longtemps à être vectorisé n'échoue plus à l'écriture dans Solr (« Received RST_STREAM ») : les clients Solr n'utilisent plus que HTTP/1.1.
- Les pages d'un fichier scanné ne sont plus envoyées à un index Solr qui n'existe pas encore : l'index est créé avant la lecture du fichier.
- Avec plusieurs workers d'embedding, tous les appels de l'indexation tombaient sur le même : chaque appel ouvre désormais sa propre connexion et les workers se partagent le travail.
- Une mise à jour ne remet plus Ollama en marche quand Sherlock est désactivé (`llm.enabled: false`).

### Modifié

- L'installateur règle le tas de Solr d'après la mémoire du serveur (1 à 4 Go, au lieu de 512 Mo), et le calcul de la mémoire des services le prend en compte.
- Quand une écriture dans Solr échoue par dépassement de délai alors que son tas est trop petit ou presque plein, le journal le dit et renvoie à la section du guide d'installation qui explique la correction.
- Le texte gardé par fichier passe de 20 à 50 millions de caractères : un gros ouvrage n'est plus coupé à mi-parcours. Les fichiers déjà coupés sont réindexés au redémarrage.
- Quand un fichier dépasse cette limite, le message du panneau d'administration dit combien de texte a été gardé et quel réglage relever.
- Sherlock sur processeur : le nombre de threads d'Ollama se règle seul (`llm.numThread`, les cœurs moins 2 à partir de 6 cœurs) et le délai par défaut (`llm.readTimeoutSeconds`) passe de 600 à 1800 s.
- Quand Sherlock dépasse son délai, le journal nomme le réglage à relever, et le guide d'installation cite le message réellement écrit.
- Le guide d'installation exige 8 cœurs physiques au minimum et donne des temps mesurés pour Sherlock sur processeur, au lieu de « 1 à 3 minutes ».

## [3.3.0] — 2026-10-02

### Ajouté

- « Vouliez-vous dire… » : lorsqu'aucun document ne contient tous les mots
  recherchés parce que l'un d'eux est mal orthographié, la recherche propose la
  bonne orthographe, en un clic. Seuls sont proposés
  des mots présents dans des documents que l'utilisateur a le droit de lire.

- L'historique de recherche complète ce qui est tapé : chaque mot saisi peut
  être le début d'un mot d'une recherche passée, sans tenir compte des accents
  ni des majuscules, et les flèches du clavier permettent de choisir.

- Sherlock cite ses sources mot pour mot et signale toute citation absente de la page citée.

- « Interroger ce document » : depuis un résultat, Sherlock répond sur ce seul document, lu en entier.

- Sherlock corrige l'orthographe des questions et affiche la correction.

- La machine GPU lit aussi les pages numérisées : environ 0,6 seconde par
  page au lieu de 17 sur un cœur de processeur. Si elle ne répond pas, le
  serveur les lit lui-même, plus lentement, et la reprend dès qu'elle répond.
  Une machine GPU déjà installée ne lit les pages qu'après une nouvelle
  installation de la machine GPU ; elle occupe alors environ 2 Go de plus sur la
  carte (13,5 Go en tout avec le modèle actuel).

- `application.yaml.reference`, installé à côté de `application.yaml`, décrit tous les réglages.

- L'installation de la machine GPU vérifie l'espace disque avant de télécharger et ne garde plus ses téléchargements.

- Machine GPU partagée : quand ses services sont arrêtés, Sherlock répond sur le serveur au lieu de refuser les questions.

- Quota quotidien de questions à Sherlock par utilisateur, en trois niveaux attribués par groupe AD.

- `visibleToGroups`, obligatoire dans `indexes.yaml`, réserve un index aux membres de groupes AD : les autres ne le voient plus dans la colonne Sources et ne peuvent plus l'interroger (l'installateur règle les index existants sur `ALL`).

### Corrigé

- Le réglage `indexing-config.cycleIntervalMinutes` était ignoré : il est désormais pris en compte. `commitWithinSeconds`, jamais appliqué, est retiré (délai fixe de 30 s).
- Les numéros de page des documents Word et OpenDocument redeviennent une estimation de la page réelle (réindexation nécessaire).
- Le panneau d'administration garde les pages/min texte et scan de la dernière indexation, même après un redémarrage.

- Les pages/min de chaque index sont mesurées sur son propre temps, et les pages scan/min sur le temps réel de l'OCR, quel que soit le nombre de workers.

- Un index réglé avec `ocr: false` n'applique plus l'OCR à ses PDF numérisés.

- Quand le service d'embedding, d'OCR ou de NER est arrêté, l'indexation attend son retour, signalé en tête du panneau d'administration, au lieu d'indexer des fichiers sans vecteurs, sans pages numérisées ou sans lieux.

- Après une coupure du partage SMB, l'indexation se reconnecte au lieu de compter chaque fichier restant en erreur.

- Un partage de plusieurs milliers de fichiers n'est plus signalé « Source inaccessible » à chaque passage alors qu'il a été lu.

- Les réponses de Sherlock affichent leurs listes et leur gras au lieu des astérisques.

- La machine GPU s'installe sur toute carte NVIDIA de 16 Go, des Pascal aux RTX 50xx.

- Le texte lu par l'OCR dans les PDF numérisés collait les mots entre eux
  (« ReunionduBureauCommunautairedu11mai2026 »), perdait les accents et
  confondait certains caractères (« I'ordre », « 20o0 »), si bien que ces
  documents échappaient à la plupart des recherches. L'OCR utilise désormais
  des modèles faits pour l'écriture latine, qui lisent aussi les paragraphes
  entiers que l'ancien modèle de détection sautait sur certaines pages. Un
  processus OCR n'occupe plus qu'un cœur, comme prévu, au lieu de tous. Les
  documents déjà indexés gardent l'ancien texte jusqu'à leur réindexation.

- Certains PDF indexaient coupés les mots contenant « tt », « ft », « Qu » ou « Th » (réindexation nécessaire).

- La répartition automatique de la mémoire comptait environ 0,2 Go par
  processus OCR, alors qu'un processus en atteint environ 1,3 Go pendant la
  lecture d'une page : sur un serveur juste en mémoire, plusieurs pages
  numérisées lues en même temps pouvaient épuiser la mémoire. Le calcul
  retient désormais 1,3 Go, et un tel serveur lance moins de processus OCR.

- L'historique de recherche était commun à tous les utilisateurs d'un même
  navigateur : sur un poste partagé, chacun voyait les recherches du précédent.
  Il est désormais propre à chaque utilisateur. L'ancien historique commun est
  effacé, faute de pouvoir dire à qui il appartenait.

- Un fichier pouvait manquer aux résultats de recherche tout en figurant comme
  indexé, et n'était alors plus jamais repris : cela se produisait lorsque
  IntraCherche s'arrêtait ou redémarrait en pleine indexation, ou lorsque
  l'index refusait l'écriture. Un fichier n'est désormais compté comme indexé
  qu'une fois réellement enregistré dans l'index ; à défaut, il est repris au
  cycle suivant.

- Le débit « Pages scan/min » du panneau d'administration comptait aussi les
  documents texte comme des pages numérisées, et surestimait donc le rythme de
  l'OCR.

- Une faute de frappe dans le statut d'un index (par exemple `DELETED` au lieu
  de `DELETE`) le laissait actif sans rien signaler, à l'inverse de ce qui
  était écrit. Un statut inconnu met désormais l'index en pause et le consigne
  dans le journal, jusqu'à ce qu'il soit corrigé.

- `llm.enabled: false` ne désactivait que les appels au modèle de langage : le
  volet Sherlock restait affiché et les questions étaient acceptées, puis
  répondues par « (Service LLM désactivé) ». Sherlock est désormais fermé à
  tous les utilisateurs, et le service de reclassement, qui ne sert qu'à lui,
  n'est plus démarré : la mémoire qu'il occupait revient à l'indexation.

- Un index en `status: DELETE` sans `confirmDelete: true` continuait d'être
  alimenté comme s'il était actif. Il est désormais mis en pause, et le journal
  le signale, jusqu'à ce que la suppression soit confirmée.

### Sécurité

- Un fichier de plus de 256 Mo n'est plus lu (il reste trouvable par son nom) et le texte gardé par fichier est limité à 20 millions de caractères ; relever ces limites réindexe les fichiers concernés.
- Une nouvelle installation sert IntraCherche en HTTPS (port 8443) avec un certificat auto-signé ; le port 8080 redirige vers HTTPS.
- `generate-certificate.sh` renouvelle le certificat ou y ajoute un nom de serveur.
- Le navigateur applique une politique de sécurité du contenu (CSP), et les blocages sont consignés dans le journal.
- En HTTPS, les cookies de session et anti-CSRF portent le préfixe `__Host-` et l'attribut SameSite=Strict.
- La déconnexion révoque la session sur le serveur : une copie du jeton de session ne fonctionne plus.
- Une session prend fin lorsque le compte est désactivé, supprimé ou expiré dans l'Active Directory, ou que son mot de passe change.
- Les utilisateurs connectés avant la mise à jour doivent se reconnecter une fois.
- Le journal d'audit consigne connexions, échecs de connexion, déconnexions, sessions interrompues et refus d'accès.
- Les modèles d'embedding et de reranking sont téléchargés à une version figée, identique en développement et dans la distribution.
- Les composants Python sont mis à jour pour corriger des vulnérabilités connues ; les vecteurs d'embedding sont inchangés, sans réindexation.

## [3.2.0] — 2026-09-22

### Corrigé

- La connexion échouait sur « Une erreur inattendue s'est produite » alors que le
  mot de passe était bon : sur un annuaire où l'utilisateur appartient à de
  nombreux groupes imbriqués, la lecture de ses droits dans l'Active Directory
  demandait une vingtaine de secondes et le navigateur abandonnait avant la
  réponse. Ces droits sont désormais lus en une seule interrogation de
  l'annuaire, puis conservés quelques minutes, ce qui ramène la connexion —
  et toute recherche, qui les relisait elle aussi — sous la seconde.

  Un utilisateur peut de ce fait voir des documents qui lui étaient jusqu'ici
  cachés à tort : ceux dont les droits sont accordés à son groupe principal
  (« Utilisateurs du domaine ») ou à un groupe intégré du domaine, que
  l'ancienne lecture omettait. Il s'agit bien des droits que Windows lui
  reconnaît sur le fichier.

  Le délai au bout duquel un changement de groupe dans l'annuaire prend effet
  se règle par `security.sidCacheTtlMinutes` (15 minutes par défaut, 0 pour
  relire l'annuaire à chaque requête).

- Lorsque le serveur tarde à répondre ou reste injoignable, la page de
  connexion le dit, au lieu d'annoncer une erreur inattendue quelle que soit
  la cause.

### Ajouté

- La durée pendant laquelle un utilisateur reste connecté sans ressaisir son
  mot de passe se règle désormais (`jwt.expirationMinutes`, 24 heures par
  défaut). Elle valait 24 heures en dur, inscrites à deux endroits qu'un
  commentaire suffisait à garder d'accord ; le jeton et le cookie la prennent
  maintenant au même endroit et ne peuvent plus diverger.

  Cette durée est aussi celle pendant laquelle un compte désactivé dans
  l'annuaire continue de fonctionner : le guide d'installation indique comment
  l'abaisser, et comment révoquer un accès sans attendre.

- Sherlock pouvait prêter à une personne des faits qu'un document énonce d'une
  autre ; ses réponses, y compris celles des questions de suivi, s'en tiennent
  désormais au sujet de la question posée.

- Certains réglages désactivaient en silence le reclassement des passages de
  Sherlock ; il suit désormais la configuration, et tout réglage impossible à
  honorer est refusé explicitement et consigné dans le journal.

### Modifié

- Une question posée à Sherlock exige désormais l'essentiel de ses termes au
  lieu de se contenter de l'un d'eux : les pages hors sujet ne viennent plus
  occuper la place de celle qui porte la réponse, et aucune réponse n'est
  perdue lorsque aucun document ne réunit assez de termes.

- Le reclassement des passages peut être réglé pour tirer parti d'une carte
  graphique lorsque le serveur en possède une.

## [3.1.0] — 2026-09-18

### Ajouté

- Les messages électroniques sont désormais indexés, aux formats `.eml` et
  `.msg`, avec leur sujet, leurs correspondants, leur date, le corps du message
  et le texte des pièces jointes ; un index dont la configuration fixe
  `allowedExtensions` doit y ajouter les deux extensions.

  Cette version ajoute des champs au schéma Solr : le ré-indexage complet doit
  être lancé une fois depuis le panneau d'administration, faute de quoi aucun
  message ne peut être indexé.

- Chaque ligne de résultat porte une icône indiquant le type du fichier, juste
  même pour un fichier mal nommé ou sans extension, sans ré-indexage.

- Le panneau d'administration permet de ré-indexer la totalité des fichiers,
  derrière une phrase de confirmation et une inscription au journal d'audit ;
  l'action n'est utile qu'après une mise à jour qui le précise.

- Sherlock ne cherche plus que dans les index sélectionnés, qu'une pastille
  affiche au-dessus de la zone de saisie et qu'une réponse restreinte permet
  d'élargir d'un clic.

### Modifié

- Le nombre de recherches par utilisateur est désormais plafonné
  (`search.rateLimitPerMinute`, 0 pour désactiver), bien au-dessus d'un usage
  normal, afin qu'un script emballé ne puisse plus ralentir le service des
  autres utilisateurs ni bloquer l'indexation.

- Le PDF scanné du banc d'essai est désormais rangé dans le dossier des données
  (`/var/lib/intracherche`) ; un PDF préparé avec une version antérieure est à
  préparer de nouveau.

- L'engrenage du panneau d'administration et le volet Sherlock ne sont plus
  affichés qu'aux membres des groupes autorisés, dont l'appartenance est
  résolue côté serveur ; l'accès lui-même était déjà refusé aux autres.

- Dans la recherche avancée, les filtres Département, Région et Pays proposent
  une liste de choix filtrable au clavier, ce qui supprime les recherches sans
  résultat dues à une faute de frappe ; le filtre Ville reste en saisie libre.

- Le bandeau qui annonce l'élargissement automatique de la recherche précise
  désormais que les filtres de la recherche avancée restent appliqués, ce que
  rappelle également l'aide des opérateurs ; le comportement est inchangé.

- La recherche trouve désormais un document dont les mots demandés sont
  répartis sur plusieurs pages, et ne s'élargit qu'à défaut ; les recherches
  employant des parenthèses, `AND`, `OR` ou un champ gardent l'ancien
  comportement.

- Un terme exclu (`-mot` ou `NOT mot`) écarte désormais tout le document dès
  qu'une de ses pages le contient, hors recherches à parenthèses, `AND`, `OR`
  ou champ.

- Les extraits affichés sous un résultat sont choisis pour montrer, ensemble,
  le plus possible des mots recherchés.

### Corrigé

- Sur un serveur neuf, les services d'analyse ne démarraient pas faute de
  dossier personnel pour l'utilisateur `intracherche` ; une nouvelle exécution
  de l'installateur corrige une installation existante.

- Sur un serveur sans accès à Internet, le service d'entités nommées ne
  démarrait pas ; les services démarrent désormais sur l'environnement installé
  tel quel, sans aucun accès au réseau.

- Le service d'OCR ne démarrait pas sur un serveur dépourvu de la bibliothèque
  graphique `libGL`, absente de la plupart des serveurs ; il n'en dépend plus.

- Une feuille de calcul OpenDocument (`.ods`) mise en forme sur toute sa largeur
  pouvait épuiser la mémoire de l'application et faire échouer l'indexation des
  fichiers traités au même moment.

- Les fichiers texte que l'analyseur reconnaît comme des données tabulaires
  (`.csv`, et certains `.txt`) n'étaient pas indexés.

- Pendant une indexation importante, la recherche pouvait ne rien afficher ;
  recherches et questions passent désormais devant l'indexation, l'indexation
  s'efface lorsque le processeur est saturé, et une recherche qui n'aboutit pas
  affiche la raison de l'échec ou prévient, par un bandeau, que les résultats
  peuvent différer des résultats habituels.

- Sherlock ne tenait pas compte des passages trouvés par la seule recherche
  sémantique.

- Un même fichier indexé dans deux index occupait deux des passages transmis à
  Sherlock, au détriment d'un passage utile.

- La recherche sémantique et Sherlock ignoraient les pages des documents
  scannés, qui ne remontaient que par leurs mots exacts ; les documents scannés
  indexés avant cette version restent dans cet état tant qu'ils n'ont pas été
  ré-indexés.

- Sherlock n'utilisait jamais le reclassement de ses extraits sur un serveur
  sans carte graphique, et retenait parfois les extraits sans le bon passage.

- Une question de suivi Sherlock n'accepte plus comme question d'origine que
  l'une des questions de l'utilisateur lui-même.

- À la connexion, l'engrenage et le volet Sherlock n'apparaissaient qu'après un
  rechargement de la page, même pour les membres des groupes autorisés.

- Le tableau des index du panneau d'administration compte désormais les
  fichiers et non les pages.

- Une recherche portant sur un mot de moins de 3 caractères ou de plus de 20
  n'affichait aucun extrait, voire ne trouvait rien sans guillemets ; les index
  constitués avant cette version gardent l'ancien comportement tant qu'ils n'ont
  pas été ré-indexés.

- Un fichier dont l'indexation échouait était marqué comme indexé et jamais
  retenté ; il reste désormais en attente et repasse au cycle suivant, et les
  fichiers laissés en échec par l'ancien comportement sont remis en file
  d'attente une fois au premier démarrage en 3.1.0.

- La suppression d'un index laissait ses fichiers dans le registre sans index,
  retentés à chaque cycle ; les registres constitués avant 3.1.0 sont nettoyés
  une fois au démarrage, sans jamais toucher aux partages momentanément
  injoignables.

- Deux serveurs exposant un partage de même nom se confondaient, et les fichiers
  d'un serveur étaient lus à l'adresse de l'autre.

- Le résumé du dernier cycle du panneau d'administration n'affiche plus les
  index en attente de suppression, et le registre signale le nombre de fichiers
  n'appartenant à aucun index.

- Le panneau d'administration n'affichait aucun index tant que rien n'avait été
  indexé ; il liste désormais les index déclarés dans `indexes.yaml`, avec la
  mention « jamais indexé » le cas échéant.

- Un partage que l'indexation ne pouvait pas ouvrir — serveur éteint, adresse
  erronée, identifiants refusés — n'apparaissait nulle part dans le panneau
  d'administration, qui annonçait « Tous les fichiers sont à jour » ; chaque
  source inaccessible est désormais signalée avec le motif du refus, et le
  nettoyage des fichiers disparus ne s'exécute plus sur une analyse qui n'a pas
  eu lieu.

- Le tas de la machine virtuelle Java valait 1 Go quelle que soit la mémoire du
  serveur, et l'indexation de PDF scannés pouvait y échouer ; l'installateur le
  calcule désormais d'après la mémoire de la machine, le panneau
  d'administration affiche la répartition de la mémoire, et un administrateur
  qui veut fixer le tas lui-même écrit `IC_HEAP_MB` dans
  `/etc/intracherche/jvm.conf`, fichier que l'installateur ne touche jamais.

- La mémoire retenue par le modèle de Sherlock n'entrait dans aucun budget et
  pouvait être distribuée une seconde fois aux services d'analyse ; elle est
  désormais prise en compte, sauf si Sherlock est désactivé ou si son moteur
  tourne sur une autre machine.

- Mettre à jour IntraCherche en relançant l'installateur échouait à mi-chemin
  sur un serveur en fonctionnement ; l'installateur arrête désormais
  IntraCherche avant de toucher aux fichiers et le redémarre à la fin.

- L'installation échouait sur un serveur Debian installé au minimum, celui que
  décrit le guide, faute d'outils absents d'une telle installation ; elle n'en
  dépend plus, vérifie désormais l'intégrité des archives téléchargées et
  n'annonce plus à tort que l'application n'a pas démarré.

## [3.0.0] — 2026-06

Première version de la branche 3.x.
