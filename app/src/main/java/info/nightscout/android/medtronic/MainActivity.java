package info.nightscout.android.medtronic;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.NotificationCompat;
import android.support.v7.view.menu.ActionMenuItemView;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.TextView.BufferType;

import com.github.mikephil.charting.charts.ScatterChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.ScatterData;
import com.github.mikephil.charting.data.ScatterDataSet;
import com.github.mikephil.charting.data.realm.implementation.RealmScatterDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.IScatterDataSet;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.github.mikephil.charting.utils.ViewPortHandler;
import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.Thing;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.materialdrawer.AccountHeaderBuilder;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.DrawerBuilder;
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import info.nightscout.android.R;
import info.nightscout.android.USB.UsbHidDriver;
import info.nightscout.android.eula.Eula;
import info.nightscout.android.eula.Eula.OnEulaAgreedTo;
import info.nightscout.android.medtronic.service.MedtronicCnlAlarmReceiver;
import info.nightscout.android.medtronic.service.MedtronicCnlIntentService;
import info.nightscout.android.model.medtronicNg.PumpInfo;
import info.nightscout.android.model.medtronicNg.PumpStatusEvent;
import info.nightscout.android.settings.SettingsActivity;
import info.nightscout.android.upload.nightscout.NightscoutUploadIntentService;
import io.realm.DynamicRealmObject;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;
import uk.co.chrisjenx.calligraphy.CalligraphyContextWrapper;

public class MainActivity extends AppCompatActivity implements OnSharedPreferenceChangeListener, OnEulaAgreedTo {
    private static final String TAG = MainActivity.class.getSimpleName();

    public static int batLevel = 0;
    private static long activePumpMac;
    boolean mEnableCgmService = true;
    SharedPreferences prefs = null;
    private PumpInfo mActivePump;
    private TextView mTextViewLog; // This will eventually move to a status page.
    private ScatterChart mChart;
    private Intent mNightscoutUploadService;
    private Handler mUiRefreshHandler = new Handler();
    private Runnable mUiRefreshRunnable = new RefreshDisplayRunnable();
    private Realm mRealm;
    private StatusMessageReceiver statusMessageReceiver = new StatusMessageReceiver();
    private MedtronicCnlAlarmReceiver medtronicCnlAlarmReceiver = new MedtronicCnlAlarmReceiver();
    public static long pollInterval = 0;
    public static long lowBatteryPollInterval = 0;

    public static void setActivePumpMac(long pumpMac) {
        activePumpMac = pumpMac;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate called");
        super.onCreate(savedInstanceState);

        mRealm = Realm.getDefaultInstance();
        mNightscoutUploadService = new Intent(this, NightscoutUploadIntentService.class);

        setContentView(R.layout.activity_main);

        PreferenceManager.getDefaultSharedPreferences(getBaseContext()).registerOnSharedPreferenceChangeListener(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        if (!prefs.getBoolean(getString(R.string.preference_eula_accepted), false)) {
            stopCgmService();
        }

        //set poll intervals
        MainActivity.pollInterval = Long.parseLong(prefs.getString("pollInterval", Long.toString(MedtronicCnlIntentService.POLL_PERIOD_MS)));
        MainActivity.lowBatteryPollInterval = Long.parseLong(prefs.getString("lowBatPollInterval", Long.toString(MedtronicCnlIntentService.LOW_BATTERY_POLL_PERIOD_MS)));

        // Disable battery optimization to avoid missing values on 6.0+
        // taken from https://github.com/NightscoutFoundation/xDrip/blob/master/app/src/main/java/com/eveningoutpost/dexdrip/Home.java#L277L298

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final String packageName = getPackageName();
            final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Requesting ignore battery optimization");
                try {
                    // ignoring battery optimizations required for constant connection
                    // to peripheral device - eg CGM transmitter.
                    final Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.d(TAG, "Device does not appear to support battery optimization whitelisting!");
                }
            }
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
                statusMessageReceiver,
                new IntentFilter(MedtronicCnlIntentService.Constants.ACTION_STATUS_MESSAGE));
        LocalBroadcastManager.getInstance(this).registerReceiver(
                new RefreshDataReceiver(),
                new IntentFilter(MedtronicCnlIntentService.Constants.ACTION_REFRESH_DATA));

        mEnableCgmService = Eula.show(this, prefs);

