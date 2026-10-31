package br.finansovr.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Ponte do Finansovr com o Android: versão, vibração, barra de status, arquivos recebidos, atualização e leitor de QR. */
@CapacitorPlugin(name = "Fz")
public class FzPlugin extends Plugin {
    static volatile JSArray pendentes = null;

    private static void p(JSObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        String v = "7.0.0";
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

    @PluginMethod
    public void vibrar(PluginCall call) {
        Integer v0 = call.getInt("ms", 8);
        int ms = v0 == null ? 8 : v0;
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    int ef = ms <= 6 ? VibrationEffect.EFFECT_TICK : ms <= 12 ? VibrationEffect.EFFECT_CLICK : ms <= 20 ? VibrationEffect.EFFECT_HEAVY_CLICK : -1;
                    v.vibrate(ef >= 0 ? VibrationEffect.createPredefined(ef) : VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Exception ignored) {}
        call.resolve();
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
}
