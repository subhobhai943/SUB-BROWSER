#!/usr/bin/env bash
# build-msi.sh — Windows MSI preparation for SUB-BROWSER (GPL-3.0)
# Runs anywhere to generate the WiX source + build commands; the actual MSI
# compile happens on a Windows CI runner with WiX installed.
#   WiX v3: choco install wixtoolset   -> candle.exe / light.exe
#   WiX v5: dotnet tool install -g wix -> wix build
set -euo pipefail

VERSION="${VERSION:-2.0.0}"
DIST_DIR="${DIST_DIR:-./dist}"
STAGING="${1:-${STAGING:-staging-windows}}"
GUID="${MSI_GUID:-}"           # stable upgrade GUID; generate once, commit it
UPGRADE_CODE="${MSI_UPGRADE_CODE:-}"
usage() {
  cat <<EOF
Usage: $(basename "$0") [WINDOWS_STAGING_DIR]
  WINDOWS_STAGING_DIR  dir containing sub-browser.exe, *.dll, resources/
Env: VERSION=$VERSION DIST_DIR=$DIST_DIR MSI_GUID MSI_UPGRADE_CODE
Generates: \$DIST_DIR/sub-browser.wxs and prints the exact WiX commands.
EOF
}
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi

# GUIDs: placeholders must be replaced with real generated GUIDs.
new_guid() { python3 -c 'import uuid;print("{"+str(uuid.uuid4()).upper()+"}")' 2>/dev/null || uuidgen; }
: "${GUID:=$(new_guid)}"
: "${UPGRADE_CODE:=$(new_guid)}"
if [ -z "${MSI_GUID:-}" ] || [ -z "${MSI_UPGRADE_CODE:-}" ]; then
  echo "NOTE: generated fresh GUIDs; for upgrades keep MSI_UPGRADE_CODE stable across releases:"
  echo "      MSI_GUID=$GUID"
  echo "      MSI_UPGRADE_CODE=$UPGRADE_CODE"
fi

mkdir -p "$DIST_DIR"
WXS="$DIST_DIR/sub-browser.wxs"
MAJOR="$(echo "$VERSION" | cut -d. -f1)"; MINOR="$(echo "$VERSION" | cut -d. -f2)"; PATCH="$(echo "$VERSION" | cut -d. -f3)"; PATCH="${PATCH:-0}"

cat > "$WXS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!-- SUB-BROWSER MSI — WiX v3-compatible source (v5 also accepts this form).
     Placeholders:
       \$(var.SourceDir)   Windows staging dir (contains sub-browser.exe)
       \$(var.Version)     $VERSION -->
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="$GUID"
           Name="SUB-BROWSER"
           Language="1033"
           Version="$MAJOR.$MINOR.$PATCH.0"
           Manufacturer="SUB-BROWSER Project"
           UpgradeCode="$UPGRADE_CODE">

    <Package InstallerVersion="500" Compressed="yes" InstallScope="perMachine"
             Platform="x64" Description="SUB-BROWSER $VERSION" Comments="GPL-3.0"/>

    <MajorUpgrade DowngradeErrorMessage="A newer version of SUB-BROWSER is already installed."/>
    <MediaTemplate EmbedCab="yes"/>

    <Feature Id="Complete" Title="SUB-BROWSER" Level="1" Display="expand" Absent="disallow">
      <ComponentGroupRef Id="ProductFiles"/>
      <ComponentRef Id="ApplicationShortcut"/>
      <ComponentRef Id="DesktopShortcut"/>
      <ComponentRef Id="AppDataDir"/>
    </Feature>

    <UIRef Id="WixUI_InstallDir"/>
    <Property Id="WIXUI_INSTALLDIR" Value="INSTALLFOLDER"/>
    <Icon Id="subbrowser.ico" SourceFile="\$(var.SourceDir)\\subbrowser.ico"/>
    <Property Id="ARPPRODUCTICON" Value="subbrowser.ico"/>
    <!-- license: WiX default -->
  </Product>

  <Fragment>
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="ProgramFiles64Folder">
        <Directory Id="INSTALLFOLDER" Name="SUB-BROWSER"/>
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="ApplicationProgramsFolder" Name="SUB-BROWSER"/>
      </Directory>
      <Directory Id="DesktopFolder" Name="Desktop"/>
      <Directory Id="LocalAppDataFolder">
        <Directory Id="AppDataFolder" Name="SUB-BROWSER"/>
      </Directory>
    </Directory>

    <!-- All exe/dll/resources: harvested at build time with
         -ext WixToolset.Util.wixext (v3: candle -ext WixUtilExtension) -->
    <ComponentGroup Id="ProductFiles" Directory="INSTALLFOLDER">
      <Component Id="MainExe" Guid="*">
        <File Id="subbrowser.exe" Source="\$(var.SourceDir)\\sub-browser.exe" KeyPath="yes"/>
      </Component>
      <!-- CI agent: run heat for the rest:
           heat dir "\$(var.SourceDir)" -gg -sfrag -srd -dr INSTALLFOLDER
                -cg ProductFilesExtra -var var.SourceDir -o productfiles.wxs
           then add <ComponentGroupRef Id="ProductFilesExtra"/> to Feature. -->
    </ComponentGroup>

    <ComponentGroup Id="Shortcuts">
      <Component Id="ApplicationShortcut" Directory="ApplicationProgramsFolder" Guid="*">
        <Shortcut Id="StartMenuShortcut" Name="SUB-BROWSER"
                  Target="[#subbrowser.exe]" WorkingDirectory="INSTALLFOLDER"/>
        <RemoveFolder Id="CleanUpShortCut" Directory="ApplicationProgramsFolder" On="uninstall"/>
        <RegistryValue Root="HKCU" Key="Software\\SUB-BROWSER" Name="installed"
                       Type="integer" Value="1" KeyPath="yes"/>
      </Component>
      <Component Id="DesktopShortcut" Directory="DesktopFolder" Guid="*">
        <Shortcut Id="DesktopShortcut" Name="SUB-BROWSER"
                  Target="[#subbrowser.exe]" WorkingDirectory="INSTALLFOLDER"/>
        <RegistryValue Root="HKCU" Key="Software\\SUB-BROWSER" Name="desktop"
                       Type="integer" Value="1" KeyPath="yes"/>
      </Component>
      <Component Id="AppDataDir" Directory="AppDataFolder" Guid="*">
        <CreateFolder/>
        <RemoveFolder Id="RemoveAppData" On="uninstall"/>
        <RegistryValue Root="HKCU" Key="Software\\SUB-BROWSER" Name="appdata"
                       Type="integer" Value="1" KeyPath="yes"/>
      </Component>
    </ComponentGroup>
    <!-- NOTE: reference Shortcuts group components individually in Feature
         (done above via ComponentRef) — WiX registers automatic uninstall
         entries per component; MajorUpgrade handles upgrades. -->
  </Fragment>
