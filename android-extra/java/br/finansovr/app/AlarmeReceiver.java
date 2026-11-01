package br.finansovr.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Acordado por volta da 0h, 12h e 18h (e ao ligar o celular, para refazer a agenda). */
public class AlarmeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int hora = i.getIntExtra("hora", -1);
        if (hora >= 0) {
            Data d = new Data.Builder().putInt("hora", hora).build();
            OneTimeWorkRequest w = new OneTimeWorkRequest.Builder(ConferirWorker.class)
                    .setInputData(d)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            WorkManager.getInstance(c).enqueueUniqueWork("conferir" + hora, ExistingWorkPolicy.REPLACE, w);
        }
        try { Fila.conferirParada(c); } catch (Exception ignored) {}   // v7.2.2: gasto parado há mais de 24 h
        Avisos.agendar(c);
    }
}
