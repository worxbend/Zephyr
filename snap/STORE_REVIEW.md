# Snap Store publication checklist

Zephyr's snap needs `confinement: classic`. The application manages the
user's existing SDKMAN installation, executes SDKMAN's shell functions and
installed toolchains, and can launch an activated host terminal. Strict
confinement cannot provide those host-development-environment semantics.

Listing copy, project/support links, the Development category, and reviewed
screenshots are maintained in [`STORE_LISTING.md`](STORE_LISTING.md). Save and
verify that listing separately from uploading or releasing a snap revision.

Before the release workflow can publish:

1. The publisher has registered the `zephyr` name.
2. Submit a classic-confinement request in Canonical's store-requests forum.
   Zephyr fits the documented supported category “tools for local, non-root
   user driven configuration of/switching to development
   workspaces/environments.” Copy the complete, forum-ready request from
   [`CLASSIC_CONFINEMENT_REQUEST.md`](CLASSIC_CONFINEMENT_REQUEST.md).
3. After approval, create a least-privilege store credential:

   ```shell
   snapcraft export-login \
     --snaps=zephyr \
     --channels=stable \
     --acls=package_access,package_push,package_update,package_release \
     --expires=2027-07-30 \
     zephyr-snapcraft-login.txt
   ```

4. Save that file's complete contents as the repository Actions secret
   `SNAPCRAFT_TOKEN` (already present in this repository). Workflows map this
   secret to the `SNAPCRAFT_STORE_CREDENTIALS` environment variable only in the
   publishing step. Do not print the credential or add it to the repository.
5. Delete the exported credential file after the secret is configured.
6. Publish the already-built, checksum-verified release snaps:

   ```shell
   gh workflow run publish-snap-store.yml -f release_tag=v1.2.3
   ```

Tag releases call this publisher automatically after the complete GitHub
release is public. Without a usable secret or while review is pending, both
architecture-specific `.snap` files are still built and attached to the GitHub
release. A failed Store upload fails the workflow but cannot discard verified
GitHub packages. Both automatic and manual publication are strict: either
architecture failing validation, publication, or exact-digest Store readback
makes the workflow fail; `fail-fast: false` lets the other architecture finish.
