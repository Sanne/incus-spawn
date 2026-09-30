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

TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

npx --yes marked -i "$PROJECT_ROOT/README.md" --gfm -o "$TMPFILE"

node -e "
  const fs = require('fs');
  const tpl = fs.readFileSync('$SCRIPT_DIR/docs.html', 'utf8');
  let body = fs.readFileSync('$TMPFILE', 'utf8');

  const tocItems = [];

  body = body.replace(/<(h[1-6])>(.*?)<\/h[1-6]>/g, (m, tag, text) => {
    const id = text.replace(/<[^>]+>/g, '').toLowerCase()
      .replace(/[^\w\s-]/g, '').replace(/\s+/g, '-').replace(/-+$/, '');
    const level = parseInt(tag.substring(1));
    const cleanText = text.replace(/<[^>]+>/g, '');

    // Only include h2 and h3 in TOC (skip h1 which is the title, and h4+ which are too granular)
    if (level === 2 || level === 3) {
      tocItems.push({ level, id, text: cleanText });
    }

    return '<' + tag + ' id=\"' + id + '\">' + text + '</' + tag + '>';
  });

  // Transform <!-- tabs:os --> ... <!-- tabs:end --> blocks into tabbed UI
  function classifyOS(text) {
    const l = text.toLowerCase();
    if (/mac/.test(l)) return 'macos';
    if (/fedora|ubuntu|debian|linux|arch|nix/.test(l)) return 'linux';
    return 'any';
  }

  const consumedIds = [];
  body = body.replace(/<!-- tabs:os -->[\\s\\S]*?<!-- tabs:end -->/g, function(match) {
    const headingRe = /<(h[1-6]) id=\"([^\"]*)\">(.*?)<\\/h[1-6]>/g;
    const tabs = [];
    let m;
    while ((m = headingRe.exec(match)) !== null) {
      if (tabs.length > 0) {
        tabs[tabs.length - 1].content = match.slice(tabs[tabs.length - 1].end, m.index);
      }
      const cleanText = m[3].replace(/<[^>]+>/g, '');
      consumedIds.push(m[2]);
      tabs.push({ id: m[2], text: cleanText, os: classifyOS(cleanText), end: m.index + m[0].length });
    }
    if (tabs.length === 0) return match;
    tabs[tabs.length - 1].content = match.slice(tabs[tabs.length - 1].end).replace('<!-- tabs:end -->', '');

    // Capture any content before the first heading (intro text)
    const firstIdx = match.indexOf('<' + match.match(/<(h[1-6]) id/)[1]);
    const preContent = match.slice(match.indexOf('-->') + 3, firstIdx).trim();

    let html = preContent ? preContent : '';
    html += '<div class=\"docs-tabs\" data-tabs=\"os\">';
    html += '<div class=\"tab-buttons\">';
    tabs.forEach(function(tab, i) {
      html += '<button class=\"tab-btn' + (i === 0 ? ' active' : '') + '\" data-tab=\"' + i + '\" data-os=\"' + tab.os + '\">' + tab.text + '</button>';
    });
    html += '</div>';
    tabs.forEach(function(tab, i) {
      html += '<div class=\"tab-panel' + (i === 0 ? ' active' : '') + '\" data-tab-panel=\"' + i + '\" id=\"' + tab.id + '\">' + tab.content + '</div>';
    });
    html += '</div>';
    return html;
  });

  // Filter consumed headings from TOC
  const filteredTocItems = tocItems.filter(function(item) { return consumedIds.indexOf(item.id) === -1; });

  // Generate TOC HTML
  let tocHtml = '<nav class=\"docs-toc\" aria-label=\"Table of contents\"><ul>';
  // Add Overview link to the top (h1 title)
  tocHtml += '<li class=\"toc-item\"><a href=\"#isx\">Overview</a></li>';
  filteredTocItems.forEach(item => {
    const className = item.level === 3 ? 'toc-item toc-item-nested' : 'toc-item';
    tocHtml += '<li class=\"' + className + '\"><a href=\"#' + item.id + '\">' + item.text + '</a></li>';
  });
  tocHtml += '</ul></nav>';

  let html = tpl.replace('<!-- README_CONTENT -->', body);
  html = html.replace('<!-- TOC -->', tocHtml);
  fs.writeFileSync('$OUT_DIR/docs.html', html);
"

# Sitemap, dated by the last commit that changed each page's content. Needs the
# history: the deploy workflows check out with fetch-depth: 0.
lastmod() { git -C "$PROJECT_ROOT" log -1 --format=%cs -- "$@"; }
cat > "$OUT_DIR/sitemap.xml" <<SITEMAP
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://isx.run/</loc>
    <lastmod>$(lastmod site/index.html)</lastmod>
  </url>
  <url>
    <loc>https://isx.run/docs</loc>
    <lastmod>$(lastmod README.md site/docs.html)</lastmod>
  </url>
</urlset>
SITEMAP

# Fingerprint the stylesheet's address. The pages are revalidated on every visit, but the
# isx.run zone lets browsers keep style.css for hours, so without this a returning visitor
# gets new HTML with an old stylesheet (seen after the terminal-frame change).
# (Node rather than sed -i and sha256sum, which differ or are missing on macOS.)
OUT_DIR="$OUT_DIR" node -e '
  const fs = require("fs"), path = require("path"), out = process.env.OUT_DIR;
  const v = require("crypto").createHash("sha256")
    .update(fs.readFileSync(path.join(out, "style.css"))).digest("hex").slice(0, 10);
  for (const f of fs.readdirSync(out).filter(f => f.endsWith(".html"))) {
    const p = path.join(out, f);
    fs.writeFileSync(p, fs.readFileSync(p, "utf8").replace(/href="(\/?)style\.css"/g, `href="$1style.css?v=${v}"`));
  }
'

# llms.txt (https://llmstxt.org): a map of the site for AI tools, plus the full
# docs as one Markdown file -- the README the docs page is built from.
cp "$SCRIPT_DIR/llms.txt" "$OUT_DIR/"
cp "$PROJECT_ROOT/README.md" "$OUT_DIR/llms-full.txt"

echo "Site built at $OUT_DIR"
