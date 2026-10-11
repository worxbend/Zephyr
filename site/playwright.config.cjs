const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 30000,
  fullyParallel: true,
  workers: 2,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { outputFolder: '../build/site-test-report', open: 'never' }]],
  outputDir: '../build/site-test-results',
  use: {
    baseURL: process.env.SITE_URL || 'http://127.0.0.1:4173/site/',
    browserName: 'chromium',
    trace: 'retain-on-failure',
  },
  webServer: process.env.SITE_URL ? undefined : {
    command: 'python3 -B -m http.server 4173 --bind 127.0.0.1 --directory ../build',
    url: 'http://127.0.0.1:4173/site/',
    reuseExistingServer: false,
  },
});
