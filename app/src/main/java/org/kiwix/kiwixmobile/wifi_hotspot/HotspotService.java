package org.kiwix.kiwixmobile.wifi_hotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import org.kiwix.kiwixmobile.KiwixApplication;
import org.kiwix.kiwixmobile.R;
import org.kiwix.kiwixmobile.utils.Constants;
import org.kiwix.kiwixmobile.webserver.ZimHostCallbacks;
import org.kiwix.kiwixmobile.webserver.WebServerHelper;
import org.kiwix.kiwixmobile.webserver.ZimHostActivity;

import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.ACTION_IS_HOTSPOT_ENABLED;
import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.ACTION_START_SERVER;
import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.ACTION_STOP_SERVER;
import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.ACTION_TURN_OFF_AFTER_O;
import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.ACTION_TURN_ON_AFTER_O;
import static org.kiwix.kiwixmobile.webserver.ZimHostActivity.SELECTED_ZIM_PATHS_KEY;

/**
 * HotspotService is used to add a foreground service for the wifi hotspot.
 * Created by Adeel Zafar on 07/01/2019.
 */

public class HotspotService extends Service {
  private static final int HOTSPOT_NOTIFICATION_ID = 666;
  private static final String ACTION_STOP = "hotspot_stop";
  private static final String TAG = "HotspotService";
  private WifiHotspotManager hotspotManager;
  private BroadcastReceiver stopReceiver;
  private NotificationManager notificationManager;
  private NotificationCompat.Builder builder;
  private ZimHostCallbacks zimHostCallbacks;
  private final IBinder serviceBinder = new HotspotBinder();
  private WebServerHelper webServerHelper;

  @Override public void onCreate() {
    KiwixApplication.getComponent().serviceComponent.service(this).build().inject(this
    super.onCreate();

    hotspotManager = new WifiHotspotManager(this);

    stopReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getAction().equals(ACTION_STOP)) {
          stopHotspotAndDismissNotification();
        }
      }
    };

    registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));

    webServerHelper = new WebServerHelper();

    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
  }

  @Override public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
    switch (intent.getAction()) {

      case ACTION_IS_HOTSPOT_ENABLED:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          zimHostCallbacks.onHotspotStateReceived(hotspotManager.checkHotspotState());
        }
        break;

      case ACTION_TURN_ON_AFTER_O:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          hotspotManager.turnOnHotspot(zimHostCallbacks);
        }
        break;

      case ACTION_TURN_OFF_AFTER_O:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          stopHotspotAndDismissNotification();
        }
        break;

      case ACTION_START_SERVER:
        if (!webServerHelper.startServerHelper(
            intent.getStringArrayListExtra(SELECTED_ZIM_PATHS_KEY))) {
          zimHostCallbacks.onServerFailedToStart();
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
            stopSelf();
            notificationManager.cancel(HOTSPOT_NOTIFICATION_ID);
          }
        } else {
          zimHostCallbacks.onServerStarted(webServerHelper.getCompleteAddress());
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startForegroundNotificationHelper();
          }
          Toast.makeText(this, R.string.server_started__successfully_toast_message,
              Toast.LENGTH_SHORT).show();
        }

        break;

      case ACTION_STOP_SERVER:
        stopHotspotAndDismissNotification();
        break;
      default:
        break;
    }
    return START_NOT_STICKY;
  }

  @Nullable @Override public IBinder onBind(@Nullable Intent intent) {
    return serviceBinder;
  }

  private Notification buildForegroundNotification(String status) {
    Log.v(TAG, "Building notification " + status);
    builder = new NotificationCompat.Builder(this);
    builder.setContentTitle(getString(R.string.hotspot_notification_content_title))
        .setContentText(status);
    Intent targetIntent = new Intent(this, ZimHostActivity.class);
    targetIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent contentIntent =
        PendingIntent.getActivity(this, 0, targetIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    builder.setContentIntent(contentIntent)
        .setSmallIcon(R.mipmap.kiwix_icon)
        .setWhen(System.currentTimeMillis());

    hotspotNotificationChannel();

    Intent stopIntent = new Intent(ACTION_STOP);
    PendingIntent stopHotspot =
        PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    builder.addAction(R.drawable.ic_close_white_24dp, getString(R.string.stop_hotspot_button),
        stopHotspot);

    return (builder.build());
  }

  //Dismiss notification and turn off hotspot for devices>=O
  void stopHotspotAndDismissNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      hotspotManager.turnOffHotspot();
    }
    webServerHelper.stopAndroidWebServer();
    zimHostCallbacks.onServerStopped();
    stopForeground(true);
    stopSelf();
    notificationManager.cancel(HOTSPOT_NOTIFICATION_ID);
  }

  @Override
  public void onDestroy() {
    if (stopReceiver != null) {
      unregisterReceiver(stopReceiver);
    }
    super.onDestroy();
  }

  private void hotspotNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel hotspotServiceChannel = new NotificationChannel(
          Constants.HOTSPOT_SERVICE_CHANNEL_ID, getString(R.string.hotspot_service_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT);
      hotspotServiceChannel.setDescription(getString(R.string.hotspot_channel_description));
      hotspotServiceChannel.setSound(null, null);
      builder.setChannelId(Constants.HOTSPOT_SERVICE_CHANNEL_ID);
      notificationManager.createNotificationChannel(hotspotServiceChannel);
    }
  }

  public void registerCallBack(@Nullable ZimHostCallbacks myCallback) {
    zimHostCallbacks = myCallback;
  }

  public void startForegroundNotificationHelper() {
    startForeground(HOTSPOT_NOTIFICATION_ID,
        buildForegroundNotification(getString(R.string.hotspot_running)));
  }

  public class HotspotBinder extends Binder {

    @NonNull public HotspotService getService() {
      return HotspotService.this;
    }
  }
}
