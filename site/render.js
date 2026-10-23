// Renders the documentation pages of isx.run: README.md at /docs, and every docs/*.md beside it at
// /docs/<name> (docs/CLI.md is /docs/cli). Maintainer notes live in docs/dev/ and are not published.
// Links between the Markdown files become links between the pages; any other relative link goes to
// the file on GitHub.
//
// Usage: node render.js <project root> <output dir>, with marked resolvable (NODE_PATH).
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { marked } = require('marked');

const [root, out] = process.argv.slice(2);
const GITHUB = 'https://github.com/Sanne/incus-spawn/blob/main/';
const tpl = fs.readFileSync(path.join(root, 'site/docs.html'), 'utf8');

// Every page: its source, relative to the root, and its address on the site. They are listed in
// reading order: the reference a user looks things up in, any page not named here, then the
// project's own character and vision.
const FIRST = ['templates', 'agents', 'tools', 'configuration', 'cli', 'mcp', 'credential-proofs', 'sandboxes', 'fedora'];
const LAST = ['vision'];
const rank = name => {
  const first = FIRST.indexOf(name), last = LAST.indexOf(name);
  return first >= 0 ? first : last >= 0 ? FIRST.length + 1 + last : FIRST.length;
};
const pages = [{ src: 'README.md', url: '/docs' }].concat(
  fs.readdirSync(path.join(root, 'docs'))
    .filter(f => f.endsWith('.md'))
    .map(f => ({ src: 'docs/' + f, name: f.slice(0, -3).toLowerCase(), url: '/docs/' + f.slice(0, -3).toLowerCase() }))
    .sort((a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name)));

// Sections that were on /docs before the reference moved to pages of its own, and are not found
// by their id on a page (#1440): the Installation tabs folded into the Quick Start, and the
// template section, now a page whose title gives it another id.
const RENAMED = {
  'macos-homebrew': '/docs#quick-start', 'fedora-rhel-dnf': '/docs#quick-start',
  'ubuntu-debian-apt': '/docs#quick-start', 'any-linux-distro-native-binary': '/docs#quick-start',
  'jvm-via-jbang': '/docs#quick-start', 'template-images': '/docs/templates',
};
for (const name of FIRST.concat(LAST)) {
  if (!pages.some(p => p.name === name)) throw new Error('render.js lists docs/' + name.toUpperCase() + '.md, which does not exist');
}
const bySrc = new Map(pages.map(p => [p.src, p]));

const decode = s => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const text = html => decode(html.replace(/<[^>]+>/g, ''));
// The same id GitHub gives the heading, for the simple headings these files use.
const slug = s => s.toLowerCase().replace(/[^\w\s-]/g, '').replace(/\s+/g, '-').replace(/-+$/, '');

function rewriteLinks(body, page) {
  return body.replace(/href="([^"#:]*)(#[^"]*)?"/g, (m, target, hash) => {
    if (target === '' || target.startsWith('/')) return m;
    const file = path.posix.normalize(path.posix.join(path.posix.dirname(page.src), target));
    const linked = bySrc.get(file);
    return 'href="' + (linked ? linked.url : GITHUB + file) + (hash || '') + '"';
  });
}

// Transform <!-- tabs:os --> ... <!-- tabs:end --> blocks into tabbed UI
function classifyOS(t) {
  const l = t.toLowerCase();
  if (/mac/.test(l)) return 'macos';
  if (/fedora|ubuntu|debian|linux|arch|nix/.test(l)) return 'linux';
  return 'any';
}

function tabs(body, consumedIds) {
  return body.replace(/<!-- tabs:os -->[\s\S]*?<!-- tabs:end -->/g, match => {
    const headingRe = /<(h[1-6]) id="([^"]*)">(.*?)<\/h[1-6]>/g;
    const found = [];
    let m;
    while ((m = headingRe.exec(match)) !== null) {
      if (found.length > 0) found[found.length - 1].content = match.slice(found[found.length - 1].end, m.index);
      const t = text(m[3]);
      consumedIds.push(m[2]);
      found.push({ id: m[2], text: t, os: classifyOS(t), start: m.index, end: m.index + m[0].length });
    }
    if (found.length === 0) return match;
    found[found.length - 1].content = match.slice(found[found.length - 1].end).replace('<!-- tabs:end -->', '');

    // Any content before the first heading (intro text)
    let html = match.slice(match.indexOf('-->') + 3, found[0].start).trim();
    html += '<div class="docs-tabs" data-tabs="os">';
    html += '<div class="tab-buttons">';
    found.forEach((tab, i) => {
      html += '<button class="tab-btn' + (i === 0 ? ' active' : '') + '" data-tab="' + i + '" data-os="' + tab.os + '">' + escape(tab.text) + '</button>';
    });
    html += '</div>';
    found.forEach((tab, i) => {
      html += '<div class="tab-panel' + (i === 0 ? ' active' : '') + '" data-tab-panel="' + i + '" id="' + tab.id + '">' + tab.content + '</div>';
    });
    html += '</div>';
    return html;
  });
}

const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

