package it.unipg.gearlab;

import android.app.Application;
import android.content.Context;

public class MyApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        com.cySdkyc.clx.Helper.install(this);
    }
}
