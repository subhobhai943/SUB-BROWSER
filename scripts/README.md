# SUB-BROWSER packaging scripts

Standalone, self-contained packaging pipeline for SUB-BROWSER (GPL-3.0,
https://github.com/subhobhai943/SUB-BROWSER). Each script runs from any clone;
no other repo files are required. They read environment variables (all have
defaults) and write artifacts to `$DIST_DIR` (default `./dist`).

Common env vars:

| Var        | Default | Meaning                                   |
|------------|---------|-------------------------------------------|
| VERSION    | 2.0.0   | Package version                           |
| ARCH       | auto    | Package architecture (deb/rpm/pacman)     |
| DIST_DIR   | ./dist  | Output directory                          |
| PREFIX     | /usr    | Install prefix inside packages            |

## Scripts

| Script               | Input                                             | Output |
|----------------------|---------------------------------------------------|--------|
| `build-deb.sh`       | staging dir (`bin/sub-browser`, `share/sub-browser/*`, `share/icons`) | `sub-browser_<ver>_<arch>.deb` via dpkg-deb |
| `build-rpm.sh`       | same staging tree                                 | `.rpm` (rpmbuild if present; pure-bash rpm 4.10+ writer fallback) |
| `build-pacman.sh`    | same staging tree                                 | `sub-browser-<ver>-<arch>.pkg.tar.zst` (makepkg via bundled PKGBUILD, or manual .PKGINFO tar) |
| `build-msi.sh`       | Windows staging dir                               | WiX `.wxs` template + `candle/light` invocation (run on Windows CI runner) |
| `build-pkg.sh`       | macOS payload dir (`payload/Applications/...`)    | `pkgbuild` component-plist + validation (run on macOS CI runner) |
| `build-appimage.sh`  | staging tree + AppRun + icon                      | self-extracting `.AppRun`/`.appimage` |
| `build-bsd.sh`       | staging tree                                      | FreeBSD `.txz` with `+MANIFEST` |
| `sign-apk-release.sh`| APK path (or `APK_IN`)                            | release-signed APK + apksigner verify + SHA-256 |
| `checksums.sh`       | dist dir                                          | `SHA256SUMS` |

## Release keystore

`sign-apk-release.sh` creates `/opt/subpack/release.keystore` on first run
(RSA 2048, 10000-day validity, alias `subbrowser`). The keystore password is
taken from `SUB_KEYSTORE_PASS` (never committed; CI should inject it from a
secret store). Back this file up offline — losing it means losing the ability
to ship upgrades signed with the same key.

## CI wiring notes

- Linux runners: deb/rpm/pacman/appimage/apk/checksums all work here.
- Windows runner: install WiX v3 (`choco install wixtoolset`) or WiX v5 (dotnet tool); then `build-msi.sh`.
- macOS runner: `build-pkg.sh` generates the component plist; run `pkgbuild`/`productbuild` there, optionally `productsign`.
- rpm fallback writer implements rpm 4.10+ filecaps ("payload compressor") format so no external compressor binary is embedded; requires RPM >= 4.10 on installing distros (RHEL 7+, Fedora, openSUSE 12.2+).
