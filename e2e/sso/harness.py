#!/usr/bin/env python3
"""
End-to-end harness for IntraCherche's single sign-on (OpenID Connect), against a throwaway Keycloak.

It drives the whole login the way a browser does: the application, then the provider's login form, then the
application's callback, and checks which directory account the user ends up in. Nothing is mocked. It exists
because the unit tests cannot see the seams: the cookies of the callback, the redirects, the public pages, the
provider's own session. Several real bugs were found only this way.

    python3 e2e/sso/harness.py up                 # start Keycloak with a test realm; writes the config to paste
    python3 e2e/sso/harness.py up --app http://localhost:8080 --app https://192.168.56.30:8443 \\
        --kc-bind 127.0.0.1 --kc-bind 192.168.56.1 --kc-host localhost --kc-host 192.168.56.1   # two applications at once
    # ... paste .work/application-local.sso.yaml into your config, (re)start the application ...
    python3 e2e/sso/harness.py test               # run the scenarios
    python3 e2e/sso/harness.py test --password-off    # app started with auth.password.enabled=false
    python3 e2e/sso/harness.py test --idp-down        # Keycloak stopped before the application started
    python3 e2e/sso/harness.py down               # remove the container and the generated secrets

See e2e/sso/README.md. Exit status is non-zero when any check fails.
"""
from __future__ import annotations

import argparse
import html
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    sys.exit("This harness needs the 'requests' package (pip install requests, or apt install python3-requests).")

HERE = Path(__file__).resolve().parent
WORK = HERE / ".work"                      # generated secrets and config; git-ignored, removed by `down`
KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0"      # pinned: `up` fails loudly if it cannot be pulled
REALM = "corp"
CLIENT_ID = "intracherche"

# The users of the laptop test domain (corp.local). Override with --users FILE (same shape).
#   upn     : the Keycloak username, which the application reads as the claim preferred_username
#   account : the directory account (sAMAccountName) the user must end up in; null = must be refused
#   admin   : whether that account is an IntraCherche administrator
#   sherlock: whether that account may use Sherlock
DEFAULT_USERS = [
    {"upn": "alice@corp.local", "account": "alice", "admin": True, "sherlock": True},
    {"upn": "bob@corp.local", "account": "bob", "admin": False, "sherlock": True},
    {"upn": "charlie@corp.local", "account": "charlie", "admin": False, "sherlock": False},
    {"upn": "mallory@corp.local", "account": None, "admin": False, "sherlock": False},   # in Keycloak, not in the AD
]


# ─── up / down ────────────────────────────────────────────────────────────────

def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def users_of(args) -> list[dict]:
    return json.load(open(args.users)) if args.users else DEFAULT_USERS


def targets_of(args) -> list[tuple[str, str]]:
    """(application URL, Keycloak host name as that application and its browser reach it), paired by position."""
    apps = [a.rstrip("/") for a in (args.app or [os.environ.get("IC_APP", "http://localhost:8080")])]
    hosts = args.kc_host or ["localhost"]
    return [(a, hosts[i] if i < len(hosts) else hosts[-1]) for i, a in enumerate(apps)]


