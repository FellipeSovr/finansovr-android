package br.finansovr.app;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;

/**
 * Finansovr: tela inteira (edge-to-edge) com as margens das barras passadas para o app, voltar pelo gesto com prévia,
 * arquivos compartilhados por outros apps (v7.0) e telas abertas por notificação, widget ou atalho do ícone (v7.1).
 */
public class MainActivity extends BridgeActivity {
    static volatile float sat = 0f, sab = 0f;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FzPlugin.class);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        try { bridge.getWebView().setBackgroundColor(Color.parseColor("#071430")); } catch (Exception ignored) {}
        Avisos.canal(this);

        // margens da barra de status e da barra de gestos; com o teclado aberto, a tela encolhe até ele
        View raiz = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, ins) -> {
            Insets barras = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean teclado = ins.isVisible(WindowInsetsCompat.Type.ime());
            Insets ime = ins.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, teclado ? ime.bottom : 0);
            float d = getResources().getDisplayMetrics().density;
            sat = barras.top / d;
            sab = teclado ? 0f : barras.bottom / d;
            js("window.fzInsets&&fzInsets(" + sat + "," + sab + ")");
            return WindowInsetsCompat.CONSUMED;
        });

        // voltar: o app fecha um nível por vez; quando não há mais nada para fechar, o app vai para o fundo
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackStarted(BackEventCompat e) {
                js("window.voltarProg&&voltarProg(0,'" + lado(e) + "')");
            }

            @Override
            public void handleOnBackProgressed(BackEventCompat e) {
                js("window.voltarProg&&voltarProg(" + e.getProgress() + ",'" + lado(e) + "')");
            }

            @Override
            public void handleOnBackCancelled() {
                js("window.voltarSolta&&voltarSolta(false)");
            }

            @Override
            public void handleOnBackPressed() {
                WebView w = bridge.getWebView();
                if (w == null) { moveTaskToBack(true); return; }
                w.evaluateJavascript("String(window.voltarSolta?voltarSolta(true):false)", r -> {
                    if (r == null || r.contains("false")) moveTaskToBack(true);
                });
            }
        });

        // v7.2.2: o app sabe na hora quando a internet cai ou volta (sem esperar um envio falhar)
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { js("window.fzRede&&fzRede(true)"); }
                @Override public void onLost(Network n) { js("window.fzRede&&fzRede(false)"); }
            });
        } catch (Exception ignored) {}

        tratarIntent(getIntent());
    }

    static boolean temRede(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            NetworkCapabilities k = n == null ? null : cm.getNetworkCapabilities(n);
            return k != null && k.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private static String lado(BackEventCompat e) {
        return e.getSwipeEdge() == BackEventCompat.EDGE_RIGHT ? "dir" : "esq";
    }

    void js(String codigo) {
        runOnUiThread(() -> {
            try {
                if (bridge != null && bridge.getWebView() != null) bridge.getWebView().evaluateJavascript(codigo, null);
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        tratarIntent(intent);
    }

    private void tratarIntent(Intent i) {
        if (i == null) return;
        // v7.1: notificação, widget ou atalho do ícone pedindo uma tela
        String dest = i.getStringExtra("dest");
        if (dest != null && !dest.isEmpty()) {
            FzPlugin.destPendente = dest;
            final String q = FzPlugin.aspas(dest);
            setIntent(new Intent(this, MainActivity.class));
            runOnUiThread(() -> {
                try {
                    bridge.getWebView().evaluateJavascript(
                            "(function(){try{if(window.__fzIniciou&&window.fzAbrir&&NAT.conectado){fzAbrir(" + q + ");return '1';}}catch(e){}return '0';})()",
                            r -> { if (r != null && r.contains("1")) FzPlugin.destPendente = null; });
                } catch (Exception ignored) {}
            });
            return;
        }
        // v7.0: arquivos recebidos pelo "Compartilhar" de outro app
        String acao = i.getAction();
        final ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(acao)) {
            Uri u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(acao)) {
            ArrayList<Uri> l = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (l != null) uris.addAll(l);
        }
        if (uris.isEmpty()) return;
        final String tipoIntent = i.getType();
        setIntent(new Intent(this, MainActivity.class));
        new Thread(() -> {
            FzPlugin.pendentes = Arquivos.ler(this, uris, tipoIntent);
            js("window.fzRecebidos&&fzRecebidos()");
        }).start();
    }
}
