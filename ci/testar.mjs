// Testes automáticos antes de compilar o APK (v7.2.0). Para o build se algo falhar.
// 1) sintaxe de todos os scripts da tela; 2) a tela abre com dados de exemplo (ci/fixture.json, fictícios) e sem erros;
// 3) as abas e as telas do Mais montam; 4) um gasto novo aparece na lista na hora ("enviando…") e é confirmado;
// 5) (v7.2.2) sem internet, o gasto vai para a fila do celular e sai quando a conexão volta;
// 6) (v7.3.0) as chaves do teste de fluidez: contador de quadros e desfoque;
// 7) (v7.3.1) chips do Painel, janela do + sem teclado, vidro "falso" em movimento e vencimento com repetição, ícone e cor.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as acorn from 'acorn';
import { chromium } from 'playwright';

const RAIZ = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const HTML = path.join(RAIZ, 'www', 'index.html');
const FIX = JSON.parse(fs.readFileSync(path.join(RAIZ, 'ci', 'fixture.json'), 'utf8'));
const LISTAS_CARTAO = FIX.getTudo.listas.cartoes[0];
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
    return (...args) => { (window.__chamadas = window.__chamadas || []).push([fn, args]); return setTimeout(() => { try { okf && okf(resp(fn, args)); } catch (e) { errf && errf(e); } }, fn === 'salvarLancamento' ? 800 : 120); };
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
// v7.2.2: sem internet o gasto vai para a fila do celular, o Painel fica estimado e a fila sai quando a conexão volta
await p.evaluate(() => { NAT.simula = true; mudarConexao(false); go('painel'); }); await p.waitForTimeout(400);
await p.evaluate(() => { const at = atalhosLista(); abrirLancar(at.length ? { atalho: at[0] } : {}); });
await p.waitForTimeout(600);
await p.evaluate(() => { const c = (LISTAS.categorias || []).find(x => !(LISTAS.reservadas || []).includes(x)) || LISTAS.categorias[0]; S.f = Object.assign({}, S.f, { valor: '10,00', desc: 'Sem internet', cat: c, meio: 'Pix' }); set(); });
await p.click('#sheetEl .save'); await p.waitForTimeout(1200);
ok(await p.evaluate(() => FL.itens.length === 1 && FL.itens[0].args[0].origem === 'App · offline'), 'sem internet: gasto guardado na fila');
ok(await p.evaluate(() => LANCS.some(x => x.desc === 'Sem internet' && x.pend === 'fila')), 'sem internet: gasto na lista marcado "no celular"');
ok(await p.evaluate(() => NAT.estimado && !!document.querySelector('.estb')), 'sem internet: Painel estimado');
await p.evaluate(() => mudarConexao(true)); await p.waitForTimeout(3000);
ok(await p.evaluate(() => FL.itens.length === 0 && LANCS.some(x => x.desc === 'Sem internet' && x.linha > 0 && !x.pend) && !NAT.estimado), 'internet de volta: fila enviada');
// v7.3.0 (versão de teste): chaves do teste de fluidez em Ajustes, contador de quadros e desfoque desligável
await p.evaluate(() => { go('mais'); }); await p.waitForTimeout(300);
await p.evaluate(() => abrirSub('ajustes', 'mais')); await p.waitForTimeout(500);
ok(await p.evaluate(() => document.body.innerText.includes('Teste de fluidez') && !document.querySelector('.tfps') && !M.vp.classList.contains('leve')), 'teste de fluidez: chaves em Ajustes, tudo como na 7.2.2 por padrão');
await p.evaluate(() => tfContador()); await p.waitForTimeout(900);
ok(await p.evaluate(() => /^\d+ · –$/.test((document.querySelector('.tfps') || {}).textContent || '')), 'contador de quadros aparece e conta');
await p.evaluate(() => abrirG('horas')); await p.waitForTimeout(1300);
ok(await p.evaluate(() => /^\d+ · \d+$/.test(document.querySelector('.tfps').textContent) && TF.pior > 0), 'contador mostra o pior trecho da última janela');
const filtro = () => p.evaluate(() => getComputedStyle(document.querySelector('#sheetEl')).backdropFilter);
ok(/blur/.test(await filtro()), 'janela com desfoque (padrão)');
await p.evaluate(() => tfDesfoque()); await p.waitForTimeout(300);
ok(await filtro() === 'none' && await p.evaluate(() => M.vp.classList.contains('leve')), 'desfoque desligado pela chave');
await p.evaluate(() => { tfDesfoque(); tfContador(); fecharSheet(); }); await p.waitForTimeout(500);
ok(await p.evaluate(() => !document.querySelector('.tfps') && !M.vp.classList.contains('leve')), 'chaves desligadas: volta ao normal');
// v7.3.1: chips do Painel
await p.evaluate(() => go('painel')); await p.waitForTimeout(500);
const chips = await p.evaluate(() => [...document.querySelectorAll('.tile .k')].map(x => x.textContent));
ok(chips[0] === 'Por dia, amanhã' && chips[1] === 'Por dia, até domingo' && !chips.includes('Na semana'), 'chips do Painel: "Por dia, amanhã" e "Por dia, até domingo"');
ok(await p.evaluate(() => { const d = DADOS.semana.find(x => x.situacao === 'Hoje'); return !!d && document.querySelectorAll('.tile .v')[1].textContent === fm(nTxt(d.limite)); }), '"Por dia, até domingo" é o limite de hoje na semana');
// v7.3.1: o teclado não abre sozinho no gasto novo, e a janela sobe sem desfoque real
await p.evaluate(() => abrirLancar({})); await p.waitForTimeout(120);
ok(await p.evaluate(() => getComputedStyle(document.getElementById('sheetEl')).backdropFilter) === 'none', 'janela subindo: vidro sem desfoque');
await p.waitForTimeout(800);
ok(await p.evaluate(() => (document.activeElement || {}).id !== 'fv'), 'gasto novo: o campo de valor não recebe o foco sozinho');
ok(/blur/.test(await p.evaluate(() => getComputedStyle(document.getElementById('sheetEl')).backdropFilter)), 'janela parada: vidro com desfoque');
await p.evaluate(() => fecharSheet()); await p.waitForTimeout(500);
// v7.3.1: vencimento com repetição, Crédito no "Todo mês", ícone e cor; com o servidor antigo, o formulário fica como era
await p.evaluate(() => go('mais')); await p.waitForTimeout(300);
await p.evaluate(() => abrirSub('venc', 'mais')); await p.waitForTimeout(700);
await p.evaluate(() => abrirG('venc', { novo: true })); await p.waitForTimeout(700);
const botoes = () => p.evaluate(() => [...document.querySelectorAll('#sheetEl button')].map(b => b.textContent.trim()));
let bt = await botoes();
ok(bt.includes('Todo mês') && bt.includes('Todo ano') && !bt.includes('Crédito') && bt.some(t => t.startsWith('Ícone e cor')), 'novo vencimento: repetição e ícone');
await p.evaluate(() => { S.fx = Object.assign({}, S.fx, { vDesc: 'Teste mensal', vVal: '50,00', vData: '2030-01-31', vRep: 'mes', vMeio: 'Crédito', vGal: true }); set(); }); await p.waitForTimeout(400);
bt = await botoes();
ok(bt.includes('Crédito') && bt.includes(LISTAS_CARTAO), '"Todo mês" aceita Crédito, com o cartão');
await p.evaluate(() => { document.querySelector('#sheetEl .icb:not(.on)').click(); }); await p.waitForTimeout(300);
await p.evaluate(() => { document.querySelector('#sheetEl .save').click(); }); await p.waitForTimeout(900);
const env = await p.evaluate(() => (window.__chamadas.filter(c => c[0] === 'salvarVencimento').pop() || [0, [{}]])[1][0]);
ok(env.rep === 'mes' && env.meio === 'Crédito' && !!env.cartao && /^[a-z0-9]+$/.test(env.icone) && /^#[0-9A-Fa-f]{6}$/.test(env.cor), 'vencimento mensal enviado com repetição, cartão, ícone e cor');
await p.evaluate(() => abrirG('venc', { novo: true })); await p.waitForTimeout(900);
await p.evaluate(() => { VENCD.v73 = false; set(); }); await p.waitForTimeout(200);
bt = await botoes();
ok(!bt.includes('Todo mês') && bt.includes('Todo ano') && !bt.some(t => t.startsWith('Ícone e cor')), 'servidor antigo: formulário sem "Todo mês" e sem ícone');
await p.evaluate(() => { fecharSheet(); }); await p.waitForTimeout(400);
ok(!(await p.evaluate(() => { abrirSub('ajustes', 'mais'); return new Promise(r => setTimeout(() => r(document.body.innerText.includes('120 Hz')), 600)); })), 'Ajustes sem a chave de 120 Hz');
ok(erros.length === 0, 'sem erros na tela' + (erros.length ? ': ' + erros.slice(0, 3).join(' | ') : ''));
await b.close();
if (falhas) { console.log(falhas + ' teste(s) falharam'); process.exit(1); }
console.log('Todos os testes passaram.');
