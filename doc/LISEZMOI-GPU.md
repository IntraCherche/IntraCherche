# IntraCherche — Utiliser une machine GPU pour Sherlock et l'OCR (optionnel)

Sherlock et la lecture des pages numérisées (OCR) peuvent confier leurs calculs les plus lents à
une **machine équipée d'une carte graphique NVIDIA**, distincte du serveur IntraCherche. Ce guide
explique comment IntraCherche s'y connecte. La préparation de la machine GPU elle-même (pilotes,
versions de PyTorch et d'onnxruntime adaptées à la carte, services, pare-feu) n'est pas décrite ici :
c'est une prestation à part (voir `doc/OFFRE.md`) ; la section « Ce que la machine GPU doit fournir » dit ce
qu'IntraCherche attend d'elle.

Cette configuration est **facultative** : sans elle, IntraCherche fonctionne entièrement sur le
processeur du serveur.

## Quand est-ce utile ?

| | Sur le processeur du serveur | Sur une carte graphique |
|---|---|---|
| Réponse de Sherlock | 1 à 3 minutes | quelques secondes |
| Reclassement de 40 passages | environ 90 secondes | 0,3 seconde |
| Passages examinés par question | 40 | 100 : Sherlock trouve davantage |
| Lecture d'une page numérisée (OCR) | environ 17 secondes par processus OCR | environ 0,6 seconde |

La recherche et le reste de l'indexation restent sur le serveur IntraCherche. Si Sherlock sert
régulièrement, si ses temps de réponse découragent les utilisateurs, ou s'il y a beaucoup de
documents numérisés à indexer, une machine GPU change l'usage. Sinon, rien ne presse.

## Ce que la machine GPU doit fournir

Une machine distincte du serveur IntraCherche, sur le même réseau local, avec une **adresse IP
fixe**, qui **ne se met pas en veille** et dont la carte n'est pas partagée avec d'autres
programmes, avec :

- une carte **NVIDIA avec au moins 16 Go de mémoire** (24 Go recommandés) et son pilote, la
  commande `nvidia-smi` affichant la carte. Les cartes AMD et Intel ne sont pas prises en charge ;
- **le modèle de Sherlock**, servi par **Ollama avec support CUDA** sur le port **11434** : le
  modèle doit s'y charger *sur la carte* (`ollama ps` indique `100% GPU`). C'est le gain le plus
  important, et le plus simple à obtenir ;
- facultativement, **le reclassement** (port **8003**) et **l'OCR** (port **8004**) : ce sont les
  services Python des dossiers `reranker-service/` et `ocr-service/` d'IntraCherche, lancés avec des
  versions CUDA de PyTorch (reclassement) et d'onnxruntime (OCR, avec `OCR_DEVICE=cuda`). Chacun
  répond sur `/health` en indiquant `"device":"cuda"` quand il calcule sur la carte.

Les trois services sont indépendants : on peut ne déplacer que le modèle de Sherlock.

## Sur le serveur IntraCherche

Dans `/etc/intracherche/application.yaml`, faites pointer vers la machine GPU les services qu'elle
fait tourner, par exemple :

```yaml
llm:
  ollamaUrl: "http://192.168.10.20:11434"   # le modèle de Sherlock
reranker:
  pythonUrl: "http://192.168.10.20:8003"    # le reclassement des passages
ocr:
  gpuUrl: "http://192.168.10.20:8004"       # la lecture des pages numérisées
```

Si `llm:` figure déjà dans le fichier (c'est le cas après `install.sh`, avec `model:`), modifiez
sa ligne `ollamaUrl:` au lieu d'ajouter un second bloc `llm:`. Puis :

```bash
sudo systemctl restart intracherche
```

Rien d'autre à régler. Le reclassement indique lui-même qu'il tourne sur la carte : Sherlock examine
alors 100 passages par question au lieu de 40. L'OCR du serveur (`ocr.pythonUrl`) reste démarré,
avec un seul processus, pour prendre le relais si la machine GPU ne répond pas.

### Machine GPU qui n'est pas toujours disponible

Si la machine GPU peut être éteinte ou occupée, indiquez-la dans `llm.gpuUrl` et `reranker.gpuUrl`
plutôt que dans `llm.ollamaUrl` et `reranker.pythonUrl`, qui restent sur `localhost`. Tant qu'elle
ne répond pas, le serveur répond lui-même, plus lentement, et la machine GPU reprend les questions
dans la minute qui suit son retour. Pour `llm.gpuUrl`, indiquez aussi `llm.gpuModel`. Démarrez
Ollama après que l'autre programme a libéré la carte : une Ollama démarrée pendant qu'il l'occupe
charge le modèle sur le processeur.

## Vérifier

- **Panneau d'administration**, carte « Services » : chaque service déplacé est « En ligne » avec la
  mention **GPU**. Un message rouge sous le tableau signale un service qui tourne sur le processeur.
- **Une question à Sherlock** revient en quelques secondes.
- **Sur la machine GPU**, `nvidia-smi` montre les processus qui utilisent la carte.

## Sécurité

- Ollama (11434), le reclassement (8003) et l'OCR (8004) **ne demandent pas de mot de passe** : ils
  sont faits pour le réseau local. **Ne redirigez jamais ces ports depuis Internet** vers cette
  machine, et limitez-les par le pare-feu au seul serveur IntraCherche.
- La machine reçoit des **extraits des documents** à chaque question posée à Sherlock, et l'**image
  de chaque page numérisée** indexée. Une fois installée, **coupez son accès Internet sortant**.

## Dépannage

**Sherlock est lent, et le panneau d'administration (ou `ollama ps`) indique que le modèle est sur
le processeur.** Ollama place un modèle au moment où il le charge, et l'y laisse : chargé pendant
qu'un autre programme occupait la carte, il reste sur le processeur. Libérez la carte (`nvidia-smi`
liste ce qui l'occupe), puis redémarrez Ollama (`sudo systemctl restart ollama`).

**Un service apparaît « Hors ligne » dans le panneau.** La machine GPU est éteinte, en veille, ou le
service est arrêté. IntraCherche répond quand même avec son propre processeur, plus lentement.

**Un service est signalé comme tournant sur le processeur.** PyTorch ou onnxruntime ne voit plus la
carte, typiquement après une mise à jour du pilote sans redémarrage : redémarrez la machine.

## Revenir au processeur

Sur le serveur IntraCherche, remettez `llm.ollamaUrl` à `http://localhost:11434` et
`reranker.pythonUrl` à `http://localhost:8003`, videz `ocr.gpuUrl` (`""`), et redémarrez
IntraCherche : il reprend son propre reclassement, son propre OCR et sa propre Ollama.

## Aller plus loin

L'installation clé en main d'une machine GPU, avec la version de PyTorch et d'onnxruntime adaptée à
la carte et au pilote, les mises à jour, l'installation sans accès Internet et le diagnostic
détaillé, est une prestation à part (voir `doc/OFFRE.md`) : contact@intracherche.fr.
