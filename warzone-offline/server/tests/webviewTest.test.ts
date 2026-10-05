import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

// Portável: funciona em /home/user/Testtezt e em /home/runner/work/Testtezt/Testtezt
// process.cwd() em vitest = warzone-offline/server
function repoRoot(): string {
  const cwd = process.cwd();
  const candidate = path.resolve(cwd, '../../');
  if (fs.existsSync(path.join(candidate, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh'))) return candidate;
  return candidate;
}

describe('M2.1 legacy tooling is retired under current M3.2/M4.1 scope', () => {
  it('hotspot harness is a no-op and --start is blocked without network changes', () => {
    const root = repoRoot();
    const script = path.join(root, 'warzone-offline/tools/webview-test/hotspot-dns-setup.sh');
    expect(fs.existsSync(script)).toBe(true);
    const help = execSync(`bash "${script}" --dry-run`, { encoding: 'utf8' });
    expect(help).toContain('retired and disabled');
    expect(help).toContain('127.0.0.1');

    let status = 0;
    try {
      execSync(`bash "${script}" --start`, { encoding: 'utf8', stdio: 'pipe' });
    } catch (error: any) {
      status = error.status;
      expect(String(error.stderr)).toContain('no changes made');
    }
    expect(status).toBe(2);
    const source = fs.readFileSync(script, 'utf8');
    expect(source).not.toMatch(/nmcli|dnsmasq\s+--|iptables\s+-t/);
  });

  it('TLS interception addon refuses to load and contains no redirect logic', () => {
    const root = repoRoot();
    const addon = fs.readFileSync(path.join(root, 'warzone-offline/tools/webview-test/mitm-redirect.py'), 'utf8');
    expect(addon).toContain('intentionally refuses to start');
    expect(addon).toContain('TLS interception/CA installation is prohibited');
    expect(addon).toContain('def load(');
    expect(addon).not.toContain('LOCAL_JS');
    expect(addon).not.toContain('from mitmproxy');
  });

  it('APK patch notes contain no diff or application steps', () => {
    const root = repoRoot();
    const readme = fs.readFileSync(path.join(root, 'warzone-offline/patches/apk-webview-patch/README.md'), 'utf8');
    const diff = fs.readFileSync(path.join(root, 'warzone-offline/patches/apk-webview-patch/APK_PATCH_DIFF.md'), 'utf8');
    expect(readme).toContain('DEPRECATED');
    expect(readme).toContain('NÃO APLICAR');
    expect(diff).toContain('removido / não executar');
    expect(diff).toContain('Não há comandos ou mudanças para aplicar');
    expect(diff).not.toContain('network_security_config.xml');
    expect(diff).not.toContain('10.42.0.1');
  });

  it('legacy WebView patch README is a non-executable retirement notice', () => {
    const root = repoRoot();
    const doc = fs.readFileSync(path.join(root, 'warzone-offline/launcher/src/webview-patch/README.md'), 'utf8');
    expect(doc).toContain('DEPRECATED / NÃO EXECUTAR');
    expect(doc).toContain('Não alterar TLS/trust/pinning');
    expect(doc).not.toContain('shouldInterceptRequest');
    expect(doc.toLowerCase()).not.toContain('demonware');
  });
});
