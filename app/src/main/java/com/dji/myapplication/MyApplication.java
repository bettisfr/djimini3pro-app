package com.dji.myapplication;

import android.app.Application;
import android.content.Context;

import com.secneo.sdk.Helper;

public class MyApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Helper.install(this);
    }
}