def cmd_up(args) -> int:
    if not shutil.which("docker"):
        sys.exit("docker is required for `up`")
    users = users_of(args)
    targets = targets_of(args)
    binds = args.kc_bind or ["127.0.0.1"]
    WORK.mkdir(mode=0o700, exist_ok=True)
    print(f"pulling {KEYCLOAK_IMAGE} (fails here if the pinned version is gone)…")
    run(["docker", "pull", "-q", KEYCLOAK_IMAGE])

    # Secrets stay the same from one `up` to the next (so a configuration you already merged stays valid), unless --fresh.
    cred_file = WORK / "credentials.json"
    old = json.loads(cred_file.read_text()) if cred_file.exists() and not args.fresh else {}
    passwords = {u["upn"]: old.get("passwords", {}).get(u["upn"]) or secrets.token_urlsafe(9) for u in users}
    client_secret = old.get("client_secret") or secrets.token_urlsafe(24)
    admin_pw = old.get("admin", {}).get("password") or secrets.token_urlsafe(12)
    redirects = [f"{app}/login/oauth2/code/{args.registration}" for app, _ in targets]
    realm = {
        "realm": REALM, "enabled": True, "sslRequired": "none", "registrationAllowed": False,
        "users": [{"username": u["upn"], "email": u["upn"], "emailVerified": True, "enabled": True,
                   "firstName": u["upn"].split("@")[0].capitalize(), "lastName": "Test",
                   "credentials": [{"type": "password", "value": passwords[u["upn"]], "temporary": False}]}
                  for u in users],
        "clients": [{"clientId": CLIENT_ID, "enabled": True, "protocol": "openid-connect", "publicClient": False,
                     "clientAuthenticatorType": "client-secret", "secret": client_secret,
                     "standardFlowEnabled": True, "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
                     "redirectUris": redirects, "attributes": {"pkce.code.challenge.method": "S256"},
                     "defaultClientScopes": ["web-origins", "profile", "email", "roles"]}],
    }
    realm_file = WORK / "corp-realm.json"
    realm_file.write_text(json.dumps(realm, indent=1)); realm_file.chmod(0o600)
    cred_file.write_text(json.dumps(
        {"admin": {"user": "admin", "password": admin_pw}, "client_secret": client_secret, "passwords": passwords}, indent=1))
    cred_file.chmod(0o600)

    subprocess.run(["docker", "rm", "-f", args.name], capture_output=True)
    publish = [x for b in binds for x in ("-p", f"{b}:{args.kc_port}:8080")]
    run(["docker", "run", "-d", "--name", args.name, *publish,
         "-e", "KC_BOOTSTRAP_ADMIN_USERNAME=admin", "-e", f"KC_BOOTSTRAP_ADMIN_PASSWORD={admin_pw}",
         "-v", f"{realm_file}:/opt/keycloak/data/import/corp-realm.json:ro",
         KEYCLOAK_IMAGE, "start-dev", "--import-realm"])

    def issuer_of(host: str) -> str:
        return f"http://{host}:{args.kc_port}/realms/{REALM}"

    for _ in range(80):
        try:
            if requests.get(issuer_of(targets[0][1]) + "/.well-known/openid-configuration", timeout=3).status_code == 200:
                break
        except requests.RequestException:
            pass
        time.sleep(3)
    else:
        sys.exit(f"Keycloak did not come up; see: docker logs {args.name}")

    print(f"Keycloak is up (container {args.name}), listening on {', '.join(f'{b}:{args.kc_port}' for b in binds)}.")
    for app, host in targets:
        disco = requests.get(issuer_of(host) + "/.well-known/openid-configuration").json()
        redirect = f"{app}/login/oauth2/code/{args.registration}"
        snippet = f"""# Generated by e2e/sso/harness.py up, for the application at {app} (Keycloak as {host}). Merge into its config:
#  - the '{args.registration}' registration and provider go under the existing spring.security.oauth2.client
#    (spring: -> security: -> oauth2: -> client:), next to registration/provider entries you already have;
#  - the auth: block replaces your auth.oidc settings.
# The three addresses are written out (not issuer-uri) so the application starts even when the provider is down.
spring:
  security:
    oauth2:
      client:
        registration:
          {args.registration}:
            client-id: {CLIENT_ID}
            client-secret: {client_secret}
            scope: openid,profile,email
            authorization-grant-type: authorization_code
            redirect-uri: {redirect}
            provider: {args.registration}
        provider:
          {args.registration}:
            authorization-uri: {disco['authorization_endpoint']}
            token-uri: {disco['token_endpoint']}
            jwk-set-uri: {disco['jwks_uri']}
            user-name-attribute: sub
auth:
  oidc:
    enabled: true
    mode: directory
    registrationId: {args.registration}
    issuer: {disco['issuer']}
    buttonLabel: "Se connecter avec Keycloak"
    directory:
      claim: preferred_username
      attribute: userPrincipalName
"""
        slug = re.sub(r"[^A-Za-z0-9]+", "-", urlparse(app).netloc).strip("-")
        out = WORK / f"application-local.sso-{slug}.yaml"
        out.write_text(snippet); out.chmod(0o600)
        print(f"Config for {app:<34}: {out}   (contains the client secret)")
    print(f"Test passwords  : {cred_file}")
    print("Then (re)start the application and run: python3 e2e/sso/harness.py test --app <its URL>")
    return 0


def cmd_down(args) -> int:
    subprocess.run(["docker", "rm", "-f", args.name], capture_output=True)
    shutil.rmtree(WORK, ignore_errors=True)
    print(f"container {args.name} and {WORK} removed")
    return 0


# ─── test ─────────────────────────────────────────────────────────────────────

