import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

// Portável: funciona em /home/user/Testtezt e em /home/runner/work/Testtezt/Testtezt (CI)
// process.cwd() em vitest = warzone-offline/server
function repoRoot(): string {
  const cwd = process.cwd();
  const candidate1 = path.resolve(cwd, '../../');
  if (fs.existsSync(path.join(candidate1, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh'))) return candidate1;
  const candidate2 = path.resolve(cwd, '../..');
  if (fs.existsSync(path.join(candidate2, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh'))) return candidate2;
  if (fs.existsSync(path.join(cwd, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh'))) return cwd;
  return candidate1;
}

describe('M2.1 webview-test tooling (S23 sem root, menos invasivo)', () => {
  it('hotspot-dns-setup.sh exists and dry-run shows prod.cdni host', () => {
    const root = repoRoot();
    const scriptPath = path.join(root, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh');
    const alt = path.join(process.cwd(), '../tools/webview-test/hotspot-dns-setup.sh');
    const exists = fs.existsSync(scriptPath) || fs.existsSync(alt);
    expect(exists).toBe(true);
    const resolved = fs.existsSync(scriptPath) ? scriptPath : alt;
    const out = execSync(`bash "${resolved}" --dry-run`, { encoding: 'utf8' });
    expect(out).toContain('prod.cdni.callofduty.com');
    expect(out).toContain('BootstrapServer');
    expect(out).not.toContain('activision.com'); // sem auth/Demonware no M2.1
  });

  it('mitm-redirect.py intercepts only prod.cdni (sem Activision/Demonware host)', () => {
    const root = repoRoot();
    const pyPath = path.join(root, 'warzone-offline/tools/webview-test/mitm-redirect.py');
    const py = fs.readFileSync(pyPath, 'utf8');
    expect(py).toContain('prod.cdni.callofduty.com');
    expect(py).toContain('LOCAL_JS');
    // deve interceptar só prod.cdni, não hosts de auth/Demonware
    expect(py).toContain('TARGET_HOST = "prod.cdni.callofduty.com"');
    expect(py).not.toContain('activision.com');
    expect(py).not.toContain('demonware.net');
    expect(py).not.toContain('DemonwarePortMapping');
  });

  it('APK patch prototype is separado e documentado, não aplicado', () => {
    const root = repoRoot();
    const readme = fs.readFileSync(path.join(root, 'warzone-offline/patches/apk-webview-patch/README.md'), 'utf8');
    const diff = fs.readFileSync(path.join(root, 'warzone-offline/patches/apk-webview-patch/APK_PATCH_DIFF.md'), 'utf8');
    expect(readme).toContain('NÃO instalar');
    expect(diff).toContain('assets/bootstrap/index.html');
    expect(diff).toContain('10.42.0.1');
    expect(diff).toContain('network_security_config.xml');
    // não deve estar no build principal (verificar que patch não é importado por server)
    const serverFiles = fs.readdirSync(path.join(root, 'warzone-offline/server/src'));
    expect(serverFiles).not.toContain('patches');
  });

  it('webview-patch README explica shouldInterceptRequest sem root', () => {
    const root = repoRoot();
    const md = fs.readFileSync(path.join(root, 'warzone-offline/launcher/src/webview-patch/README.md'), 'utf8');
    expect(md).toContain('shouldInterceptRequest');
    expect(md).toContain('prod.cdni.callofduty.com');
    expect(md.toLowerCase()).not.toContain('demonware');
  });
});
