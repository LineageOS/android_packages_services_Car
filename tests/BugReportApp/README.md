# AAE BugReport App

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
