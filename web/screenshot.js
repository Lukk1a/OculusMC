const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'dark'
  });
  
  // Login Page (2FA mocked)
  await page.goto('http://localhost:3000/login', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: '../.github/assets/2fa.png', fullPage: true });

  // Console Page
  await page.goto('http://localhost:3000/dashboard/console', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: '../.github/assets/console.png', fullPage: true });
  
  await browser.close();
})();
