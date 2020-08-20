package com.aurora.services.activities;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.aurora.services.PrivilegedService;
import com.aurora.services.R;
import com.aurora.services.sheet.LogSheet;
import com.aurora.services.sheet.WhitelistSheet;
import com.aurora.services.utils.AdbWifi;
import io.reactivex.disposables.CompositeDisposable;

public class AuroraActivity extends AppCompatActivity {

    @BindView(R.id.txt_permission)
    TextView textPermission;
    @BindView(R.id.txt_status)
    TextView textStatus;

    private CompositeDisposable disposable = new CompositeDisposable();

    private static AdbWifi adbWifi;

    boolean hasAdbWifi = false;

    boolean loadingAdbWifi = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aurora);
        ButterKnife.bind(this);
        init();
        if (isPermissionGranted()) {
            tryInitAdbWifi();
        }
    }

    void tryInitAdbWifi() {
        new Thread(() -> {
            try {
                adbWifi = new AdbWifi(this);
                String res = adbWifi.exec("echo 'working'");
                if (res != null && res.contains("working")) {
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
            } finally {
                adbWifi.terminate();
            }
        }).start();
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
        if (!isAdbWifiLoading() && !isAdbWifiGranted()) {
            tryInitAdbWifi();
        }
    }

    @OnClick(R.id.card_health)
    public void requestPermission() {
        if (!isPermissionGranted()) {
            askPermissions();
        }
    }

    private void init() {
        if (!isPermissionGranted()) {
            textStatus.setText(getString(R.string.service_not_available));
            textStatus.setTextColor(getResources().getColor(R.color.colorRed));
        } else if (isAdbWifiLoading()) {
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

        if (isPermissionGranted()) {
            textPermission.setText(getString(R.string.perm_granted));
            textPermission.setTextColor(getResources().getColor(R.color.colorGreen));
        } else {
            textPermission.setText(getString(R.string.perm_not_granted));
            textPermission.setTextColor(getResources().getColor(R.color.colorRed));
            askPermissions();
        }
    }

    private boolean isAdbWifiGranted() {
        return hasAdbWifi;
    }

    private boolean isAdbWifiLoading() {
        return loadingAdbWifi;
    }

    private boolean isPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                1337);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case 1337: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    init();
                    tryInitAdbWifi();
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
