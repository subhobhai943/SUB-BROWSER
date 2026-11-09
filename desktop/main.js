/*
 * SUB-BROWSER — GPL-3.0-or-later
 * Copyright (C) 2026 Subhadip Sarkar
 * See LICENSE at repository root.
 */
/**
 * SUB-BROWSER — main process
 * ------------------------------------------------------------------
 * Single-window browser shell. Page content is rendered inside
 * sandboxed <webview> elements managed by the renderer.
 *
 * Responsibilities:
 *  - App identity (name "SUB-BROWSER") and User-Agent suffix.
 *  - Session persistence in userData.
 *  - Minimal application menu.
 *  - Window creation + IPC for opening/closing hidden smoke-test windows.
 */

const { app, BrowserWindow, Menu, session, ipcMain } = require('electron');
const path = require('path');

// ------------------------------------------------------------------
// Browser identity
// ------------------------------------------------------------------
// App name shown in menus / userData path resolution.
app.setName('SUB-BROWSER');

// Append "SUBBrowser/1.0" to Chromium's default User-Agent so sites
// can identify this browser. Must run after app ready (sessions are
// unavailable before), so it is applied in whenReady below.

// ------------------------------------------------------------------
// Session persistence — cookies/storage live under app.getPath('userData')
// by default; we set a partition-friendly userData dir explicitly.
// ------------------------------------------------------------------
app.setPath('userData', path.join(app.getPath('appData'), 'SUB-BROWSER'));

// ------------------------------------------------------------------
// Minimal menu — just essentials (edit + window + devtools).
// ------------------------------------------------------------------
function buildMinimalMenu() {
  const template = [
    {
      label: 'SUB-BROWSER',
      submenu: [
        { role: 'quit' }
      ]
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' }, { role: 'redo' }, { type: 'separator' },
        { role: 'cut' }, { role: 'copy' }, { role: 'paste' },
        { role: 'selectall' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' }, { role: 'forceReload' },
        { role: 'toggleDevTools' }, { type: 'separator' },
        { role: 'togglefullscreen' }
      ]
    },
    {
      label: 'Window',
      submenu: [
        { role: 'minimize' }, { role: 'close' }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ------------------------------------------------------------------
// Window creation
// ------------------------------------------------------------------
let mainWindow = null;

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 640,
    minHeight: 420,
    backgroundColor: '#050505',          // black base, matches dark theme
    icon: path.join(__dirname, 'assets', 'icon.png'),
    title: 'SUB-BROWSER',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,            // renderer gets a safe bridge only
      nodeIntegration: false,            // no Node in the chrome UI
      sandbox: true,
      // Allow <webview> tags in the shell renderer:
      webviewTag: true
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'browser.html'));

  mainWindow.on('closed', () => { mainWindow = null; });
}

// ------------------------------------------------------------------
// Smoke-test helpers — used when launched with --sub-smoke by
// scripts/smoketest.js. Loads a URL in a hidden BrowserWindow and
// resolves with its real webContents title (or an error string).
// ------------------------------------------------------------------
const SMOKE_FLAG = '--sub-smoke';
const smokeMode = process.argv.includes(SMOKE_FLAG);

function loadHidden(url) {
  const win = new BrowserWindow({ show: false, webPreferences: { sandbox: true } });
  return new Promise((resolve) => {
    const finish = (result) => { win.destroy(); resolve(result); };
    win.webContents.once('did-finish-load', () =>
      finish({ title: win.webContents.getTitle(), url: win.webContents.getURL() }));
    win.webContents.once('did-fail-load', (_e, code, desc) =>
      finish({ error: `did-fail-load ${code} ${desc}` }));
    win.loadURL(url);
  });
}

/**
 * Smoke sequence:
 *  1) Load the bundled new-tab page and assert its title.
 *  2) Load https://example.com in a hidden window and assert its title.
 * Exits 0 on success, 1 on any failure.
 */
async function runSmoke() {
  const failures = [];

  // 1. New-tab page (file URL, same file the real tabs open with).
  const newtabPath = path.join(__dirname, 'renderer', 'newtab.html');
  const nt = await loadHidden('file://' + newtabPath);
  if (nt.error) {
    failures.push(`new-tab page failed to load: ${nt.error}`);
  } else if (nt.title !== 'SUB-BROWSER \u2014 New Tab') {
    failures.push(`new-tab title mismatch: got "${nt.title}"`);
  } else {
    console.log(`SMOKE: new-tab page title OK: "${nt.title}"`);
  }

  // 2. Remote page over the network.
  const ex = await loadHidden('https://example.com');
  if (ex.error) {
    failures.push(`example.com failed to load: ${ex.error}`);
  } else if (ex.title !== 'Example Domain') {
    failures.push(`example.com title mismatch: got "${ex.title}"`);
  } else {
    console.log(`SMOKE: example.com title OK: "${ex.title}"`);
  }

  if (failures.length) {
    console.error('SMOKE FAILED:\n - ' + failures.join('\n - '));
    app.exit(1);
  } else {
    console.log('SMOKE: all assertions passed');
    app.exit(0);
  }
}

// ------------------------------------------------------------------
// App lifecycle
// ------------------------------------------------------------------
app.whenReady().then(() => {
  // Apply the UA suffix now that the default session exists, before
  // any window loads.
  const BASE_UA = session.defaultSession.getUserAgent();
  session.defaultSession.setUserAgent(`${BASE_UA} SUBBrowser/1.0`);

  buildMinimalMenu();
  createMainWindow();

  // Smoke mode: exercise the real boot path, then run assertions and exit.
  if (smokeMode) {
    mainWindow.webContents.once('did-finish-load', () => {
      // Give the shell renderer a beat to spawn its initial new-tab webview.
      setTimeout(runSmoke, 500);
    });
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
});

// Quit when all windows are closed (standard non-macOS behavior).
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
