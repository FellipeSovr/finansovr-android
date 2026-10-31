package br.finansovr.app;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
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
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Finansovr v7.0: tela inteira (edge-to-edge) com as margens das barras passadas para o app,
 * voltar pelo gesto com prévia (Android 14+) e arquivos compartilhados por outros apps.
 */
public class MainActivity extends BridgeActivity {
    static volatile float sat = 0f, sab = 0f;
    private static final long MAX = 25L * 1024 * 1024;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FzPlugin.class);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        try { bridge.getWebView().setBackgroundColor(Color.parseColor("#071430")); } catch (Exception ignored) {}

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

        tratarIntent(getIntent());
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

    // arquivos recebidos pelo "Compartilhar" de outro app: lidos aqui e entregues ao app pelo plugin
    private void tratarIntent(Intent i) {
        if (i == null) return;
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
            JSArray arr = new JSArray();
            for (Uri u : uris) {
                JSObject o = new JSObject();
                String nome = "arquivo";
                long tam = -1;
                try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                        if (n >= 0) nome = c.getString(n);
                        if (s >= 0 && !c.isNull(s)) tam = c.getLong(s);
                    }
                } catch (Exception ignored) {}
                String tipo = null;
                try { tipo = getContentResolver().getType(u); } catch (Exception ignored) {}
                if (tipo == null) tipo = tipoIntent != null ? tipoIntent : "";
                try {
                    o.put("nome", nome);
                    o.put("tipo", tipo);
                    try {
                        if (tam > MAX) throw new Exception("maior que 25 MB");
                        byte[] b = ler(u);
                        o.put("tam", b.length);
                        o.put("dados", Base64.encodeToString(b, Base64.NO_WRAP));
                    } catch (Exception e) {
                        o.put("tam", (int) Math.max(0, Math.min(tam, Integer.MAX_VALUE)));
                        o.put("erro", e.getMessage() == null ? "não deu para ler o arquivo" : e.getMessage());
                    }
                } catch (Exception ignored) {}
                arr.put(o);
            }
            FzPlugin.pendentes = arr;
            js("window.fzRecebidos&&fzRecebidos()");
        }).start();
    }

    private byte[] ler(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("não deu para abrir o arquivo");
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX) throw new Exception("maior que 25 MB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
