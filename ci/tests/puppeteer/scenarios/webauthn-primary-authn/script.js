const puppeteer = require('puppeteer');
const assert = require('assert');

(async () => {
    const browser = await puppeteer.launch({
        ignoreHTTPSErrors: true,
        headless: false,
        devtools: true,
        defaultViewport: null,
        args: ['--start-maximized']
    });
    const page = await browser.newPage();
    await page.goto("https://localhost:8443/cas/login");

    await page.waitForTimeout(1000)

    let element = await page.$('#webauthnLoginPanel div h2#status');
    assert(await element.boundingBox() != null);
    const header = await page.evaluate(element => element.textContent, element);
    console.log(header)
    assert(header === "Login with FIDO2-enabled Device");

    await browser.close();
})();
