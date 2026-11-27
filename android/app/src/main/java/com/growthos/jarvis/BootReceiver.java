package com.growthos.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an app update, turns listening back on if the user had it on. Starts once; no retry loop. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (WakeWordService.isEnabled(c) && !WakeWordService.running) WakeWordService.start(c, null);
    }
}
