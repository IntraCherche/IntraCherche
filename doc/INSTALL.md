# IntraCherche — Guide d'installation

Ce guide est destiné à l'administrateur système qui installe IntraCherche sur un serveur
Debian 13 (testé sur 13.5, probablement compatible avec 12), sans accès à Internet. Il couvre
l'installation standard, de l'archive à une première recherche. Pour les installations plus
avancées (réglage de la mémoire, machine GPU séparée, connexion unique SSO, autre méthode
d'obtention de l'AC LDAPS, dimensionnement, diagnostics détaillés), vous pouvez nous demander de
vous accompagner : l'installation sur site et les offres d'assistance technique sont décrites dans
`OFFRE.md`.

---

## Ce dont vous avez besoin

### Archives

| Archive | Contenu | Taille approximative |
|---|---|---|
| `intracherche-X.Y.Z-base.tar.gz` | Application, services Python, modèles IA, Solr et moteur Ollama | ~2 Go |
| `intracherche-ollama-qwen3.5-9b.tar.gz` | Poids du modèle de langage de Sherlock (uniquement) | ~6 Go |

Chaque archive est accompagnée d'un fichier `.sha256` permettant de vérifier son intégrité. La
seconde archive ne change pas d'une version à l'autre : lors d'une mise à jour, seule la première
est à re-télécharger.

**Vérifier les archives.** Le serveur n'a pas d'accès à Internet : les archives se téléchargent sur
un poste connecté, puis se copient sur le serveur (`scp`). Deux vérifications, deux endroits :

- *sur le poste connecté, avant la copie* : l'**authenticité**. Les archives fournies dans le cadre
  d'un contrat d'assistance sont signées (fichier `.minisig`) ; la clé publique est
  `distribution/intracherche-signing.pub` dans le dépôt :
  `minisign -Vm intracherche-X.Y.Z-base.tar.gz -p intracherche-signing.pub`
  (paquet `minisign` de Debian). Une archive que vous avez construite vous-même n'est pas signée ;
- *sur le serveur, après la copie* : l'**intégrité** de la copie, avec
  `sha256sum -c intracherche-X.Y.Z-base.tar.gz.sha256`.

### Prérequis serveur

- Debian 13, architecture x86-64. Installation en mode « serveur » : à l'étape « Sélection des
  logiciels », cochez **serveur SSH** et **utilitaires usuels du système**. Aucun bureau n'est
  nécessaire.
- **16 Go de RAM au minimum** (32 Go recommandés). Avec Sherlock sur le serveur lui-même et le
  modèle 9B, prévoyez 24 à 32 Go.
- **8 cœurs physiques au minimum** (ou 8 vCPU dédiés).
- 20 Go d'espace disque libre, hors données indexées.
- Accès `sudo` ou connexion en `root`.
- Accès réseau au contrôleur de domaine Active Directory (LDAPS, port 636) et aux partages SMB
  à indexer.

### Informations à préparer

L'installateur ne pose aucune question : il génère `/etc/intracherche/application.yaml` avec des
valeurs `CHANGE_ME` que vous renseignez ensuite. Préparez :

- l'URL LDAPS du contrôleur (ex. `ldaps://dc.corp.local:636`) et le DN de base (ex.
  `dc=corp,dc=local`) ;
- un compte de liaison en lecture seule (identifiant et mot de passe) et la base de recherche des
  utilisateurs (ex. `CN=Users`) ;
- le certificat de l'**autorité de certification racine** de l'entreprise, au format PEM (voir
  « Connexion LDAPS au contrôleur de domaine ») ;
- les partages SMB à indexer, avec un compte de service qui les lit ;
- l'ouverture des ports **8443** (HTTPS) et **8080** (redirection) sur le pare-feu du serveur.

---

## Installation

### 1. Vérifier les archives

Placez les deux archives et leurs fichiers `.sha256` dans un même répertoire de travail, puis :

```bash
sha256sum -c intracherche-X.Y.Z-base.tar.gz.sha256
sha256sum -c intracherche-ollama-qwen3.5-9b.tar.gz.sha256
```

Les deux commandes doivent afficher `OK`. N'installez pas si l'une échoue.

### 2. Extraire l'archive de base

```bash
tar -xzf intracherche-X.Y.Z-base.tar.gz
```

**N'extrayez pas la seconde archive.** L'installateur la cherche dans le **dossier parent de
celui qui contient `install.sh`**. Le répertoire de travail doit donc ressembler à ceci :

