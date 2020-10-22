package com.aurora.services.manager;

import android.content.Context;

import com.aurora.services.Constants;
import com.aurora.services.model.item.HostItem;
import com.aurora.services.utils.PrefUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class TargetHostManager {

    private Context context;
    private Gson gson;

    private HostItem targetHost = new HostItem();

    public TargetHostManager(Context context) {
        this.context = context;
        this.gson = new Gson();
    }

    public void setTargetHost(String host, Integer port) {
        targetHost.host = host;
        targetHost.port = port;
        PrefUtil.putString(context, Constants.PREFERENCE_TARGET_HOST, gson.toJson(targetHost));
    }

    public HostItem getTargetHost() {
        HostItem hostItem = null;
        try {
            String raw = PrefUtil.getString(context, Constants.PREFERENCE_TARGET_HOST);
            Type type = new TypeToken<HostItem>() {
            }.getType();
            hostItem = gson.fromJson(raw, type);
        }catch(Exception e){

        }

        if (hostItem == null) {
            hostItem = new HostItem();
        }
        return hostItem;
    }
}
