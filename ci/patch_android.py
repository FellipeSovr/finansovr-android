"""Ajusta o projeto Android gerado pelo Capacitor (npx cap add android) com o que é do Finansovr:
código nativo, ícones, tela de abertura, permissões, compartilhar para o app, versão e assinatura.
Para na hora (erro) se o modelo do Capacitor mudou e algum ponto de ajuste não for encontrado."""
import os, re, shutil, sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(RAIZ, 'android', 'app')
MAIN = os.path.join(APP, 'src', 'main')
EXTRA = os.path.join(RAIZ, 'android-extra')


def falha(msg):
    print('::error::' + msg)
    sys.exit(1)


def ler(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def gravar(p, s):
    with open(p, 'w', encoding='utf-8') as f:
        f.write(s)


def troca(s, velho, novo, onde):
    if velho not in s:
        falha('Ponto de ajuste não encontrado em ' + onde + ': ' + velho[:80])
    return s.replace(velho, novo, 1)


# 1. código Java e recursos (ícones, cores, tela de abertura, caminhos do FileProvider)
shutil.copytree(os.path.join(EXTRA, 'java'), os.path.join(MAIN, 'java'), dirs_exist_ok=True)
shutil.copytree(os.path.join(EXTRA, 'res'), os.path.join(MAIN, 'res'), dirs_exist_ok=True)

gravar(os.path.join(MAIN, 'res', 'values', 'styles.xml'), '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:windowBackground">@color/fz_fundo</item>
    </style>
    <style name="AppTheme.NoActionBar" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="windowActionBar">false</item>
        <item name="windowNoTitle">true</item>
        <item name="android:background">@null</item>
        <item name="android:windowBackground">@color/fz_fundo</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
    <style name="AppTheme.NoActionBarLaunch" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/fz_fundo</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/fz_splash</item>
        <item name="postSplashScreenTheme">@style/AppTheme.NoActionBar</item>
    </style>
</resources>
''')

# 2. manifesto: voltar com prévia, permissões e "Compartilhar com o Finansovr"
mp = os.path.join(MAIN, 'AndroidManifest.xml')
m = ler(mp)
m = troca(m, '<application', '<application\n        android:enableOnBackInvokedCallback="true"', 'AndroidManifest.xml')
filtros = '''</intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="image/*" />
                <data android:mimeType="text/csv" />
                <data android:mimeType="text/comma-separated-values" />
                <data android:mimeType="application/vnd.ms-excel" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
                <data android:mimeType="application/x-ofx" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="image/*" />
                <data android:mimeType="text/csv" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>'''
m = troca(m, '</intent-filter>', filtros, 'AndroidManifest.xml')
if 'android:windowSoftInputMode' not in m:
    m = troca(m, 'android:name=".MainActivity"', 'android:name=".MainActivity"\n            android:windowSoftInputMode="adjustResize"', 'AndroidManifest.xml')
m = troca(m, '</application>', '''    <meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="barcode_ui" />
        <!-- v7.1: widgets, conferências agendadas (0h, 12h e 18h) e reagendamento ao ligar o celular -->
        <receiver android:name=".WidgetMedio" android:exported="false" android:label="@string/wd_medio">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/fz_widget_medio" />
        </receiver>
        <receiver android:name=".WidgetPequeno" android:exported="false" android:label="@string/wd_pequeno">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/fz_widget_pequeno" />
        </receiver>
        <receiver android:name=".AlarmeReceiver" android:exported="true">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
        </receiver>
    </application>''', 'AndroidManifest.xml')
perms = ['android.permission.VIBRATE', 'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.CAMERA',
         'android.permission.ACCESS_NETWORK_STATE', 'android.permission.POST_NOTIFICATIONS', 'android.permission.RECEIVE_BOOT_COMPLETED',
         'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS']
extra = ''.join('    <uses-permission android:name="%s" />\n' % p for p in perms if p not in m)
extra += '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n'
m = troca(m, '</manifest>', extra + '</manifest>', 'AndroidManifest.xml')
gravar(mp, m)

# 3. build.gradle: versão, assinatura e leitor de QR do Google Play Services
versao = ler(os.path.join(RAIZ, 'versao.txt')).strip()
if not re.fullmatch(r'\d+\.\d+\.\d+', versao):
    falha('versao.txt precisa ter o formato 7.0.0 (veio "%s")' % versao)
a, b, c = (int(x) for x in versao.split('.'))
codigo = a * 10000 + b * 100 + c
gp = os.path.join(APP, 'build.gradle')
g = ler(gp)
g, n1 = re.subn(r'versionCode\s+\d+', 'versionCode %d' % codigo, g, count=1)
g, n2 = re.subn(r'versionName\s+"[^"]*"', 'versionName "%s"' % versao, g, count=1)
if not (n1 and n2):
    falha('versionCode/versionName não encontrados em app/build.gradle')
g = troca(g, '    buildTypes {', '''    signingConfigs {
        release {
            storeFile file("finansovr.jks")
            storePassword System.getenv("FZ_KS_SENHA")
            keyAlias "finansovr"
            keyPassword System.getenv("FZ_KS_SENHA")
        }
    }
    buildTypes {''', 'app/build.gradle')
g, n3 = re.subn(r'(buildTypes \{\s*release \{)', r'\1\n            signingConfig signingConfigs.release', g, count=1)
if not n3:
    falha('bloco buildTypes.release não encontrado em app/build.gradle')
g, n4 = re.subn(r'\ndependencies \{', "\ndependencies {\n    implementation 'com.google.android.gms:play-services-code-scanner:16.1.0'\n    implementation 'androidx.work:work-runtime:2.9.1'", g, count=1)
if not n4:
    falha('bloco dependencies não encontrado em app/build.gradle')
g, n5 = re.subn(r'\nandroid \{', '\nandroid {\n    lint {\n        checkReleaseBuilds false\n        abortOnError false\n    }', g, count=1)
if not n5:
    falha('bloco android não encontrado em app/build.gradle')
gravar(gp, g)

# 4. fonte dos widgets (Plus Jakarta Sans em TTF; sem ela, os widgets usam a fonte do sistema)
import urllib.request
fontes = os.path.join(MAIN, 'res', 'font')
os.makedirs(fontes, exist_ok=True)
try:
    for peso in (600, 700, 800):
        css = urllib.request.urlopen('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@%d' % peso, timeout=30).read().decode()
        url = re.search(r'url\((https://fonts\.gstatic\.com/[^)]+\.ttf)\)', css).group(1)
        open(os.path.join(fontes, 'fz_jakarta_%d.ttf' % peso), 'wb').write(urllib.request.urlopen(url, timeout=30).read())
    print('Fonte dos widgets embutida.')
except Exception as e:
    print('::warning::Fonte dos widgets não baixada (%s); os widgets usam a fonte do sistema.' % e)
    for nome in ('widget_medio.xml', 'widget_pequeno.xml'):
        lp = os.path.join(MAIN, 'res', 'layout', nome)
        gravar(lp, re.sub(r'\s*android:fontFamily="@font/[^"]*"', '', ler(lp)))

print('Projeto Android ajustado: versão %s (código %d)' % (versao, codigo))