```
~/intracherche-install/
├── intracherche-X.Y.Z-base.tar.gz
├── intracherche-ollama-qwen3.5-9b.tar.gz       ← laissée telle quelle, NON extraite
└── intracherche-X.Y.Z/                         ← contient install.sh
```

> Si l'extraction a créé un dossier d'enrobage (`intracherche-X.Y.Z-base/`) autour de
> `intracherche-X.Y.Z/`, l'archive du modèle doit aller **dans** ce dossier d'enrobage.
> Pour vérifier : depuis le dossier qui contient `install.sh`, `ls ..` doit lister
> `intracherche-ollama-qwen3.5-9b.tar.gz`. Mal placée, Sherlock démarre sans modèle
> (avertissement *Ollama model tarball not found*).

### 3. Lancer l'installateur

```bash
cd intracherche-X.Y.Z
sudo ./install.sh
```

(Sans `sudo` : `su -`, puis `cd` vers le dossier extrait, puis `./install.sh`.)

L'installateur crée l'utilisateur système `intracherche`, déploie l'application dans
`/opt/intracherche/`, installe Solr et les quatre services Python (OCR, entités nommées,
vectorisation, reclassement), installe Ollama avec le modèle de Sherlock, génère
`/etc/intracherche/application.yaml` et active le démarrage automatique. Il ne pose **aucune
question**.

Lors d'une première installation, la configuration contient encore des `CHANGE_ME` : le service
n'est donc **pas** démarré. Renseignez-la (sections suivantes) puis lancez
`sudo systemctl start intracherche`.

### 4. Vérifier le démarrage

L'interface répond en quelques secondes, mais Solr et les services Python peuvent demander
quelques minutes. Vérifiez-les depuis le serveur (la fonction ne dépend que de `bash`) :

```bash
http_get() { timeout 5 bash -c 'exec 3<>/dev/tcp/127.0.0.1/$0 && printf "GET %s HTTP/1.0\r\n\r\n" "$1" >&3 && cat <&3' "$1" "$2" 2>/dev/null; }

# L'interface — la première ligne doit contenir 302 (redirection vers HTTPS) ou 200
http_get 8080 / | head -1

# Les services Python (OCR 8000, entités nommées 8001, vectorisation 8002, reclassement 8003)
for p in 8000 8001 8002 8003; do echo -n "$p : "; { http_get $p /health || echo "ne répond pas"; } | tail -1; echo; done
```

Depuis un navigateur : `https://<nom-du-serveur>:8443`. Un avertissement de sécurité s'affiche
d'abord (certificat auto-signé, voir « Accès sécurisé (HTTPS) ») ; passez-le. Si la page de
connexion s'affiche, l'application est accessible.

Si la page ne s'affiche pas, ou si un service ne répond pas :

```bash
systemctl status intracherche.service
sudo journalctl -u intracherche -n 100 --no-pager
grep -E '\[(ocr|ner|embedding|reranker)\]' /var/log/intracherche/app.log | grep -iv 'GET /health' | tail -40
```

---

## Configuration post-installation

### Variables à adapter

Les commandes de cette section utilisent les variables ci-dessous. **Les valeurs sont des
exemples** : remplacez-les par celles de *votre* environnement et collez le bloc dans le terminal
du serveur (après une nouvelle connexion SSH ou un `sudo -i`, collez-le de nouveau).

```bash
DC_HOST=dc.corp.local                # nom du contrôleur de domaine (FQDN, identique au CN/SAN de son certificat)
DC_IP=192.0.2.10                     # son adresse, seulement si le DNS ne résout pas DC_HOST (exemple, à remplacer)
BASE_DN="dc=corp,dc=local"           # DN de base de l'annuaire
BIND_USER="ldap-reader@corp.local"   # compte de liaison en lecture seule
TEST_LOGIN=votrelogin                # un identifiant (sAMAccountName) existant
```

### Connexion LDAPS au contrôleur de domaine (obligatoire)

L'authentification se fait via l'Active Directory en LDAPS. Renseignez d'abord les valeurs
`CHANGE_ME` du bloc « SET THESE UP FIRST », au début de `/etc/intracherche/application.yaml`
(clés à plat, sans indentation) : `spring.ldap.urls` (`ldaps://<DC_HOST>:636`), `spring.ldap.base`
(`BASE_DN`), `spring.ldap.username` (`BIND_USER`), `spring.ldap.password`, la base de recherche des
utilisateurs et `admin.adminGroupSid` (le groupe qui administre). Ensuite, tout se règle depuis le
panneau d'administration.

