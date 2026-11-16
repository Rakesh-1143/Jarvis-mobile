package com.growthos.jarvis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Opens another app from the background. Android blocks background apps from starting
 * activities, so when KALKI is the default assistant this goes through the assistant session
 * (an Android-approved path); otherwise it tries directly, which Android may block.
 */
final class AppOpener {
    static final String EXTRA_TARGET = "jarvis_target";

    static void open(Context c, Intent target) {
        // RESET_TASK_IF_NEEDED only for "open app": for a search or link it would just bring the app's
        // old screen to the front and drop the search.
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | (Intent.ACTION_MAIN.equals(target.getAction()) ? Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED : 0));
        JarvisVoiceInteractionService vis = JarvisVoiceInteractionService.instance;
        if (vis != null) {
            Bundle b = new Bundle();
            b.putParcelable(EXTRA_TARGET, target);
            try { vis.showSession(b, 0); return; } catch (Exception ignored) { }
        }
        try { c.startActivity(new Intent(c, LaunchActivity.class).putExtra(EXTRA_TARGET, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }
}
