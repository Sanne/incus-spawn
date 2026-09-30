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
| `isx.run` | TXT | `google-site-verification=…` (Google Search Console; removing it loses access to the property) | DNS only |

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

**Other zone settings:**
- **DNSSEC** on, for both zones. The DS record is published at each domain's registrar, from the values in Cloudflare's
  DNS → Settings.
- **Minimum TLS version** 1.2.
- **Bot Fight Mode** off, and AI crawlers allowed with no Cloudflare-managed `robots.txt`. A bot challenge would break
  `curl … | sh` without a clear error, and AI assistants reading the site (`llms.txt`) is intended.
- **Web Analytics** on, in the Pages project's Metrics tab. Cloudflare adds its beacon script to the pages at deploy time, so
  enabling it takes effect on the next deploy.
- **Observatory** (Speed → Observatory) runs scheduled Lighthouse tests against `https://isx.run/`.

**Search engines:** isx.run is a *Domain* property in Google Search Console, verified by the TXT record above, with
`https://isx.run/sitemap.xml` submitted; Bing Webmaster Tools can import it from there. The Performance and Pages
reports show what Google indexes and which searches find the site.
- Left off on purpose: Smart Shield, Argo, Cache Reserve, APO, Polish, Rocket Loader. They help an origin server or a
  heavy site, and a Pages site has no origin server; Rocket Loader rewrites the page's scripts.

### getisx.com zone

This zone only redirects: `getisx.com` and `www.getisx.com`, over HTTP and HTTPS, send a 301 to `https://isx.run/` with the path kept.
`curl -fsSL https://getisx.com | sh` works by following that redirect into the installer rule above. It has the same
email records as isx.run.

## How the site is built

Everything below is produced by `site/build.sh` from files in `site/`, unless a generator script is named.

**Headers** (`site/_headers`): the demo casts are served as `text/plain`, because Cloudflare does not compress the
`application/octet-stream` an unknown extension gets (`hero.cast` 84 KB -> 12 KB). Files with a version in their name
(`vendor/asciinema-player-*`, `vendor/fonts/*`) are cached for a year as `immutable`, so **replacing one means a new
file name**.

**Fonts** (`site/vendor/fonts/`, written by `site/subset-fonts.py`, which also writes the `fonts:begin`/`fonts:end`
block in `style.css`). Inter, JetBrains Mono and Space Grotesk are self-hosted: no request leaves isx.run. Per family:
- `*-core.woff2`: ASCII and the site's punctuation, the file every page needs, preloaded for Inter and Space Grotesk.
  It keeps every weight: cutting the weight axis saved ~11 KB but shifted the text's anti-aliasing.
- `*-latin.woff2`, `*-latin-ext.woff2`: Google Fonts' files, unchanged, for every other character. A browser only
  fetches them for a page that has such a character, so no character falls back to another font.
- `jetbrains-mono-2.304-symbols.woff2`: box drawing, blocks, arrows, shapes and the Powerline branch icon, cut from
  the upstream JetBrains Mono release (Google does not serve them). The casts' TUIs and prompts, and the docs' diagrams,
  draw with it.

The script's output is reproducible. After changing it, compare screenshots of the pages before and after: the core
files were checked to render pixel-identically to Google's.

**Demo casts** (`site/demo/`, made with `record.py` and `compress.py`, see `SCRIPT.md`). The asciinema player is
loaded with `defer`, so it does not hold up the first paint. How the player is set up matters:
- It sizes the terminal from one character it measures in `terminalFontFamily` at a hard-coded 15px, ignoring both
  `terminalFontSize` and the theme's font. So `index.html` passes JetBrains Mono as `terminalFontFamily`, creates each
  player only once that font has loaded, and uses `fit: "width"`; `style.css` makes every terminal frame 80 columns
  wide at 0.84rem, which is what sets the text size. Setting `terminalFontSize` instead breaks fullscreen, which
  scales from that measurement.
- The `isx` theme in `style.css` maps ANSI colours to the site's palette: greys, the site's green, muted red and
  amber. The player draws bold in the bright variant (colour + 8), so colours 9-14 repeat 1-6 and bold stays a
  weight rather than a brighter colour. Colours a program sets as 24-bit RGB (Claude Code, btop, the TUI) are not
  remapped; only a new recording changes those.

**Link previews and search:**
- `og.png`, the 1200x630 card for `og:image`/`twitter:image`, is drawn by `site/og-image.py` from the headline and
  `screenshot.png`. Rerun it and commit the result when either changes.
- The home page's meta, Open Graph and Twitter descriptions and its JSON-LD `SoftwareApplication` description are one
  text; change them together.
- `sitemap.xml` is generated, dated by each page's last commit, which is why the deploy workflows fetch the full
  history (`fetch-depth: 0`).
- `llms.txt` is written by hand and should say what the home page says; `llms-full.txt` is the README, copied at build
  time.

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
curl -sSI -H 'Accept-Encoding: br' https://isx.run/casts/hero.cast | grep -i '^content-encoding'   # br
curl -sSI https://isx.run/vendor/fonts/inter-v20-core.woff2 | grep -i '^cache-control'      # max-age=31536000, immutable
```

For speed, `npx lighthouse https://isx.run/ --only-categories=performance` (mobile, simulated slow 4G) scored 100
after these changes, with the first paint at about 0.9 s.