Sur un serveur neuf, **deux conditions** doivent en plus être réunies. Sans elles, le login
échoue avec le message générique « Invalid username or password » (voir *Dépannage*).

**1. Le serveur doit résoudre le nom du contrôleur de domaine :**

```bash
getent hosts "$DC_HOST"
```

Si rien ne s'affiche, faites pointer le DNS du serveur vers le contrôleur. À défaut seulement,
ajoutez une entrée statique (le nom doit être identique à celui de `spring.ldap.urls` et au
CN/SAN du certificat) :

```bash
echo "$DC_IP  $DC_HOST" | sudo tee -a /etc/hosts
```

> ⚠️ Vérifiez que `DC_IP` et `DC_HOST` sont ceux de *votre* contrôleur : `192.0.2.0/24` est une
> plage réservée à la documentation et provoque `Network is unreachable`.

**2. La JVM doit approuver le certificat LDAPS.** Le contrôleur présente presque toujours un
certificat émis par l'autorité de certification (AC) de l'entreprise : les postes Windows du
domaine la connaissent, une JVM Linux neuve non. Importez le certificat de l'**AC racine**. Ce
certificat est public (aucune clé privée) ; seuls les droits root sur le serveur IntraCherche
sont nécessaires.

**2a. Vérifier que LDAPS répond** (si rien ne s'affiche, un pare-feu bloque le port 636, ou le
contrôleur n'a pas de certificat LDAPS : demandez aux administrateurs du domaine de l'activer) :

```bash
openssl s_client -connect "$DC_HOST:636" </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -issuer -enddate
```

**2b. Obtenir le certificat de l'AC racine** — depuis un poste Windows du domaine. Lancez
`certlm.msc`, ouvrez **Autorités de certification racines de confiance › Certificats**, repérez
l'AC de l'entreprise (celle qui est affichée comme émetteur en 2a), puis clic droit › **Toutes
les tâches › Exporter…** › **X.509 encodé en base 64 (.cer)**. Notez son **empreinte
numérique** (onglet *Détails*), copiez le fichier sur le serveur et renommez-le `corp-ca.crt`.

> Contrôleur Samba AD ou certificat auto-signé : demandez le fichier de l'AC à l'administrateur
> du contrôleur.

**2c. Contrôler le certificat avant de l'importer.**

```bash
openssl x509 -in corp-ca.crt -noout -subject -issuer -enddate -fingerprint -sha1

openssl s_client -connect "$DC_HOST:636" -CAfile corp-ca.crt \
  -verify_hostname "$DC_HOST" </dev/null 2>/dev/null | grep "Verify return code"
```

L'empreinte SHA-1 doit correspondre à celle de Windows, et la seconde commande doit afficher
`Verify return code: 0 (ok)`. Le code `20` signale une mauvaise AC ou une AC intermédiaire
manquante ; le code `62`, un nom différent de celui du certificat : utilisez dans
`spring.ldap.urls` le nom exact (FQDN), pas une IP ni un alias. Un fichier PEM commence par
`-----BEGIN CERTIFICATE-----` ; s'il est binaire : `openssl x509 -inform DER -in corp-ca.cer -out corp-ca.crt`.

**2d. Importer le certificat dans la JVM du service, puis redémarrer.**

```bash
EXEC_PATH=$(systemctl show -p ExecStart intracherche | grep -o 'path=[^ ;]*' | cut -d= -f2)
JAVA_BIN=$(readlink -f "$EXEC_PATH")
KEYTOOL="$(dirname "$JAVA_BIN")/keytool"
CACERTS=$(readlink -f "$(dirname "$(dirname "$JAVA_BIN")")/lib/security/cacerts")
echo "Magasin : $CACERTS"

sudo "$KEYTOOL" -importcert -noprompt -alias corp-ca -file corp-ca.crt \
  -keystore "$CACERTS" -storepass changeit
sudo systemctl restart intracherche
```

La commande doit répondre `Certificate was added to keystore`. `changeit` est le mot de passe par
défaut du magasin de la JVM : saisissez-le tel quel. **Le redémarrage est obligatoire** : la JVM ne
lit son magasin qu'à son démarrage. **Importez exactement le fichier contrôlé en 2c.**

