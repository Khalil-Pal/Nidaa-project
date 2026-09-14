# Performance

Measurements taken with Lighthouse 13.4.1 (performance category only, simulated
throttling, headless Chrome) against a local build at `http://127.0.0.1:8081`.
Phase 6 PF-1 adds load measurements of the API; this file records what has been
measured so far.

## F-3 · Image optimisation (Phase 4)

`index.html` shipped nine images as full-size PNG/JPEG (1408×768 and 1376×768
files displayed at 84×46 to 568×310 CSS pixels). They were converted to WebP at
twice their CSS display size, given explicit `width`/`height` attributes so
layout is reserved before they load, and `loading="lazy"` where they sit below
the fold. The hero image is the page's largest-contentful-paint element and is
therefore *not* lazy-loaded; it gets `fetchpriority="high"` instead.
`nidaa-logo.png` (1.0 MB) was referenced by nothing and was deleted.

| File | Before | After |
|---|---|---|
| `nidaa-logo-nobg.jpg` → `nidaa-logo.webp` | 1,090 KB, 1408×768 | 10.7 KB, 293×160 |
| `nidaa-hero.png` → `nidaa-hero.webp` | 1,198 KB, 1408×768 | 26.8 KB, 1140×622 |
| `nidaa-logo.png` (unreferenced) | 976 KB | deleted |
| `images/help-types/*-help.png` ×6 → `.webp` | 11,257 KB, ~1376×768 each | 133.4 KB, 440×330 each |
| **Total image bytes on `index.html`** | **13,879 KB** | **178 KB** (mobile: 138 KB, two cards below the fold not fetched) |

The plan's target was the three root images under 150 KB combined: they are
37.5 KB. The six help-type images were not in the plan's inventory but were
78 % of the page weight, so they received the same treatment.

### Lighthouse, `index.html`

| Metric | Mobile before | Mobile after | Desktop before | Desktop after |
|---|---|---|---|---|
| Performance score | 64 | 98 | 67 | 82 |
| Largest Contentful Paint | 55.7 s | 2.1 s | 55.7 s | 2.0 s |
| First Contentful Paint | 1.7 s | 1.8 s | 1.7 s | 1.7 s |
| Speed Index | 23.1 s | 2.0 s | 1.7 s | 1.7 s |
| Total bytes | 13,843 KB | 428 KB | 13,840 KB | 465 KB |
| Image bytes | 13,553 KB | 138 KB | 13,553 KB | 178 KB |

What remains on desktop (82) is not images: the render-blocking Google Fonts
and Font Awesome stylesheets loaded from CDNs, which F-5's CSP work does not
change. Recorded here so the next measurement has a baseline.

Raw Lighthouse output: `scripts/gate/` reproduces the run with
`node scripts/gate/lighthouse.js http://127.0.0.1:8081/index.html <label>`
(needs `lighthouse` and `puppeteer-core` resolvable, see `scripts/gate/README.md`).
