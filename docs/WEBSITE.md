# Website: isx.run

The site at **https://isx.run** is built from `site/` and `README.md` by `site/build.sh`, and served by
**Cloudflare Pages**. The DNS for both domains, isx.run and getisx.com, is on Cloudflare as well. Most of the
setup lives in the Cloudflare dashboard, not in this repository, so it is written down here.

## Deploys

| What | Where it goes | Workflow |
|---|---|---|
| Push to `main` touching `site/**`, `README.md` or the workflow | Production: isx.run | `.github/workflows/pages.yml` |
| Pull request touching `site/**` or `README.md` | `https://pr-<N>.isx-5ks.pages.dev`, linked in a PR comment | `.github/workflows/preview.yml` |
| Pull request closed | Its preview deployments are deleted | `.github/workflows/preview-cleanup.yml` |

- **Project:** all three use the Cloudflare Pages project **`isx`**. Its address is `isx-5ks.pages.dev`; `isx.pages.dev` belongs to someone else.
- **Credentials:** the repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
- **Production branch:** the project's production branch (Pages project → Settings → Branch control) must stay `main`. `pages.yml` deploys with `--branch=main`, and a deploy to any other branch name only becomes a preview.

Cloudflare Pages behaves differently from GitHub Pages in two ways the site accounts for:

- **404 page:** without a `404.html`, every unknown path gets `index.html` with status 200. `site/404.html` is served
  instead, with status 404. It is served at every depth, so its links are absolute.
- **`.html` extensions:** `/docs.html` is redirected (308) to `/docs`, so the site links to `/docs`. Old `docs.html` links still work
  through the redirect.

## Cloudflare configuration

### isx.run zone

**DNS records**
| Name | Type | Value | Proxy |
|---|---|---|---|
| `isx.run` | Pages custom domain | project `isx` | proxied |
| `www` | CNAME | `isx-5ks.pages.dev` (any proxied target works: the redirect rule answers first) | proxied |
| `isx.run` | MX | `0 .` (null MX: the domain accepts no mail) | DNS only |
| `isx.run` | TXT | `v=spf1 -all` (nothing may send mail as isx.run) | DNS only |
| `_dmarc` | TXT | `v=DMARC1; p=reject;` | DNS only |

There is no CAA record. If one is ever added, it must allow `letsencrypt.org` and `pki.goog`, which Cloudflare uses for
the edge certificates.

**Redirect rules** (Rules → Redirect Rules). Cloudflare answers these itself, before the request reaches Pages:
1. **Installer:** `(http.request.uri.path eq "/") and (http.user_agent contains "curl" or http.user_agent contains "Wget")`
   → 302 to `https://raw.githubusercontent.com/Sanne/incus-spawn/main/get-isx.sh`. This is what makes
   `curl -fsSL https://isx.run | sh` work.
2. **www to apex:** hostname equals `www.isx.run` → dynamic `concat("https://isx.run", http.request.uri.path)`, 301,
   preserve query string on. Don't use the dashboard's "WWW to root" template expression
   (`wildcard_replace(http.request.full_uri, "https://www.*", ...)`): its match condition also covers plain HTTP,
   but its expression only rewrites `https://` addresses, so `http://www.isx.run` redirected to itself.

**SSL/TLS:** encryption mode **Full (strict)**, **Always Use HTTPS** on, and HSTS off. Pages has no GitHub server behind it, so
the mode only matters for anything proxied to another server later.

### getisx.com zone

This zone only redirects: `getisx.com` and `www.getisx.com`, over HTTP and HTTPS, send a 301 to `https://isx.run/` with the path kept.
`curl -fsSL https://getisx.com | sh` works by following that redirect into the installer rule above. It has the same
email records as isx.run.

## Why not GitHub Pages

The site used to be on GitHub Pages, with the Cloudflare proxy in front. GitHub does not issue or renew a
certificate for a custom domain whose DNS points at a proxy: its health check
(`gh api repos/Sanne/incus-spawn/pages/health`, needs admin) reported `is_proxied: true` and `is_https_eligible: false`.
The domain's first certificate (issued 2026-07-02) was never renewed. On 2026-09-30 it expired, and Cloudflare, in Full
(strict) mode, answered every page with a 526 until the site moved to Pages. Don't move it back behind the proxy.
The only ways to use GitHub Pages behind Cloudflare are an SNI override (Enterprise plans only) or running without origin
certificate validation.

## Checking the site

```sh
curl -sSI https://isx.run/ | grep -iE '^(HTTP|cache-control)'   # 200, max-age=0, must-revalidate (Pages)
curl -sS -o /dev/null -w '%{http_code}\n' https://isx.run/no-such-page   # 404
curl -sS -o /dev/null -w '%{redirect_url}\n' http://www.isx.run/docs     # https://isx.run/docs
curl -fsSL https://isx.run | head -1                                      # #!/bin/sh
curl -fsSL https://getisx.com | head -1                                   # #!/bin/sh
```
