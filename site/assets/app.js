'use strict';

// The complete page is visible without JavaScript. Reveal controls only
// after wiring each enhancement, then narrow the relevant content.
(() => {
  const architectureControls = document.querySelector('.architecture-controls');
  const architectureButtons = Array.from(document.querySelectorAll('[data-architecture]'));
  const architecturePanels = Array.from(document.querySelectorAll('[data-arch-panel]'));
  if (architectureControls && architectureButtons.length === 2 && architecturePanels.length === 6) {
    const selectArchitecture = (architecture) => {
      architectureButtons.forEach((button) => {
        button.setAttribute('aria-pressed', String(button.dataset.architecture === architecture));
      });
      architecturePanels.forEach((panel) => {
        panel.hidden = panel.dataset.archPanel !== architecture;
      });
    };
    architectureButtons.forEach((button) => {
      button.addEventListener('click', () => selectArchitecture(button.dataset.architecture));
    });
    selectArchitecture('amd64');
    architectureControls.hidden = false;
  }

  const galleryControls = document.querySelector('.gallery-controls');
  const galleryButtons = Array.from(document.querySelectorAll('[data-screen]'));
  const galleryPanels = Array.from(document.querySelectorAll('[data-screen-panel]'));
  if (galleryControls && galleryButtons.length === 3 && galleryPanels.length === 3) {
    const selectScreenshot = (screen) => {
      galleryButtons.forEach((button) => {
        button.setAttribute('aria-pressed', String(button.dataset.screen === screen));
      });
      galleryPanels.forEach((panel) => {
        panel.hidden = panel.dataset.screenPanel !== screen;
        // A selected screenshot is needed now, even in background tabs
        // where the browser may defer intersection-based lazy loading.
        if (!panel.hidden) {
          const image = panel.querySelector('img');
          if (image) image.loading = 'eager';
        }
      });
    };
    galleryButtons.forEach((button) => {
      button.addEventListener('click', () => selectScreenshot(button.dataset.screen));
    });
    selectScreenshot('jdk');
    galleryControls.hidden = false;
  }

  const status = document.getElementById('copy-status');
  if (!status) return;
  document.querySelectorAll('.copy-command').forEach((button) => {
    const code = button.closest('.command-block')?.querySelector('pre code');
    if (!code) return;
    button.addEventListener('click', async () => {
      const label = button.getAttribute('aria-label')?.replace(/^Copy /, '') || 'commands';
      status.textContent = '';
      button.disabled = true;
      try {
        if (!navigator.clipboard || !window.isSecureContext) {
          throw new Error('Clipboard unavailable');
        }
        await navigator.clipboard.writeText(code.textContent.trim());
        status.textContent = `Copied ${label}. Verify the checksum before continuing with installation.`;
      } catch {
        status.textContent = 'Could not copy to the clipboard. Select and copy the commands from the code block instead.';
      } finally {
        button.disabled = false;
      }
    });
    button.hidden = false;
  });
})();