</Wix>
EOF

echo "== generated WiX source: $WXS"

# --- runner detection -------------------------------------------------------
if command -v candle >/dev/null 2>&1 && command -v light >/dev/null 2>&1; then
  WIX3=1
elif command -v wix >/dev/null 2>&1; then
  WIX5=1
else
  if [[ "${OSTYPE:-}" == cygwin* || "${OSTYPE:-}" == msys* || "$(uname -s)" == MINGW* ]]; then
    cat >&2 <<'EOF'
ERROR: WiX toolset not found on this Windows machine. Install exactly one:
  WiX v3:  choco install wixtoolset
           (or winget install WiXToolset.WiXToolset)
  WiX v5:  dotnet tool install --global wix
           wix extension add -g WixToolset.Util.wixext
           wix extension add -g WixToolset.UI.wixext
EOF
    exit 127
  else
    echo "Non-Windows host (this box): MSI compile must run on the Windows CI runner."
  fi
fi

cat <<EOF

== Windows runner build commands ==
  # WiX v3 (candle/light):
  candle.exe -arch x64 -dSourceDir=%SOURCE_DIR% -dVersion=$VERSION sub-browser.wxs
  light.exe -ext WixUIExtension -ext WixUtilExtension -o sub-browser-$VERSION-x64.msi sub-browser.wixobj

  # WiX v5 (dotnet tool):
  wix build sub-browser.wxs -arch x64 -d SourceDir=%SOURCE_DIR% -d Version=$VERSION ^
      -ext WixToolset.Util.wixext -ext WixToolset.UI.wixext ^
      -o sub-browser-$VERSION-x64.msi

  # Optional Authenticode signing of inner exe + msi:
  signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /f cert.pfx /p %CERT_PASS% sub-browser-$VERSION-x64.msi

This box: $(uname -s) — no WiX; artifact produced is the .wxs template only.
EOF

# If exe is staged and WiX present, actually build:
if [ -n "${WIX3:-}${WIX5:-}" ] && [ -f "$STAGING/sub-browser.exe" ]; then
  ( cd "$(dirname "$WXS")"
    if [ -n "${WIX3:-}" ]; then
      candle.exe -arch x64 -dSourceDir="$STAGING" -dVersion="$VERSION" sub-browser.wxs
      light.exe -ext WixUIExtension -o "sub-browser-$VERSION-x64.msi" sub-browser.wixobj
    else
      wix build sub-browser.wxs -arch x64 -d SourceDir="$STAGING" -d Version="$VERSION" \
        -ext WixToolset.Util.wixext -ext WixToolset.UI.wixext -o "sub-browser-$VERSION-x64.msi"
    fi )
  echo "== built: $(pwd)/$(dirname "$WXS")/sub-browser-$VERSION-x64.msi"
fi
