#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$PROJECT_ROOT/_site"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cp "$SCRIPT_DIR/index.html" "$OUT_DIR/"
cp "$SCRIPT_DIR/404.html" "$OUT_DIR/"
cp "$SCRIPT_DIR/style.css" "$OUT_DIR/"
cp "$SCRIPT_DIR/copy.js" "$OUT_DIR/"
cp "$SCRIPT_DIR/favicon.svg" "$OUT_DIR/"
cp "$SCRIPT_DIR/robots.txt" "$OUT_DIR/"
# IndexNow key file: proves the pings sent after a deploy (pages.yml) come from this site.
cp "$SCRIPT_DIR/indexnow-key.txt" "$OUT_DIR/"
cp "$SCRIPT_DIR/_headers" "$OUT_DIR/"
cp "$SCRIPT_DIR/og.png" "$OUT_DIR/"

if [ -f "$SCRIPT_DIR/screenshot.png" ]; then
  cp "$SCRIPT_DIR/screenshot.png" "$OUT_DIR/"
fi

# Vendored player + font subset
mkdir -p "$OUT_DIR/vendor"
cp -R "$SCRIPT_DIR"/vendor/. "$OUT_DIR/vendor/"

# Casts: copy at stable names, referenced directly from index.html. No content
# hashing and no separate manifest: those introduced a cache-desync bug where a
# long-cached casts.js pointed at hashed files that a redeploy had deleted,
# 404ing the player. Stable names never 404; ETag revalidation (short max-age)
# propagates retakes on its own.
mkdir -p "$OUT_DIR/casts"
for c in "$SCRIPT_DIR"/demo/*-web.cast; do
  cp "$c" "$OUT_DIR/casts/$(basename "$c" -web.cast).cast"
done

# The docs pages: README.md at /docs, every docs/*.md at /docs/<name>, with the sitemap and
# llms-full.txt that list them (site/render.js)
MARKED=$(mktemp -d)
trap 'rm -rf "$MARKED"' EXIT
npm install --silent --no-save --prefix "$MARKED" marked@18.1.1 >/dev/null
NODE_PATH="$MARKED/node_modules" node "$SCRIPT_DIR/render.js" "$PROJECT_ROOT" "$OUT_DIR"

# Fingerprint the addresses of the stylesheet and of copy.js. The pages are revalidated on every
# visit, but the isx.run zone lets browsers keep these for hours, so without this a returning
# visitor gets new HTML with an old stylesheet (seen after the terminal-frame change).
# (Node rather than sed -i and sha256sum, which differ or are missing on macOS.)
OUT_DIR="$OUT_DIR" node -e '
  const fs = require("fs"), path = require("path"), out = process.env.OUT_DIR;
  const hash = f => require("crypto").createHash("sha256")
    .update(fs.readFileSync(path.join(out, f))).digest("hex").slice(0, 10);
  const css = hash("style.css"), js = hash("copy.js");
  for (const f of fs.readdirSync(out, { recursive: true }).filter(f => f.endsWith(".html"))) {
    const p = path.join(out, f);
    fs.writeFileSync(p, fs.readFileSync(p, "utf8")
      .replace(/href="(\/?)style\.css"/g, `href="$1style.css?v=${css}"`)
      .replace(/src="(\/?)copy\.js"/g, `src="$1copy.js?v=${js}"`));
  }
'

# llms.txt (https://llmstxt.org): a map of the site for AI tools
cp "$SCRIPT_DIR/llms.txt" "$OUT_DIR/"

echo "Site built at $OUT_DIR"
