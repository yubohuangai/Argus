// PATCH: collective restart
package com.googleresearch.capturesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
public class RestartAppBroadcastReceiver extends BroadcastReceiver {

    public static final String ACTION_RESTART_APP = "com.googleresearch.capturesync.ACTION_RESTART_APP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_RESTART_APP.equals(intent.getAction())) {
            Log.d("RestartAppReceiver", "Received restart signal. Launching MainActivity.");

            // Create an Intent to launch the main activity
            Intent launchIntent = new Intent(context, MainActivity.class);
            // These flags are crucial for starting a new task from a non-activity context
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launchIntent);
        }
    }
}