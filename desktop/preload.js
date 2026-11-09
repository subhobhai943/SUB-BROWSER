/*
 * SUB-BROWSER — GPL-3.0-or-later
 * Copyright (C) 2026 Subhadip Sarkar
 * See LICENSE at repository root.
 */
/**
 * SUB-BROWSER — preload for the browser-shell renderer.
 * ------------------------------------------------------------------
 * Runs with contextIsolation. The shell UI needs no privileged APIs
 * for normal browsing, so this bridge only exposes product identity
 * (and a no-op-ready reporting hook). Page content itself is always
 * loaded inside sandboxed <webview> tags and never receives this
 * bridge.
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('subbrowser', {
  /** Product identity for UI labels. */
  productName: 'SUB-BROWSER',
  version: '1.0',

  /**
   * Report shell state (tab count, active title). Reserved hook for
   * diagnostics/smoke tooling; currently informational only.
   */
  reportShellInfo: (info) => {
    try { ipcRenderer.send('subbrowser:shell-info', info); } catch (_) { /* ignore */ }
  }
});
