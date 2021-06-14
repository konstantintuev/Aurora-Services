package com.aurora.services.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.aurora.services.PrivilegedService;
import com.aurora.services.R;
import com.aurora.services.dialog.TargetHostConfigDialog;
import com.aurora.services.manager.TargetHostManager;
import com.aurora.services.model.item.HostItem;
import com.aurora.services.sheet.LogSheet;
import com.aurora.services.sheet.WhitelistSheet;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;
import io.reactivex.disposables.CompositeDisposable;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

public class AuroraActivity extends AppCompatActivity {

    @BindView(R.id.txt_permission)
    TextView textPermission;
    @BindView(R.id.txt_status)
    TextView textStatus;

    private CompositeDisposable disposable = new CompositeDisposable();

    private static AdbConnection connection = null;

    boolean hasAdbWifi = false;

    boolean loadingAdbWifi = true;
    private Thread adbWifiThread;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aurora);
        ButterKnife.bind(this);
        init();
        tryInitAdbWifi();
    }

    void tryInitAdbWifi() {
        adbWifiThread = new Thread(() -> {
            try {
                HostItem hostItem = new TargetHostManager(this).getTargetHost();
                Socket socket = new Socket(hostItem.host, hostItem.port);

                File privateKey = new File(getFilesDir() + "/adbkey");
                File publicKey = new File(getFilesDir() + "/adbkey.pub");
                if (!privateKey.exists()) {
                    AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP));
                    crypto.saveAdbKeyPair(privateKey, publicKey);
                }
                AdbCrypto crypto = AdbCrypto.loadAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP),
                        privateKey,
                        publicKey);

                connection = AdbConnection.create(socket, crypto);
                connection.connect();

                AdbStream stream = connection.open("exec:"+ "echo 'working'");
                String out = new String(stream.read()).trim();
                Log.d("ADB", out);
                stream.close();
                if (out.equals("working")) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        hasAdbWifi = true;
                        loadingAdbWifi = false;
                        startService(new Intent(this, PrivilegedService.class));
                        init();
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        hasAdbWifi = false;
                        loadingAdbWifi = false;
                        init();
                    });
                }
            } catch (Throwable th) {
                th.printStackTrace();
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        adbWifiThread.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        disposable.dispose();
        super.onDestroy();
    }

    @OnClick(R.id.card_whitelist)
    public void showWhitelistSheet() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(WhitelistSheet.TAG) == null) {
            final WhitelistSheet sheet = new WhitelistSheet();
            sheet.show(fragmentManager, WhitelistSheet.TAG);
        }
    }

    @OnClick(R.id.card_log)
    public void showLogSheet() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(LogSheet.TAG) == null) {
            final LogSheet sheet = new LogSheet();
            sheet.show(getSupportFragmentManager(), "");
        }
    }

    @OnClick(R.id.card_status)
    public void requestService() {
        wifiInit();
    }

    @OnClick(R.id.service_config)
    public void openConfigDialog() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(TargetHostConfigDialog.TAG) == null) {
            TargetHostConfigDialog dialog = new TargetHostConfigDialog(getApplicationContext());
            dialog.show(fragmentManager, TargetHostConfigDialog.TAG);
        }
    }

    @OnClick(R.id.card_health)
    public void requestPermission() {
    }

    public void wifiInit(){
        loadingAdbWifi = true;
        hasAdbWifi = false;
        if(adbWifiThread != null){
            adbWifiThread.interrupt();
        }
        init();
        tryInitAdbWifi();
    }

    private void init() {
        if (isAdbWifiLoading()) {
            textStatus.setText(getString(R.string.service_loading));
            textStatus.setTextColor(getResources().getColor(R.color.colorYellow));
        } else {
            if (isAdbWifiGranted()) {
                textStatus.setText(getString(R.string.service_enabled));
                textStatus.setTextColor(getResources().getColor(R.color.colorGreen));
            } else {
                textStatus.setText(getString(R.string.service_disabled));
                textStatus.setTextColor(getResources().getColor(R.color.colorRed));
            }
        }

        textPermission.setText(getString(R.string.perm_granted));
        textPermission.setTextColor(getResources().getColor(R.color.colorGreen));
    }

    private boolean isAdbWifiGranted() {
        return hasAdbWifi;
    }

    private boolean isAdbWifiLoading() {
        return loadingAdbWifi;
    }
}
