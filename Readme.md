OUYA fork of CyanogenMod CMUpdater
==================================

This is the fork of the CMUpdater for CyanogenMod used for providing
OTA functionality for OUYA consoles.

The main changes are;

* The default frequency is "check at boot".
* The update service can download updates automatically upon finding them.
* The update service sends a broadcast intent if it starts an automatic download.
* The update service can have its notifications disabled.
* The update service sends a broadcast intent if no new updates are available.
* The update service looks for the update information URL in a build property before using the configuration option.
* The update service uses a simple GET on the update URL with no parameters.
* The settings page doesn't show historical updates, only newer ones.
* The settings page responds to the no new updates broadcast with a dialog box.
* The settings page response to the auto download broadcast by starting the progress bar for the update.
* The update process has changed from being an out of process script to a Thread which copies the data into the /cache partition and uses the RecoverySystem object to reboot into recovery mode.
