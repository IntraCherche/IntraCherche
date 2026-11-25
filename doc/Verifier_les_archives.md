# Vérifier une archive signée

Les archives livrées aux clients sous contrat sont signées par IntraCherche, pour prouver leur
origine. Sur un poste connecté, avec le paquet `minisign` de Debian :

```bash
minisign -Vm intracherche-X.Y.Z-base.tar.gz -p intracherche-signing.pub
```

La clé publique `intracherche-signing.pub` (identifiant `F6B0C744922DECF4`) figure dans
`distribution/` et sur votre devis ; elle doit être la même aux deux endroits :

```
RWT07C2SRMew9uje15/gQMZTE4BWek9b1PzfLQipMQr4TwAwLT0UPQaG
```

La signature prouve que l'archive vient d'IntraCherche et n'a pas été modifiée depuis. Après la copie
sur le serveur, vérifiez que la copie est intacte avec la somme de contrôle livrée avec l'archive :

```bash
sha256sum -c intracherche-X.Y.Z-base.tar.gz.sha256
```