for (const page of pages) {
  page.md = fs.readFileSync(path.join(root, page.src), 'utf8');
  page.title = (page.md.match(/^# (.*)$/m) || [, page.src])[1].replace(/[`*]/g, '');
}

// The page's introduction, its first paragraph before any section, cut at a word to the length
// search engines show; null when the page opens with a section
function description(body) {
  const intro = body.split(/<h2[ >]/)[0].match(/<p>([\s\S]*?)<\/p>/);
  if (!intro) return null;
  const first = text(intro[1]).replace(/\s+/g, ' ').trim();
  if (first.length <= 160) return first;
  const cut = first.lastIndexOf(' ', 157);
  return first.slice(0, cut > 0 ? cut : 157).replace(/[.,;:]$/, '') + '…';
}

for (const page of pages) {
  let body = marked.parse(page.md, { gfm: true });
  page.tocItems = [];
  page.ids = new Set();
  page.oldIds = new Map(); // the id the inline renderer gave a heading before #1440, where it differs
  body = body.replace(/<(h[1-6])>(.*?)<\/h[1-6]>/g, (m, tag, inner) => {
    const t = text(inner);
    const id = slug(t);
    const level = parseInt(tag.substring(1));
    if (level === 1 && page.top === undefined) page.top = id;
    page.ids.add(id);
    const old = slug(inner.replace(/<[^>]+>/g, ''));
    if (old !== id) page.oldIds.set(old, id);
    // Only h2 and h3 in the TOC: h1 is the title, h4 and below are too granular
    if (level === 2 || level === 3) page.tocItems.push({ level, id, text: t });
    return '<' + tag + ' id="' + id + '">' + inner + '</' + tag + '>';
  });
  page.consumedIds = [];
  page.body = rewriteLinks(tabs(body, page.consumedIds), page);
}

// Where a link to /docs#<id> goes when the README no longer has that id: the page that has it
const moved = Object.assign({}, RENAMED);
for (const page of pages) {
  for (const [old, id] of page.oldIds) if (!pages[0].ids.has(old)) moved[old] ??= page.url + '#' + id;
}
for (const page of pages.slice(1)) {
  for (const id of page.ids) if (!pages[0].ids.has(id)) moved[id] ??= page.url + '#' + id;
}

// On /docs only: a link to a section that moved goes on to its page
const redirect = `<script>
(function() {
  var moved = ${JSON.stringify(moved).replace(/</g, '\\u003c')};
  function follow() {
    var id;
    try { id = decodeURIComponent(location.hash.slice(1)); } catch (e) { return; }
    if (id && !document.getElementById(id) && moved[id]) location.replace(moved[id]);
  }
  follow();
  window.addEventListener('hashchange', follow);
})();
</script>`;

for (const page of pages) {
  const { body, tocItems, consumedIds, top } = page;

  let toc = '<nav class="docs-toc" aria-label="Table of contents"><ul>';
  toc += '<li class="toc-item"><a href="#' + top + '">Overview</a></li>';
  tocItems.filter(item => !consumedIds.includes(item.id)).forEach(item => {
    toc += '<li class="toc-item' + (item.level === 3 ? ' toc-item-nested' : '') + '"><a href="#' + item.id + '">' + escape(item.text) + '</a></li>';
  });
  toc += '</ul><p class="toc-heading">Documentation</p><ul>';
  pages.forEach(p => {
    toc += '<li class="toc-item"><a href="' + p.url + '"' + (p === page ? ' aria-current="page" class="active"' : '') + '>'
      + escape(p === pages[0] ? 'Start here' : p.title) + '</a></li>';
  });
  toc += '</ul></nav>';

  // The TOC is hidden on narrow screens, so the page list also follows the content there
  const more = '<nav class="docs-pages" aria-label="Documentation"><p class="toc-heading">Documentation</p><ul>'
    + pages.map(p => '<li><a href="' + p.url + '">' + escape(p === pages[0] ? 'Start here' : p.title) + '</a></li>').join('')
    + '</ul></nav>';
  let html = tpl.replace('<!-- README_CONTENT -->', () => body + more).replace('<!-- TOC -->', () => toc)
    .replace('<!-- MOVED -->', () => page === pages[0] ? redirect : '');
  if (page !== pages[0]) {
    const url = 'https://isx.run' + page.url;
    const desc = description(body);
    html = html.replace(/<title>.*?<\/title>/, '<title>' + escape(page.title) + ' &mdash; isx</title>')
      .replace(/(<meta (?:name="description"|property="og:description"|name="twitter:description") content=")[^"]*/g,
        (m, attr) => desc === null ? m : attr + escape(desc))
      .replace(/(<link rel="canonical" href=")[^"]*/, '$1' + url)
      .replace(/(<meta property="og:url" content=")[^"]*/, '$1' + url)
      .replace(/(<meta (?:property="og|name="twitter):title" content=")[^"]*/g, '$1' + escape(page.title) + ' — isx');
  }
  const file = path.join(out, page.url.slice(1) + '.html');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, html);
}

// Sitemap, dated by the last commit that changed each page's content. Needs the history: the
// deploy workflows check out with fetch-depth: 0.
const lastmod = (...files) => execFileSync('git', ['-C', root, 'log', '-1', '--format=%cs', '--', ...files]).toString().trim();
const entries = [{ url: '/', mod: lastmod('site/index.html') }]
  .concat(pages.map(p => ({ url: p.url, mod: lastmod(p.src, 'site/docs.html') })));
fs.writeFileSync(path.join(out, 'sitemap.xml'), '<?xml version="1.0" encoding="UTF-8"?>\n'
  + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
  + entries.map(e => '  <url>\n    <loc>https://isx.run' + e.url + '</loc>\n    <lastmod>' + e.mod + '</lastmod>\n  </url>\n').join('')
  + '</urlset>\n');

// llms.txt (https://llmstxt.org) links here: the full docs as one Markdown file, every page in order
fs.writeFileSync(path.join(out, 'llms-full.txt'), pages
  .map(p => (p === pages[0] ? '' : '\n\n<!-- ' + p.src + ': https://isx.run' + p.url + ' -->\n\n')
    + p.md)
  .join(''));