Contrôle de bout en bout : suivez le journal pendant une vraie connexion.

```bash
sudo tail -f /var/log/intracherche/app.log | grep -i "certpath\|Login"
```

Seule la ligne d'authentification doit apparaître. `SunCertPathBuilderException` signifie que le
magasin ne contient pas le bon certificat. (Pour le magasin sous `/etc/ssl/certs/`, une AC
introuvable ou le remplacement d'un certificat déjà importé, nous pouvons vous accompagner,
voir `OFFRE.md`.)

### Définir les sources de documents (obligatoire)

L'installateur crée un modèle dans `/etc/intracherche/indexes.yaml`. Il définit les partages à
indexer. Éditez-le :

```bash
sudo nano /etc/intracherche/indexes.yaml
```

```yaml
indexes:
  - coreName: RH
    status: ACTIVE
    visibleToGroups: [RH, Direction]   # seuls les membres de ces groupes AD voient cet index
    ocr: true
    indexedDirs:
      - mountType: SMB
        smbServer: fileserver.corp.local
        smbShare: RH
        smbUser: cs_indexer
        smbPassword: motdepasse
        smbDomain: CORP            # facultatif

  - coreName: Archives
    status: ACTIVE
    visibleToGroups: ALL           # tous les utilisateurs
    ocr: false
    allowedExtensions: [pdf, docx, txt]
    indexedDirs:
      - mountType: SMB
        smbServer: fileserver.corp.local
        smbShare: Archives
        smbUser: cs_indexer
        smbPassword: motdepasse
```

| Champ | Obligatoire | Défaut | Description |
| --- | --- | --- | --- |
| `coreName` | oui | — | Nom unique de l'index. |
| `status` | non | `ACTIVE` | `ACTIVE` : indexé et consultable. `PAUSED` : conservé, non mis à jour. `DELETE` : supprime l'index (exige `confirmDelete: true`). |
| `visibleToGroups` | oui | — | `ALL`, ou la liste des groupes AD (nom « antérieur à Windows 2000 ») qui voient l'index. Les noms sont vérifiés au démarrage : un nom inconnu empêche le démarrage. Ce réglage restreint, il n'accorde rien : chaque fichier reste soumis à ses droits NTFS. |
| `ocr` | non | `true` | Applique l'OCR aux PDF scannés. |
| `allowedExtensions` | non | `pdf, doc, docx, odt, xls, xlsx, ods, ppt, pptx, odp, txt, eml, msg` | Extensions indexées. |
| `indexedDirs` | oui | — | Les sources : `mountType: SMB`, `smbServer`, `smbShare`, `smbUser`, `smbPassword`, `smbDomain` (facultatif). |

Puis : `sudo systemctl restart intracherche`.

### Restreindre le panneau d'administration

Le panneau d'administration (`/admin`) est fermé à tous tant que ce paramètre n'est pas
renseigné. **À faire avant la mise en production.** Créez un groupe AD dédié et ajoutez son SID
dans `/etc/intracherche/application.yaml` :

```yaml
admin:
  adminGroupSid: "S-1-5-21-123456789-987654321-111111111-5678"
```

Le SID d'un groupe s'obtient sous Windows par
`(Get-ADGroup -Identity "<groupe>" -Properties ObjectSID).ObjectSID.Value`. Puis :
`sudo systemctl restart intracherche`.

---

## Accès sécurisé (HTTPS)

Une nouvelle installation sert IntraCherche **en HTTPS**, sur le port **8443**, avec un
certificat **auto-signé** généré pour ce serveur. Le port 8080 ne fait que rediriger vers HTTPS.
Ainsi, les mots de passe Active Directory et les documents ne circulent jamais en clair.

Ce que l'administrateur doit faire :

1. **Ouvrir les ports 8443 et 8080** sur le pare-feu du serveur.
2. **Déployer le certificat sur les postes**, pour que les navigateurs cessent d'avertir. Le
   certificat public est `/etc/intracherche/intracherche.crt`. Par GPO : *Configuration
   ordinateur › Stratégies › Paramètres Windows › Paramètres de sécurité › Stratégies de clé
   publique › Autorités de certification racines de confiance* › Importer. Firefox utilise son
   propre magasin : activez `security.enterprise_roots.enabled` pour qu'il reprenne celui de
   Windows.
