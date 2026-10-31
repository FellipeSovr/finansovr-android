package br.finansovr.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.media.AudioAttributes;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Ponte do Finansovr com o Android: versão, vibração, barra de status, arquivos, atualização, leitor de QR (v7.0)
 * e, na v7.1, notificações, permissões do guia, widget, atalhos do ícone e a agenda das conferências.
 */
@CapacitorPlugin(name = "Fz", permissions = {
        @Permission(alias = "notificacoes", strings = {Manifest.permission.POST_NOTIFICATIONS})
})
public class FzPlugin extends Plugin {
    static volatile JSArray pendentes = null;
    static volatile String destPendente = null;

    private static void p(JSObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        String v = "7.1.1";
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            if (pi.versionName != null) v = pi.versionName;
        } catch (Exception ignored) {}
        p(r, "versao", v);
        p(r, "aparelho", (Build.MANUFACTURER + " " + Build.MODEL).trim());
        p(r, "sat", Double.valueOf(MainActivity.sat));
        p(r, "sab", Double.valueOf(MainActivity.sab));
        call.resolve(r);
    }

    // v7.1.1: vibração pelo motor com uso "toque" (o mesmo canal da vibração ao tocar no sistema). A 7.0 usava efeitos
    // prontos e a 7.1.0 vibrava sem dizer o uso: no HyperOS, as duas formas podem ser silenciadas.
    // A resposta diz o que o celular informa, para o app explicar quando a vibração está desligada no sistema.
    @PluginMethod
    public void vibrar(PluginCall call) {
        Integer v0 = call.getInt("ms", 8);
        int ms = Math.max(8, Math.min(80, v0 == null ? 8 : v0));
        JSObject r = new JSObject();
        boolean tem = false;
        int toque = 1;
        try { toque = Settings.System.getInt(getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1); } catch (Exception ignored) {}
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
            tem = v != null && v.hasVibrator();
            if (tem) {
                if (Build.VERSION.SDK_INT >= 33 && toque != 0) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Exception e) {
            p(r, "erro", e.getMessage() == null ? "falhou" : e.getMessage());
        }
        p(r, "tem", Boolean.valueOf(tem));
        p(r, "toque", Boolean.valueOf(toque != 0));
        call.resolve(r);
    }

    @PluginMethod
    public void tema(PluginCall call) {
        Boolean c0 = call.getBoolean("claro", false);
        final boolean claro = c0 != null && c0;
        getActivity().runOnUiThread(() -> {
            try {
                WindowInsetsControllerCompat w = WindowCompat.getInsetsController(getActivity().getWindow(), getActivity().getWindow().getDecorView());
                w.setAppearanceLightStatusBars(claro);
                w.setAppearanceLightNavigationBars(claro);
                getBridge().getWebView().setBackgroundColor(Color.parseColor(claro ? "#EAF0FA" : "#071430"));
            } catch (Exception ignored) {}
        });
        call.resolve();
    }

    @PluginMethod
    public void recebidos(PluginCall call) {
        JSObject r = new JSObject();
        JSArray a = pendentes;
        pendentes = null;
        p(r, "arqs", a != null ? a : new JSArray());
        call.resolve(r);
    }

    // ---------- atualização (v7.0)
    @PluginMethod
    public void baixar(PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("endereço do download vazio"); return; }
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(getContext().getCacheDir(), "atualizacao");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("sem espaço para o download");
                File f = new File(dir, "finansovr.apk");
                String atual = url;
                int voltas = 0;
                while (true) {
                    con = (HttpURLConnection) new URL(atual).openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(30000);
                    int st = con.getResponseCode();
                    if (st >= 300 && st < 400 && voltas++ < 6) {
                        atual = con.getHeaderField("Location");
                        con.disconnect();
                        if (atual == null) throw new Exception("redirecionamento sem destino");
                        continue;
                    }
                    if (st != 200) throw new Exception("o download respondeu " + st);
                    break;
                }
                long total = con.getContentLengthLong(), feito = 0;
                int ult = -1;
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        feito += n;
                        if (total > 0) {
                            int pc = (int) (feito * 100 / total);
                            if (pc != ult) {
                                ult = pc;
                                JSObject e = new JSObject();
                                p(e, "p", Integer.valueOf(pc));
                                notifyListeners("progresso", e);
                            }
                        }
                    }
                }
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? "o download falhou" : e.getMessage());
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }

    @PluginMethod
    public void instalar(PluginCall call) {
        Context ctx = getContext();
        File f = new File(new File(ctx.getCacheDir(), "atualizacao"), "finansovr.apk");
        if (!f.exists()) { call.reject("o arquivo baixado não foi encontrado"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(s);
            call.reject("permissao");
            return;
        }
        Uri u = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void lerQR(PluginCall call) {
        GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
        GmsBarcodeScanner s = GmsBarcodeScanning.getClient(getActivity(), o);
        s.startScan()
                .addOnSuccessListener(b -> {
                    JSObject r = new JSObject();
                    p(r, "texto", b.getRawValue() == null ? "" : b.getRawValue());
                    call.resolve(r);
                })
                .addOnCanceledListener(() -> call.reject("cancelado"))
                .addOnFailureListener(e -> call.reject(e.getMessage() == null ? "o leitor falhou" : e.getMessage()));
    }

    @PluginMethod
    public void sair(PluginCall call) {
        getActivity().runOnUiThread(() -> getActivity().moveTaskToBack(true));
        call.resolve();
    }

    // ---------- v7.1: telas abertas por notificação, widget ou atalho
    @PluginMethod
    public void destino(PluginCall call) {
        JSObject r = new JSObject();
        p(r, "dest", destPendente == null ? "" : destPendente);
        destPendente = null;
        call.resolve(r);
    }

    // ---------- v7.1: endereço da API e chave deste celular, para as conferências com o app fechado
    @PluginMethod
    public void credenciais(PluginCall call) {
        String api = call.getString("api", ""), id = call.getString("id", ""), chave = call.getString("chave", "");
        Avisos.prefs(getContext()).edit().putString("api", api == null ? "" : api).putString("id", id == null ? "" : id)
                .putString("chave", chave == null ? "" : chave).apply();
        call.resolve();
    }

    @PluginMethod
    public void agenda(PluginCall call) {
        Boolean n = call.getBoolean("notif", false), l = call.getBoolean("lembrete", false);
        Avisos.prefs(getContext()).edit().putBoolean("notif", n != null && n).putBoolean("lembrete", l != null && l).apply();
        Avisos.canal(getContext());
        Avisos.agendar(getContext());
        call.resolve();
    }

    @PluginMethod
    public void notificar(PluginCall call) {
        String tipo = call.getString("tipo", "aviso");
        Avisos.notificar(getContext(), tipo + ":" + System.currentTimeMillis(), call.getString("tit", "Finansovr"),
                call.getString("txt", ""), call.getString("acao", ""), call.getString("dest", "painel"));
        call.resolve();
    }

    @PluginMethod
    public void widget(PluginCall call) {
        JSObject d = call.getObject("dados", new JSObject());
        if (d != null) {
            Widgets.salvarTudo(getContext(), d);
            Atalhos.atualizar(getContext(), d.optJSONArray("atalhos"));
        }
        call.resolve();
    }

    // ---------- v7.1: guia "Avisos no celular" (permissão, Início automático e bateria)
    private boolean notifLiberadas() {
        return NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
    }

    private boolean bateriaLivre() {
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    private void ok(PluginCall call, boolean ok) {
        JSObject r = new JSObject();
        p(r, "ok", Boolean.valueOf(ok));
        call.resolve(r);
    }

    @PluginMethod
    public void permissao(PluginCall call) {
        String t = call.getString("tipo", "");
        Context ctx = getContext();
        String pkg = ctx.getPackageName();
        if ("estado".equals(t)) {
            JSObject r = new JSObject();
            p(r, "perm", Boolean.valueOf(notifLiberadas()));
            p(r, "bat", Boolean.valueOf(bateriaLivre()));
            call.resolve(r);
        } else if ("perm".equals(t)) {
            if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notificacoes") != PermissionState.GRANTED) {
                requestPermissionForAlias("notificacoes", call, "permVolta");
            } else if (!notifLiberadas()) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                startActivityForResult(call, i, "configVolta");
            } else {
                ok(call, true);
            }
        } else if ("auto".equals(t)) {
            Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            if (i.resolveActivity(ctx.getPackageManager()) == null) {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            }
            startActivityForResult(call, i, "autoVolta");
        } else if ("bat".equals(t)) {
            if (bateriaLivre()) { ok(call, true); return; }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg));
            if (i.resolveActivity(ctx.getPackageManager()) == null) i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivityForResult(call, i, "batVolta");
        } else {
            call.reject("tipo de permissão desconhecido");
        }
    }

    @PermissionCallback
    private void permVolta(PluginCall call) {
        ok(call, notifLiberadas());
    }

    @ActivityCallback
    private void configVolta(PluginCall call, ActivityResult r) {
        ok(call, notifLiberadas());
    }

    @ActivityCallback
    private void autoVolta(PluginCall call, ActivityResult r) {
        ok(call, true);
    }

    @ActivityCallback
    private void batVolta(PluginCall call, ActivityResult r) {
        ok(call, bateriaLivre());
    }

    // ---------- v7.1: atalho "Importar fatura": seletor de arquivos do Android
    @PluginMethod
    public void escolherArquivos(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", "text/csv", "text/comma-separated-values",
                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/x-ofx", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, i, "arquivosVolta");
    }

    @ActivityCallback
    private void arquivosVolta(PluginCall call, ActivityResult res) {
        final Intent d = res.getData();
        if (res.getResultCode() != Activity.RESULT_OK || d == null) {
            JSObject r = new JSObject();
            p(r, "arqs", new JSArray());
            call.resolve(r);
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        ClipData cd = d.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (d.getData() != null) {
            uris.add(d.getData());
        }
        new Thread(() -> {
            JSObject r = new JSObject();
            p(r, "arqs", Arquivos.ler(getContext(), uris, null));
            call.resolve(r);
        }).start();
    }

    static String aspas(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }
}