        IntentFilter batteryIntentFilter = new IntentFilter();
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_OKAY);
        registerReceiver(new BatteryReceiver(), batteryIntentFilter);

        UsbReceiver usbReceiver = new UsbReceiver();
        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbIntentFilter.addAction(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, usbIntentFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                usbReceiver,
                new IntentFilter(MedtronicCnlIntentService.Constants.ACTION_NO_USB_PERMISSION));
        LocalBroadcastManager.getInstance(this).registerReceiver(
                usbReceiver,
                new IntentFilter(MedtronicCnlIntentService.Constants.ACTION_USB_REGISTER));

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);

        if (toolbar != null) {
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setElevation(0);
            getSupportActionBar().setTitle("Nightscout");
        }

        final PrimaryDrawerItem itemSettings = new PrimaryDrawerItem()
                .withName("Settings")
                .withIcon(GoogleMaterial.Icon.gmd_settings)
                .withSelectable(false);
        final PrimaryDrawerItem itemRegisterUsb = new PrimaryDrawerItem()
                .withName("Registered Devices")
                .withIcon(GoogleMaterial.Icon.gmd_usb)
                .withSelectable(false);
        final PrimaryDrawerItem itemStopCollecting = new PrimaryDrawerItem()
                .withName("Stop collecting data")
                .withIcon(GoogleMaterial.Icon.gmd_stop)
                .withSelectable(false);
        final PrimaryDrawerItem itemGetNow = new PrimaryDrawerItem()
                .withName("Read data now")
                .withIcon(GoogleMaterial.Icon.gmd_play_arrow)
                .withSelectable(false);
        final PrimaryDrawerItem itemClearLog = new PrimaryDrawerItem()
                .withName("Clear Log")
                .withIcon(GoogleMaterial.Icon.gmd_clear_all)
                .withSelectable(false);

        new DrawerBuilder()
                .withActivity(this)
                .withAccountHeader(new AccountHeaderBuilder()
                        .withActivity(this)
                        .withHeaderBackground(R.drawable.drawer_header)
                        .build()
                )
                .withTranslucentStatusBar(false)
                .withToolbar(toolbar)
                .withActionBarDrawerToggle(true)
                .withSelectedItem(-1)
                .addDrawerItems(
                        itemSettings,
                        itemRegisterUsb,
                        itemStopCollecting,
                        itemGetNow,
                        itemClearLog
                )
                .withOnDrawerItemClickListener(new Drawer.OnDrawerItemClickListener() {
                    @Override
                    public boolean onItemClick(View view, int position, IDrawerItem drawerItem) {
                        if (drawerItem.equals(itemSettings)) {
                            openSettings();
                        } else if (drawerItem.equals(itemRegisterUsb)) {
                            openUsbRegistration();
                        } else if (drawerItem.equals(itemStopCollecting)) {
                            mEnableCgmService = false;
                            stopCgmService();
                            finish();
                        } else if (drawerItem.equals(itemGetNow)) {
                            startCgmService();
                        } else if (drawerItem.equals(itemClearLog)) {
                            clearLogText();
                        }

                        return false;
                    }
                })
                .build();

        mTextViewLog = (TextView) findViewById(R.id.textview_log);
        mChart = (ScatterChart) findViewById(R.id.chart);

        mChart.setDescription(null);    // Hide the description

        mChart.setTouchEnabled(true);
        mChart.setPinchZoom(true);
        mChart.setHighlightPerDragEnabled(false);
        mChart.setHighlightPerTapEnabled(false);
        mChart.setOnChartGestureListener(new OnChartGestureListener() {

            @Override
            public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}

            @Override
            public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}

            @Override
            public void onChartLongPressed(MotionEvent me) {
                mChart.fitScreen();
            }

            @Override
            public void onChartDoubleTapped(MotionEvent me) {}

            @Override
            public void onChartSingleTapped(MotionEvent me) {}

            @Override
            public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {}

            @Override
            public void onChartScale(MotionEvent me, float scaleX, float scaleY) {}

            @Override
            public void onChartTranslate(MotionEvent me, float dX, float dY) {}
        });

        XAxis xAxis = mChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextSize(10f);
        xAxis.setTextColor(Color.WHITE);
        xAxis.setDrawAxisLine(true);
        xAxis.setDrawGridLines(true);
        xAxis.setDrawLabels(true);
        xAxis.setValueFormatter(new IAxisValueFormatter() {
            private DateFormat mFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                return mFormat.format(new Date((long) value));
            }
        });

        // left axis
        mChart.getAxisLeft().setDrawLabels(false);

        // right axis
        YAxis yAxis = mChart.getAxisRight();
        yAxis.setTextSize(10f);
        yAxis.setTextColor(Color.WHITE);

        mChart.getLegend().setEnabled(false);   // Hide the legend
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        startCgmService();
        startDisplayRefreshLoop();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(CalligraphyContextWrapper.wrap(newBase));

        // setup self handling alarm receiver
        medtronicCnlAlarmReceiver.setContext(getBaseContext());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_menu_status:
                Intent intent = new Intent(this, StatusActivity.class);
                startActivity(intent);
                break;
        }
        return true;
    }

    private boolean hasUsbPermission() {
        UsbManager usbManager = (UsbManager) this.getSystemService(Context.USB_SERVICE);
        UsbDevice cnlDevice = UsbHidDriver.getUsbDevice(usbManager, MedtronicCnlIntentService.USB_VID, MedtronicCnlIntentService.USB_PID);

        return !(usbManager != null && cnlDevice != null && !usbManager.hasPermission(cnlDevice));
    }

    private void waitForUsbPermission() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent permissionIntent = new Intent(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION);
        permissionIntent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, hasUsbPermission());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, pendingIntent);
    }

    private void requestUsbPermission() {
        if (!hasUsbPermission()) {
            UsbManager usbManager = (UsbManager) this.getSystemService(Context.USB_SERVICE);
            UsbDevice cnlDevice = UsbHidDriver.getUsbDevice(usbManager, MedtronicCnlIntentService.USB_VID, MedtronicCnlIntentService.USB_PID);
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(cnlDevice, permissionIntent);
        }
    }

    private void refreshDisplay() {
        cancelDisplayRefreshLoop();
        startDisplayRefreshLoop();
    }

    private void clearLogText() {
        statusMessageReceiver.clearMessages();
        //mTextViewLog.setText("", BufferType.EDITABLE);
    }

    private void startDisplayRefreshLoop() {
        mUiRefreshHandler.post(mUiRefreshRunnable);
    }

    private void cancelDisplayRefreshLoop() {
        mUiRefreshHandler.removeCallbacks(mUiRefreshRunnable);
    }

    private void startCgmService() {
        startCgmService(System.currentTimeMillis() + 1000);
    }

    private void startCgmService(long initialPoll) {
        Log.i(TAG, "startCgmService called");

        if (!mEnableCgmService) {
            return;
        }

        //clearLogText();

        // Cancel any existing polling.
        stopCgmService();
        medtronicCnlAlarmReceiver.setAlarm(initialPoll);
    }

    private void uploadCgmData() {
        startService(mNightscoutUploadService);
    }

    private void stopCgmService() {
        Log.i(TAG, "stopCgmService called");
        medtronicCnlAlarmReceiver.cancelAlarm();
    }

    private void showDisconnectionNotification(String title, String message) {
        int notifyId = 1;

        NotificationCompat.Builder mBuilder =
                (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setSmallIcon(R.drawable.ic_launcher) // FIXME - this icon doesn't follow the standards (ie, it has black in it)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setTicker(message)
                        .setVibrate(new long[]{1000, 1000, 1000, 1000, 1000});
        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // notifyId allows you to update the notification later on.
        mNotificationManager.notify(notifyId, mBuilder.build());
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy called");
        super.onDestroy();

        PreferenceManager.getDefaultSharedPreferences(getBaseContext()).unregisterOnSharedPreferenceChangeListener(this);
        cancelDisplayRefreshLoop();

        mRealm.close();

        if (!mEnableCgmService) {
            stopCgmService();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        if (key.equals(getString(R.string.preference_eula_accepted))) {
            if (!sharedPreferences.getBoolean(getString(R.string.preference_eula_accepted), false)) {
                mEnableCgmService = false;
                stopCgmService();
            } else {
                mEnableCgmService = true;
                startCgmService();
            }
        } else if (key.equals("mmolxl")) {
            refreshDisplay();
        } else if (key.equals("pollInterval")) {
            MainActivity.pollInterval = Long.parseLong(sharedPreferences.getString("pollInterval",
                    Long.toString(MedtronicCnlIntentService.POLL_PERIOD_MS)));
        } else if (key.equals("lowBatPollInterval")) {
            MainActivity.lowBatteryPollInterval = Long.parseLong(sharedPreferences.getString("lowBatPollInterval",
                    Long.toString(MedtronicCnlIntentService.LOW_BATTERY_POLL_PERIOD_MS)));
        }
    }

    @Override
    public void onEulaAgreedTo() {
        mEnableCgmService = true;
    }

    @Override
    public void onEulaRefusedTo() {
        mEnableCgmService = false;
    }

    public void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    public void openUsbRegistration() {
        Intent manageCNLIntent = new Intent(this, ManageCNLActivity.class);
        startActivity(manageCNLIntent);
    }

    private String renderTrendHtml(PumpStatusEvent.CGM_TREND trend) {
        switch (trend) {
            case DOUBLE_UP:
                return "&#x21c8;";
            case SINGLE_UP:
                return "&#x2191;";
            case FOURTY_FIVE_UP:
                return "&#x2197;";
            case FLAT:
                return "&#x2192;";
            case FOURTY_FIVE_DOWN:
                return "&#x2198;";
            case SINGLE_DOWN:
                return "&#x2193;";
            case DOUBLE_DOWN:
                return "&#x21ca;";
            default:
                return "&mdash;";
        }
    }

    private PumpInfo getActivePump() {
        if (activePumpMac != 0L && (mActivePump == null || !mActivePump.isValid() || mActivePump.getPumpMac() != activePumpMac)) {
            mActivePump = null;

            PumpInfo pump = mRealm
                    .where(PumpInfo.class)
                    .equalTo("pumpMac", MainActivity.activePumpMac)
                    .findFirst();

            if (pump != null && pump.isValid()) {
                mActivePump = pump;
            }
        }

        return mActivePump;
    }

    /**
     * ATTENTION: This was auto-generated to implement the App Indexing API.
     * See https://g.co/AppIndexing/AndroidStudio for more information.
     */
    public Action getIndexApiAction() {
        Thing object = new Thing.Builder()
                .setName("Main Page") // TODO: Define a title for the content shown.
                // TODO: Make sure this auto-generated URL is correct.
                .setUrl(Uri.parse("http://[ENTER-YOUR-URL-HERE]"))
                .build();
        return new Action.Builder(Action.TYPE_VIEW)
                .setObject(object)
                .setActionStatus(Action.STATUS_TYPE_COMPLETED)
                .build();
    }


    private class StatusMessageReceiver extends BroadcastReceiver {
        private class StatusMessage {
            private long timestamp;
            private String message;

            public StatusMessage(String message) {
                this(System.currentTimeMillis(), message);
            }

            public StatusMessage(long timestamp, String message) {
                this.timestamp = timestamp;
                this.message = message;
            }

            public long getTimestamp() {
                return timestamp;
            }

            public void setTimestamp(long timestamp) {
                this.timestamp = timestamp;
            }

            public String getMessage() {
                return message;
            }

            public void setMessage(String message) {
                this.message = message;
            }

            public String toString() {
                return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(timestamp) + ": " + message;
            }
        }

        private Queue<StatusMessage> messages = new ArrayBlockingQueue<>(10);

        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(MedtronicCnlIntentService.Constants.EXTENDED_DATA);
            Log.i(TAG, "Message Receiver: " + message);

            synchronized (messages) {
                while (messages.size() > 8) {
                    messages.poll();
                }
                messages.add(new StatusMessage(message));
            }

            StringBuilder sb = new StringBuilder();
            for (StatusMessage msg : messages) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append(msg);
            }

            mTextViewLog.setText(sb.toString(), BufferType.EDITABLE);
        }

        public void clearMessages() {
            synchronized (messages) {
                messages.clear();
            }

            mTextViewLog.setText("", BufferType.EDITABLE);
        }
    }

    private class RefreshDisplayRunnable implements Runnable {
        @Override
        public void run() {
            // UI elements - TODO do these need to be members?
            TextView textViewBg = (TextView) findViewById(R.id.textview_bg);
            TextView textViewBgTime = (TextView) findViewById(R.id.textview_bg_time);
            TextView textViewUnits = (TextView) findViewById(R.id.textview_units);
            if (prefs.getBoolean("mmolxl", false)) {
                textViewUnits.setText(R.string.text_unit_mmolxl);
            } else {
                textViewUnits.setText(R.string.text_unit_mgxdl);
            }
            TextView textViewTrend = (TextView) findViewById(R.id.textview_trend);
            TextView textViewIOB = (TextView) findViewById(R.id.textview_iob);

            // Get the most recently written CGM record for the active pump.
            PumpStatusEvent pumpStatusData = null;

            PumpInfo pump = getActivePump();

            if (pump != null && pump.isValid()) {
                pumpStatusData = pump.getPumpHistory().last();
            }

            // FIXME - grab the last item from the activePump's getPumpHistory
            updateChart(mRealm.where(PumpStatusEvent.class)
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - 1000*60*60*24))
                    .findAllSorted("eventDate", Sort.ASCENDING));

            if (pumpStatusData != null) {

                String sgvString, units;
                if (prefs.getBoolean("mmolxl", false)) {
                    DecimalFormat df;
                    if (prefs.getBoolean("mmolDecimals", false))
                        df = new DecimalFormat("0.00");
                    else
                        df = new DecimalFormat("0.0");

                    float fBgValue = (float) pumpStatusData.getSgv();
                    sgvString = df.format(fBgValue / 18.016f);
                    units = "mmol/L";
                    Log.d(TAG, "mmolxl true --> " + sgvString);

                } else {
                    sgvString = String.valueOf(pumpStatusData.getSgv());
                    units = "mg/dL";
                    Log.d(TAG, "mmolxl false --> " + sgvString);
                }

                textViewBg.setText(sgvString);
                textViewUnits.setText(units);
                textViewBgTime.setText(DateUtils.getRelativeTimeSpanString(pumpStatusData.getEventDate().getTime()));
                textViewTrend.setText(Html.fromHtml(renderTrendHtml(pumpStatusData.getCgmTrend())));
                textViewIOB.setText(String.format(Locale.getDefault(), "%.2f", pumpStatusData.getActiveInsulin()));

                ActionMenuItemView batIcon = ((ActionMenuItemView) findViewById(R.id.status_battery));
                if (batIcon != null) {
                    switch (pumpStatusData.getBatteryPercentage()) {
                        case 0:
                            batIcon.setTitle("0%");
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_0));
                            break;
                        case 25:
                            batIcon.setTitle("25%");
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_25));
                            break;
                        case 50:
                            batIcon.setTitle("50%");
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_50));
                            break;
                        case 75:
                            batIcon.setTitle("75%");
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_75));
                            break;
                        case 100:
                            batIcon.setTitle("100%");
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_100));
                            break;
                        default:
                            batIcon.setTitle(getResources().getString(R.string.menu_name_status));
                            batIcon.setIcon(getResources().getDrawable(R.drawable.battery_unknown));
                    }
                }

            }

            // Run myself again in 60 seconds;
            mUiRefreshHandler.postDelayed(this, 60000L);
        }

        private void updateChart(RealmResults<PumpStatusEvent> results) {
            int size = results.size();
            if (size == 0) return;

            List<Entry> entries = new ArrayList<Entry>(size);
            int[] colors = new int[size * 2];  // getColor is called with (i/2)

            for (PumpStatusEvent pumpStatus: results) {
                // turn your data into Entry objects
                int sgv = pumpStatus.getSgv(),
                    pos = entries.size() * 2;

                entries.add(new Entry(pumpStatus.getEventDate().getTime(), pumpStatus.getSgv()));
                if (sgv < 80)
                    colors[pos] = colors[pos+1] = Color.RED;
                else if (sgv <= 180)
                    colors[pos] = colors[pos+1] = Color.GREEN;
                else if (sgv <= 260)
                    colors[pos] = colors[pos+1] = Color.YELLOW;
                else
                    colors[pos] = colors[pos+1] = Color.RED;
            }

            if (mChart.getData() == null) {
                mChart.setMinimumHeight(200);

                ScatterDataSet dataSet = new ScatterDataSet(entries, null);

                dataSet.setColors(colors);
                dataSet.setValueTextColor(Color.WHITE);
                dataSet.setScatterShape(ScatterChart.ScatterShape.CIRCLE);
                dataSet.setScatterShapeSize(7.2f);
                dataSet.setValueFormatter(new IValueFormatter() {
                    @Override
                    public String getFormattedValue(float value, Entry entry, int dataSetIndex, ViewPortHandler viewPortHandler) {
                        DecimalFormat df;

                        if (prefs.getBoolean("mmolxl", false)) {
                            if (prefs.getBoolean("mmolDecimals", false))
                                df = new DecimalFormat("0.00");
                            else
                                df = new DecimalFormat("0.0");

                            return df.format(value / 18.016f);
                        } else {
                            return new DecimalFormat("0").format(value);
                        }
                    }
                });

                ArrayList<IScatterDataSet> dataSets = new ArrayList<IScatterDataSet>();
                dataSets.add(dataSet);

                ScatterData lineData = new ScatterData(dataSets);
                mChart.setData(lineData);
            } else {
                ((ScatterDataSet)mChart.getScatterData().getDataSets().get(0)).setValues(entries);
                ((ScatterDataSet)mChart.getScatterData().getDataSets().get(0)).setColors(colors);
                //dataSet.notifyDataSetChanged();
            }

            //TODO: make the display timespan configurable
            mChart.getXAxis().setAxisMaximum(System.currentTimeMillis());
            mChart.getXAxis().setAxisMinimum(mChart.getXAxis().getAxisMaximum() - 24 * 60 * 60 * 1000);

            mChart.postInvalidate();
        }
    }

    private class RefreshDataReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            // If the MainActivity has already been destroyed (meaning the Realm instance has been closed)
            // then don't worry about processing this broadcast
            if (mRealm.isClosed()) {
                return;
            }

            PumpStatusEvent pumpStatusData = null;

            PumpInfo pump = getActivePump();

            if (pump != null && pump.isValid()) {
                pumpStatusData = pump.getPumpHistory().last();
            } else {
                return;
            }

            long nextPoll = pumpStatusData.getEventDate().getTime() + pumpStatusData.getPumpTimeOffset()
                    + MedtronicCnlIntentService.POLL_GRACE_PERIOD_MS;

            if (pumpStatusData.getBatteryPercentage() > 25) {
                // poll every 5 min
                nextPoll += MainActivity.pollInterval;
            } else {
                // if pump battery seems to be empty reduce polling to save battery (every 15 min)
                //TODO configurable???
                //TODO add message & document it
                nextPoll += MainActivity.lowBatteryPollInterval;
            }
            startCgmService(nextPoll);

            // Delete invalid or old records from Realm
            // TODO - show an error message if the valid records haven't been uploaded
            final RealmResults<PumpStatusEvent> results =
                    mRealm.where(PumpStatusEvent.class)
                            .equalTo("sgv", 0)
                            .or()
                            .lessThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 60 * 1000)))
                            .findAll();

            if (results.size() > 0) {
                mRealm.executeTransaction(new Realm.Transaction() {
                    @Override
                    public void execute(Realm realm) {
                        // Delete all matches
                        Log.d(TAG, "Deleting " + results.size() + " records from realm");
                        results.deleteAllFromRealm();
                    }
                });
            }

            // TODO - handle isOffline in NightscoutUploadIntentService?
            uploadCgmData();

            refreshDisplay();
        }
    }

    private class UsbReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION.equals(action)) {
                boolean permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (permissionGranted) {
                    Log.d(TAG, "Got permission to access USB");
                    startCgmService();
                } else {
                    Log.d(TAG, "Still no permission for USB. Waiting...");
                    waitForUsbPermission();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "USB plugged in");

                if (hasUsbPermission()) {
                    // Give the USB a little time to warm up first
                    startCgmService(System.currentTimeMillis() + MedtronicCnlIntentService.USB_WARMUP_TIME_MS);
                } else {
                    Log.d(TAG, "No permission for USB. Waiting.");
                    waitForUsbPermission();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB unplugged");
                if (mEnableCgmService) {
                    showDisconnectionNotification("USB Error", "Contour Next Link unplugged.");
                }
            } else if (MedtronicCnlIntentService.Constants.ACTION_NO_USB_PERMISSION.equals(action)) {
                Log.d(TAG, "No permission to read the USB device.");
                requestUsbPermission();
            } else if (MedtronicCnlIntentService.Constants.ACTION_USB_REGISTER.equals(action)) {
                openUsbRegistration();
            }
        }
    }

    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_LOW)
                    || arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_CHANGED)
                    || arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_OKAY)) {
                batLevel = arg1.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            }
        }
    }

    private class PumsStatusDataSet extends RealmScatterDataSet<PumpStatusEvent> {

        public PumsStatusDataSet(RealmResults<PumpStatusEvent> result, String yValuesField) {
            super(result, yValuesField);
        }

        public PumsStatusDataSet(RealmResults<PumpStatusEvent> result, String xValuesField, String yValuesField) {
            super(result, xValuesField, yValuesField);
        }

        public Entry buildEntryFromResultObject(PumpStatusEvent realmObject, float x) {
            DynamicRealmObject dynamicObject = new DynamicRealmObject(realmObject);
            float xFloat, yFloat;

            if (mXValuesField == null) {
                xFloat = x;
            } else {
                xFloat = dynamicObject.getDate(mXValuesField).getTime();
            }
            yFloat = dynamicObject.getInt(mYValuesField);

            return new Entry(mXValuesField == null ? x : xFloat, yFloat);
        }

    }


}
