/*
 * SUB-BROWSER — GPL-3.0-or-later
 * Copyright (C) 2026 Subhadip Sarkar
 * See LICENSE at repository root.
 */
/**
 * SUB-BROWSER — shell renderer logic.
 * ------------------------------------------------------------------
 * Manages the tab strip and one sandboxed <webview> per tab.
 * No Node access here (contextIsolation + sandbox); only the tiny
 * `window.subbrowser` bridge from preload.js.
 */

(() => {
  'use strict';

  const tabsEl   = document.getElementById('tabs');
  const viewsEl  = document.getElementById('views');
  const address  = document.getElementById('address');
  const btnNew   = document.getElementById('newtab');
  const btnBack  = document.getElementById('back');
  const btnFwd   = document.getElementById('forward');
  const btnRel   = document.getElementById('reload');
  const btnHome  = document.getElementById('home');
  const btnGo    = document.getElementById('go');

  const NEWTAB_URL = new URL('../renderer/newtab.html', location.href).href;

  /** All open tabs. */
  const tabs = [];
  let activeTab = null;
  let nextId = 1;

  // ----------------------------------------------------------------
  // URL / query handling — address bar accepts a URL or search query.
  // ----------------------------------------------------------------
  function toTarget(input) {
    const text = String(input || '').trim();
    if (!text) return null;

    // Explicit scheme → treat as URL.
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(text)) return text;

    // Looks like a host (contains a dot, no spaces) → https URL.
    if (!/\s/.test(text) && /\./.test(text)) return 'https://' + text;

    // Anything else → Google search.
    return 'https://www.google.com/search?q=' + encodeURIComponent(text);
  }

  // ----------------------------------------------------------------
  // Tab management
  // ----------------------------------------------------------------
  function createTab(url = NEWTAB_URL, activate = true) {
    const id = nextId++;

    // Tab chip in the strip.
    const chip = document.createElement('div');
    chip.className = 'tab';
    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = 'New Tab';
    const close = document.createElement('button');
    close.className = 'close';
    close.textContent = '×';
    close.title = 'Close tab (Ctrl+W)';
    close.addEventListener('click', (e) => { e.stopPropagation(); closeTab(tab); });
    chip.append(title, close);
    chip.addEventListener('click', () => activateTab(tab));
    tabsEl.appendChild(chip);

    // Sandboxed guest content.
    const wv = document.createElement('webview');
    wv.setAttribute('sandbox', '');        // guest page fully sandboxed
    wv.setAttribute('allowpopups', '');
    wv.src = url;
    viewsEl.appendChild(wv);

    const tab = { id, chip, titleEl: title, webview: wv };
    tabs.push(tab);

    // Keep chip + address bar in sync with guest state.
    wv.addEventListener('page-title-updated', (e) => {
      title.textContent = e.title || 'New Tab';
      if (tab === activeTab) reportShellInfo();
    });
    wv.addEventListener('did-navigate-in-page', syncFromTab);
    wv.addEventListener('did-navigate', syncFromTab);
    wv.addEventListener('did-stop-loading', syncFromTab);

    if (activate) activateTab(tab);
    reportShellInfo();
    return tab;
  }

  function activateTab(tab) {
    if (!tab || !tabs.includes(tab)) return;
    activeTab = tab;
    for (const t of tabs) {
      t.chip.classList.toggle('active', t === tab);
      t.webview.classList.toggle('active', t === tab);
    }
    syncFromTab();
    reportShellInfo();
  }

  function closeTab(tab) {
    const i = tabs.indexOf(tab);
    if (i === -1) return;
    tabs.splice(i, 1);
    tab.webview.destroy?.();
    tab.webview.remove();
    tab.chip.remove();

    if (tab === activeTab) {
      activateTab(tabs[Math.max(0, i - 1)] || tabs[0] || null);
    }
    // Closing the last tab closes the window (standard browser feel).
    if (tabs.length === 0) window.close();
  }

  function activeGuest() { return activeTab ? activeTab.webview : null; }

  function syncFromTab() {
    const g = activeGuest();
    if (!g) return;
    // Reflect guest URL in the address bar unless the user is typing.
    if (document.activeElement !== address) {
      const src = g.getURL() || '';
      address.value = src === NEWTAB_URL ? '' : src;
    }
    btnBack.disabled = !g.canGoBack?.();
    btnFwd.disabled = !g.canGoForward?.();
  }

  function navigate(target) {
    const g = activeGuest();
    if (!g) return;
    const url = toTarget(target);
    if (url) g.loadURL(url);
  }

  // ----------------------------------------------------------------
  // Toolbar + address bar wiring
  // ----------------------------------------------------------------
  btnNew.addEventListener('click', () => { createTab(); address.focus(); });
  btnBack.addEventListener('click', () => activeGuest()?.goBack?.());
  btnFwd.addEventListener('click', () => activeGuest()?.goForward?.());
  btnRel.addEventListener('click', () => activeGuest()?.reload?.());
  btnHome.addEventListener('click', () => activeGuest()?.loadURL(NEWTAB_URL));
  btnGo.addEventListener('click', () => { navigate(address.value); address.blur(); });
  address.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { navigate(address.value); address.blur(); }
  });

  // ----------------------------------------------------------------
  // Shortcuts: Ctrl/Cmd+T new tab, Ctrl/Cmd+W close tab.
  // ----------------------------------------------------------------
  window.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && !e.shiftKey && e.key.toLowerCase() === 't') {
      e.preventDefault();
      createTab();
      address.focus();
      address.select();
    } else if (mod && !e.shiftKey && e.key.toLowerCase() === 'w') {
      e.preventDefault();
      if (activeTab) closeTab(activeTab);
    }
  });

  // ----------------------------------------------------------------
  // Smoke hook — report the active guest title to main (no-op there
  // unless the app was launched with --sub-smoke).
  // ----------------------------------------------------------------
  function reportShellInfo() {
    try {
      window.subbrowser?.reportShellInfo({
        ready: true,
        tabCount: tabs.length,
        activeTitle: activeTab?.titleEl?.textContent || ''
      });
    } catch (_) { /* bridge unavailable — ignore */ }
  }

  // Boot: one new-tab page, address bar focused.
  createTab();
  address.focus();
})();
