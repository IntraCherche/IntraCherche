# IntraCherche

**Find any document, without any data leaving your organisation.**

IntraCherche is a document search engine that runs on your own server, with no Internet
connection. It indexes your Windows (SMB) file shares, scanned pages included, and shows each
user only the documents their Active Directory rights allow. An assistant, Sherlock, answers
questions in French from the documents themselves and quotes the passages it relies on. Nothing
is sent to an outside service, not even for the AI.

Free software under the EUPL 1.2: no licence key, no user limit, no expiry date.

> En français : voir [`doc/INSTALL.md`](doc/INSTALL.md) (installation),
> [`doc/OFFRE.md`](doc/OFFRE.md) (installation sur site, assistance) et
> [intracherche.eu](https://intracherche.eu). Essayez la [démonstration](https://try.intracherche.eu) en ligne sur le site.

## What it does

- **Search** across PDF, Word, Excel, PowerPoint, OpenDocument, e-mail and text files; scanned
  pages are read with OCR, so they are found like any other page. Search by meaning is supported.
- **Rights respected**: the access rights of the directory (Active Directory, LDAPS) and of each
  file decide what appears in the results. A document a user may not read never shows up for them.
- **Sherlock**: ask a question in French and get a written answer with checked quotations. It runs
  on your server through Ollama; a graphics card makes answers arrive in seconds.
- **Offline by design**: no cloud service, no telemetry, no call to the outside. Updates arrive as
  an archive that your administrator verifies, never automatically.

## Sources: SMB shares only

The only supported source today is **SMB network shares** (`mountType: SMB`), read with a
service account. Local directories and other protocols are not supported in production. Authentication
is Active Directory over LDAPS.

## Install

Production installs target **Debian 13** (x86-64) and are done from a ready-to-install archive,
without Internet access on the server. The guide, in French, is [`doc/INSTALL.md`](doc/INSTALL.md);
sizing examples are in [`doc/Configuration_de_reference.md`](doc/Configuration_de_reference.md).
Installing the archives, keeping them up to date and answering questions is what the paid offer
covers (see [`doc/OFFRE.md`](doc/OFFRE.md)); the software itself is free.

## Build from source

Frontend first: Vite writes it into `src/main/resources/static/`, which is not versioned, and the
JAR serves it from there. Java 21 and Node are required.

```bash
(cd src/ui-vue && npm ci && npm run build)
mvn clean install
```

Run the tests with `mvn test`. Tests that need a demo Solr or an Ollama server skip themselves when
it is not reachable; pass `-Dsherlock.ollamaUrl=http://host:11434` to point the language-model
tests at a given Ollama.

### Running on a development machine

The application needs a Solr, the Python services (embeddings, reranker, OCR, entity recognition)
and Ollama, so the practical way to run it is the install bundle above. To run the Java
application from sources, put your settings in `config/application-local.yaml` and
`config/indexes-test.yaml` at the repository root (both gitignored: they hold credentials), then:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Logs go to a fixed location, `/var/log/intracherche`, on every machine. Create it once:

```bash
sudo install -d -o $USER -g $USER -m 755 /var/log/intracherche
```

## Configuration

- `src/main/resources/config/application.yaml` and `indexes.yaml` are the bundled templates. The
  installer copies them to `/etc/intracherche/`, where the site-specific values live.
- `indexes.yaml` declares the indexes (one Solr core each), who sees them (`visibleToGroups`) and
  which SMB shares feed them. Each file's own rights still apply inside a visible index.

```yaml
indexes:
  - coreName: HR_Documents
    visibleToGroups: [HR]            # AD groups (sAMAccountName), or ALL
    ocr: true
    indexedDirs:
      - mountType: SMB
        smbServer: hr-server
        smbShare: HR_Docs
        smbUser: cs_indexer          # service account that reads the share
        smbPassword: secret
```

The full list of fields is documented in the comments of `indexes.yaml` and in `doc/INSTALL.md`.
Restart the service after any change: `sudo systemctl restart intracherche`.

## Contributing and licence

See [`CONTRIBUTING.md`](CONTRIBUTING.md). IntraCherche is licensed under the European Union Public
Licence v. 1.2 (EUPL-1.2); see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
