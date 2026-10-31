package br.finansovr.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/** Os dois widgets (médio 4×2 e pequeno 2×2), desenhados a partir do último resumo guardado. */
final class Widgets {
    private static final String CHAVE = "widget";

    private Widgets() {}

    static JSONObject ler(Context c) {
        try {
            return new JSONObject(Avisos.prefs(c).getString(CHAVE, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Resumo completo vindo do app (números, atalhos e valores escondidos). */
    static void salvarTudo(Context c, JSONObject d) {
        Avisos.prefs(c).edit().putString(CHAVE, d.toString()).apply();
        atualizar(c);
    }

    /** Só os números, vindos da conferência em segundo plano: mantém atalhos e valores escondidos. */
    static void salvarNumeros(Context c, JSONObject n) {
        JSONObject d = ler(c);
        String[] ks = {"pode", "gasto", "lim", "semana", "livre", "dentro", "quando"};
        for (String k : ks) {
            if (n.has(k)) {
                try { d.put(k, n.get(k)); } catch (Exception ignored) {}
            }
        }
        salvarTudo(c, d);
    }

    static String dinheiro(double v, boolean oculto) {
        if (oculto) return "R$ •••";
        NumberFormat f = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return (v < -0.004 ? "−" : "") + "R$ " + f.format(Math.abs(v));
    }

    static int cor(String s, int padrao) {
        try { return Color.parseColor(s); } catch (Exception e) { return padrao; }
    }

    static int icone(Context c, String chave) {
        int id = c.getResources().getIdentifier("fz_c_" + chave, "drawable", c.getPackageName());
        return id != 0 ? id : R.drawable.fz_c_outros;
    }

    static void atualizar(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            JSONObject d = ler(c);
            int[] med = m.getAppWidgetIds(new ComponentName(c, WidgetMedio.class));
            if (med.length > 0) m.updateAppWidget(med, medio(c, d));
            int[] peq = m.getAppWidgetIds(new ComponentName(c, WidgetPequeno.class));
            if (peq.length > 0) m.updateAppWidget(peq, pequeno(c, d));
        } catch (Exception ignored) {}
    }

    private static int progresso(JSONObject d) {
        double lim = d.optDouble("lim", 0), g = d.optDouble("gasto", 0);
        double fr = lim > 0 ? g / lim : (g > 0 ? 1 : 0);
        return (int) Math.round(Math.max(0.03, Math.min(1, fr)) * 1000);
    }

    static RemoteViews medio(Context c, JSONObject d) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_medio);
        boolean oc = d.optBoolean("oculto", false), tem = d.has("pode");
        double pode = d.optDouble("pode", 0);
        boolean neg = pode < -0.004, dentro = d.optBoolean("dentro", true);
        v.setTextViewText(R.id.wm_valor, tem ? dinheiro(pode, oc) : "—");
        v.setTextColor(R.id.wm_valor, ContextCompat.getColor(c, neg ? R.color.wd_neg : R.color.wd_tx));
        v.setViewVisibility(R.id.wm_barra, neg ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.wm_barra_neg, neg ? View.VISIBLE : View.GONE);
        v.setProgressBar(neg ? R.id.wm_barra_neg : R.id.wm_barra, 1000, progresso(d), false);
        v.setTextViewText(R.id.wm_semana, tem ? dinheiro(d.optDouble("semana", 0), oc) : "—");
        v.setTextViewText(R.id.wm_livre, tem ? dinheiro(d.optDouble("livre", 0), oc) : "—");
        v.setTextViewText(R.id.wm_rodape, tem ? ((dentro ? "dentro do limite" : "passou do limite") + " · atualizado " + d.optString("quando", "")) : "Abra o app para carregar");
        v.setOnClickPendingIntent(R.id.wm_raiz, Avisos.abrir(c, "painel", 200));
        JSONArray at = d.optJSONArray("atalhos");
        int[][] ids = {
                {R.id.wm_q1, R.id.wm_q1_bg, R.id.wm_q1_ic, R.id.wm_q1_tx},
                {R.id.wm_q2, R.id.wm_q2_bg, R.id.wm_q2_ic, R.id.wm_q2_tx},
                {R.id.wm_q3, R.id.wm_q3_bg, R.id.wm_q3_ic, R.id.wm_q3_tx}};
        for (int i = 0; i < 3; i++) {
            JSONObject a = at != null ? at.optJSONObject(i) : null;
            if (a == null) {
                v.setViewVisibility(ids[i][0], View.GONE);
                continue;
            }
            int k = cor(a.optString("cor"), 0xFF8FE6FF);
            v.setViewVisibility(ids[i][0], View.VISIBLE);
            v.setInt(ids[i][1], "setColorFilter", k);
            v.setInt(ids[i][1], "setImageAlpha", 64);
            v.setImageViewResource(ids[i][2], icone(c, a.optString("ic")));
            v.setInt(ids[i][2], "setColorFilter", k);
            v.setTextViewText(ids[i][3], a.optString("nome"));
            v.setOnClickPendingIntent(ids[i][0], Avisos.abrir(c, "lancar:" + a.optString("cat"), 210 + i));
        }
        return v;
    }

    static RemoteViews pequeno(Context c, JSONObject d) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_pequeno);
        boolean oc = d.optBoolean("oculto", false), tem = d.has("pode");
        double pode = d.optDouble("pode", 0);
        boolean neg = pode < -0.004, dentro = d.optBoolean("dentro", true);
        v.setTextViewText(R.id.wp_valor, tem ? dinheiro(pode, oc) : "—");
        v.setTextColor(R.id.wp_valor, ContextCompat.getColor(c, neg ? R.color.wd_neg : R.color.wd_tx));
        v.setViewVisibility(R.id.wp_barra, neg ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.wp_barra_neg, neg ? View.VISIBLE : View.GONE);
        v.setProgressBar(neg ? R.id.wp_barra_neg : R.id.wp_barra, 1000, progresso(d), false);
        v.setTextViewText(R.id.wp_gasto, tem ? "Gasto hoje " + dinheiro(d.optDouble("gasto", 0), oc) : "Abra o app para carregar");
        v.setTextViewText(R.id.wp_estado, dentro ? "dentro do limite" : "passou do limite");
        v.setTextColor(R.id.wp_estado, ContextCompat.getColor(c, dentro ? R.color.wd_ok : R.color.wd_neg));
        v.setViewVisibility(R.id.wp_estado, tem ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.wp_rodape, tem ? "atualizado " + d.optString("quando", "") : "");
        v.setOnClickPendingIntent(R.id.wp_raiz, Avisos.abrir(c, "painel", 201));
        return v;
    }
}
