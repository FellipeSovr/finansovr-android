package br.finansovr.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget médio (4×2): pode gastar hoje, semana, livre no mês e três botões de lançamento rápido. */
public class WidgetMedio extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Widgets.atualizar(c);
    }
}
