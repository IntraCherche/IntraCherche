# IntraCherche — configurations de référence

> BROUILLON. Les chiffres cités sont ceux mesurés ou publiés dans les guides ; les lignes marquées
> « à compléter » attendent une mesure.

Ce document aide à rédiger un cahier des charges pour le serveur qui fera tourner IntraCherche. Il
indique ce qui dimensionne la machine, puis trois configurations de départ. Ce ne sont pas des
garanties : un dimensionnement précis dépend de vos documents (voir la fin).

## Ce qui dimensionne le serveur

| Question | Ce qu'elle change |
|---|---|
| Combien de pages à indexer ? | Durée de la première indexation, taille du disque |
| Quelle part de pages **numérisées** (scans) ? | L'OCR est le poste le plus lent : environ 17 secondes par page et par processus sur processeur, environ 0,6 seconde sur une carte graphique |
| Sherlock est-il utilisé, par combien de personnes en même temps ? | Sans carte graphique, une réponse prend de quelques minutes à plus de vingt ; sur carte, quelques secondes |
| Le serveur doit-il rester joignable pendant l'indexation ? | Mémoire disponible pour la recherche |

La recherche elle-même est légère : c'est l'indexation, l'OCR et Sherlock qui demandent du matériel.

## Trois configurations de départ

| | **A. Recherche seule** | **B. Recherche + Sherlock sur carte** | **C. B + beaucoup de scans** |
|---|---|---|---|
| Usage | Recherche plein texte et sémantique ; Sherlock facultatif et lent | Sherlock utilisable au quotidien | Fonds numérisés importants |
| Serveur IntraCherche | Debian 13, 8 cœurs physiques, **16 Go de RAM minimum (32 Go recommandés)** | idem | idem |
| Carte graphique | aucune | NVIDIA, **16 Go minimum, 24 Go recommandés**, sur le serveur ou sur une machine à part | idem |
| Sherlock | de quelques minutes à plus de vingt par question | quelques secondes | quelques secondes |
| Reclassement des passages | environ 90 s pour 40 passages | 0,3 s pour 100 passages : Sherlock trouve davantage | idem |
| OCR | sur le processeur, environ 17 s par page et par processus | idem | sur la carte, environ 0,6 s par page |
| Disque | 20 Go libres, plus l'index : à compléter | idem | idem |

Repère de vitesse d'indexation (texte, serveur de 8 cœurs) : environ **62 pages par minute** avec
Sherlock sur le même serveur, **76** en le désactivant pendant l'indexation (`llm.enabled: false`),
soit de 3 700 à 4 500 pages par heure. Un million de pages de
texte demande donc une dizaine de jours de calcul. Ce repère vaut pour du texte : les scans sont
plus lents (voir l'OCR ci-dessus).

Quand la machine à carte graphique est distincte du serveur : même réseau local, adresse IP fixe, pas
de mise en veille, carte non partagée. Voir `LISEZMOI-GPU.md`.

## Ce qui n'est pas dans ce tableau

- Le nombre d'utilisateurs simultanés : à compléter après mesure.
- La taille de l'index par million de pages : à compléter après mesure.
- Les prix du matériel : ils varient et dépendent de votre fournisseur.

## Un dimensionnement pour votre organisation

Un dimensionnement précis part de vos données (volume, part de scans, nombre d'utilisateurs,
usage de Sherlock, contraintes de réseau fermé) et aboutit à un cahier des charges que vous pouvez
joindre à un marché, avec une vérification du bon fonctionnement sur le matériel livré. C'est une prestation sur devis :
contact@intracherche.fr.
