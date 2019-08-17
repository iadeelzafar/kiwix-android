package org.kiwix.kiwixmobile.webserver;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.tasks.Task;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.kiwix.kiwixmobile.R;
import org.kiwix.kiwixmobile.base.BaseActivity;
import org.kiwix.kiwixmobile.utils.AlertDialogShower;
import org.kiwix.kiwixmobile.utils.KiwixDialog;
import org.kiwix.kiwixmobile.utils.NetworkUtils;
import org.kiwix.kiwixmobile.wifi_hotspot.HotspotService;
import org.kiwix.kiwixmobile.zim_manager.fileselect_view.SelectionMode;
import org.kiwix.kiwixmobile.zim_manager.fileselect_view.adapter.BookOnDiskDelegate;
import org.kiwix.kiwixmobile.zim_manager.fileselect_view.adapter.BooksOnDiskAdapter;
import org.kiwix.kiwixmobile.zim_manager.fileselect_view.adapter.BooksOnDiskListItem;

import static org.kiwix.kiwixmobile.utils.StyleUtils.dialogStyle;
import static org.kiwix.kiwixmobile.webserver.WebServerHelper.getAddress;
import static org.kiwix.kiwixmobile.webserver.WebServerHelper.isServerStarted;

public class ZimHostActivity extends BaseActivity implements
    ZimHostCallbacks, ZimHostContract.View {

  @BindView(R.id.startServerButton)
  Button startServerButton;
  @BindView(R.id.server_textView)
  TextView serverTextView;
  @BindView(R.id.recycler_view_zim_host)
  RecyclerView recyclerViewZimHost;

  @Inject
  ZimHostContract.Presenter presenter;

  @Inject AlertDialogShower alertDialogShower;

  public static final String ACTION_TURN_ON_AFTER_O = "Turn_on_hotspot_after_oreo";
  public static final String ACTION_TURN_OFF_AFTER_O = "Turn_off_hotspot_after_oreo";
  public static final String ACTION_IS_HOTSPOT_ENABLED = "Is_hotspot_enabled";
  public static final String ACTION_START_SERVER = "start_server";
  public static final String ACTION_STOP_SERVER = "stop_server";
  public static final String SELECTED_ZIM_PATHS_KEY = "selected_zim_paths";
  private static final String IP_STATE_KEY = "ip_state_key";
  private static final String TAG = "ZimHostActivity";
  private static final int MY_PERMISSIONS_ACCESS_FINE_LOCATION = 102;
  private static final int LOCATION_SETTINGS_PERMISSION_RESULT = 101;
  private Intent serviceIntent;
  private Task<LocationSettingsResponse> task;

  private BooksOnDiskAdapter booksAdapter;
  HotspotService hotspotService;
  private String ip;
  boolean bound;
  private ServiceConnection serviceConnection;
  private ArrayList<String> selectedBooksPath = new ArrayList<>();

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_zim_host);

    setUpToolbar();

     final BroadcastReceiver mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {

          // get Wi-Fi Hotspot state here
          int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 0);

          if (WifiManager.WIFI_STATE_ENABLED == state % 10) {
            Log.v("DANG","Intent filter : Hotspot enabled");
          }
          else
          {
            Log.v("DANG","Intent filter : Hotspot disabled");
          }

        }
      }
    };

    IntentFilter mIntentFilter = new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED");
    registerReceiver(mReceiver, mIntentFilter);

    if (savedInstanceState != null) {
      ip = savedInstanceState.getString(IP_STATE_KEY);
      layoutServerStarted();
    }
    BookOnDiskDelegate.BookDelegate bookDelegate =
        new BookOnDiskDelegate.BookDelegate(sharedPreferenceUtil,
            null,
            null,
            bookOnDiskItem -> {
              select(bookOnDiskItem);
              return Unit.INSTANCE;
            });
    bookDelegate.setSelectionMode(SelectionMode.MULTI);
    booksAdapter = new BooksOnDiskAdapter(bookDelegate,
        BookOnDiskDelegate.LanguageDelegate.INSTANCE
    );

    presenter.attachView(this);

    presenter.loadBooks();
    recyclerViewZimHost.setAdapter(booksAdapter);

    serviceConnection = new ServiceConnection() {

      @Override
      public void onServiceConnected(ComponentName className, IBinder service) {
        HotspotService.HotspotBinder binder = (HotspotService.HotspotBinder) service;
        hotspotService = binder.getService();
        bound = true;
        hotspotService.registerCallBack(ZimHostActivity.this);
      }

      @Override
      public void onServiceDisconnected(ComponentName arg0) {
        bound = false;
      }
    };

    serviceIntent = new Intent(this, HotspotService.class);

    startServerButton.setOnClickListener(v -> {
      //Get the path of ZIMs user has selected
      if (!isServerStarted) {
        getSelectedBooksPath();
        if (selectedBooksPath.size() > 0) {
          startHotspotHelper();
        } else {
          Toast.makeText(ZimHostActivity.this, R.string.no_books_selected_toast_message,
              Toast.LENGTH_SHORT).show();
        }
      } else {
        startHotspotHelper();
      }
    });
  }

  private void startHotspotHelper() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      toggleHotspot();
    } else {
      //TO DO: show Dialog() + within that add check mobile Data check later.
      //if (isMobileDataEnabled(context)) {
      //  mobileDataDialog();
      //} else {
      if (isServerStarted) {
        startService(ACTION_STOP_SERVER);
      } else {
        startHotspotManuallyDialog();
      }
      //}
    }
  }

  void layoutServerStarted() {
    serverTextView.setText(
        getString(R.string.server_started_message, ip));
    startServerButton.setText(getString(R.string.stop_server_label));
    startServerButton.setBackgroundColor(getResources().getColor(R.color.stopServer));
  }

  void layoutServerStopped() {
    serverTextView.setText(getString(R.string.server_textview_default_message));
    startServerButton.setText(getString(R.string.start_server_label));
    startServerButton.setBackgroundColor(getResources().getColor(R.color.greenTick));
  }

  private void getSelectedBooksPath() {
    BooksOnDiskListItem.BookOnDisk bookOnDisk;

    for (BooksOnDiskListItem item : booksAdapter.getItems()) {
      if (item.isSelected()) {
        bookOnDisk = (BooksOnDiskListItem.BookOnDisk) item;
        File file = bookOnDisk.getFile();
        selectedBooksPath.add(file.getAbsolutePath());
        Log.v(TAG, "ZIM PATH : " + file.getAbsolutePath());
      }
    }
  }

  private void select(@NonNull BooksOnDiskListItem.BookOnDisk bookOnDisk) {
    ArrayList<BooksOnDiskListItem> booksList = new ArrayList<>();
    for (BooksOnDiskListItem item : booksAdapter.getItems()) {
      if (item.equals(bookOnDisk)) {
        if (item.isSelected()) {
          item.setSelected(false);
        } else {
          item.setSelected(true);
        }
      }
      booksList.add(item);
    }
    booksAdapter.setItems(booksList);
  }

  @Override protected void onStart() {
    super.onStart();
    bindService();
  }

  @Override protected void onStop() {
    super.onStop();
    unbindService();
  }

  private void bindService() {

    bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    bound = true;
  }

  private void unbindService() {
    if (bound && !isServerStarted) {
      hotspotService.registerCallBack(null); // unregister
      unbindService(serviceConnection);
      bound = false;
    }
  }

  private void toggleHotspot() {
    //Check if location permissions are granted
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED) {

      startService(ACTION_IS_HOTSPOT_ENABLED); //If hotspot is already enabled, turn it off
    } else {
      //Ask location permission if not granted
      ActivityCompat.requestPermissions(this,
          new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
          MY_PERMISSIONS_ACCESS_FINE_LOCATION);
    }
  }

  @SuppressLint("PrivateApi") @Override protected void onResume() {
    super.onResume();
    presenter.loadBooks();

    WifiManager wifiManager =
        (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    Method method = null;
    try {
      method = wifiManager.getClass().getDeclaredMethod("getWifiApState");
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    }
    method.setAccessible(true);
    try {
      int actualState = (Integer) method.invoke(wifiManager, (Object[]) null);
      if(actualState ==13)
        Log.v("DANG","Reflection hotspot enabled");
      else if(actualState==11)
        Log.v("DANG","Reflection hotspot disabled");
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    } catch (InvocationTargetException e) {
      e.printStackTrace();
    }

    ip = getAddress();
    ip = ip.replaceAll("\n", "");

    //If wifi is enabled
    if (NetworkUtils.isWiFi(this)) {
      Log.v("DANG", "1");
      layoutServerStopped();
      isServerStarted = false;
      if(hotspotService!=null)
      {
        Log.v("DANG","1.5");
        hotspotService.stopWebServerHelper();
      }
    }

    //Check if hotspot is enabled
    //If var has ip address apart from port no. and other chars
    else if (ip.length() > 13) {
      Log.v("DANG", "2.1");
      if (isServerStarted) {
        Log.v("DANG", "2.2");
        layoutServerStarted();
      }
    }
    //Neither hotspot nor WIFI is enabled
    else {
      Log.v("DANG", "3");
      layoutServerStopped();
      isServerStarted = false;
      if(hotspotService!=null)
      {
        hotspotService.stopWebServerHelper();
      }
    }


  }

  // This method checks if mobile data is enabled in user's device.
  static boolean isMobileDataEnabled(Context context) {
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    try {
      Class cmClass = Class.forName(cm.getClass().getName());
      Method method = cmClass.getDeclaredMethod("getMobileDataEnabled");
      method.setAccessible(true);
      return (Boolean) method.invoke(cm);
    } catch (Exception e) {
      Log.e(TAG, e.toString());
    }
    return false;
  }

  //This method sends the user to data usage summary settings activity
  private void openMobileDataActivity() {
    startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
  }

  @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
      @NonNull int[] grantResults) {
    if (requestCode == MY_PERMISSIONS_ACCESS_FINE_LOCATION) {
      if (grantResults.length > 0
          && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          toggleHotspot();
        }
      }
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    //Checking the result code for LocationSettings resolution
    if (requestCode == LOCATION_SETTINGS_PERMISSION_RESULT) {
      final LocationSettingsStates states = LocationSettingsStates.fromIntent(data);
      switch (resultCode) {
        case Activity.RESULT_OK:
          // All required changes were successfully made
          Log.v(TAG, states.isLocationPresent() + "");
          startService(ACTION_TURN_ON_AFTER_O);
          break;
        case Activity.RESULT_CANCELED:
          // The user was asked to change settings, but chose not to
          Log.v(TAG, "Canceled");
          break;
        default:
          break;
      }
    }
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    presenter.detachView();
  }

  private void setUpToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setTitle(getString(R.string.menu_host_books));
    getSupportActionBar().setHomeButtonEnabled(true);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    toolbar.setNavigationOnClickListener(v -> onBackPressed());
  }

  private void setupLocationServices() {
    LocationRequest locationRequest = new LocationRequest();
    locationRequest.setInterval(10);
    locationRequest.setSmallestDisplacement(10);
    locationRequest.setFastestInterval(10);
    locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    LocationSettingsRequest.Builder builder = new
        LocationSettingsRequest.Builder();
    builder.addLocationRequest(locationRequest);

    task = LocationServices.getSettingsClient(this).checkLocationSettings(builder.build());

    locationSettingsResponseBuilder();
  }

  private void locationSettingsResponseBuilder() {
    task.addOnCompleteListener(task -> {
      try {
        LocationSettingsResponse response = task.getResult(ApiException.class);
        // All location settings are satisfied. The client can initialize location
        // requests here.

        startService(ACTION_TURN_ON_AFTER_O);

        //}
      } catch (ApiException exception) {
        switch (exception.getStatusCode()) {
          case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
            // Location settings are not satisfied. But could be fixed by showing the
            // user a dialog.
            try {
              // Cast to a resolvable exception.
              ResolvableApiException resolvable = (ResolvableApiException) exception;
              // Show the dialog by calling startResolutionForResult(),
              // and check the result in onActivityResult().
              resolvable.startResolutionForResult(
                  ZimHostActivity.this,
                  LOCATION_SETTINGS_PERMISSION_RESULT);
            } catch (IntentSender.SendIntentException e) {
              // Ignore the error.
            } catch (ClassCastException e) {
              // Ignore, should be an impossible error.
            }
            break;
          case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
            // Location settings are not satisfied. However, we have no way to fix the
            // settings so we won't show the dialog.
            break;
          default:
            break;
        }
      }
    });
  }

  //Advice user to turn on hotspot manually for API<26
  private void startHotspotManuallyDialog() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this, dialogStyle());

    builder.setPositiveButton(getString(R.string.go_to_settings_label),
        (dialog, id) -> launchTetheringSettingsScreen());

    builder.setNeutralButton(getString(R.string.hotspot_dialog_neutral_button), (dialog, id) -> {
      //TO DO: START SERVER WITHIN THE SERVICE.
      //Adding a handler because sometimes hotspot can take time to turn on.
      //TO DO: Add a progress dialog instead of handler
      ProgressDialog progressDialog =
          ProgressDialog.show(this, getString(R.string.progress_dialog_starting_server), "",
              true);
      progressDialog.show();
      final Handler handler = new Handler();
      handler.postDelayed(() -> {
        progressDialog.dismiss();
        startService(ACTION_START_SERVER);
      }, 7000);
    });

    builder.setTitle(getString(R.string.hotspot_dialog_title));
    builder.setMessage(
        getString(R.string.hotspot_dialog_message)
    );
    AlertDialog dialog = builder.create();
    dialog.show();
  }

  private void startService(String ACTION) {
    if (ACTION.equals(ACTION_START_SERVER)) {
      serviceIntent.putStringArrayListExtra(SELECTED_ZIM_PATHS_KEY, selectedBooksPath);
    }
    serviceIntent.setAction(ACTION);
    this.startService(serviceIntent);
  }

  void mobileDataDialog() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      AlertDialog.Builder builder = new AlertDialog.Builder(this, dialogStyle());

      builder.setPositiveButton(this.getString(R.string.yes),
          (dialog, id) -> openMobileDataActivity());
      builder.setNegativeButton((android.R.string.no),
          (dialog, id) -> startHotspotManuallyDialog());
      builder.setTitle(this.getString(R.string.mobile_data_enabled));
      builder.setMessage(
          this.getString(R.string.mobile_data_message) + "\n" + this.getString(
              R.string.mobile_data_message_confirmation)
      );
      builder.setCancelable(false);
      AlertDialog dialog = builder.create();
      dialog.show();
    }
  }

  @Override public void onServerStarted(@NonNull String ip) {
    this.ip = ip;
    layoutServerStarted();
    isServerStarted = true;
  }

  @Override public void onServerStopped() {
    layoutServerStopped();
    isServerStarted = false;
  }

  @Override public void onServerFailedToStart() {
    Toast.makeText(this, R.string.server_failed_message, Toast.LENGTH_LONG).show();
  }

  @Override public void onHotspotTurnedOn(@NonNull WifiConfiguration wifiConfiguration) {

    hotspotService.startForegroundNotificationHelper();

    //Show an alert dialog for hotspot details
    AlertDialog.Builder builder = new AlertDialog.Builder(this, dialogStyle());

    ProgressDialog progressDialog =
        ProgressDialog.show(this, getString(R.string.progress_dialog_starting_server), "",
            true);
    progressDialog.show();

    builder.setPositiveButton(android.R.string.ok, (dialog, id) -> {
      final Handler handler = new Handler();
      handler.postDelayed(() -> {
        progressDialog.dismiss();
        startService(ACTION_START_SERVER);
      }, 2000);
    });

    builder.setTitle(this.getString(R.string.hotspot_turned_on));
    builder.setMessage(
        this.getString(R.string.hotspot_details_message) + "\n" + this.getString(
            R.string.hotspot_ssid_label) + " " + wifiConfiguration.SSID + "\n" + this.getString(
            R.string.hotspot_pass_label) + " " + wifiConfiguration.preSharedKey);

    builder.setCancelable(false);
    AlertDialog dialog = builder.create();
    dialog.show();

    //setupServer();
  }

  void launchTetheringSettingsScreen() {
    final Intent intent = new Intent(Intent.ACTION_MAIN, null);
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    final ComponentName cn =
        new ComponentName("com.android.settings", "com.android.settings.TetherSettings");
    intent.setComponent(cn);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  @Override public void onHotspotFailedToStart() {
    //Show a dialog to turn off default hotspot

    alertDialogShower.show(KiwixDialog.HotspotFailed.INSTANCE,
        new Function0<Unit>() {
          @Override public Unit invoke() {
            launchTetheringSettingsScreen();
            return Unit.INSTANCE;
          }
        });
  }

  @Override public void onHotspotStateReceived(@NonNull Boolean isHotspotEnabled) {
    if (isHotspotEnabled) //if hotspot is already enabled, turn it off.
    {
      startService(ACTION_TURN_OFF_AFTER_O);
    } else //If hotspot is not already enabled, then turn it on.
    {
      setupLocationServices();
    }
  }

  @Override protected void onSaveInstanceState(@Nullable Bundle outState) {
    super.onSaveInstanceState(outState);
    if (isServerStarted) {
      outState.putString(IP_STATE_KEY, ip);
    }
  }

  @Override public void addBooks(@Nullable List<BooksOnDiskListItem> books) {
    booksAdapter.setItems(books);
  }
}