3. **Utiliser un nom prévu par le certificat.** Il couvre le nom court du serveur, son nom
   complet, `localhost` et ses adresses IP. Pour un alias DNS ajouté plus tard :

   ```bash
   sudo /opt/intracherche/generate-certificate.sh --name recherche.corp.local
   sudo systemctl restart intracherche
   ```

   Redéployez ensuite `intracherche.crt` sur les postes.
4. **Renouveler le certificat** avant son expiration (825 jours) : même commande, sans option.
   Le journal le signale 30 jours avant.

Si l'organisation a une AC interne, un certificat émis par elle évite tout déploiement sur les
postes ; nous pouvons vous aider à l'obtenir (voir `OFFRE.md`).

| Symptôme | Cause |
|---|---|
| Avertissement de sécurité dans le navigateur | Certificat non déployé, ou nom absent du certificat |
| Connexion acceptée puis retour à la page de connexion | Accès en HTTP avec `app.cookie.secure: true` : le navigateur refuse le cookie de session |
| Plus aucun accès, sans pouvoir passer l'avertissement | Certificat expiré ou nom changé après approbation : renouvelez-le |

## En-têtes de sécurité du navigateur

IntraCherche demande au navigateur, à chaque page, des protections (politique de sécurité du
contenu, HSTS, cookies `__Host-`). Rien n'est à régler. Si le navigateur bloque quelque chose que
l'interface utilise (une extension de navigateur ou un proxy qui injecte du contenu), `app.log`
contient une ligne « Politique de sécurité du contenu (CSP) : le navigateur a bloqué … »,
indiquant quoi et sur quelle page.

---

## Sherlock (assistant IA)

Sherlock répond à des questions en s'appuyant sur les documents indexés. Il repose sur Ollama,
qui sert un modèle de langage (Qwen 3.5 9B, dans la seconde archive). L'installateur le configure
seul : **vous n'avez normalement rien à faire**.

