package br.finansovr.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;

/**
 * Notificações e agenda das conferências: por volta da 0h (só o widget), das 12h (vencimentos) e das 18h (o resto).
 * O alarme acorda o AlarmeReceiver, que pede ao ConferirWorker para falar com a planilha quando houver internet.
 */
final class Avisos {
    static final String CANAL = "avisos";
    static final int[][] HORAS = {{0, 5}, {12, 0}, {18, 0}};

    private Avisos() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fz", Context.MODE_PRIVATE);
    }

    static void canal(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Avisos do Finansovr", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Vencimentos, limite da semana, importações, resumos e lembretes");
        nm.createNotificationChannel(ch);
    }

    /** Abre o app numa tela: painel, semana, venc, imp, pend, rev, resumo, lancar, lancar:Categoria, importar. */
    static PendingIntent abrir(Context c, String dest, int pedido) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("br.finansovr.app.ABRIR." + pedido);
        i.putExtra("dest", dest);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, pedido, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void notificar(Context c, String id, String tit, String txt, String acao, String dest) {
        canal(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;
        int nid = (id == null || id.isEmpty() ? tit : id).hashCode() & 0x3fffffff;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_stat_fz)
                .setColor(0xFF3AA7F0)
                .setContentTitle(tit)
                .setContentText(txt)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(txt))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(abrir(c, dest, nid));
        if (acao != null && !acao.isEmpty()) b.addAction(0, acao, abrir(c, dest, nid + 1));
        try {
            nm.notify(nid, b.build());
        } catch (SecurityException ignored) {}
    }

    /** Mostra só os avisos que ainda não apareceram. Na primeira conferência, o que é antigo (importação, assinatura, revisão) só é marcado. */
    static void mostrarNovos(Context c, JSONArray itens, boolean notif) {
        SharedPreferences sp = prefs(c);
        boolean semeado = sp.getBoolean("semeado", false);
        LinkedHashSet<String> env = new LinkedHashSet<>();
        try {
            JSONArray e = new JSONArray(sp.getString("enviados", "[]"));
            for (int i = 0; i < e.length(); i++) env.add(e.getString(i));
        } catch (Exception ignored) {}
        for (int i = 0; i < itens.length(); i++) {
            JSONObject n = itens.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            if (id.isEmpty() || env.contains(id)) continue;
            env.add(id);
            boolean antigo = !semeado && (id.startsWith("imp:") || id.startsWith("senha:") || id.startsWith("assin:") || id.startsWith("rev:"));
            if (notif && !antigo) notificar(c, id, n.optString("tit"), n.optString("txt"), n.optString("acao"), n.optString("dest", "painel"));
        }
        ArrayList<String> l = new ArrayList<>(env);
        while (l.size() > 300) l.remove(0);
        sp.edit().putString("enviados", new JSONArray(l).toString()).putBoolean("semeado", true).apply();
    }

    static void agendar(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int[] h : HORAS) {
            Calendar k = Calendar.getInstance();
            k.set(Calendar.HOUR_OF_DAY, h[0]);
            k.set(Calendar.MINUTE, h[1]);
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
            if (k.getTimeInMillis() <= System.currentTimeMillis() + 60000) k.add(Calendar.DAY_OF_MONTH, 1);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, k.getTimeInMillis(), alarme(c, h[0]));
        }
    }

    static PendingIntent alarme(Context c, int hora) {
        Intent i = new Intent(c, AlarmeReceiver.class).setAction("br.finansovr.app.CONFERIR").putExtra("hora", hora);
        return PendingIntent.getBroadcast(c, 100 + hora, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Chamada à API do Apps Script (o mesmo doPost que o app usa), seguindo o redirecionamento do Google. */
    static JSONObject post(String api, String corpo) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(api).openConnection();
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout(20000);
        con.setReadTimeout(90000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
        try (OutputStream o = con.getOutputStream()) {
            o.write(corpo.getBytes(StandardCharsets.UTF_8));
        }
        int st = con.getResponseCode();
        String loc = con.getHeaderField("Location");
        if (st >= 300 && st < 400 && loc != null) {
            con.disconnect();
            con = (HttpURLConnection) new URL(loc).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(90000);
        }
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            con.disconnect();
        }
    }
}
