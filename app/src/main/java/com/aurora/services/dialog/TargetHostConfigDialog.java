package com.aurora.services.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.aurora.services.R;
import com.aurora.services.activities.AuroraActivity;
import com.aurora.services.manager.TargetHostManager;
import com.aurora.services.model.item.HostItem;

public class TargetHostConfigDialog extends DialogFragment {
    public static final String TAG = "HOST_CONFIG_DIALOG";

    public TargetHostManager targetHostManager;

    private AsyncTask callback = null;

    public TargetHostConfigDialog(Context context){
        targetHostManager = new TargetHostManager(context);
        callback = null;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();

        HostItem hostItem = targetHostManager.getTargetHost();

        final View customLayout = inflater.inflate(R.layout.targethost_config, null);
        EditText hostEditText = (EditText)customLayout.findViewById(R.id.host);
        hostEditText.setText(hostItem.host, TextView.BufferType.EDITABLE);
        EditText portEditText = (EditText)customLayout.findViewById(R.id.port);
        portEditText.setText(String.valueOf(hostItem.port), TextView.BufferType.EDITABLE);
        builder.setView(customLayout)
                .setTitle("Target Host")
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String host = ((EditText) customLayout.findViewById(R.id.host)).getText().toString();
                        Integer port = Integer.parseInt(((EditText) customLayout.findViewById(R.id.port)).getText().toString());
                        targetHostManager.setTargetHost(host, port);
                        AuroraActivity callingActivity = (AuroraActivity) getActivity();
                        callingActivity.wifiInit();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                    }
                });
        AlertDialog dialog = builder.create();
        return dialog;
    }
}