- **Sans GPU (par défaut)** : Sherlock fonctionne, mais chaque réponse prend de quelques minutes
  (de l'ordre de 6 minutes sur un processeur de bureau modeste). Si c'est trop long pour vos
  utilisateurs, une carte graphique est la solution : `LISEZMOI-GPU.md`, dans cette archive, explique
  comment IntraCherche s'y connecte.
- **Restreindre l'accès** : par défaut, tout utilisateur authentifié y a accès. Pour le réserver à
  un groupe AD, créez le groupe et ajoutez son SID :

  ```yaml
  llm:
    sherlockGroupSid: "S-1-5-21-123456789-987654321-111111111-1234"
  ```

- **Désactiver** (politique interne, serveur trop limité) :

  ```yaml
  llm:
    enabled: false
  ```

  Le panneau Sherlock disparaît, le reste de la recherche fonctionne. Vous pouvez alors aussi
  arrêter l'Ollama fourni : `sudo systemctl disable --now intracherche-ollama`.

Après toute modification : `sudo systemctl restart intracherche`.

> Sherlock est **expérimental** et optimisé pour les questions en **français**.

---

## Gestion quotidienne

```bash
sudo systemctl status intracherche        # état du service
sudo journalctl -u intracherche -f        # journal du service, en direct
tail -f /var/log/intracherche/app.log     # journal de l'application
sudo systemctl restart intracherche       # après un changement de configuration
```

IntraCherche démarre automatiquement avec le serveur.

| Chemin | Contenu |
|---|---|
| `/etc/intracherche/application.yaml` | Configuration du site (LDAP, SMTP, ports…) |
| `/etc/intracherche/application.yaml.reference` | Tous les réglages, avec leur valeur par défaut et leur rôle — non lu, pour consultation |
| `/etc/intracherche/indexes.yaml` | Sources de documents |
| `/var/log/intracherche/app.log` | Journal de l'application |
| `/var/lib/intracherche/` | Base des fichiers indexés et index Solr |
| `/opt/intracherche/` | Installation (ne pas modifier) |

Les fichiers `application.yaml`, `indexes.yaml` et `jvm.conf` contiennent des mots de passe : ils
doivent appartenir à `root:intracherche` en mode `640`, et le dossier `/etc/intracherche` en mode `770`
(l'installateur s'en charge) :

```bash
sudo chown root:intracherche /etc/intracherche /etc/intracherche/application.yaml /etc/intracherche/indexes.yaml
sudo chmod 770 /etc/intracherche
sudo chmod 640 /etc/intracherche/application.yaml /etc/intracherche/indexes.yaml
```

### Droits d'écriture

Le panneau d'administration (**Administration > Configuration**) modifie ces fichiers : index, réglages
courants et avancés, mémoire (tas Java dans `jvm.conf`, tas Solr dans `solr.in.sh`). Il remplace un fichier
en le renommant, d'où le droit d'écriture sur le **dossier** `/etc/intracherche` accordé au compte du
service ; les fichiers eux-mêmes restent en `640`. Avant chaque enregistrement, l'ancienne version est
conservée à côté (`application.yaml.bak-AAAAMMJJ-HHMMSS-mmm`, les dix dernières).

Un fichier enregistré par le panneau commence par « Auto generated… do not edit by hand » et ne
contient plus de commentaires : les explications de chaque réglage sont dans le panneau. Modifier ces
fichiers à la main reste possible (puis `sudo systemctl restart intracherche`), mais le prochain
enregistrement depuis le panneau réécrit tout le fichier. Si le panneau indique que le dossier n'est
pas accessible en écriture, relancez l'installateur ou appliquez les commandes ci-dessus.

---

## Mise à jour

```bash
sha256sum -c intracherche-X.Y.Z-base.tar.gz.sha256
tar -xzf intracherche-X.Y.Z-base.tar.gz        # dans le même répertoire que l'archive du modèle
cd intracherche-X.Y.Z
sudo ./install.sh                              # détecte l'installation existante
```

L'installateur n'écrase jamais `application.yaml` ni `indexes.yaml`. Les réglages nouveaux, avec
leur rôle, figurent dans `application.yaml.reference` (l'ancienne version est gardée en
`.previous` : `diff` montre ce que la mise à jour a ajouté). Le service est redémarré et vérifié
automatiquement.

---

## Désinstallation

Il n'existe pas de script de désinstallation, pour éviter d'effacer par erreur des données
indexées. En tant que `root` :

```bash
sudo systemctl disable --now intracherche
sudo systemctl disable --now intracherche-ollama 2>/dev/null
sudo rm -f /etc/systemd/system/intracherche.service /etc/systemd/system/intracherche-ollama.service
sudo systemctl daemon-reload

sudo rm -rf /opt/intracherche /etc/intracherche /var/log/intracherche
sudo userdel intracherche
```

Ces commandes ne touchent pas aux données indexées (`/var/lib/intracherche`, plusieurs heures de
ré-indexation si vous les supprimez) : **conservez-les** si vous réinstallez ou migrez. Ne les
supprimez (`sudo rm -rf /var/lib/intracherche`) que pour un décommissionnement définitif. Si
vous aviez installé vous-même un Ollama GPU avant IntraCherche, **ne le supprimez pas** : il vous
appartient.

---

## Dépannage

### L'application ne démarre pas

```bash
sudo journalctl -u intracherche -n 100 --no-pager
```

- **`JWT_SECRET` absent ou trop court** : vérifiez `jwt.secret` dans `application.yaml` (64
  caractères minimum).
- **Solr inaccessible** : Solr est lancé par IntraCherche depuis `/opt/intracherche/solr/` ; le
  répertoire doit exister et être lisible par l'utilisateur `intracherche`.
- **Port 8080 déjà utilisé** : `ss -tlnp | grep 8080` ; changez `server.port` dans
  `application.yaml`.

### Erreur LDAP au login (« Invalid username or password »)

Ce message est générique : un mot de passe erroné et un contrôleur injoignable ou non approuvé
produisent la même réponse à l'écran. La cause réelle est dans le journal :

```bash
grep "Login failed" /var/log/intracherche/app.log | tail -1
```

| Cause racine dans le journal | Signification | Correctif |
|---|---|---|
| `UnknownHostException` | Le nom du contrôleur ne se résout pas | Pointez le DNS du serveur vers le contrôleur (ou une entrée `/etc/hosts`) ; le nom doit être celui de `spring.ldap.urls` |
| `ConnectException` / `timed out` | Nom résolu mais port 636 injoignable | Ouvrez le port 636 vers le contrôleur |
| `SSLHandshakeException` / `unable to find valid certification path` / `Connection or outbound has closed` | Certificat LDAPS non approuvé par la JVM | Importez l'AC racine (« Connexion LDAPS au contrôleur de domaine », étapes 2b à 2d) ; vérifiez que c'est la bonne AC (2c), la bonne JVM, et que le service a été redémarré |
| `LDAP: error code 49` / `Bad credentials` | Connexion correcte, identifiants refusés | Vérifiez le login, le mot de passe et la base de recherche des utilisateurs |

### Un administrateur ne voit pas le panneau d'administration

Connecté, ouvrez `https://<serveur>:8443/api/auth/me`. Si `adminGroupConfigured` vaut `false`,
`admin.adminGroupSid` est vide dans `application.yaml`. S'il vaut `true` mais que
`adminSidInToken` vaut `false`, l'utilisateur n'est pas membre du groupe (ou le SID n'est pas
celui du bon groupe). Les groupes d'un utilisateur sont relus au plus toutes les 15 minutes :
déconnectez-vous puis reconnectez-vous après un changement.

### Aucun document indexé

1. Vérifiez que `/etc/intracherche/indexes.yaml` est correctement renseigné.
2. Consultez les erreurs d'indexation dans le panneau d'administration (`/admin`).
3. Vérifiez que le serveur a accès aux partages SMB définis.

### Java manque de mémoire (« Java heap space », tous les fichiers en erreur)

Signes : dans `app.log`, `java.lang.OutOfMemoryError: Java heap space`, suivi d'avertissements
`Core '…' unavailable; leaving file …` ; dans le panneau d'administration, aucun fichier indexé et
des milliers d'erreurs. Le plus souvent, un document très volumineux ou un grand nombre de PDF
scannés ont rempli la mémoire de l'application.

Depuis la version 3.5.0, le service s'arrête de lui-même au premier dépassement de mémoire et
systemd le relance au bout de 15 secondes ; l'indexation reprend alors là où elle s'était arrêtée.
Pour relancer manuellement: `sudo systemctl restart intracherche`.

Si le service s'arrête deux fois pendant la lecture du même fichier, lu seul la seconde fois,
IntraCherche cesse de le lire : il n'indexe plus que son nom (le fichier reste trouvable par son
nom) et le signale dans le panneau d'administration avec le réglage conseillé. Il est relu si la
mémoire est augmentée (voir ci-dessous) ou si le fichier est modifié.

