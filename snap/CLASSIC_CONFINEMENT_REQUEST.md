# Classic confinement request: zephyr

Use the text below to open a topic in the Snapcraft forum's
[`store-requests` / `classic-confinement` category][forum-category] after the
`zephyr` name is registered.

---

- **name:** `zephyr`
- **description:** Zephyr is a desktop interface for SDKMAN. It inventories
  installed JDKs and SDKs, previews and performs SDKMAN transactions, manages
  project `.sdkmanrc` environments, and launches terminals with selected host
  toolchains activated.
- **snapcraft:**
  https://github.com/w0rxbend/Zephyr/blob/main/snap/snapcraft.yaml
- **upstream:** https://github.com/w0rxbend/Zephyr
- **upstream-relation:** I am the upstream developer, repository owner, and
  Snap publisher.
- **supported-category:** Tools for local, non-root user driven configuration
  of/switching to development workspaces/environments.
- **reasoning:** Zephyr intentionally operates on the user's existing SDKMAN
  installation, normally at `$HOME/.sdkman` but optionally at another
  user-selected location. SDKMAN is implemented as shell functions: Zephyr
  sources `sdkman-init.sh` and invokes its install, uninstall, default, update,
  and self-update commands. Those operations modify the existing SDKMAN
  installation and manage its host-installed toolchains. Zephyr also reads
  project `.sdkmanrc` files and starts host terminal applications with selected
  host-installed JDKs and SDKs activated.

  Strict interfaces such as `home`, `personal-files`, and desktop portals can
  grant access to selected data, but they cannot let the application source an
  arbitrary existing SDKMAN shell environment, expose arbitrary
  SDKMAN-installed host binaries in activated shells, or launch arbitrary host
  terminal programs.
  Bundling a separate SDKMAN installation and toolchain set inside the snap
  would replace, rather than manage, the user's development environment and
  would not satisfy the application's purpose.

  All operations are initiated by the logged-in, non-root user. Mutating
  operations are shown in an explicit transaction preview before execution;
  Zephyr is not a system-management daemon and does not require root access.

I understand that strict confinement is generally preferred over classic. I
evaluated the available interfaces, but they do not provide the host shell and
arbitrary host-toolchain execution required by this application.

---

Canonical lists Zephyr's category as supported, while still requiring manual
publisher vetting and technical review. Keep the forum topic URL after
submission; Store reviewers attach it to the snap declaration when approval is
granted.

[forum-category]: https://forum.snapcraft.io/t/about-the-classic-confinement-category/43830
