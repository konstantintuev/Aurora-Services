package com.aurora.services.manager;

import android.app.NotificationManager;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.aurora.services.utils.Log;
import com.google.gson.JsonObject;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public class ProfileManager {

    private Context context;
    private JsonObject profile;

    public ProfileManager(Context context, JsonObject profile) {
        this.context = context;
        this.profile = profile;
    }

    public boolean switchProfile() {
        try {
            switchWifi();
            switchBluetooth();
            switchNFC();
            switchSync();
            switchData();
            switchPowerSave();
            //switchSound();
            setBrightness();
            setTimeout();
            return true;
        } catch (Exception e) {
            Log.e(e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void switchWifi() {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null)
            wifiManager.setWifiEnabled(profile.get("wifi").getAsBoolean());
    }

    private void switchBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getApplicationContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            if (profile.get("bluetooth").getAsBoolean()) {
                bluetoothManager.getAdapter().enable();
            } else {
                bluetoothManager.getAdapter().disable();
            }
        }
    }

    private void switchSync() {
        ContentResolver.setMasterSyncAutomatically(profile.get("sync").getAsBoolean());
    }

    private void switchNFC() {
        boolean state = profile.get("nfc").getAsBoolean();
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(context);

        if (nfcAdapter == null) {
            // NFC is not supported
            Log.e("No NFC");
        } else {
            try {
                Class<?> NfcAdapterClass = Class.forName(nfcAdapter.getClass().getName());
                Method declaredMethod = NfcAdapterClass.getDeclaredMethod(state ? "enable" : "disable");
                declaredMethod.setAccessible(true);
                declaredMethod.invoke(nfcAdapter);
            } catch (Exception e) {
                Log.e("Failed to configure NFC");
            }
        }
    }

    private void switchSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final  NotificationManager notificationManager = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                if (profile.get("sound").getAsBoolean())
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                else
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
            }
        } else {
            final AudioManager audioManager = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                if (profile.get("sound").getAsBoolean())
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                else
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            }
        }
    }

    private void switchGPS() {
        //TODO : Find a way
    }

    private void switchPowerSave() {
        boolean state = profile.get("powersave").getAsBoolean();
        try {
            PowerManager powerManager = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);

            if (powerManager != null) {
                final Class<?> powerManagerClass = Class.forName(powerManager.getClass().getName());
                final Method setPowerSaveModeEnabled = powerManagerClass.getDeclaredMethod("setPowerSaveModeEnabled", boolean.class);
                setPowerSaveModeEnabled.invoke(powerManager, state);
            }
        } catch (Exception e) {
        }
    }

    private void switchData() {
        try {
            final TelephonyManager telephonyManager = (TelephonyManager) context.getApplicationContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                Method setDataEnabled = telephonyManager.getClass().getDeclaredMethod("setDataEnabled", boolean.class);
                setDataEnabled.invoke(telephonyManager, profile.get("data").getAsBoolean());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void setBrightness() {
        final ContentResolver contentResolver = context.getApplicationContext().getContentResolver();
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                profile.get("brightness").getAsInt());
    }

    private void setTimeout() {
        final ContentResolver contentResolver = context.getApplicationContext().getContentResolver();
        Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                TimeUnit.SECONDS.toMillis(profile.get("timeout").getAsInt()));
    }
}
