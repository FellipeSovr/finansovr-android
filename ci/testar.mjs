// Testes automáticos antes de compilar o APK (v7.2.0). Para o build se algo falhar.
// 1) sintaxe de todos os scripts da tela; 2) a tela abre com dados de exemplo (ci/fixture.json, fictícios) e sem erros;
// 3) as abas e as telas do Mais montam; 4) um gasto novo aparece na lista na hora ("enviando…") e é confirmado.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as acorn from 'acorn';
import { chromium } from 'playwright';

const RAIZ = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const HTML = path.join(RAIZ, 'www', 'index.html');
const FIX = JSON.parse(fs.readFileSync(path.join(RAIZ, 'ci', 'fixture.json'), 'utf8'));
let falhas = 0;
const ok = (c, msg) => { if (c) console.log('ok   ' + msg); else { falhas++; console.log('::error::' + msg); } };

// 1. sintaxe
const html = fs.readFileSync(HTML, 'utf8');
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
scripts.forEach((s, i) => {
  try { acorn.parse(s, { ecmaVersion: 'latest', sourceType: 'script' }); ok(true, 'sintaxe do script ' + (i + 1)); }
  catch (e) { ok(false, 'sintaxe do script ' + (i + 1) + ': ' + e.message); }
});

// 2-4. tela de verdade num Chromium sem janela, com o servidor simulado
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true });
const erros = [];
p.on('pageerror', e => erros.push(String(e)));
p.on('console', m => { if (m.type() === 'error') erros.push(m.text()); });
await p.route(/fonts\.(googleapis|gstatic)\.com/, r => r.fulfill({ status: 200, body: '' }));
await p.route(/api\.github\.com/, r => r.fulfill({ status: 404, body: '{}' }));
await p.addInitScript((fix) => {
  const resp = (fn, args) => {
    if (fix[fn]) return JSON.parse(JSON.stringify(fix[fn]));
    if (fn === 'salvarLancamento') return { ok: true, linha: 2500 };
    if (/^(get|listar)/.test(fn)) return fn === 'listarArquivos' ? [] : {};
    return { ok: true };
  };
  const runner = (okf, errf) => new Proxy({}, { get: (_, fn) => {
    if (fn === 'withSuccessHandler') return (f) => runner(f, errf);
    if (fn === 'withFailureHandler') return (f) => runner(okf, f);
    return (...args) => setTimeout(() => { try { okf && okf(resp(fn, args)); } catch (e) { errf && errf(e); } }, fn === 'salvarLancamento' ? 800 : 120);
  } });
  window.google = { script: { run: runner(null, null) } };
}, FIX);
await p.goto('file://' + HTML);
await p.waitForFunction(() => typeof DADOS !== 'undefined' && DADOS && document.body.innerText.includes('Pode gastar hoje'), null, { timeout: 15000 }).then(() => ok(true, 'Painel abriu'), () => ok(false, 'Painel não abriu'));
for (const t of ['semana', 'lista', 'mais', 'painel']) {
  await p.evaluate((t) => go(t), t); await p.waitForTimeout(500);
  ok(erros.length === 0, 'aba ' + t + (erros.length ? ': ' + erros[0] : ''));
}
await p.evaluate(() => go('mais')); await p.waitForTimeout(300);
for (const k of ['pend', 'imp', 'sim', 'orc', 'ana', 'venc', 'reemb', 'rendas', 'ajustes', 'glo', 'cat']) {
  await p.evaluate((k) => { try { abrirSub(k, 'mais'); } catch (e) { console.error('abrirSub ' + k + ': ' + e.message); } }, k); await p.waitForTimeout(450);
  await p.evaluate(() => { try { voltarSub(); } catch (e) {} }); await p.waitForTimeout(250);
  ok(erros.length === 0, 'tela ' + k + (erros.length ? ': ' + erros[0] : ''));
}
// gasto novo: aparece na hora e depois é confirmado
await p.evaluate(() => go('lista')); await p.waitForTimeout(400);
await p.evaluate(() => { const at = atalhosLista(); abrirLancar(at.length ? { atalho: at[0] } : {}); });
await p.waitForTimeout(600);
await p.evaluate(() => { S.f = Object.assign({}, S.f, { valor: '12,34', desc: 'Teste automático', cat: S.f.cat || LISTAS.categorias[0], meio: 'Pix' }); set(); });
await p.click('#sheetEl .save'); await p.waitForTimeout(700);
ok(await p.evaluate(() => LANCS.some(x => x.desc === 'Teste automático' && x.pend === 'env')), 'gasto novo aparece na hora (enviando…)');
await p.waitForTimeout(1500);
ok(await p.evaluate(() => LANCS.some(x => x.desc === 'Teste automático' && x.linha === 2500 && !x.pend)), 'gasto confirmado pela planilha');
ok(erros.length === 0, 'sem erros na tela' + (erros.length ? ': ' + erros.slice(0, 3).join(' | ') : ''));
await b.close();
if (falhas) { console.log(falhas + ' teste(s) falharam'); process.exit(1); }
console.log('Todos os testes passaram.');
