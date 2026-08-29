package com.v2ray.ang.bazshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Keeps Bazcode VPN sharing alive after boot/network/hotspot changes. */
public final class ShareReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        ShareService.ensureRunning(context);
    }
}
