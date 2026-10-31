# Finansovr para Android

O Finansovr empacotado como app Android com o [Capacitor](https://capacitorjs.com). As telas são as mesmas do app no Apps Script (`app/Index.html`); os dados ficam na planilha, acessada pela implantação "API do app" com uma chave por celular.

- `app/Index.html`: a tela do app (gerada junto com a versão do Apps Script).
- `android-extra/`: código nativo (voltar com prévia, compartilhar para "A importar", atualização, vibração, leitor de QR) e ícones.
- `ci/`: preparação da pasta `www` e ajustes no projeto Android gerado pelo Capacitor.
- `.github/workflows/apk.yml`: compila o APK a cada envio para `main` e publica a versão de `versao.txt` em Releases.

Segredos do repositório (Settings › Secrets and variables › Actions): `FZ_KEYSTORE` (chave de assinatura em base64) e `FZ_KEYSTORE_SENHA`. Nenhum dado financeiro nem chave de acesso à planilha fica neste repositório.
