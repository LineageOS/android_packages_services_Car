# AAE BugReport App

BugReport App for Android Automotive OS.

## Flow

1. User long presses Notification icon
2. It opens BugReportActivity as dialog under current user (e.g. u10)
3. BugReportActivity connects to BugReportService and checks if a bugreporting is running.
4. If bugreporting is already running it shows in progress dialog
5. Otherwise it creates MetaBugReport record in a local db and starts recording audio message.
6. When the submit button is clicked, it saves the audio message in temp directory and starts
   BugReportService.
7. If the drivers cancels the dialog, the BugReportActivity deletes temp directory and closes the
   activity.
8. BugReportService running under current user (e.g. u10) starts collecting logs using dumpstate,
    and when finished it updates MetaBugReport using BugStorageProvider.
9. BugStorageProvider is running under u0, it schedules UploadJob.
10. UploadJob runs SimpleUploaderAsyncTask to upload the bugreport.

Bug reports are zipped and uploaded to GCS. GCS enables creating Pub/Sub
notifications that can be used to track when new  bug reports are uploaded.

## System configuration

BugReport app uses `CarBugreportServiceManager` to collect bug reports and
screenshots. `CarBugreportServiceManager` allows only one bug report app to
use it's APIs, by default it's none.

To allow AAE BugReport app to access the API, you need to overlay
`config_car_bugreport_application` in `packages/services/Car/service/res/values/config.xml`
with value `com.google.android.car.bugreport`.

## App Configuration

UI and upload configs are located in `res/` directory. Resources can be
[overlayed](https://source.android.com/setup/develop/new-device#use-resource-overlays)
for specific products.

### Upload configuration

BugReport app uses `res/raw/gcs_credentials.json` for authentication and
`res/values/configs.xml` for obtaining GCS bucket name.
