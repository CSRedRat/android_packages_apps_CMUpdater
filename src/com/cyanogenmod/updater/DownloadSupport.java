package com.cyanogenmod.updater;

import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import com.cyanogenmod.updater.customTypes.UpdateInfo;
import com.cyanogenmod.updater.misc.Constants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Supports the downloading of updates
 */
public class DownloadSupport {

    /**
     * The broadcast made when a download starts
     */

    public static final String DOWNLOAD_STARTED_ACTION = "com.cyanogenmod.updater.UPDATE_DOWNLOAD_STARTED";

    /**
     * The extra holding the download manager ID
     */

    public static final String DOWNLOAD_STARTED_EXTRA_ID = "downloadId";

    /**
     * The extra holding the UpdateInfo
     */

    public static final String DOWNLOAD_STARTED_EXTRA_UPDATE_INFO = "updateInfo";

    /**
     * Log tag
     */

    private static final String TAG = "UpdateDownloadSupport";

    /**
     * Folder for storing updates and changelogs.
     */

    private static File sUpdateFolder =
            new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Constants.UPDATES_FOLDER);

    /**
     * The system download manager
     */
    private DownloadManager mDownloadManager;

    /**
     * The application name for requests.
     */
    private String mAppName;

    /**
     * The User Agent for requests
     */
    private String mUserAgent;

    /**
     * Constructor, extract the data required for the download headers.
     *
     * @param context The context in which we're operating.
     */

    public DownloadSupport(final Context context) {
        mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        mAppName = context.getString(R.string.app_name);
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            StringBuilder userAgent = new StringBuilder(packageInfo.packageName.length() + packageInfo.versionName.length()+1);
            userAgent.append(packageInfo.packageName);
            userAgent.append('/');
            userAgent.append(packageInfo.versionName);
            mUserAgent = userAgent.toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException nnfe) {
            mUserAgent = "CMUpdater";
        }
    }

    /**
     * Start the downloading of an update.
     *
     * @param ui The update to download.
     * @return The download ID from the DownloadManager.
     */

    public long startDownload(final UpdateInfo ui) {
        // Create the download request and set some basic parameters
        String fullFolderPath = Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/" + Constants.UPDATES_FOLDER;
        //If directory doesn't exist, create it
        File directory = new File(fullFolderPath);
        if (!directory.exists()) {
            if(!directory.mkdirs()) {
                Log.e(TAG, "Unable to create UpdateFolder");
            }
            Log.d(TAG, "UpdateFolder created");
        }

        // Save the Changelog content to the sdcard for later use
        writeLogFile(ui.getFileName(), ui.getChanges());

        // Build the name of the file to download, adding .partial at the end.  It will get
        // stripped off when the download completes
        String fullFilePath = "file://" + fullFolderPath + "/" + ui.getFileName() + ".partial";
        Log.i(TAG, "Getting "+ui.getDownloadUrl());
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(ui.getDownloadUrl()));
        request.addRequestHeader("Cache-Control", "no-cache");
        request.addRequestHeader("User-Agent", mUserAgent);
        request.setTitle(mAppName);
        request.setDescription(ui.getFileName());
        request.setDestinationUri(Uri.parse(fullFilePath));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);

        return mDownloadManager.enqueue(request);
    }

    /**
     * Write the changelog to a file in the updates folder.
     *
     * @param filename The filename to write the log to.
     * @param log The log to write.
     */

    private void writeLogFile(final String filename, final String log) {
        File logFile = new File(sUpdateFolder, filename + ".changelog");
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(logFile));
            bw.write(log);
            bw.close();
        } catch (IOException e) {
            Log.e(TAG, "File write failed: " + e.toString());
        }
    }

}
