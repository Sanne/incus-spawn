#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$PROJECT_ROOT/_site"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cp "$SCRIPT_DIR/index.html" "$OUT_DIR/"
cp "$SCRIPT_DIR/style.css" "$OUT_DIR/"

if [ -f "$SCRIPT_DIR/screenshot.png" ]; then
  cp "$SCRIPT_DIR/screenshot.png" "$OUT_DIR/"
fi

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

  // Generate TOC HTML
  let tocHtml = '<nav class=\"docs-toc\" aria-label=\"Table of contents\"><ul>';
  // Add Overview link to the top (h1 title)
  tocHtml += '<li class=\"toc-item\"><a href=\"#incus-spawn\">Overview</a></li>';
  tocItems.forEach(item => {
    const className = item.level === 3 ? 'toc-item toc-item-nested' : 'toc-item';
    tocHtml += '<li class=\"' + className + '\"><a href=\"#' + item.id + '\">' + item.text + '</a></li>';
  });
  tocHtml += '</ul></nav>';

  let html = tpl.replace('<!-- README_CONTENT -->', body);
  html = html.replace('<!-- TOC -->', tocHtml);
  fs.writeFileSync('$OUT_DIR/docs.html', html);
"

echo "Site built at $OUT_DIR"