class BrowserLikeSession(requests.Session):
    """Keycloak sets Secure cookies even on http://localhost, which browsers still send (localhost is a secure
    origin) and `requests` does not. Drop the flag so this client behaves like a browser."""

    def send(self, request, **kw):
        r = super().send(request, **kw)
        for c in self.cookies:
            c.secure = False
        return r


class Checks:
    def __init__(self) -> None:
        self.failed = 0

    def check(self, label: str, ok: bool, detail: str = "") -> None:
        print(("PASS  " if ok else "FAIL  ") + label + (f"   ({detail})" if detail else ""))
        self.failed += 0 if ok else 1


def cmd_test(args) -> int:
    creds = json.loads((WORK / "credentials.json").read_text()) if (WORK / "credentials.json").exists() else None
    if creds is None:
        sys.exit("no .work/credentials.json: run `up` first (this harness reads the passwords it generated)")
    users = users_of(args)
    if args.no_sherlock:       # llm.enabled: false closes Sherlock for everybody, whatever their groups
        users = [{**u, "sherlock": False} for u in users]
    app = targets_of(args)[0][0]
    verify = not args.insecure
    if args.insecure:
        import urllib3
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
    reg = args.registration
    c = Checks()

    def session() -> BrowserLikeSession:
        s = BrowserLikeSession(); s.verify = verify
        return s

    def sso_login(s: BrowserLikeSession, upn: str, same_idp_session: bool = False) -> requests.Response:
        """App -> Keycloak login form -> back to the app's callback. Keycloak keeps its own session (signed in once,
        it signs the same person in again without a form), so unless asked otherwise forget it first."""
        if not same_idp_session:
            for ck in list(s.cookies):
                if ck.path.startswith("/realms/"):
                    s.cookies.clear(ck.domain, ck.path, ck.name)
        r = s.get(f"{app}/oauth2/authorization/{reg}", allow_redirects=True)
        if "kc-form-login" not in r.text:
            return r
        action = html.unescape(re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', r.text).group(1))
        return s.post(action, data={"username": upn, "password": creds["passwords"][upn], "credentialId": ""},
                      allow_redirects=True)

    def me(s: BrowserLikeSession):
        r = s.get(f"{app}/api/auth/me")
        return r.status_code, (r.json() if r.status_code == 200 else None)

    opts = requests.get(f"{app}/api/auth/options", verify=verify).json()
    c.check("the application answers and offers single sign-on", opts.get("oidc") is True, str(opts))

    if args.idp_down:
        r = requests.get(f"{app}/oauth2/authorization/{reg}", allow_redirects=False, verify=verify)
        c.check("the way out to the provider is built although the provider is down",
                r.status_code == 302 and "/protocol/openid-connect/auth" in r.headers.get("Location", ""),
                f"{r.status_code} {r.headers.get('Location', '')[:70]}")
        print("\n" + ("ALL PASSED" if c.failed == 0 else f"{c.failed} FAILED"))
        return 1 if c.failed else 0

    if args.password_off:
        c.check("the login page is told the password form is off", opts.get("passwordEnabled") is False and opts.get("passwordForm") is False)
        r = requests.post(f"{app}/api/auth/login", json={"username": users[0]["account"] or "x", "password": "any"}, verify=verify)
        c.check("the password login refuses everyone, whatever the password", r.status_code == 403, f"HTTP {r.status_code}")
    else:
        c.check("the password login is still on", opts.get("passwordEnabled") is True)

    sessions = {}
    for u in users:
        s = session(); r = sso_login(s, u["upn"]); code, body = me(s); sessions[u["upn"]] = s
        if u["account"]:
            ok = code == 200 and body["username"] == u["account"] and body["isAdmin"] == u["admin"] and body["canUseSherlock"] == u["sherlock"]
            c.check(f"{u['upn']} signs in as '{u['account']}' (admin={u['admin']}, sherlock={u['sherlock']})", ok, f"/me={body}")
        else:
            ok = "oidcError=1" in r.url and code == 401 and "jwt" not in s.cookies and "__Host-jwt" not in s.cookies
            c.check(f"{u['upn']} (no directory account) is refused, back on the login page with its message", ok, f"landed {r.url.replace(app, '')}, /me={code}")

    known = [u for u in users if u["account"]]
    unknown = [u for u in users if not u["account"]]
    if known and unknown:
        s = session(); sso_login(s, known[0]["upn"]); before = me(s)[0]
        sso_login(s, unknown[0]["upn"]); after = me(s)[0]
        c.check("a refused sign-in ends the session that was open in the same browser", before == 200 and after == 401, f"/me before={before}, after={after}")

    if known:
        s = session(); sso_login(s, known[0]["upn"]); s.get(f"{app}/api/auth/options")
        xsrf = s.cookies.get("__Host-XSRF-TOKEN") or s.cookies.get("XSRF-TOKEN")
        r = s.post(f"{app}/api/auth/logout", headers={"X-XSRF-TOKEN": xsrf or ""}); after = me(s)[0]
        c.check("logout after SSO works (POST with the CSRF token the page sends)", r.status_code == 200 and after == 401, f"logout={r.status_code}, /me after={after}")

    if known:
        # The callback response, read by hand: the handshake session is thrown away, so its cookie must be expired too
        # (Spring Security changes the session id on authentication, which sets a cookie for a session already gone).
        s = session(); r = s.get(f"{app}/oauth2/authorization/{reg}")
        if "kc-form-login" in r.text:
            action = html.unescape(re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', r.text).group(1))
            r = s.post(action, data={"username": known[0]["upn"], "password": creds["passwords"][known[0]["upn"]], "credentialId": ""},
                       allow_redirects=False)
            cb = s.get(r.headers["Location"], allow_redirects=False)
            jsession = [h for h in cb.raw.headers.getlist("Set-Cookie") if h.startswith("JSESSIONID=")]
            c.check("the handshake session cookie is expired when the login ends (no dead cookie left behind)",
                    bool(jsession) and "Max-Age=0" in jsession[-1], f"{len(jsession)} JSESSIONID header(s), last: {jsession[-1][:60] if jsession else 'none'}")
        else:
            c.check("the handshake session cookie is expired when the login ends", False, "no login form")

    admins = [u for u in known if u["admin"]]; others = [u for u in known if not u["admin"]]
    if admins and others:
        ra = sessions[admins[0]["upn"]].get(f"{app}/api/admin/indexer/status")
        rb = sessions[others[0]["upn"]].get(f"{app}/api/admin/indexer/status")
        c.check(f"admin API: '{admins[0]['account']}' allowed, '{others[0]['account']}' refused", ra.status_code == 200 and rb.status_code == 403, f"{ra.status_code} / {rb.status_code}")

    r = requests.get(f"{app}/login", params={"oidcError": "1"}, allow_redirects=False, verify=verify)
    c.check("the public login page keeps its query string (no redirect to '/')", r.status_code == 200, f"HTTP {r.status_code}")

    print("\n" + ("ALL PASSED" if c.failed == 0 else f"{c.failed} FAILED"))
    return 1 if c.failed else 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["up", "test", "down"])
    p.add_argument("--app", action="append",
                   help="application base URL (default $IC_APP or http://localhost:8080). `up` accepts it several times, one "
                        "per application to register; `test` uses the first")
    p.add_argument("--registration", default="sso", help="registrationId of the provider in the application's config")
    p.add_argument("--name", default="ic-sso-test", help="Keycloak container name")
    p.add_argument("--kc-port", type=int, default=8081, help="port of Keycloak")
    p.add_argument("--kc-bind", action="append",
                   help="address Keycloak listens on, repeatable (default 127.0.0.1); to serve an application on a VM add the "
                        "host-only address (e.g. 192.168.56.1)")
    p.add_argument("--kc-host", action="append",
                   help="name Keycloak is reached by, one per --app in the same order (default localhost): the browser and the "
                        "application must both reach it that way (e.g. 192.168.56.1 for a VM)")
    p.add_argument("--fresh", action="store_true", help="`up`: generate new secrets instead of keeping the ones in .work/")
    p.add_argument("--users", help="JSON file replacing the default test users (same shape)")
    p.add_argument("--insecure", action="store_true", help="do not verify the application's TLS certificate")
    p.add_argument("--no-sherlock", action="store_true",
                   help="the application runs with llm.enabled: false (Sherlock closed for everybody)")
    p.add_argument("--password-off", action="store_true", help="expect the application to run with auth.password.enabled=false")
    p.add_argument("--idp-down", action="store_true", help="expect Keycloak to be stopped; only check the application still serves")
    args = p.parse_args()
    return {"up": cmd_up, "test": cmd_test, "down": cmd_down}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
