package id.nvr.stb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Menyalakan perekaman otomatis setelah box menyala (mis. setelah mati listrik). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        RecorderService.start(ctx);   // nyala jika rekaman atau web server diaktifkan

        // Seperti NVR: langsung tampilkan live view di TV setelah menyala (jika diaktifkan).
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (boot && new Config(ctx).isLiveOnBoot()) {
            Intent live = new Intent(ctx, LiveActivity.class);
            live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(live);
        }
    }
}
