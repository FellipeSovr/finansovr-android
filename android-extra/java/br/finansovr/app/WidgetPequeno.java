package br.finansovr.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Widget pequeno (2×2): o dia de hoje. */
public class WidgetPequeno extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Widgets.atualizar(c);
    }
}
