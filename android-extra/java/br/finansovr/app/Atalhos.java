package br.finansovr.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Atalhos ao segurar o ícone: Novo gasto, Lançar (categoria mais usada), Semana e Importar fatura. */
final class Atalhos {
    private Atalhos() {}

    static void atualizar(Context c, JSONArray at) {
        try {
            List<ShortcutInfoCompat> l = new ArrayList<>();
            int r = 0;
            l.add(atalho(c, "novo", "Novo gasto", "lancar", R.drawable.sc_novo, 0xFF8FE6FF, r++));
            JSONObject a = at != null ? at.optJSONObject(0) : null;
            if (a != null) {
                l.add(atalho(c, "categoria", "Lançar " + a.optString("nome"), "lancar:" + a.optString("cat"),
                        Widgets.icone(c, a.optString("ic")), Widgets.cor(a.optString("cor"), 0xFF8FE6FF), r++));
            }
            l.add(atalho(c, "semana", "Semana", "semana", R.drawable.sc_semana, 0xFFB9A6FF, r++));
            l.add(atalho(c, "importar", "Importar fatura", "importar", R.drawable.sc_importar, 0xFF6FD3FF, r));
            ShortcutManagerCompat.setDynamicShortcuts(c, l);
        } catch (Exception ignored) {}
    }

    private static ShortcutInfoCompat atalho(Context c, String id, String nome, String dest, int icone, int cor, int ordem) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("dest", dest)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return new ShortcutInfoCompat.Builder(c, id).setShortLabel(nome).setLongLabel(nome)
                .setIcon(IconCompat.createWithBitmap(bitmap(c, icone, cor))).setIntent(i).setRank(ordem).build();
    }

    private static Bitmap bitmap(Context c, int res, int cor) {
        int px = Math.round(48 * c.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF0E1F45);
        k.drawCircle(px / 2f, px / 2f, px / 2f, p);
        p.setColor(cor);
        p.setAlpha(60);
        k.drawCircle(px / 2f, px / 2f, px / 2f, p);
        Drawable d = ContextCompat.getDrawable(c, res);
        if (d != null) {
            d = DrawableCompat.wrap(d.mutate());
            DrawableCompat.setTint(d, cor);
            int m = Math.round(px * 0.26f);
            d.setBounds(m, m, px - m, px - m);
            d.draw(k);
        }
        return b;
    }
}
