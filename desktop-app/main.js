const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');

let mainWindow;
let splashWindow;
let springBootProcess = null;
let logStream = null;

const PORT = 8080;
const HEALTH_URL = `http://localhost:${PORT}/api/system/health`;

function getJavaExecutable() {
    return 'java';
}

function startSpringBoot() {
    let jarPath = path.join(__dirname, 'payment-0.0.1-SNAPSHOT.jar');

    if (!fs.existsSync(jarPath)) {
        jarPath = path.join(__dirname, '../build_sim/libs/payment-0.0.1-SNAPSHOT.jar');
    }

    const logPath = path.join(app.getPath('userData'), 'spring-boot.log');
    console.log('Writing Spring Boot logs to:', logPath);
    try {
        logStream = fs.createWriteStream(logPath, { flags: 'a' });
        logStream.write(`\n=== Starting Application at ${new Date().toLocaleString()} ===\n`);
        logStream.write(`Jar Path: ${jarPath}\n`);
    } catch (e) {
        console.error('Failed to create log stream:', e);
    }

    console.log('Starting Spring Boot Jar:', jarPath);

    const javaCmd = getJavaExecutable();
    const args = ['-jar', jarPath];

    const env = { ...process.env };
    const jbrBin = "C:\\Program Files\\Android\\Android Studio\\jbr\\bin";
    const oracleBin = "C:\\Program Files\\oracleJdk-25\\bin";
    env.PATH = `${jbrBin};${oracleBin};${env.PATH}`;
    env.JAVA_HOME = "C:\\Program Files\\Android\\Android Studio\\jbr";

    springBootProcess = spawn(javaCmd, args, {
        env: env,
        cwd: path.dirname(jarPath)
    });

    springBootProcess.stdout.on('data', (data) => {
        console.log(`[SpringBoot STDOUT]: ${data}`);
        if (logStream) logStream.write(`[STDOUT] ${data}`);
    });

    springBootProcess.stderr.on('data', (data) => {
        console.error(`[SpringBoot STDERR]: ${data}`);
        if (logStream) logStream.write(`[STDERR] ${data}`);
    });

    springBootProcess.on('error', (err) => {
        console.error('Failed to spawn Spring Boot process:', err);
        if (logStream) logStream.write(`[LAUNCH ERROR] ${err.message}\n`);

        dialog.showErrorBox(
            "Java 실행 실패 (Java Runtime Error)",
            "데스크톱 애플리케이션 백엔드 구동에 실패했습니다.\n" +
            "시스템에 Java Runtime Environment (JRE) 또는 JDK 17 이상이 설치되어 있고 시스템 PATH 환경 변수에 등록되어 있는지 확인해 주세요.\n\n" +
            "상세 오류: " + err.message
        );
        app.quit();
    });

    springBootProcess.on('close', (code) => {
        console.log(`Spring Boot process exited with code ${code}`);
        if (logStream) logStream.write(`[EXIT] Process exited with code ${code}\n`);
    });
}

function createSplashWindow() {
    splashWindow = new BrowserWindow({
        width: 500,
        height: 320,
        frame: false,
        alwaysOnTop: true,
        transparent: true,
        resizable: false,
        webPreferences: {
            nodeIntegration: false
        }
    });

    splashWindow.loadFile(path.join(__dirname, 'splash.html'));
}

function createMainWindow() {
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 1024,
        minHeight: 680,
        show: false,
        title: '에브리세일 | 엔터프라이즈 커머스 운영 플랫폼',
        // ─── Remove OS frame; we paint our own title bar ───
        frame: false,
        titleBarStyle: 'hidden',
        backgroundColor: '#050E1A',
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        }
    });

    mainWindow.setMenu(null);
    mainWindow.loadURL(`http://localhost:${PORT}/`);

    // Notify renderer when maximize state changes
    mainWindow.on('maximize',   () => { if (mainWindow) mainWindow.webContents.send('window-maximized-change', true); });
    mainWindow.on('unmaximize', () => { if (mainWindow) mainWindow.webContents.send('window-maximized-change', false); });

    mainWindow.once('ready-to-show', () => {
        if (splashWindow) {
            splashWindow.destroy();
            splashWindow = null;
        }
        mainWindow.show();
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// ─── IPC handlers for custom title bar buttons ───────────────────────────────
ipcMain.on('window-minimize', () => {
    if (mainWindow) mainWindow.minimize();
});

ipcMain.on('window-maximize', () => {
    if (!mainWindow) return;
    if (mainWindow.isMaximized()) {
        mainWindow.unmaximize();
    } else {
        mainWindow.maximize();
    }
});

ipcMain.on('window-close', () => {
    if (mainWindow) mainWindow.close();
});

function checkBackendHealth(callback) {
    http.get(HEALTH_URL, (res) => {
        if (res.statusCode === 200) {
            callback(true);
        } else {
            callback(false);
        }
    }).on('error', () => {
        callback(false);
    });
}

function pollBackend() {
    checkBackendHealth((isHealthy) => {
        if (isHealthy) {
            console.log('Backend is healthy, creating main window...');
            createMainWindow();
        } else {
            console.log('Backend not ready yet, polling in 500ms...');
            setTimeout(pollBackend, 500);
        }
    });
}

app.whenReady().then(() => {
    createSplashWindow();

    checkBackendHealth((isAlreadyRunning) => {
        if (isAlreadyRunning) {
            console.log('Backend is already running. Skipping spawn.');
            createMainWindow();
        } else {
            startSpringBoot();
            setTimeout(pollBackend, 1000);
        }
    });
});

app.on('window-all-closed', () => {
    if (springBootProcess) {
        console.log('Killing Spring Boot process...');
        try {
            if (process.platform === 'win32') {
                spawn('taskkill', ['/pid', springBootProcess.pid, '/f', '/t']);
            } else {
                springBootProcess.kill('SIGTERM');
            }
        } catch (e) {
            console.error('Failed to kill Spring Boot process:', e);
        }
    }
    app.quit();
});
