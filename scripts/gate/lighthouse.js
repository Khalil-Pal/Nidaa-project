// Lighthouse run (F-3, PF-1): performance score, LCP and image bytes for one page, mobile and desktop.
const lighthouse = require('lighthouse').default || require('lighthouse');
const puppeteer = require('puppeteer-core');
const fs = require('fs');

const url = process.argv[2] || 'http://127.0.0.1:8081/index.html';
const label = process.argv[3] || 'run';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

(async () => {
    const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--remote-debugging-port=9333'] });
    const out = {};
    try {
        for (const formFactor of ['mobile', 'desktop']) {
            const opts = { port: 9333, output: 'json', onlyCategories: ['performance'], formFactor,
                screenEmulation: formFactor === 'desktop' ? { mobile: false, width: 1350, height: 940, deviceScaleFactor: 1, disabled: false } : undefined,
                throttlingMethod: 'simulate' };
            const result = await lighthouse(url, opts);
            const lhr = result.lhr;
            const imgs = lhr.audits['network-requests'].details.items.filter((i) => i.resourceType === 'Image');
            const imgBytes = imgs.reduce((a, i) => a + (i.transferSize || 0), 0);
            out[formFactor] = {
                performance: Math.round(lhr.categories.performance.score * 100),
                lcpMs: Math.round(lhr.audits['largest-contentful-paint'].numericValue),
                fcpMs: Math.round(lhr.audits['first-contentful-paint'].numericValue),
                speedIndexMs: Math.round(lhr.audits['speed-index'].numericValue),
                totalBytes: Math.round(lhr.audits['total-byte-weight'].numericValue),
                imageBytes: imgBytes,
                images: imgs.map((i) => `${i.url.replace(url.replace('/index.html', ''), '')} ${Math.round((i.transferSize || 0) / 1024)}KB`),
                modernFormatsSavingsKB: Math.round((lhr.audits['modern-image-formats']?.details?.overallSavingsBytes || 0) / 1024),
                responsiveSavingsKB: Math.round((lhr.audits['uses-responsive-images']?.details?.overallSavingsBytes || 0) / 1024),
            };
        }
    } finally { await browser.close(); }
    console.log(JSON.stringify({ label, url, ...out }, null, 2));
    fs.writeFileSync(`lighthouse-${label}.json`, JSON.stringify({ label, url, ...out }, null, 2));
})().catch((e) => { console.error(e); process.exit(2); });
