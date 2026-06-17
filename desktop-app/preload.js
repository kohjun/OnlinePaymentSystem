const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    minimizeWindow: () => ipcRenderer.send('window-minimize'),
    maximizeWindow: () => ipcRenderer.send('window-maximize'),
    closeWindow:    () => ipcRenderer.send('window-close'),
    onMaximizeChange: (cb) => ipcRenderer.on('window-maximized-change', (_, isMax) => cb(isMax)),
});
