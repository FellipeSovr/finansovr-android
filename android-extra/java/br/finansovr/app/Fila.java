package br.finansovr.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * v7.2.2: gastos feitos sem internet. O app guarda aqui uma cópia da fila (fn, args e a etiqueta única de cada envio);
 * quando a internet volta, mesmo com o app fechado, o Android acorda este envio. O resultado de cada etiqueta fica
 * guardado para o app ler ao abrir. A planilha ignora etiquetas repetidas: se o app e este envio mandarem o mesmo
 * gasto, ele entra uma vez só.
 */
public class Fila extends Worker {
    static final String TRABALHO = "fila";

    public Fila(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static JSONArray itens(Context c) {
        try { return new JSONArray(Avisos.prefs(c).getString("fila", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static JSONObject mapa(Context c, String k) {
        try { return new JSONObject(Avisos.prefs(c).getString(k, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** O app mandou a fila inteira: guarda e, se tiver algo, agenda o envio para quando houver internet. */
    static void guardar(Context c, JSONArray l) {
        synchronized (Fila.class) { Avisos.prefs(c).edit().putString("fila", l.toString()).commit(); }
        WorkManager wm = WorkManager.getInstance(c);
        if (l.length() == 0) { wm.cancelUniqueWork(TRABALHO); return; }
        OneTimeWorkRequest w = new OneTimeWorkRequest.Builder(Fila.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build();
        wm.enqueueUniqueWork(TRABALHO, ExistingWorkPolicy.REPLACE, w);
    }

    /** Resultados guardados (etiqueta → resposta) e erros (etiqueta → mensagem); o app lê e limpa. */
    static JSONObject estado(Context c, boolean limpar) {
        JSONObject r = new JSONObject();
        try {
            r.put("feitos", mapa(c, "filaFeitos"));
            r.put("erros", mapa(c, "filaErros"));
            r.put("pendentes", itens(c).length());
        } catch (Exception ignored) {}
        if (limpar) Avisos.prefs(c).edit().putString("filaFeitos", "{}").putString("filaErros", "{}").apply();
        return r;
    }

    /** Há gasto parado há mais de 24 h? Avisa uma vez por item mais antigo (sem precisar de internet). */
    static void conferirParada(Context c) {
        SharedPreferences sp = Avisos.prefs(c);
        if (!sp.getBoolean("notif", false)) return;
        JSONArray l = itens(c);
        if (l.length() == 0) return;
        long mais = Long.MAX_VALUE;
        String etq = "";
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o == null) continue;
            long q = o.optLong("quando", Long.MAX_VALUE);
            if (q < mais) { mais = q; etq = o.optString("etq"); }
        }
        if (System.currentTimeMillis() - mais < 24L * 3600 * 1000) return;
        if (etq.equals(sp.getString("filaAvisada", ""))) return;
        sp.edit().putString("filaAvisada", etq).apply();
        int n = l.length();
        Avisos.notificar(c, "fila:" + etq, n == 1 ? "1 gasto ainda não foi para a planilha" : n + " gastos ainda não foram para a planilha",
                "Estão no celular há mais de 24 horas. Abra o app com internet para enviar.", "Ver gastos", "fila");
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences sp = Avisos.prefs(c);
        String api = sp.getString("api", ""), id = sp.getString("id", ""), chave = sp.getString("chave", "");
        if (api.isEmpty() || id.isEmpty() || chave.isEmpty()) return Result.success();
        JSONArray l = itens(c);
        JSONObject feitos = mapa(c, "filaFeitos"), erros = mapa(c, "filaErros");
        JSONArray resto = new JSONArray();
        boolean deNovo = false;
        for (int i = 0; i < l.length(); i++) {
            JSONObject it = l.optJSONObject(i);
            if (it == null) continue;
            String etq = it.optString("etq");
            if (deNovo || erros.has(etq)) { resto.put(it); continue; }
            try {
                JSONObject corpo = new JSONObject();
                corpo.put("fn", it.optString("fn"));
                corpo.put("args", it.optJSONArray("args") == null ? new JSONArray() : it.optJSONArray("args"));
                corpo.put("id", id);
                corpo.put("chave", chave);
                corpo.put("etq", etq);
                corpo.put("v", "fila do celular");
                JSONObject r = Avisos.post(api, corpo.toString());
                if (r.optBoolean("ok", false)) {
                    JSONObject x = new JSONObject();
                    x.put("r", r.opt("r") == null ? JSONObject.NULL : r.opt("r"));
                    feitos.put(etq, x);
                } else if (r.optBoolean("ocupada", false)) {
                    resto.put(it); deNovo = true;                     // planilha ocupada: tenta de novo depois
                } else {
                    erros.put(etq, r.optString("erro", "a planilha recusou"));
                    resto.put(it);
                }
            } catch (Exception e) {
                resto.put(it); deNovo = true;                         // caiu a internet no meio: o resto espera
            }
        }
        // o app pode ter mudado a fila enquanto isto rodava: tira só o que foi enviado agora, sem sobrescrever o resto
        synchronized (Fila.class) {
            JSONArray atual = itens(c), nova = new JSONArray();
            for (int i = 0; i < atual.length(); i++) {
                JSONObject it = atual.optJSONObject(i);
                if (it != null && !feitos.has(it.optString("etq"))) nova.put(it);
            }
            resto = nova;
            sp.edit().putString("fila", resto.toString()).putString("filaFeitos", feitos.toString()).putString("filaErros", erros.toString()).commit();
        }
        boolean falta = false;
        for (int i = 0; i < resto.length(); i++) { JSONObject o = resto.optJSONObject(i); if (o != null && !erros.has(o.optString("etq"))) falta = true; }
        return deNovo && falta ? Result.retry() : Result.success();
    }
}
