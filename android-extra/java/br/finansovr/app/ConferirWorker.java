package br.finansovr.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conferência em segundo plano: pede à planilha os avisos (12h e 18h) e os números do widget (18h e 0h). */
public class ConferirWorker extends Worker {
    public ConferirWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences sp = Avisos.prefs(c);
        String api = sp.getString("api", ""), id = sp.getString("id", ""), chave = sp.getString("chave", "");
        if (api.isEmpty() || id.isEmpty() || chave.isEmpty()) return Result.success();
        int hora = getInputData().getInt("hora", 18);
        boolean notif = sp.getBoolean("notif", false), lembrete = sp.getBoolean("lembrete", false);
        try {
            JSONObject corpo = new JSONObject();
            corpo.put("id", id);
            corpo.put("chave", chave);
            JSONArray args = new JSONArray();
            if (hora == 0) {
                corpo.put("fn", "getWidget");
            } else {
                corpo.put("fn", "getNotificacoes");
                args.put(String.valueOf(hora));
                JSONObject o = new JSONObject();
                o.put("lembrete", lembrete);
                args.put(o);
            }
            corpo.put("args", args);
            JSONObject r = Avisos.post(api, corpo.toString());
            if (!r.optBoolean("ok", false)) return Result.success();
            JSONObject x = r.optJSONObject("r");
            if (x == null) return Result.success();
            if (hora == 0) {
                Widgets.salvarNumeros(c, x);
            } else {
                JSONObject w = x.optJSONObject("widget");
                if (w != null) Widgets.salvarNumeros(c, w);
                JSONArray itens = x.optJSONArray("itens");
                if (itens != null) Avisos.mostrarNovos(c, itens, notif);
            }
            return Result.success();
        } catch (Exception e) {
            return getRunAttemptCount() < 2 ? Result.retry() : Result.success();
        }
    }
}
