# Snap Store listing

The registered snap is **`zephyr`**. Edit its listing at
<https://snapcraft.io/zephyr/listing>. The public page is
<https://snapcraft.io/zephyr>; preparing or saving this listing does not approve
classic confinement or make a revision installable.

## Text and links

[`snapcraft.yaml`](snapcraft.yaml) is the source of truth for the title, summary,
full description, license, and project links. Use the complete YAML `description`
value as the Store description, without the YAML indentation or `|` marker.
It includes features, SDKMAN setup, offline limitations, terminal prerequisites,
and a plain-language explanation of classic confinement.

| Store field | Value |
| --- | --- |
| Name | `zephyr` (registered; do not rename) |
| Title | Zephyr |
| Summary | Manage Java and SDKMAN toolchains from your Linux desktop |
| Primary category | Development (`development`) |
| Secondary category | Leave unset |
| Search keywords | `sdkman`, `java`, `jdk`, `sdk`, `toolchains`, `development` |
| License | MIT |
| Website | https://github.com/worxbend/Zephyr |
| Source code | https://github.com/worxbend/Zephyr |
| Issues | https://github.com/worxbend/Zephyr/issues |
| Contact / support | https://github.com/worxbend/Zephyr/issues |
| Visibility | Public |
| Price | Free |
| Update metadata on release | Off; maintain the reviewed listing separately |

Do not publish a personal email address as the support contact. Donation and
video fields are intentionally unset: there is no project donation or video URL
to advertise. Do not add an invented privacy-policy URL. The recipe's MIT license
covers Zephyr; bundled third-party components retain their own licenses.

Architecture (`amd64` and `arm64`), version, and release-channel information come
from uploaded revisions, not free-form listing text. Keep version numbers out of
the main description so it remains accurate between releases.

## Icon and screenshots

Upload [`listing/icon.png`](listing/icon.png), a 480 × 480 PNG derived from the
existing application icon. The package continues to use the original icon at
`packaging/linux/com.worxbend.zephyr.png`.

Upload the following 1280 × 820 PNGs in this order. Captions and alternative text
are recorded in [`listing/media.json`](listing/media.json) for storefronts that
support them; the manifest also records dimensions and SHA-256 checksums.

1. [`overview-light.png`](listing/overview-light.png) — Your SDKMAN toolchains at a glance.
2. [`installed-jdk-dark.png`](listing/installed-jdk-dark.png) — Manage installed Java versions.
3. [`search-light.png`](listing/search-light.png) — Find pages, versions, and actions with the keyboard.
4. [`settings-dark.png`](listing/settings-dark.png) — Choose an appearance that works for you.

These are real Linux captures of the production Compose application from the
visual review documented in [`UI_DESIGN.md`](../UI_DESIGN.md), not mockups or
claims of a GTK implementation. Originals were saved under
`desktopApp/build/reports/ui-review/`. Only the SDKMAN path in the header and
status bar has been redacted. The offline state remains visible; no installed
versions, actions, or status indicators have been fabricated. Captures demonstrate
the application UI, not installation of an approved Store snap.

A promotional banner and video are optional and are not included. The root-level
`Screenshot-*.png` files are maintained separately for the README; refreshing
those images does not update this Store gallery.

## Save and verify

1. Sign in to the publisher dashboard and open the listing URL above.
2. Set the text, links, license, and Development category from this document and
   `snapcraft.yaml`. Upload the icon and four screenshots in the listed order.
3. Save, reopen the listing, and verify the exact text, category, links, image
   order, and previews. Check the public page when the Store makes it available.
   Keep automatic metadata updates on release off: a retry of an older binary
   must not replace this reviewed copy with its embedded description.
4. Keep classic-confinement review separate: follow
   [`STORE_REVIEW.md`](STORE_REVIEW.md) and
   [`CLASSIC_CONFINEMENT_REQUEST.md`](CLASSIC_CONFINEMENT_REQUEST.md).

For a future **newly built** snap, `snapcraft upload-metadata path/to/new.snap`
can upload the summary, description, and icon (installed Snapcraft 9.1.3 also
sends title and license when present). It does not cover links, categories, or
screenshots. Inspect conflicts before choosing `--force`; do not
blindly overwrite dashboard edits. Previously released v1.2.1 binaries contain
the earlier description, so uploading metadata from those files would restore
stale text. Do not rebuild or replace published release assets just to edit the
listing. Publishing a revision and editing a Store listing are separate actions;
the release workflow does not synchronize all listing fields.

The complete listing was saved through Canonical's metadata and binary-metadata
APIs and read back on 2026-10-07. The text, links, Development category, keywords,
disabled automatic metadata updates, and the exact ordered icon/screenshot
hashes matched the local sources. This verifies the publisher listing, not
classic-confinement approval or public installability.

Once classic confinement is approved and a stable revision is available, the
Store installation command is `sudo snap install zephyr --classic`. Until then,
use the available GitHub release packages and do not advertise Store availability.

## Local validation

```shell
/usr/bin/python3 -m unittest discover -s packaging/snap -p 'test_*.py' -v
```

The listing checks validate required metadata, the summary length, repository
links, the SDKMAN/classic-confinement disclosures, and every PNG's dimensions and
checksum. They are discovered by the existing packaging checks in CI.

## References

- [Canonical's listing checklist](https://canonical.com/blog/make-your-snap-store-page-pop)
- [Canonical's metadata and media API](https://dashboard.snapcraft.io/docs/reference/v1/snap.html#managing-snap-metadata)
- [Snapcraft project-file reference](https://documentation.ubuntu.com/snapcraft/latest/reference/snapcraft-yaml/)
- [Snapcraft upload-metadata reference](https://documentation.ubuntu.com/snapcraft/latest/reference/commands/upload-metadata/)
