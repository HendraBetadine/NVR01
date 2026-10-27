package id.nvr.stb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Menyalakan perekaman otomatis setelah box menyala (mis. setelah mati listrik). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (new Config(ctx).isEnabled()) {
            RecorderService.start(ctx);
        }
    }
}
