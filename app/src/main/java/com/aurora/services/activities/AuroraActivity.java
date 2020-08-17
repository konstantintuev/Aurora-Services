package com.aurora.services.activities;

import android.Manifest;
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aurora);
        ButterKnife.bind(this);
        new Thread(() -> {
            adbWifi = new AdbWifi(this);
            if (adbWifi.exec("echo 'working'").contains("working")) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    hasAdbWifi = true;
                    init();
                });
            }
            adbWifi.terminate();
        }).start();
        init();
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

    private void init() {
        if (isSystemApp()) {
            textStatus.setText(getString(R.string.service_enabled));
            textStatus.setTextColor(getResources().getColor(R.color.colorGreen));
        } else {
            textStatus.setText(getString(R.string.service_disabled));
            textStatus.setTextColor(getResources().getColor(R.color.colorRed));
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

    private boolean isSystemApp() {
        return hasAdbWifi;
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
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
