const { test, expect } = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;

async function publishedVersion(page) {
  const response = await page.request.get('build-meta.json');
  expect(response.ok()).toBeTruthy();
  const meta = await response.json();
  expect(meta.release_tag).toMatch(/^v\d+\.\d+\.\d+$/);
  return meta.release_tag.slice(1);
}

for (const width of [320, 375, 768, 1440]) {
  test(`responsive landing page at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 960 });
    const errors = [];
    const requests = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text());
    });
    page.on('request', request => requests.push(request.url()));
    const response = await page.goto('./');
    expect(response.ok()).toBeTruthy();
    await expect(page.locator('h1')).toHaveText('Your toolchains.All in view.');
    await expect(page.locator('.feature-grid article')).toHaveCount(6);
    await expect(page.locator('.hero-screen img')).toHaveJSProperty('naturalWidth', 1280);
    const version = await publishedVersion(page);
    await expect(page.locator('#install')).toContainText(`Published release v${version}`);
    await expect(page.locator('body')).not.toContainText('{{');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    for (const arch of ['arm64', 'amd64']) {
      await page.locator(`[data-architecture="${arch}"]`).click();
      await expect(page.locator(`[data-architecture="${arch}"]`)).toHaveAttribute('aria-pressed', 'true');
      for (const fmt of ['AppImage', 'Flatpak', 'Snap']) {
        const suffix = fmt === 'AppImage' ? fmt : fmt.toLowerCase();
        const link = page.getByRole('link', { name: `Download ${arch} ${fmt}`, exact: true });
        await expect(link).toBeVisible();
        await expect(link).toHaveAttribute('href', `https://github.com/worxbend/Zephyr/releases/download/v${version}/Zephyr-${version}-linux-${arch}.${suffix}`);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    }
    for (const screen of ['jdk', 'sdks', 'catalog']) {
      await page.locator(`[data-screen="${screen}"]`).click();
      const panel = page.locator(`[data-screen-panel="${screen}"]`);
      await expect(panel).toBeVisible();
      await expect(panel.locator('img')).toHaveJSProperty('naturalWidth', 1280);
    }
    const origin = new URL(page.url()).origin;
    expect(requests.filter(url => !url.startsWith(`${origin}/`))).toEqual([]);
    expect(errors).toEqual([]);
    await page.goto('./');
    await page.screenshot({ path: testInfo.outputPath(`landing-${width}.png`), fullPage: width === 1440 });
  });
}

test('all download commands and screenshots remain available without JavaScript', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 320, height: 900 } });
  const page = await context.newPage();
  try {
    await page.goto(baseURL);
    for (const panel of await page.locator('[data-arch-panel]').all()) await expect(panel).toBeVisible();
    for (const panel of await page.locator('[data-screen-panel]').all()) await expect(panel).toBeVisible();
    await expect(page.locator('.copy-command:visible')).toHaveCount(0);
    await expect(page.locator('.architecture-controls')).toBeHidden();
    await expect(page.locator('.gallery-controls')).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  } finally {
    await context.close();
  }
});

test('blocked enhancement script leaves full installation fallback', async ({ page }) => {
  await page.route('**/assets/app.js', route => route.abort());
  await page.goto('./');
  await expect(page.locator('[data-arch-panel]:visible')).toHaveCount(6);
  await expect(page.locator('[data-screen-panel]:visible')).toHaveCount(3);
  await expect(page.locator('.architecture-controls')).toBeHidden();
});

test('installation commands stop on checksum or installation failure', async ({ page }) => {
  await page.goto('./');
  const commands = await page.locator('.command-block pre code').allTextContents();
  expect(commands).toHaveLength(6);
  for (const command of commands) {
    const lines = command.trim().split('\n');
    expect(lines[0]).toBe('sha256sum --check --ignore-missing SHA256SUMS &&');
    for (const line of lines.slice(0, -1)) expect(line.trim()).toMatch(/&&$/);
    expect(command).not.toMatch(/curl.*\|.*(?:ba)?sh/);
  }
  await expect(page.locator('#flatpak-title').locator('..')).toBeVisible();
  await expect(page.locator('#install')).toContainText('not a Flathub listing');
  await expect(page.locator('#install')).toContainText('activated host-terminal launch is unsupported');
  await expect(page.locator('#install')).toContainText('--dangerous skips Snap Store verification');
});

test('keyboard skip link and architecture selection retain visible focus', async ({ page }) => {
  await page.goto('./');
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  expect(new URL(page.url()).hash).toBe('#main');
  const arm = page.getByRole('button', { name: 'arm64 (ARM64)', exact: true });
  await arm.focus();
  await page.keyboard.press('Space');
  await expect(arm).toHaveAttribute('aria-pressed', 'true');
  await expect(arm).toBeFocused();
  const focus = await arm.evaluate(button => ({ width: getComputedStyle(button).outlineWidth, style: getComputedStyle(button).outlineStyle }));
  expect(parseFloat(focus.width)).toBeGreaterThanOrEqual(3);
  expect(focus.style).toBe('solid');
});

test('clipboard success copies exactly the visible architecture command', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('./');
  await page.getByRole('button', { name: 'arm64 (ARM64)', exact: true }).click();
  const commands = await page.getByLabel('arm64 AppImage commands', { exact: true }).textContent();
  const button = page.getByRole('button', { name: 'Copy arm64 AppImage commands', exact: true });
  await button.click();
  await expect(page.getByRole('status')).toContainText('Copied arm64 AppImage commands.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(commands.trim());
  await expect(button).toBeEnabled();
});

test('clipboard denial gives a usable, honest fallback', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => { throw new Error('fixture: denied'); } }, configurable: true });
  });
  await page.goto('./');
  const button = page.getByRole('button', { name: 'Copy amd64 AppImage commands', exact: true });
  await button.click();
  await expect(page.getByRole('status')).toContainText('Could not copy to the clipboard.');
  await expect(page.getByRole('status')).toContainText('Select and copy the commands');
  await expect(button).toBeEnabled();
});

for (const width of [320, 1440]) {
  test(`WCAG AA accessibility at ${width}px and both architecture states`, async ({ page }) => {
    await page.setViewportSize({ width, height: 960 });
    await page.goto('./');
    for (const arch of ['amd64', 'arm64']) {
      await page.locator(`[data-architecture="${arch}"]`).click();
      const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      expect(result.violations).toEqual([]);
    }
  });
}

test('reduced-motion preference is respected', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('./');
  expect(await page.locator('html').evaluate(element => getComputedStyle(element).scrollBehavior)).toBe('auto');
  expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBeTruthy();
});
