"""Prepara a pasta www do APK a partir de app/Index.html (a mesma tela do Apps Script):
põe o endereço da API e o repositório, e embute a fonte Plus Jakarta Sans para o app abrir sem internet."""
import json, os, re, sys, urllib.request

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WWW = os.path.join(RAIZ, 'www')
os.makedirs(os.path.join(WWW, 'fontes'), exist_ok=True)

cfg = json.load(open(os.path.join(RAIZ, 'config.json'), encoding='utf-8'))
api = (cfg.get('api') or '').strip()
repo = os.environ.get('FZ_REPO') or cfg.get('repo') or ''
sem_api = not re.match(r'^https://script\.google\.com/macros/s/[\w-]+/exec$', api)
if sem_api:
    # ainda sem o endereço: compila mesmo assim (para conferir o código), mas não publica versão
    print('::warning::config.json › "api" ainda não tem o endereço da implantação "API do app"; o APK sai só como teste.')
    open(os.path.join(RAIZ, '.sem_api'), 'w').write('1')
    api = '__FZ_API_URL__'

html = open(os.path.join(RAIZ, 'app', 'Index.html'), encoding='utf-8').read()
for k, v in (('__FZ_API_URL__', api), ('__FZ_REPO__', repo)):
    if k not in html:
        print('::error::marcador %s não encontrado em app/Index.html' % k)
        sys.exit(1)
    html = html.replace(k, v)
html = html.replace('<base target="_top">', '')

# fonte embutida (se o download falhar, fica a fonte da internet)
try:
    UA = {'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36'}
    url = 'https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap'
    css = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode()
    for i, f in enumerate(sorted(set(re.findall(r'url\((https://fonts\.gstatic\.com/[^)]+)\)', css)))):
        nome = 'f%d.woff2' % i
        open(os.path.join(WWW, 'fontes', nome), 'wb').write(urllib.request.urlopen(urllib.request.Request(f, headers=UA), timeout=30).read())
        css = css.replace(f, 'fontes/' + nome)
    open(os.path.join(WWW, 'fontes', 'fontes.css'), 'w', encoding='utf-8').write(css)
    html = re.sub(r'<link rel="stylesheet" href="https://fonts\.googleapis\.com/css2[^"]*">', '<link rel="stylesheet" href="fontes/fontes.css">', html)
    html = re.sub(r'<link rel="preconnect"[^>]*>\n?', '', html)
    print('Fonte embutida.')
except Exception as e:
    print('::warning::Fonte não embutida (%s); o app usa a da internet.' % e)

open(os.path.join(WWW, 'index.html'), 'w', encoding='utf-8').write(html)
print('www/index.html pronto (API configurada, repositório %s).' % (repo or '—'))
