package io.github.yugosandboxcoder.lockmemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** 更新のインストール結果を Android から受け取る */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // この端末では利用者の確認が必要
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                Updater.onNeedsUserAction(context, confirm);
            } else {
                Updater.onInstallFailed(context, null);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.onInstalled(context);
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Updater.onInstallFailed(context, "取り消されました");
        } else {
            Updater.onInstallFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
