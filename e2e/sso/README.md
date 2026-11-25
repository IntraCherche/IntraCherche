# Single sign-on end-to-end harness

`harness.py` drives IntraCherche's OpenID Connect login against a **throwaway Keycloak**, the way a browser
does: the application, then the provider's login form, then the application's callback. It checks which
directory account each user ends up in. Nothing is mocked.

## Why it exists

The unit tests cannot see the seams of a login: cookies set on the callback, redirects, which pages are public,
the provider's own session. This harness found, in one afternoon, two real defects that Google testing had hidden:

- `/login` was not on the public list, so an unauthenticated request was redirected to `/` and **lost its query
  string**: a refused sign-in showed no message and never cleaned up the previous session.
- A refused sign-in left the previous user's session open behind the login page.

Add a scenario here when a login bug is found in the field; it stays found.

## What it proves

| Scenario | Why it matters |
|---|---|
| each known user ends up in **their own** directory account, with the right admin / Sherlock rights | the claim → attribute mapping, and that rights come from the directory |
| a user the provider knows but the directory does not is refused and lands on `/login?oidcError=1` | no account, no session |
| a refused sign-in ends the session that was open in the same browser | nobody stays signed in as someone else |
| logout after SSO works (POST with the CSRF token the page sends) | the session cookie and the CSRF cookie coexist |
| the admin API is open to the administrator and a 403 for the others | rights follow the account |
| `/login?oidcError=1` is served, not redirected | the refusal message can show |
| the handshake session cookie is expired when the login ends | no dead `JSESSIONID` is left in the browser |
| `--password-off`: the login page is told the form is off, any password gets a 403 | `auth.password.enabled: false` |
| `--idp-down`: the application serves and still builds the way out to the provider | the application **starts without the provider** (explicit endpoints, not `issuer-uri`) |

## Prerequisites

- Docker, and Python 3 with `requests` (`apt install python3-requests`).
- The **test directory** the defaults are written for: the laptop test domain `corp.local`, where `alice` (administrator,
  Sherlock), `bob` (Sherlock) and `charlie` (neither) have the UPNs `alice@corp.local`, `bob@corp.local`,
  `charlie@corp.local`. For another directory, pass `--users users.json` (a list of
  `{"upn", "account", "admin", "sherlock"}`; `"account": null` means the user must be refused).
- The application configured for the provider (below). The generated secrets and config go in `e2e/sso/.work/`,
  which is git-ignored.

## Run it

```bash
python3 e2e/sso/harness.py up        # pulls the pinned Keycloak, imports a 'corp' realm, writes the config to merge
# merge e2e/sso/.work/application-local.sso.yaml into config/application-local.yaml, then (re)start the application
python3 e2e/sso/harness.py test
```

Two more runs, each needing the application restarted the way it says:

```bash
# application started with -Dauth.password.enabled=false
python3 e2e/sso/harness.py test --password-off

# docker stop ic-sso-test, THEN start the application (it must start), then:
python3 e2e/sso/harness.py test --idp-down
```

One Keycloak can serve **several applications at once** (your dev machine and a VM, say): give `up` each application with
`--app`, the address Keycloak must listen on with `--kc-bind` (repeat it), and the name each application reaches Keycloak by
with `--kc-host` (in the same order as the `--app`s). It writes one config file per application, and **keeps the same secrets
from one `up` to the next** (so a configuration you already merged stays valid) unless you pass `--fresh`.

To test an application installed **on a VM** (the real release package, over HTTPS), let Keycloak listen on the
host-only network and give both sides the same address, then point the harness at the VM:

```bash
python3 e2e/sso/harness.py up   --app https://192.168.56.30:8443 --kc-bind 192.168.56.1 --kc-host 192.168.56.1
python3 e2e/sso/harness.py test --app https://192.168.56.30:8443 --insecure
# both at once (dev machine and VM):
python3 e2e/sso/harness.py up --app http://localhost:8080 --app https://192.168.56.30:8443 \
    --kc-bind 127.0.0.1 --kc-bind 192.168.56.1 --kc-host localhost --kc-host 192.168.56.1
```

Keycloak is then reachable by everything on that network (the throwaway secrets are all it protects); `down` it when
you are done. If the installation runs with `llm.enabled: false` (no model, as in a lab install), add `--no-sherlock`: Sherlock is then
closed for everybody, whatever their groups. Remove everything with `python3 e2e/sso/harness.py down`. Options: `--app URL` (or `IC_APP`; repeatable for `up`), `--registration ID`,
`--name`, `--kc-port`, `--kc-bind` and `--kc-host` (repeatable), `--fresh`, `--users FILE`, `--insecure` (self-signed HTTPS).

## Notes and limits

- Keycloak listens on `127.0.0.1` only, with throwaway secrets generated by `up`. Do not reuse them anywhere.
- Keycloak keeps its own session: signed in once, it signs the same person back in without a form. The harness
  forgets it before each sign-in; a real user sees this too (INSTALL.md says so).
- Keycloak sets `Secure` cookies even on `http://localhost`; browsers send them to localhost, plain `requests`
  does not, so the client drops the flag.
- It can only drive a login **form it can parse** (Keycloak's). It cannot automate Entra ID or Google (consent
  screens, multi-factor). For those, pilot by hand and use the log messages table in INSTALL.md.
- The Keycloak image is pinned in `harness.py`; `up` fails loudly if that version can no longer be pulled.
