const { spawn } = require('node:child_process');
const child = spawn(process.execPath, [require.resolve('@playwright/test/cli'), 'test', '--config=playwright.cu03-real.config.ts'], {
  cwd: __dirname, stdio: 'inherit', windowsHide: true,
  env: { ...process.env, CU03_AUTOMATIC: 'true',
    ...(process.argv.includes('--nuevo') ? { CU03_FLOW: 'new' } : {}),
    NODE_USE_SYSTEM_CA: process.env.NODE_USE_SYSTEM_CA || '1' },
});
child.on('error', () => { console.error('No se pudo iniciar Playwright.'); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
