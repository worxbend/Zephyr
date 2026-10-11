# Zephyr microsite

The public landing page is https://worxbend.github.io/Zephyr/.
It is static HTML/CSS with small, optional JavaScript enhancements. There is
no production framework, analytics, third-party font or browser-side API call.
JavaScript-disabled visitors can still read features and use every installer.

## Build and verify

From the repository root:

    python3 -B -m unittest discover -s tools -p 'test_site_build.py' -v
    python3 -B tools/build_site.py
    npm --prefix site ci --ignore-scripts
    npm --prefix site exec -- playwright install chromium
    npm --prefix site test

Serve the generated artifact with:

    python3 -B -m http.server 4173 --bind 127.0.0.1 --directory build

Then open http://127.0.0.1:4173/site/. Testing at this nested path catches
asset URLs that would break on the GitHub Pages project subpath.
Browser reports and screenshots are written under `build/`, never deployed.
The builder copies the existing reviewed application screenshots and icon;
node_modules, tests, dependency manifests and source templates are excluded.

## Release-aware downloads

`site/release.json` is a small public GitHub API snapshot for reproducible,
offline development. It records an actually published stable release, not
`zephyrVersion` or an unpublished tag. Do not replace it with fabricated assets.

The Pages workflow reads `/repos/worxbend/Zephyr/releases/latest` afresh and
passes that response to `tools/build_site.py`. The builder requires a public
stable numeric tag, matching canonical URLs, six non-empty architecture/format
packages, checksums and a build manifest. Invalid metadata fails the deployment
rather than publishing broken or unverified-version download links.

AppImage is recommended for host SDKMAN integration. The downloaded classic
Snap instructions disclose unsigned-package installation and host access.
The Flatpak instructions disclose standalone-bundle, host-integration and
activated-terminal limits. There is no invented Flathub listing or unverified
Snap Store install command.

## Publication

`.github/workflows/pages.yml` validates the builder, builds from published
release metadata, runs Chromium responsive/interaction/accessibility tests,
and uploads only `build/site`. Only main-branch non-PR runs can deploy.

The site refreshes on scoped main pushes, explicit workflow dispatches, and
completion of the tag-triggered Linux release pipeline. `workflow_run` is used
because a release created by the Actions GITHUB_TOKEN does not trigger another
workflow's `release` event. If Snap Store upload fails after GitHub publication,
the site can still advertise the valid public release; if no newer release was
published, it retains the previous published version.

For an immediate manual refresh of public release metadata:

    gh workflow run pages.yml --ref main

GitHub Pages uses Actions deployment with HTTPS. Deployment metadata is exposed
at `build-meta.json` so the production source SHA and advertised release can be
read back. A successful build is not itself a successful deployment: verify the
Pages job and actual public HTML/assets before reporting publication.