Si cela se répète, augmentez la mémoire de l'application. L'installateur la fixe au huitième de la
mémoire du serveur (4 Go pour 32 Go). Créez `/etc/intracherche/jvm.conf` :

```
IC_HEAP_MB=8192
```

puis `sudo systemctl restart intracherche`. Ce fichier n'est jamais modifié par l'installateur : la
valeur survit aux mises à jour. Vérification : `ps -o args= -C java | grep -o 'Xmx[0-9a-z]*'`.
Joignez `app.log` (les lignes qui précèdent la première erreur `OutOfMemoryError`) à votre demande
d'assistance.

### Solr manque de mémoire (documents très longs)

Un document très long (plusieurs milliers de pages) n'est jamais indexé, ou la recherche se fige,
avec dans `app.log` : `Timeout occurred while waiting response from server at: http://localhost:8983/solr/…`.
Solr a un tas de 512 Mo par défaut dans les versions antérieures à 3.3.1. Mettez
IntraCherche à jour, ou fixez le tas à la main : dans `/opt/intracherche/solr/bin/solr.in.sh`,
ajoutez `SOLR_HEAP="2048m"` (hors du bloc `IntraCherche heap`, qui serait réécrit), puis
`sudo systemctl restart intracherche`. Si un document reste en échec, abaissez
`indexing-config.maxFileSizeMb` ou `indexing-config.maxTextChars` dans `application.yaml` : il ne
sera alors indexé que par son nom.

### Sherlock ne répond pas ou est lent

Sur un serveur sans GPU, Sherlock peut prendre de quelques minutes à plus de vingt minutes par
question. Si des questions sont marquées `FAILED` avec un timeout, le journal l'indique
(`grep "generation aborted" /var/log/intracherche/app.log`). Augmentez alors le délai dans
`application.yaml` :

```yaml
llm:
  readTimeoutSeconds: 3600
```

Après un timeout, la charge du processeur peut persister quelques minutes : Ollama n'interrompt
pas le calcul pendant l'analyse du contexte en mode CPU. C'est une limite d'Ollama, pas un défaut
d'IntraCherche. Une carte graphique supprime ce problème.

---

**Assistance :** contact@intracherche.fr. Joignez le résultat de
`sudo journalctl -u intracherche -n 200 --no-pager` (le journal du service n'est lisible
qu'avec `sudo`, ou par un utilisateur membre du groupe `systemd-journal`).
