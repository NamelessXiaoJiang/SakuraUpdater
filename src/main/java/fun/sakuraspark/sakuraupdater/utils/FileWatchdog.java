package fun.sakuraspark.sakuraupdater.utils;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

public class FileWatchdog {

    private WatchService watcher;

    private Runnable fileChangeHandler;

    private boolean is_file_changed = false;

    public FileWatchdog() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return;
        }
        // run FileWatchdog in a separate thread
        Thread thread = new Thread(() -> {
            while (true) {
                WatchKey key;
                try {
                    key = watcher.take();
                    key.reset();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    e.printStackTrace();
                    break; // watcher closed, exit the loop
                }
                if (!key.pollEvents().isEmpty()) {
                    fileChanged();
                }
                key.reset();
                if (!key.isValid()) {
                    break;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void registerPath(Path path) {
        try {
            path.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void fileChanged() {
        synchronized (this) {
            is_file_changed = true;
        }

        if (fileChangeHandler != null && is_file_changed) {
            fileChangeHandler.run();
        }
    }

    public void setFileChangeHandler(Runnable fileChangeHandler) {
        this.fileChangeHandler = fileChangeHandler;
    }

    public boolean isFileChanged() {
        return is_file_changed;
    }

    // call this method after handled the file change
    public void handled() {
        synchronized (this) {
            is_file_changed = false;
        }
    }

}
