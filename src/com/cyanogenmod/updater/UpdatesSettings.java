/*
 * Copyright (C) 2012 The CyanogenMod Project (DvTonder)
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater;

import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cyanogenmod.updater.customTypes.FullUpdateInfo;
import com.cyanogenmod.updater.customTypes.UpdateInfo;
import com.cyanogenmod.updater.customization.Customization;
import com.cyanogenmod.updater.misc.Constants;
import com.cyanogenmod.updater.misc.State;
import com.cyanogenmod.updater.service.UpdateCheckService;
import com.cyanogenmod.updater.tasks.UpdateCheckTask;
import com.cyanogenmod.updater.utils.SysUtils;
import com.cyanogenmod.updater.utils.UpdateFilter;

import java.io.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class UpdatesSettings extends PreferenceActivity implements OnPreferenceChangeListener {
    private static String TAG = "UpdatesSettings";
    private static final boolean DEBUG = false;

    private static String UPDATES_CATEGORY = "updates_category";

    private static final int MENU_REFRESH = 0;
    private static final int MENU_DELETE_ALL = 1;
    private static final int MENU_SYSTEM_INFO = 2;

    private SharedPreferences mPrefs;
    private CheckBoxPreference mBackupRom;
    private ListPreference mUpdateCheck;
    private ListPreference mUpdateType;

    private PreferenceCategory mUpdatesList;
    private UpdatePreference mDownloadingPreference;

    private File mUpdateFolder;
    private ArrayList<UpdateInfo> mServerUpdates;
    private ArrayList<UpdateInfo> mLocalUpdates;

    private boolean mStartUpdateVisible = false;

    private DownloadManager mDownloadManager;
    private boolean mDownloading = false;
    private long mEnqueue;
    private String mFileName;

    private String mSystemMod;
    private String mSystemRom;

    private Handler mUpdateHandler = new Handler();

    private DownloadSupport mDownloadSupport;

    /**
     * Broadcast receiver to kick start the download progress bar if the background service
     * automatically starts an update.
     */
    private final BroadcastReceiver mDownloadBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Got broadcast "+intent.getAction()+" : "+intent.getCategories());
            if(!DownloadSupport.DOWNLOAD_STARTED_ACTION.equals(intent.getAction())) {
                return;
            }

            UpdateInfo ui = (UpdateInfo) intent.getExtras().get(DownloadSupport.DOWNLOAD_STARTED_EXTRA_UPDATE_INFO);
            if(ui == null) {
                Log.i(TAG, "Bailing");
                return;
            }

            // Start the download
            mEnqueue = intent.getExtras().getLong(DownloadSupport.DOWNLOAD_STARTED_EXTRA_ID);
            mFileName = ui.getFileName();
            mDownloading = true;

            // Store in shared preferences
            mPrefs.edit().putLong(Constants.DOWNLOAD_ID, mEnqueue).apply();
            mPrefs.edit().putString(Constants.DOWNLOAD_MD5, ui.getMD5()).apply();
        }
    };

    /**
     * Broadcast receiver to show the user there are no new updates
     */
    private final BroadcastReceiver mNoUpdatesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UpdatesSettings.this.runOnUiThread( new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(UpdatesSettings.this)
                            .setMessage(R.string.no_updates_found)
                            .setTitle(R.string.checking_for_updates)
                            .setPositiveButton(R.string.ok, null)
                            .create().show();
                }
            });
        }
    };

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup the download support object
        mDownloadSupport = new DownloadSupport(this);

        // Load the layouts
        addPreferencesFromResource(R.xml.main);
        PreferenceScreen prefSet = getPreferenceScreen();
        mUpdatesList = (PreferenceCategory) prefSet.findPreference(UPDATES_CATEGORY);

        // Load the stored preference data
        mPrefs = getSharedPreferences("CMUpdate", Context.MODE_MULTI_PROCESS);
        mUpdateCheck = (ListPreference) findPreference(Constants.UPDATE_CHECK_PREF);
        if (mUpdateCheck != null) {
            int check = mPrefs.getInt(Constants.UPDATE_CHECK_PREF, Constants.UPDATE_FREQ_AT_BOOT);
            mUpdateCheck.setValue(String.valueOf(check));
            mUpdateCheck.setSummary(mapCheckValue(check));
            mUpdateCheck.setOnPreferenceChangeListener(this);
        }

        mUpdateType = (ListPreference) findPreference(Constants.UPDATE_TYPE_PREF);
        if (mUpdateType != null) {
            int type = mPrefs.getInt(Constants.UPDATE_TYPE_PREF, 0);
            mUpdateType.setValue(String.valueOf(type));
            mUpdateType.setSummary(mUpdateType.getEntries()[type]);
            mUpdateType.setOnPreferenceChangeListener(this);
        }

        /* TODO: add this back once we have a way of doing backups that is not recovery specific
        mBackupRom = (CheckBoxPreference) prefSet.findPreference(Constants.BACKUP_PREF);
        mBackupRom.setChecked(mPrefs.getBoolean(Constants.BACKUP_PREF, true));
        */

        // Get the currently installed system Mod and Rom for later matching
        mSystemMod = SysUtils.getSystemProperty(Customization.BOARD);
        mSystemRom = SysUtils.getSystemProperty(Customization.SYS_PROP_MOD_VERSION);

        // Initialize the arrays
        mServerUpdates = new ArrayList<UpdateInfo>();
        mLocalUpdates = new ArrayList<UpdateInfo>();

        // Determine if there are any in-progress downloads
        mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        mEnqueue = mPrefs.getLong(Constants.DOWNLOAD_ID, -1);
        if (mEnqueue != -1) {
            Cursor c = mDownloadManager.query(new DownloadManager.Query().setFilterById(mEnqueue));
            if (c == null) {
                Toast.makeText(this, R.string.download_not_found, Toast.LENGTH_LONG).show();
            } else {
                if (c.moveToFirst()) {
                    String lFile = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (lFile != null && status != DownloadManager.STATUS_FAILED) {
                        String[] temp = lFile.split("/");
                        // Strip the .partial at the end of the name
                        mFileName = (temp[temp.length - 1]).replace(".partial", "");
                    }
                }
            }
            c.close();
        }

        // Set 'HomeAsUp' feature of the actionbar to fit better into Settings
        final ActionBar bar = getActionBar();
        bar.setDisplayHomeAsUpEnabled(true);

        // Turn on the Options Menu and update the layout
        invalidateOptionsMenu();
        updateLayout();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_REFRESH, 0, R.string.menu_refresh)
                .setIcon(R.drawable.ic_menu_refresh)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS
                        | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        menu.add(0, MENU_DELETE_ALL, 0, R.string.menu_delete_all)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        menu.add(0, MENU_SYSTEM_INFO, 0, R.string.menu_system_info)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REFRESH:
                checkForUpdates();
                return true;

            case MENU_DELETE_ALL:
                confirmDeleteAll();
                return true;

            case MENU_SYSTEM_INFO:
                showSysInfo();
                return true;

            case android.R.id.home:
                UpdatesSettings.this.onBackPressed();
                return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mBackupRom) {
            mPrefs.edit().putBoolean(Constants.BACKUP_PREF, mBackupRom.isChecked()).apply();
            return true;
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Check if we need to refresh the screen to show new updates
        boolean doCheck = intent.getBooleanExtra(Constants.CHECK_FOR_UPDATE, false);
        if (doCheck) {
            updateLayout();
        }

        // Check if we have been asked to start an update
        boolean startUpdate = intent.getBooleanExtra(Constants.START_UPDATE, false);
        if (startUpdate) {
            UpdateInfo ui = (UpdateInfo) intent.getSerializableExtra(Constants.KEY_UPDATE_INFO);
            if (ui != null) {
                UpdatePreference pref = findMatchingPreference(ui.getFileName());
                if (pref != null) {
                    pref.setStyle(UpdatePreference.STYLE_DOWNLOADED);
                    startUpdate(ui);
                }
            }
        }

        // Check if we have been asked to start the 'download completed' functionality
        boolean downloadCompleted = intent.getBooleanExtra(Constants.DOWNLOAD_COMPLETED, false);
        if (downloadCompleted) {
            long id = intent.getLongExtra(Constants.DOWNLOAD_ID, -1);
            String fullPathname = intent.getStringExtra(Constants.DOWNLOAD_FULLPATH);
            if (id != -1 && fullPathname != null) {
                downloadCompleted(id, fullPathname);
            }
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mUpdateCheck) {
            int value = Integer.valueOf((String) newValue);
            mPrefs.edit().putInt(Constants.UPDATE_CHECK_PREF, value).apply();
            mUpdateCheck.setSummary(mapCheckValue(value));
            scheduleUpdateService(value * 1000);
            return true;

        } else if (preference == mUpdateType) {
            int value = Integer.valueOf((String) newValue);
            mPrefs.edit().putInt(Constants.UPDATE_TYPE_PREF, value).apply();
            mUpdateType.setSummary(mUpdateType.getEntries()[value]);
            checkForUpdates();
            return true;
        }

        return false;
    }

    @Override
    public boolean onKeyUp(int keycode, KeyEvent event) {
        if(keycode == KeyEvent.KEYCODE_DPAD_CENTER || keycode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            int selectionPosition = getSelectedItemPosition();
            Object selection = getPreferenceScreen().getRootAdapter().getItem(selectionPosition);
            if(selection != null && selection instanceof UpdatePreference) {
                UpdatePreference selectedPreference = (UpdatePreference) selection;
                switch(selectedPreference.getStyle()) {
                    case UpdatePreference.STYLE_NEW:
                        startDownload(selectedPreference.getKey());
                        break;
                    case UpdatePreference.STYLE_DOWNLOADING:
                        stopDownload();
                        break;
                    case UpdatePreference.STYLE_DOWNLOADED:
                        startUpdate(selectedPreference.getUpdateInfo());
                        break;
                }
                return true;
            }

        }
        return super.onKeyUp(keycode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mDownloadBroadcastReceiver, new IntentFilter(DownloadSupport.DOWNLOAD_STARTED_ACTION));
        registerReceiver(mNoUpdatesReceiver, new IntentFilter(UpdateCheckService.NO_UPDATES_ACTION));
        mUpdateHandler.post(updateProgress);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mNoUpdatesReceiver);
        unregisterReceiver(mDownloadBroadcastReceiver);
        mUpdateHandler.removeCallbacks(updateProgress);
    }

    //*********************************************************
    // Supporting methods
    //*********************************************************

    protected void startDownload(String key) {

        // If there is no internet connection, display a message and return.
        if (!isOnline(getBaseContext())) {
            Toast.makeText(this, R.string.data_connection_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (mDownloading) {
            Toast.makeText(this, R.string.download_already_running, Toast.LENGTH_LONG).show();
            return;
        }

        UpdatePreference pref = findMatchingPreference(key);
        if (pref != null) {
            // We have a match, get ready to trigger the download
            mDownloadingPreference = pref;
            UpdateInfo ui = mDownloadingPreference.getUpdateInfo();
            if (ui != null) {
                mDownloadingPreference.setStyle(UpdatePreference.STYLE_DOWNLOADING);

                // Start the download
                mEnqueue = mDownloadSupport.startDownload(ui);
                mFileName = ui.getFileName();
                mDownloading = true;

                // Store in shared preferences
                mPrefs.edit().putLong(Constants.DOWNLOAD_ID, mEnqueue).apply();
                mPrefs.edit().putString(Constants.DOWNLOAD_MD5, ui.getMD5()).apply();
                mUpdateHandler.post(updateProgress);
            }
        }
    }

    Runnable updateProgress = new Runnable() {
        public void run() {
            if (mDownloadingPreference != null) {
                if (mDownloadingPreference.getProgressBar() != null && mDownloading) {
                    DownloadManager mgr = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById(mEnqueue);
                    Cursor cursor = mgr.query(q);
                    if (!cursor.moveToFirst()) {
                        return;
                    }
                    int bytes_downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int bytes_total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    cursor.close();
                    ProgressBar prog = mDownloadingPreference.getProgressBar();
                    if (bytes_total < 0) {
                        prog.setIndeterminate(true);
                    } else {
                        prog.setIndeterminate(false);
                        prog.setMax(bytes_total);
                    }
                    prog.setProgress(bytes_downloaded);
                }
                if (mDownloading) {
                    mUpdateHandler.postDelayed(this, 1000);
                }
            }
        }
    };

    protected void stopDownload() {
        if (!mDownloading || mFileName == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_download_cancelation_dialog_title);
        builder.setMessage(R.string.confirm_download_cancelation_dialog_message);
        builder.setPositiveButton(R.string.dialog_yes, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // Set the preference back to new style
                UpdatePreference pref = findMatchingPreference(mFileName);
                if (pref != null) {
                    pref.setStyle(UpdatePreference.STYLE_NEW);
                }
                // We are OK to stop download, trigger it
                mDownloadManager.remove(mEnqueue);
                mUpdateHandler.removeCallbacks(updateProgress);
                mEnqueue = -1;
                mFileName = null;
                mDownloading = false;

                // Clear the stored data from sharedpreferences
                mPrefs.edit().putLong(Constants.DOWNLOAD_ID, mEnqueue).apply();
                mPrefs.edit().putString(Constants.DOWNLOAD_MD5, "").apply();
                Toast.makeText(UpdatesSettings.this, R.string.download_cancelled, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.dialog_no, null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void downloadCompleted(long downloadId, String fullPathname) {
        mDownloading = false;

        String[] temp = fullPathname.split("/");
        String fileName = temp[temp.length - 1];

        // Find the matching preference so we can retrieve the UpdateInfo
        UpdatePreference pref = findMatchingPreference(fileName);
        if (pref != null) {
            UpdateInfo ui = pref.getUpdateInfo();
                pref.setStyle(UpdatePreference.STYLE_DOWNLOADED);
                startUpdate(ui);
        }
    }

    private UpdatePreference findMatchingPreference(String key) {
        if (mUpdatesList != null) {
            // Find the matching preference
            for (int i = 0; i < mUpdatesList.getPreferenceCount(); i++) {
                UpdatePreference pref = (UpdatePreference) mUpdatesList.getPreference(i);
                if (pref.getKey().equals(key)) {
                    return pref;
                }
            }
        }
        return null;
    }

    private String mapCheckValue(Integer value) {
        Resources resources = getResources();
        String[] checkNames = resources.getStringArray(R.array.update_check_entries);
        String[] checkValues = resources.getStringArray(R.array.update_check_values);
        for (int i = 0; i < checkValues.length; i++) {
            if (Integer.decode(checkValues[i]).equals(value)) {
                return checkNames[i];
            }
        }
        return getString(R.string.unknown);
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnected()) {
            return true;
        }
        return false;
    }

    public void checkForUpdates() {

        // If there is no internet connection, display a message and return.
        if (!isOnline(getBaseContext())) {
            Toast.makeText(this, R.string.data_connection_required, Toast.LENGTH_SHORT).show();
            return;
        }

        //Refresh the Layout when UpdateCheck finished
        UpdateCheckTask task = new UpdateCheckTask(this);
        task.execute((Void) null);
        updateLayout();
    }

    public void updateLayout() {
        FullUpdateInfo availableUpdates = null;
        try {
            availableUpdates = State.loadState(this);
        } catch (IOException e) {
            Log.e(TAG, "Unable to restore activity status", e);
        }

        // Read existing Updates
        List<String> existingFilenames = null;
        mUpdateFolder = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + Constants.UPDATES_FOLDER);
        FilenameFilter f = new UpdateFilter(".zip");
        File[] files = mUpdateFolder.listFiles(f);

        // If Folder Exists and Updates are present(with md5files)
        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory() && files != null && files.length > 0) {
            mSystemRom = SysUtils.getSystemProperty(Customization.SYS_PROP_MOD_VERSION);
            boolean deleteOld = getResources().getBoolean(R.bool.config_delete_old);

            existingFilenames = new ArrayList<String>();
            for (File file : files) {
                if (file.isFile()) {
                    String filename = file.getName();
                    String versionString = filename;
                    int prefixDash = filename.indexOf('-');
                    if(prefixDash != -1) {
                        versionString = filename.substring(prefixDash+1,filename.length());
                    }

                    if(deleteOld && SysUtils.compareVersionNumbers(versionString, mSystemRom) <= 0) {
                        deleteUpdate(filename);
                    } else {
                        existingFilenames.add(file.getName());
                    }
                }
            }
            //For sorting the Filenames, have to find a way to do natural sorting
            existingFilenames = Collections.synchronizedList(existingFilenames);
            Collections.sort(existingFilenames, Collections.reverseOrder());
        }
        files = null;

        // Clear the notification if one exists
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(R.string.not_new_updates_found_title);

        // Sets the Rom Variables
        List<UpdateInfo> availableRoms = null;
        if (availableUpdates != null) {
            if (availableUpdates.roms != null)
                availableRoms = availableUpdates.roms;
            //Add the incrementalUpdates
            if (availableUpdates.incrementalRoms != null)
                availableRoms.addAll(availableUpdates.incrementalRoms);
        }

        // Existing Updates Layout
        mLocalUpdates.clear();
        if (existingFilenames != null && existingFilenames.size() > 0) {
            for (String fileName : existingFilenames) {
                UpdateInfo ui = new UpdateInfo();
                ui.setName(fileName);
                ui.setFileName(fileName);
                mLocalUpdates.add(ui);
            }
        }

        // Available Roms Layout
        mServerUpdates.clear();
        if (availableRoms != null && availableRoms.size() > 0) {
            for (UpdateInfo rom:availableRoms) {

                // See if we have matching updates already downloaded
                boolean matched = false;
                for (UpdateInfo ui : mLocalUpdates) {
                    if (ui.getFileName().equals(rom.getFileName())) {
                        matched = true;
                    }
                }

                // Only add updates to the server list that are not already downloaded
                if (!matched) {
                    mServerUpdates.add(rom);
                }
            }
        }

        // Update the preference list
        refreshPreferences();
    }

    private String readLogFile(String filename) {
        StringBuilder text = new StringBuilder();

        File logFile = new File(mUpdateFolder + "/" + filename + ".changelog");
        try {
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            String line;

            while ((line = br.readLine()) != null) {
                text.append(line);
                text.append('\n');
            }
            br.close();
        } catch (IOException e) {
            return getString(R.string.no_changelog_alert);
        }

        return text.toString();
    }

    private void refreshPreferences() {
        if (mUpdatesList != null) {
            // Clear the list
            mUpdatesList.removeAll();
            boolean foundMatch;
            int style;

            // Convert the systemRom name to the associated filename
            String installedZip = mSystemRom.toString() + ".zip";

            // Add the server based updates
            // Since these will almost always be newer, they should appear at the top
            if (!mServerUpdates.isEmpty()) {
                for (UpdateInfo ui : mServerUpdates) {

                    // Determine the preference style and create the preference
                    foundMatch = ui.getFileName().equals(mFileName);
                    if (foundMatch) {
                        // In progress download
                        style = UpdatePreference.STYLE_DOWNLOADING;
                    } else if (ui.getFileName().equals(installedZip)) {
                        // This is the currently installed mod
                        style = UpdatePreference.STYLE_INSTALLED;
                    } else {
                        style = UpdatePreference.STYLE_NEW;
                    }

                    // Create a more user friendly title by stripping of the '-device.zip' at the end
                    String title = ui.getFileName().replace("-" + mSystemMod + ".zip", "");
                    UpdatePreference up = new UpdatePreference(this, ui, title, style);
                    up.setKey(ui.getFileName());

                    // If we have an in progress download, link the preference
                    if (foundMatch) {
                        mDownloadingPreference = up;
                        mUpdateHandler.post(updateProgress);
                        foundMatch = false;
                        mDownloading = true;
                    }

                    // Add to the list
                    mUpdatesList.addPreference(up);
                }
            }

            // Add the locally saved update files last
            // Since these will almost always be older versions, they should appear at the bottom
            if (!mLocalUpdates.isEmpty()) {
                for (UpdateInfo ui : mLocalUpdates) {

                    // Retrieve the changelog
                    ui.setChanges(readLogFile(ui.getFileName()));

                    // Create a more user friendly title by stripping of the '-device.zip' at the end
                    String title = ui.getFileName().replace("-" + mSystemMod + ".zip", "");
                    UpdatePreference up = new UpdatePreference(this, ui, title, ui.getFileName().equals(installedZip)
                            ? UpdatePreference.STYLE_INSTALLED : UpdatePreference.STYLE_DOWNLOADED);
                    up.setKey(ui.getFileName());

                    // Add to the list
                    mUpdatesList.addPreference(up);
                }
            }

            // If no updates are in the list, show the default message
            if (mUpdatesList.getPreferenceCount() == 0) {
                Preference npref = new Preference(this);
                npref.setLayoutResource(R.layout.preference_empty_list);
                npref.setTitle(R.string.no_available_updates_intro);
                npref.setEnabled(false);
                mUpdatesList.addPreference(npref);
            }
        }
    }

    public boolean deleteUpdate(String filename) {
        boolean success = false;
        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            File zipFileToDelete = new File(mUpdateFolder + "/" + filename);
            File logFileToDelete = new File(mUpdateFolder + "/" + filename + ".changelog");
            if (zipFileToDelete.exists()) {
                zipFileToDelete.delete();
            } else {
                Log.d(TAG, "Update to delete not found");
                return false;
            }
            if (logFileToDelete.exists()) {
                logFileToDelete.delete();
            }
            zipFileToDelete = null;
            logFileToDelete = null;

            success = true;
            Toast.makeText(this, MessageFormat.format(getResources().getString(R.string.delete_single_update_success_message),
                    filename), Toast.LENGTH_SHORT).show();

        } else if (!mUpdateFolder.exists()) {
            Toast.makeText(this, R.string.delete_updates_noFolder_message, Toast.LENGTH_SHORT).show();

        } else {
            Toast.makeText(this, R.string.delete_updates_failure_message, Toast.LENGTH_SHORT).show();
        }

        // Update the list
        updateLayout();
        return success;
    }

    private void scheduleUpdateService(int updateFrequency) {
        // Get the intent ready
        Intent i = new Intent(this, UpdateCheckService.class);
        i.putExtra(Constants.CHECK_FOR_UPDATE, true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);

        // Clear any old alarms before we start
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);

        // Check if we need to schedule a new alarm
        if (updateFrequency > 0) {
            Date lastCheck = new Date(mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0));
            am.setRepeating(AlarmManager.RTC_WAKEUP, lastCheck.getTime() + updateFrequency, updateFrequency, pi);
        }
    }

    private void confirmDeleteAll() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_delete_dialog_title);
        builder.setMessage(R.string.confirm_delete_all_dialog_message);
        builder.setPositiveButton(R.string.dialog_yes, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // We are OK to delete, trigger it
                deleteOldUpdates();
                updateLayout();
            }
        });
        builder.setNegativeButton(R.string.dialog_no, null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private boolean deleteOldUpdates() {
        boolean success;
        //mUpdateFolder: Foldername with fullpath of SDCARD
        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            deleteDir(mUpdateFolder);
            mUpdateFolder.mkdir();
            success = true;
            Toast.makeText(this, R.string.delete_updates_success_message, Toast.LENGTH_SHORT).show();
        } else if (!mUpdateFolder.exists()) {
            success = false;
            Toast.makeText(this, R.string.delete_updates_noFolder_message, Toast.LENGTH_SHORT).show();
        } else {
            success = false;
            Toast.makeText(this, R.string.delete_updates_failure_message, Toast.LENGTH_SHORT).show();
        }
        return success;
    }

    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (String aChildren : children) {
                boolean success = deleteDir(new File(dir, aChildren));
                if (!success) {
                    return false;
                }
            }
        }
        // The directory is now empty so delete it
        return dir.delete();
    }

    private void showSysInfo() {
        // Build the message
        Date lastCheck = new Date(mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0));
        String message = getString(R.string.sysinfo_device) + " " + mSystemMod + "\n\n"
                + getString(R.string.sysinfo_running)+ " "+ mSystemRom + "\n\n"
                + getString(R.string.sysinfo_last_check) + " " + lastCheck.toString();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.menu_system_info);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.dialog_ok, null);
        AlertDialog dialog = builder.create();
        dialog.show();
        ((TextView)dialog.findViewById(android.R.id.message)).setTextAppearance(this,
                android.R.style.TextAppearance_DeviceDefault_Small);
    }

    protected void startUpdate(final UpdateInfo updateInfo) {
        // Prevent the dialog from being triggered more than once
        if (mStartUpdateVisible) {
            return;
        } else {
            mStartUpdateVisible = true;
        }

        // Get the message body right
        String dialogBody = MessageFormat.format(
                getResources().getString(R.string.apply_update_dialog_text),
                updateInfo.getFileName());

        // Display the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.apply_update_dialog_title);
            builder.setMessage(dialogBody);
            builder.setPositiveButton(R.string.dialog_update, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    new Thread(new CopyAndStartUpdate(updateInfo)).start();
                }
            });
            builder.setNegativeButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    mStartUpdateVisible = false;
                }
            });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private String getStorageMountpoint() {        
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    private class CopyAndStartUpdate implements Runnable {
        private UpdateInfo updateInfo;

        CopyAndStartUpdate(UpdateInfo updateInfo) {
            this.updateInfo = updateInfo;
        }

        public void run() {
            try {
                final File cachedUpdateFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + Constants.UPDATES_FOLDER, updateInfo.getFileName());
                final File updateFile = new File("/cache/update.zip");
                updateFile.delete();		// Ensure we don't have an old version hanging around.    
                updateFile.createNewFile();            
                FileInputStream fis = new FileInputStream(cachedUpdateFile);
                FileOutputStream fos = new FileOutputStream(updateFile);
                try {
                	byte[] buffer = new byte[1024];
                	int read;
                	while((read = fis.read(buffer)) != -1) {
                		fos.write(buffer, 0, read);
                	}
                	fos.flush();
                } finally {
                    fos.close();
                    fis.close();
                }
                cachedUpdateFile.delete();
                RecoverySystem.installPackage(UpdatesSettings.this, updateFile);
            } catch (IOException e) {
                Log.e(TAG, "Unable to reboot into recovery mode:", e);
                Toast.makeText(UpdatesSettings.this, R.string.apply_unable_to_reboot_toast, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
