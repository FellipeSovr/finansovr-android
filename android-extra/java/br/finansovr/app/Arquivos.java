package br.finansovr.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Lê arquivos recebidos (compartilhar) ou escolhidos (atalho "Importar fatura") e entrega ao app em base64. */
final class Arquivos {
    static final long MAX = 25L * 1024 * 1024;

    private Arquivos() {}

    static JSArray ler(Context c, List<Uri> uris, String tipoPadrao) {
        JSArray arr = new JSArray();
        for (Uri u : uris) {
            JSObject o = new JSObject();
            String nome = "arquivo";
            long tam = -1;
            try (Cursor k = c.getContentResolver().query(u, null, null, null, null)) {
                if (k != null && k.moveToFirst()) {
                    int n = k.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = k.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0) nome = k.getString(n);
                    if (s >= 0 && !k.isNull(s)) tam = k.getLong(s);
                }
            } catch (Exception ignored) {}
            String tipo = null;
            try { tipo = c.getContentResolver().getType(u); } catch (Exception ignored) {}
            if (tipo == null) tipo = tipoPadrao != null ? tipoPadrao : "";
            try {
                o.put("nome", nome);
                o.put("tipo", tipo);
                try {
                    if (tam > MAX) throw new Exception("maior que 25 MB");
                    byte[] b = bytes(c, u);
                    o.put("tam", b.length);
                    o.put("dados", Base64.encodeToString(b, Base64.NO_WRAP));
                } catch (Exception e) {
                    o.put("tam", (int) Math.max(0, Math.min(tam, Integer.MAX_VALUE)));
                    o.put("erro", e.getMessage() == null ? "não deu para ler o arquivo" : e.getMessage());
                }
            } catch (Exception ignored) {}
            arr.put(o);
        }
        return arr;
    }

    private static byte[] bytes(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
