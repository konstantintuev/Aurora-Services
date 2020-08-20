/*
 * Aurora Droid
 * Copyright (C) 2019, Rahul Kumar Patel <whyorean@gmail.com>
 *
 * Aurora Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Aurora Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Aurora Droid.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.aurora.services.utils;

import android.content.Context;
import android.text.TextUtils;
import net.grandcentrix.tray.AppPreferences;
import org.json.JSONObject;

import java.util.*;

public class PrefUtil {

    public static void putString(Context context, String key, String value) {
        Util.getPrefs(context.getApplicationContext()).put(key, value);
    }

    public static void putInteger(Context context, String key, int value) {
        Util.getPrefs(context.getApplicationContext()).put(key, value);
    }

    public static void putFloat(Context context, String key, float value) {
        Util.getPrefs(context.getApplicationContext()).put(key, value);
    }

    public static void putBoolean(Context context, String key, boolean value) {
        Util.getPrefs(context.getApplicationContext()).put(key, value);
    }

    public static void putListString(Context context, String key, ArrayList<String> stringList) {
        String[] myStringList = stringList.toArray(new String[stringList.size()]);
        Util.getPrefs(context.getApplicationContext()).put(key, TextUtils.join("‚‗‚", myStringList));
    }


    public static String getString(Context context, String key) {
        return Util.getPrefs(context.getApplicationContext()).getString(key, "");
    }

    public static int getInteger(Context context, String key) {
        return Util.getPrefs(context.getApplicationContext()).getInt(key, 0);
    }

    public static float getFloat(Context context, String key) {
        return Util.getPrefs(context.getApplicationContext()).getFloat(key, 0.0f);
    }

    public static Boolean getBoolean(Context context, String key) {
        return Util.getPrefs(context.getApplicationContext()).getBoolean(key, false);
    }

    public static ArrayList<String> getListString(Context context, String key) {
        return new ArrayList<String>(Arrays.asList(TextUtils.split(
                Util.getPrefs(context.getApplicationContext()).getString(key, ""), "‚‗‚")));
    }

    public static void saveMap(Context context, Map<String, String> map, String key) {
        AppPreferences mPreferences = Util.getPrefs(context);
        if (mPreferences != null) {
            JSONObject jsonObject = new JSONObject(map);
            String jsonString = jsonObject.toString();
            mPreferences.remove(key);
            mPreferences.put(key, jsonString);
        }
    }

    public static Map<String, String> getMap(Context context, String key) {
        Map<String, String> outputMap = new HashMap<>();
        AppPreferences mPreferences = Util.getPrefs(context);
        try {
            if (mPreferences != null) {
                String jsonString = mPreferences.getString(key, (new JSONObject()).toString());
                JSONObject jsonObject = new JSONObject(jsonString);
                Iterator<String> keysItr = jsonObject.keys();
                while (keysItr.hasNext()) {
                    String k = keysItr.next();
                    String value = (String) jsonObject.get(k);
                    outputMap.put(k, value);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return outputMap;
    }
}
