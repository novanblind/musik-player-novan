package com.novanmusik;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 501;
    private static final int NOTIF_ID = 101;
    private static final String CHANNEL_ID = "channel_pemutar_musik";

    private static final String ACTION_PREV = "com.novanmusik.ACTION_PREV";
    private static final String ACTION_REWIND = "com.novanmusik.ACTION_REWIND";
    private static final String ACTION_TOGGLE = "com.novanmusik.ACTION_TOGGLE";
    private static final String ACTION_FORWARD = "com.novanmusik.ACTION_FORWARD";
    private static final String ACTION_NEXT = "com.novanmusik.ACTION_NEXT";

    private static final String PREFS = "novan_folder_audio_player_prefs";
    private static final String KEY_FIRST_SCAN_DONE = "first_scan_done";
    private static final String KEY_CACHED_SONGS = "cached_songs_list";
    private static final String KEY_LAST_PATH = "last_played_path";
    private static final String KEY_LAST_POS = "last_played_pos";
    private static final String KEY_FOLDER = "last_folder_path";
    private static final String KEY_FAVORITES = "favorite_songs";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    private SharedPreferences prefs;
    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private MediaSession mediaSession;

    private MediaPlayer player;
    private MediaPlayer nextPlayer;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private PresetReverb reverb;

    private final List<String> allSongs = new ArrayList<>();
    private final List<String> visibleSongs = new ArrayList<>();
    private final List<FolderData> folders = new ArrayList<>();

    private int currentIndex = -1;
    private String currentPath = "";
    private String currentFolderPath = "";
    private String currentFolderName = "Semua Lagu";
    private boolean userSeeking;
    private boolean crossfading;
    private boolean destroyed;
    private boolean loopAB;
    private int loopA;
    private int loopB;
    private float pitch = 1.0f;
    private float speed = 1.0f;
    private long sleepAt = 0L;

    private TextView titleView;
    private TextView folderView;
    private TextView songTitleView;
    private TextView timerView;
    private SeekBar progress;
    private Button playButton;
    private Button repeatButton;
    private Button rewindButton;
    private Button forwardButton;

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String act = intent.getAction();
            if (ACTION_TOGGLE.equals(act)) {
                togglePlay();
            } else if (ACTION_PREV.equals(act)) {
                previous();
            } else if (ACTION_NEXT.equals(act)) {
                next();
            } else if (ACTION_REWIND.equals(act)) {
                seekRelative(-seekStep());
            } else if (ACTION_FORWARD.equals(act)) {
                seekRelative(seekStep());
            }
        }
    };

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            updateUiAndTransitions();
            if (!destroyed) handler.postDelayed(this, 500);
        }
    };

    private static class FolderData {
        String name;
        String path;
        List<String> songs = new ArrayList<>();
        FolderData(String name, String path) { this.name = name; this.path = path; }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        buildUi();
        initMediaSession();
        createNotificationChannel();
        registerControlReceiver();

        initMusicLibrary();
        handler.post(timerRunnable);
    }

    private void registerControlReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PREV);
        filter.addAction(ACTION_REWIND);
        filter.addAction(ACTION_TOGGLE);
        filter.addAction(ACTION_FORWARD);
        filter.addAction(ACTION_NEXT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT >= 21) {
            mediaSession = new MediaSession(this, "NovanMediaSession");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() { togglePlay(); }
                @Override
                public void onPause() { togglePlay(); }
                @Override
                public void onSkipToNext() { next(); }
                @Override
                public void onSkipToPrevious() { previous(); }
                @Override
                public void onFastForward() { seekRelative(seekStep()); }
                @Override
                public void onRewind() { seekRelative(-seekStep()); }
                @Override
                public void onSeekTo(long pos) {
                    if (player != null) {
                        player.seekTo((int) pos);
                        updateNotification();
                    }
                }
                @Override
                public void onStop() { finishAndStop(); }
            });
            mediaSession.setActive(true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Kontrol Musik", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Menampilkan tombol kontrol lagu di notifikasi dan layar kunci");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(ch);
        }
    }

    private void updateNotification() {
        if (notificationManager == null) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        boolean isPlaying = (player != null && player.isPlaying());
        String songName = (currentIndex >= 0 && currentIndex < visibleSongs.size())
                ? new File(visibleSongs.get(currentIndex)).getName()
                : "Pemutar Musik Folder";

        int flags = (Build.VERSION.SDK_INT >= 23) ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;

        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pContent = PendingIntent.getActivity(this, 0, contentIntent, flags);

        PendingIntent pPrev = PendingIntent.getBroadcast(this, 1, new Intent(ACTION_PREV).setPackage(getPackageName()), flags);
        PendingIntent pRew = PendingIntent.getBroadcast(this, 2, new Intent(ACTION_REWIND).setPackage(getPackageName()), flags);
        PendingIntent pToggle = PendingIntent.getBroadcast(this, 3, new Intent(ACTION_TOGGLE).setPackage(getPackageName()), flags);
        PendingIntent pFwd = PendingIntent.getBroadcast(this, 4, new Intent(ACTION_FORWARD).setPackage(getPackageName()), flags);
        PendingIntent pNext = PendingIntent.getBroadcast(this, 5, new Intent(ACTION_NEXT).setPackage(getPackageName()), flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        int appIcon = getApplicationInfo().icon != 0 ? getApplicationInfo().icon : android.R.drawable.ic_media_play;

        builder.setContentTitle(songName)
               .setContentText(currentFolderName)
               .setSmallIcon(appIcon)
               .setContentIntent(pContent)
               .setVisibility(Notification.VISIBILITY_PUBLIC)
               .setOngoing(isPlaying)
               .setCategory(Notification.CATEGORY_TRANSPORT)
               .addAction(android.R.drawable.ic_media_previous, "Sebelumnya", pPrev)
               .addAction(android.R.drawable.ic_media_rew, "Mundur", pRew)
               .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, isPlaying ? "Jeda" : "Putar", pToggle)
               .addAction(android.R.drawable.ic_media_ff, "Maju", pFwd)
               .addAction(android.R.drawable.ic_media_next, "Selanjutnya", pNext);

        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 2, 4);
            builder.setStyle(style);

            long state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            long pos = (player != null) ? player.getCurrentPosition() : 0;
            long dur = (player != null) ? player.getDuration() : 0;

            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                                PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND |
                                PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                    .setState((int)state, pos, speed)
                    .build());

            MediaMetadata.Builder meta = new MediaMetadata.Builder();
            meta.putString(MediaMetadata.METADATA_KEY_TITLE, songName);
            meta.putString(MediaMetadata.METADATA_KEY_ARTIST, currentFolderName);
            meta.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            mediaSession.setMetadata(meta.build());
            mediaSession.setActive(true);
        }

        try {
            notificationManager.notify(NOTIF_ID, builder.build());
        } catch (Exception ignored) {}
    }

    private void initMusicLibrary() {
        boolean firstDone = prefs.getBoolean(KEY_FIRST_SCAN_DONE, false);
        if (firstDone) {
            if (!loadCachedSongs()) {
                requestPermissionsAndScan();
            }
        } else {
            requestPermissionsAndScan();
        }
    }

    private boolean loadCachedSongs() {
        String saved = prefs.getString(KEY_CACHED_SONGS, "");
        if (saved.isEmpty()) return false;
        String[] lines = saved.split("\n");
        List<String> loaded = new ArrayList<>();
        for (String p : lines) {
            if (!p.trim().isEmpty() && new File(p).isFile()) {
                loaded.add(p);
            }
        }
        if (loaded.isEmpty()) return false;
        allSongs.clear();
        allSongs.addAll(loaded);
        visibleSongs.clear();
        visibleSongs.addAll(loaded);
        buildFolders();

        currentFolderPath = prefs.getString(KEY_FOLDER, "");
        if (!currentFolderPath.isEmpty()) {
            selectFolderByPath(currentFolderPath, false);
        } else {
            currentFolderName = "Semua Lagu";
        }
        folderView.setText("Folder: " + currentFolderName);

        String last = prefs.getString(KEY_LAST_PATH, "");
        if (!last.isEmpty() && new File(last).isFile()) {
            currentPath = last;
            currentIndex = visibleSongs.indexOf(last);
            if (currentIndex >= 0 && songTitleView != null) {
                songTitleView.setText(new File(last).getName());
            }
        }
        return true;
    }

    private void requestPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= 33) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), REQ_AUDIO);
            } else {
                scanAsync(false);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_AUDIO);
            } else {
                scanAsync(false);
            }
        } else {
            scanAsync(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        boolean firstDone = prefs.getBoolean(KEY_FIRST_SCAN_DONE, false);
        if (!firstDone) {
            scanAsync(false);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 12, 18, 18);

        titleView = text("Pemutar Musik Folder", 20, Gravity.CENTER);
        root.addView(titleView);
        folderView = text("Folder: " + currentFolderName, 14, Gravity.CENTER);
        root.addView(folderView);
        songTitleView = text("Belum ada lagu diputar", 16, Gravity.CENTER);
        root.addView(songTitleView);
        timerView = text("0:00 / 0:00", 14, Gravity.CENTER);
        root.addView(timerView);

        progress = new SeekBar(this);
        progress.setMax(1000);
        root.addView(progress);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    try {
                        int d = player.getDuration();
                        if (d > 0) timerView.setText(formatTime((long)d * p / 1000) + " / " + formatTime(d));
                    } catch (Exception ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) {
                userSeeking = false;
                seekPercent(b.getProgress());
            }
        });

        LinearLayout row1 = row();
        Button prev = button("SEBELUM");
        rewindButton = button("MUNDUR 10D");
        playButton = button("PUTAR");
        forwardButton = button("MAJU 10D");
        Button next = button("LANJUT");
        row1.addView(prev, weight(1)); row1.addView(rewindButton, weight(1));
        row1.addView(playButton, weight(1.2f)); row1.addView(forwardButton, weight(1)); row1.addView(next, weight(1));
        root.addView(row1);

        LinearLayout row2 = row();
        Button foldersButton = button("FOLDER");
        Button songsButton = button("DAFTAR LAGU");
        repeatButton = button("ULANG: MATI");
        row2.addView(foldersButton, weight(1)); row2.addView(songsButton, weight(1)); row2.addView(repeatButton, weight(1));
        root.addView(row2);

        LinearLayout row3 = row();
        Button search = button("CARI");
        Button all = button("SEMUA");
        Button library = button("PUSTAKA");
        Button favorite = button("+ FAVORIT");
        row3.addView(search, weight(1)); row3.addView(all, weight(1)); row3.addView(library, weight(1)); row3.addView(favorite, weight(1));
        root.addView(row3);

        LinearLayout row4 = row();
        Button scan = button("PINDAI");
        Button settings = button("SETELAN");
        Button exit = button("HENTIKAN");
        row4.addView(scan, weight(1)); row4.addView(settings, weight(1)); row4.addView(exit, weight(1));
        root.addView(row4);

        setContentView(root);

        playButton.setOnClickListener(v -> togglePlay());
        prev.setOnClickListener(v -> previous());
        next.setOnClickListener(v -> next());
        rewindButton.setOnClickListener(v -> seekRelative(-seekStep()));
        forwardButton.setOnClickListener(v -> seekRelative(seekStep()));
        repeatButton.setOnClickListener(v -> cycleRepeat());
        foldersButton.setOnClickListener(v -> showFolders());
        songsButton.setOnClickListener(v -> showSongs());
        search.setOnClickListener(v -> searchSongs());
        all.setOnClickListener(v -> showAll());
        library.setOnClickListener(v -> showLibrary());
        favorite.setOnClickListener(v -> addFavorite());
        scan.setOnClickListener(v -> scanAsync(false));
        settings.setOnClickListener(v -> showSettings());
        exit.setOnClickListener(v -> finishAndStop());
    }

    private TextView text(String s, int size, int gravity) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setGravity(gravity); t.setPadding(4, 7, 4, 7); return t;
    }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(12); return b; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w); }

    private void scanAsync(boolean silent) {
        if (!silent) Toast.makeText(this, "Memindai musik...", Toast.LENGTH_SHORT).show();
        scanExecutor.execute(() -> {
            List<String> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            ContentResolver r = getContentResolver();
            String[] projection = {MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.IS_MUSIC, MediaStore.Audio.Media.DISPLAY_NAME};
            try (Cursor c = r.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                if (c != null) while (c.moveToNext()) {
                    int data = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                    if (data >= 0) {
                        String p = c.getString(data);
                        if (p != null && !p.isEmpty() && seen.add(p) && new File(p).isFile()) found.add(p);
                    }
                }
            } catch (Exception ignored) {}
            Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
            handler.post(() -> applyScan(found, silent));
        });
    }

    private void applyScan(List<String> found, boolean silent) {
        allSongs.clear();
        allSongs.addAll(found);
        visibleSongs.clear();
        visibleSongs.addAll(found);
        buildFolders();

        StringBuilder sb = new StringBuilder();
        for (String p : found) sb.append(p).append('\n');
        prefs.edit().putBoolean(KEY_FIRST_SCAN_DONE, true).putString(KEY_CACHED_SONGS, sb.toString()).apply();

        currentFolderPath = prefs.getString(KEY_FOLDER, "");
        if (!currentFolderPath.isEmpty()) selectFolderByPath(currentFolderPath, false);
        else currentFolderName = "Semua Lagu";
        folderView.setText("Folder: " + currentFolderName);

        if (!silent) Toast.makeText(this, "Selesai: " + allSongs.size() + " lagu, " + folders.size() + " folder.", Toast.LENGTH_LONG).show();

        String last = prefs.getString(KEY_LAST_PATH, "");
        if (!last.isEmpty() && new File(last).isFile()) {
            currentPath = last;
            currentIndex = visibleSongs.indexOf(last);
            if (currentIndex >= 0 && songTitleView != null) {
                songTitleView.setText(new File(last).getName());
            }
        }
    }

    private void buildFolders() {
        folders.clear();
        for (String p : allSongs) {
            File f = new File(p);
            String parent = f.getParent();
            if (parent == null) continue;
            FolderData fd = null;
            for (FolderData x : folders) {
                if (x.path.equals(parent)) { fd = x; break; }
            }
            if (fd == null) {
                fd = new FolderData(new File(parent).getName(), parent);
                folders.add(fd);
            }
            fd.songs.add(p);
        }
        Collections.sort(folders, Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
    }

    private void showFolders() {
        if (folders.isEmpty()) { Toast.makeText(this, "Belum ada folder. Tekan PINDAI.", Toast.LENGTH_SHORT).show(); return; }
        List<String> names = new ArrayList<>();
        names.add("Semua Folder (" + allSongs.size() + " lagu)");
        for (FolderData f : folders) names.add(f.name + " (" + f.songs.size() + " lagu)");
        new AlertDialog.Builder(this).setTitle("Pilih Folder Musik").setItems(names.toArray(new String[0]), (d, which) -> {
            if (which == 0) { showAll(); return; }
            FolderData f = folders.get(which - 1);
            selectFolder(f, true);
        }).setNegativeButton("Batal", null).show();
    }

    private void selectFolder(FolderData f, boolean offerPlay) {
        currentFolderPath = f.path;
        currentFolderName = f.name;
        visibleSongs.clear();
        visibleSongs.addAll(f.songs);
        currentIndex = -1;
        prefs.edit().putString(KEY_FOLDER, f.path).apply();
        folderView.setText("Folder: " + f.name);
        if (offerPlay) new AlertDialog.Builder(this).setTitle(f.name).setItems(folderSongNames(f), (d, w) -> {
            currentIndex = w;
            playPath(visibleSongs.get(currentIndex), 0, false);
        }).setNegativeButton("Batal", null).show();
    }

    private String[] folderSongNames(FolderData f) {
        String[] a = new String[f.songs.size()];
        for (int i = 0; i < f.songs.size(); i++) a[i] = (i + 1) + ". " + new File(f.songs.get(i)).getName();
        return a;
    }

    private void selectFolderByPath(String path, boolean speak) {
        for (FolderData f : folders) {
            if (f.path.equals(path)) {
                currentFolderName = f.name;
                visibleSongs.clear();
                visibleSongs.addAll(f.songs);
                if (speak) folderView.setText("Folder: " + f.name);
                return;
            }
        }
        visibleSongs.clear();
        visibleSongs.addAll(allSongs);
        currentFolderPath = "";
        currentFolderName = "Semua Lagu";
    }

    private void showSongs() {
        if (visibleSongs.isEmpty()) { Toast.makeText(this, "Daftar lagu kosong.", Toast.LENGTH_SHORT).show(); return; }
        String[] a = new String[visibleSongs.size()];
        for (int i = 0; i < a.length; i++) a[i] = (i + 1) + ". " + new File(visibleSongs.get(i)).getName();
        new AlertDialog.Builder(this).setTitle("Daftar: " + currentFolderName).setItems(a, (d, w) -> {
            currentIndex = w;
            playPath(visibleSongs.get(w), 0, false);
        }).setNegativeButton("Tutup", null).show();
    }

    private void searchSongs() {
        EditText e = new EditText(this);
        e.setHint("Ketik nama lagu");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Cari Musik").setView(e).setPositiveButton("Cari", (d, w) -> {
            String q = e.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) return;
            visibleSongs.clear();
            for (String p : allSongs) {
                if (new File(p).getName().toLowerCase(Locale.ROOT).contains(q)) visibleSongs.add(p);
            }
            currentFolderName = "Hasil Cari (" + q + ")";
            currentFolderPath = "SEARCH";
            folderView.setText("Folder: " + currentFolderName);
            Toast.makeText(this, "Ditemukan " + visibleSongs.size() + " hasil.", Toast.LENGTH_SHORT).show();
        }).setNegativeButton("Batal", null).show();
    }

    private void showAll() {
        visibleSongs.clear();
        visibleSongs.addAll(allSongs);
        currentFolderPath = "";
        currentFolderName = "Semua Lagu";
        currentIndex = -1;
        prefs.edit().remove(KEY_FOLDER).apply();
        folderView.setText("Folder: Semua Lagu");
    }

    private void showLibrary() {
        String[] o = {"Pilih dari Folder", "Lagu Terakhir Diputar", "Daftar Favorit"};
        new AlertDialog.Builder(this).setTitle("Pustaka Audio").setItems(o, (d, w) -> {
            if (w == 0) {
                showFolders();
            } else if (w == 1) {
                String p = prefs.getString(KEY_LAST_PATH, "");
                int pos = prefs.getInt(KEY_LAST_POS, 0);
                if (new File(p).isFile()) {
                    currentIndex = visibleSongs.indexOf(p);
                    if (currentIndex < 0) {
                        visibleSongs.clear();
                        visibleSongs.addAll(allSongs);
                        currentIndex = visibleSongs.indexOf(p);
                    }
                    playPath(p, pos, true);
                } else Toast.makeText(this, "Tidak ada lagu terakhir.", Toast.LENGTH_SHORT).show();
            } else {
                showFavorites();
            }
        }).show();
    }

    private Set<String> favoriteSet() {
        String raw = prefs.getString(KEY_FAVORITES, "");
        Set<String> s = new HashSet<>();
        for (String x : raw.split("\n")) if (!x.isEmpty()) s.add(x);
        return s;
    }

    private void saveFavorites(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(x).append('\n');
        prefs.edit().putString(KEY_FAVORITES, b.toString()).apply();
    }

    private void addFavorite() {
        if (currentIndex < 0 || currentIndex >= visibleSongs.size()) return;
        Set<String> s = favoriteSet();
        String p = visibleSongs.get(currentIndex);
        if (s.add(p)) {
            saveFavorites(s);
            Toast.makeText(this, "Ditambahkan ke Favorit.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Lagu sudah menjadi Favorit.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFavorites() {
        Set<String> s = favoriteSet();
        List<String> list = new ArrayList<>();
        for (String p : s) if (new File(p).isFile()) list.add(p);
        if (list.isEmpty()) { Toast.makeText(this, "Favorit kosong.", Toast.LENGTH_SHORT).show(); return; }
        String[] a = new String[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = (i + 1) + ". " + new File(list.get(i)).getName();
        new AlertDialog.Builder(this).setTitle("Favorit").setItems(a, (d, w) -> {
            visibleSongs.clear();
            visibleSongs.addAll(list);
            currentFolderName = "Favorit";
            currentFolderPath = "FAVORITES";
            currentIndex = w;
            folderView.setText("Folder: Favorit");
            playPath(list.get(w), 0, false);
        }).setNegativeButton("Tutup", null).show();
    }

    private void togglePlay() {
        if (player == null) {
            if (visibleSongs.isEmpty()) {
                Toast.makeText(this, "Daftar lagu kosong. Tekan PINDAI terlebih dahulu.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean resume = prefs.getBoolean("pref_resume", false);
            String last = prefs.getString(KEY_LAST_PATH, "");
            int pos = prefs.getInt(KEY_LAST_POS, 0);
            if (resume && new File(last).isFile()) {
                int i = visibleSongs.indexOf(last);
                if (i >= 0) currentIndex = i;
                playPath(last, pos, true);
            } else {
                currentIndex = 0;
                playPath(visibleSongs.get(0), 0, true);
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                playButton.setText("PUTAR");
                prefs.edit().putInt(KEY_LAST_POS, player.getCurrentPosition()).apply();
            } else {
                player.start();
                playButton.setText("JEDA");
            }
        } catch (Exception e) {
            releasePlayer();
        }
        updateNotification();
    }

    private void playPath(String path, int startMs, boolean immediate) {
        if (path == null || path.isEmpty()) return;
        if (!immediate && player != null && player.isPlaying() && prefs.getBoolean("pref_crossfade", true)) {
            startCrossfade(path, currentIndex, crossfadeDuration());
            return;
        }
        releaseNext();
        releaseEffects();
        releasePlayer();
        try {
            player = new MediaPlayer();
            configurePlayer(player);
            player.setDataSource(path);
            player.prepare();
            if (startMs > 0) player.seekTo(startMs);
            player.setVolume(1f, 1f);
            player.start();

            currentPath = path;
            currentIndex = visibleSongs.indexOf(path);
            if (currentIndex < 0) currentIndex = 0;

            playButton.setText("JEDA");
            prefs.edit().putString(KEY_LAST_PATH, path).putInt(KEY_LAST_POS, startMs).apply();
            songTitleView.setText(new File(path).getName());

            applyPlaybackParams();
            applyEffects();
            prepareNext();
            updateNotification();
        } catch (Exception e) {
            releasePlayer();
            Toast.makeText(this, "Berkas audio tidak dapat diputar.", Toast.LENGTH_SHORT).show();
        }
    }

    private void configurePlayer(MediaPlayer p) {
        if (Build.VERSION.SDK_INT >= 21) {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
        }
        p.setOnCompletionListener(mp -> onCompleted());
        p.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Berkas audio bermasalah, dilewati.", Toast.LENGTH_SHORT).show();
            next();
            return true;
        });
    }

    private void onCompleted() {
        if (loopAB) {
            try {
                player.seekTo(loopA);
                player.start();
            } catch (Exception ignored) {}
            return;
        }
        int n = nextIndex();
        if (n >= 0 && n < visibleSongs.size()) {
            currentIndex = n;
            playPath(visibleSongs.get(n), 0, false);
        } else {
            playButton.setText("PUTAR");
            updateNotification();
        }
    }

    private int nextIndex() {
        if (visibleSongs.isEmpty()) return -1;
        int repeat = prefs.getInt("pref_repeat", 0);
        int shuffle = prefs.getInt("pref_shuffle", 0);
        if (repeat == 1) return currentIndex;
        if (shuffle == 1 && visibleSongs.size() > 1) {
            int n;
            do { n = random.nextInt(visibleSongs.size()); } while (n == currentIndex);
            return n;
        }
        if (currentIndex + 1 < visibleSongs.size()) return currentIndex + 1;
        if (repeat == 2) return 0;
        return -1;
    }

    private void previous() {
        if (currentIndex > 0) {
            currentIndex--;
            playPath(visibleSongs.get(currentIndex), 0, false);
        } else if (!visibleSongs.isEmpty()) {
            currentIndex = visibleSongs.size() - 1;
            playPath(visibleSongs.get(currentIndex), 0, false);
        }
    }

    private void next() {
        int n = nextIndex();
        if (n >= 0) {
            currentIndex = n;
            playPath(visibleSongs.get(n), 0, false);
        }
    }

    private void cycleRepeat() {
        int r = (prefs.getInt("pref_repeat", 0) + 1) % 3;
        prefs.edit().putInt("pref_repeat", r).apply();
        repeatButton.setText(new String[]{"ULANG: MATI", "ULANG: LAGU", "ULANG: FOLDER"}[r]);
    }

    private long seekStep() {
        int i = prefs.getInt("pref_seek_step", 1);
        return new long[]{5000, 10000, 15000, 30000}[Math.max(0, Math.min(3, i))];
    }

    private void seekRelative(long delta) {
        if (player == null) return;
        try {
            int p = (int) Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + delta));
            player.seekTo(p);
            prefs.edit().putInt(KEY_LAST_POS, p).apply();
            updateNotification();
        } catch (Exception ignored) {}
    }

    private void seekPercent(int value) {
        if (player == null) return;
        try {
            int p = (int) ((long) player.getDuration() * value / 1000);
            player.seekTo(p);
            prefs.edit().putInt(KEY_LAST_POS, p).apply();
            updateNotification();
        } catch (Exception ignored) {}
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(30, 10, 30, 10);
        addSpinner(box, "Shuffle", new String[]{"Mati", "Acak Folder", "Acak Semua"}, "pref_shuffle", 0);
        addSpinner(box, "Repeat", new String[]{"Mati", "Ulangi Lagu", "Ulangi Folder"}, "pref_repeat", 0);
        addSpinner(box, "Resume", new String[]{"Mati", "Hidup"}, "pref_resume", 0);
        addSpinner(box, "Kecepatan", new String[]{"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"}, "pref_speed", 2);
        addSpinner(box, "Durasi Mundur/Maju", new String[]{"5 Detik", "10 Detik", "15 Detik", "30 Detik"}, "pref_seek_step", 1);
        addSpinner(box, "Crossfade", new String[]{"Mati", "Hidup"}, "pref_crossfade", 1);
        addSpinner(box, "Durasi Crossfade", new String[]{"3 Detik", "5 Detik", "8 Detik", "10 Detik", "12 Detik", "15 Detik"}, "pref_crossfade_dur", 1);
        addSpinner(box, "Equalizer", new String[]{"Normal", "Hip Hop", "Rock", "Pop", "Jazz", "Klasik"}, "pref_equalizer", 0);
        addSpinner(box, "Gema", new String[]{"Tidak Ada", "Ruangan Kecil", "Ruangan Sedang", "Aula Besar"}, "pref_echo", 0);
        addSpinner(box, "Bass Boost", new String[]{"Mati", "Rendah", "Sedang", "Kuat"}, "pref_bass", 0);
        addSpinner(box, "Sleep Timer", new String[]{"Mati", "10 Menit", "15 Menit", "30 Menit", "45 Menit", "60 Menit"}, "pref_sleep_timer", 0);

        Button pitchBtn = button("ATUR NADA & TEMPO");
        pitchBtn.setOnClickListener(v -> showPitchSpeed());
        box.addView(pitchBtn);

        Button ab = button("PENGULANGAN A-B");
        ab.setOnClickListener(v -> showAB());
        box.addView(ab);

        new AlertDialog.Builder(this).setTitle("Pengaturan").setView(box).setPositiveButton("Simpan", (d, w) -> applyAllSettings(box)).setNegativeButton("Batal", null).show();
    }

    private void addSpinner(LinearLayout box, String label, String[] items, String key, int def) {
        TextView l = text(label, 14, Gravity.START);
        box.addView(l);
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(prefs.getInt(key, def));
        s.setTag(key);
        box.addView(s);
    }

    private void applyAllSettings(LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof Spinner && v.getTag() != null) {
                prefs.edit().putInt(String.valueOf(v.getTag()), ((Spinner) v).getSelectedItemPosition()).apply();
            }
        }
        applyPlaybackParams();
        applyEffects();
        scheduleSleepTimer();
        int s = prefs.getInt("pref_seek_step", 1);
        rewindButton.setText("MUNDUR " + new String[]{"5D", "10D", "15D", "30D"}[Math.max(0, Math.min(3, s))]);
        forwardButton.setText("MAJU " + new String[]{"5D", "10D", "15D", "30D"}[Math.max(0, Math.min(3, s))]);
    }

    private void scheduleSleepTimer() {
        int i = prefs.getInt("pref_sleep_timer", 0);
        long[] mins = {0, 10, 15, 30, 45, 60};
        if (i < 0 || i >= mins.length) i = 0;
        sleepAt = mins[i] > 0 ? System.currentTimeMillis() + mins[i] * 60000L : 0;
    }

    private void showPitchSpeed() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(30, 20, 30, 20);
        TextView t = text("Nada saat ini: " + String.format(Locale.US, "%.1f", pitch), 15, Gravity.CENTER);
        box.addView(t);
        Button down = button("NADA -");
        Button up = button("NADA +");
        LinearLayout r = row();
        r.addView(down, weight(1));
        r.addView(up, weight(1));
        box.addView(r);
        down.setOnClickListener(v -> {
            pitch = Math.max(.5f, pitch - .1f);
            t.setText("Nada saat ini: " + String.format(Locale.US, "%.1f", pitch));
            applyPlaybackParams();
        });
        up.setOnClickListener(v -> {
            pitch = Math.min(2f, pitch + .1f);
            t.setText("Nada saat ini: " + String.format(Locale.US, "%.1f", pitch));
            applyPlaybackParams();
        });
        new AlertDialog.Builder(this).setTitle("Nada & Tempo").setView(box).setPositiveButton("Selesai", null).show();
    }

    private void showAB() {
        String[] o = {"Tetapkan A sekarang", "Tetapkan B sekarang", "Hapus A-B"};
        new AlertDialog.Builder(this).setTitle("Pengulangan A-B").setItems(o, (d, w) -> {
            try {
                if (player == null) return;
                if (w == 0) {
                    loopA = player.getCurrentPosition();
                    Toast.makeText(this, "Titik A ditetapkan.", Toast.LENGTH_SHORT).show();
                } else if (w == 1) {
                    loopB = player.getCurrentPosition();
                    if (loopB > loopA) loopAB = true;
                    else Toast.makeText(this, "B harus lebih besar dari A.", Toast.LENGTH_SHORT).show();
                } else {
                    loopAB = false;
                    loopA = loopB = 0;
                }
            } catch (Exception ignored) {}
        }).show();
    }

    private void applyPlaybackParams() {
        if (player == null || Build.VERSION.SDK_INT < 23) return;
        try {
            PlaybackParams p;
            try {
                p = player.getPlaybackParams();
            } catch (Exception e) {
                p = new PlaybackParams();
            }
            p.setPitch(pitch);
            p.setSpeed(speedFromPref());
            player.setPlaybackParams(p);
        } catch (Exception ignored) {}
    }

    private float speedFromPref() {
        int i = prefs.getInt("pref_speed", 2);
        return new float[]{.5f, .75f, 1f, 1.25f, 1.5f, 2f}[Math.max(0, Math.min(5, i))];
    }

    private void applyEffects() {
        releaseEffects();
        if (player == null) return;
        int sid = player.getAudioSessionId();

        try {
            equalizer = new Equalizer(0, sid);
            int eq = prefs.getInt("pref_equalizer", 0);
            equalizer.setEnabled(eq > 0);
            if (equalizer.getNumberOfBands() > 0) {
                short[] range = equalizer.getBandLevelRange();
                for (short b = 0; b < equalizer.getNumberOfBands(); b++) {
                    short level = (short) Math.max(range[0], Math.min(range[1], eqCurve(eq, b, equalizer.getNumberOfBands())));
                    equalizer.setBandLevel(b, level);
                }
            }
        } catch (Exception ignored) {}

        try {
            bassBoost = new BassBoost(0, sid);
            int bass = prefs.getInt("pref_bass", 0);
            bassBoost.setStrength((short) new int[]{0, 300, 600, 900}[Math.max(0, Math.min(3, bass))]);
            bassBoost.setEnabled(bass > 0);
        } catch (Exception ignored) {}

        try {
            reverb = new PresetReverb(0, sid);
            int rv = prefs.getInt("pref_echo", 0);
            reverb.setPreset((short) new int[]{0, 1, 2, 5}[Math.max(0, Math.min(3, rv))]);
            reverb.setEnabled(rv > 0);
        } catch (Exception ignored) {}
    }

    private short eqCurve(int eq, int band, int n) {
        float[] c;
        switch (eq) {
            case 1: c = new float[]{7, 2, -3}; break;
            case 2: c = new float[]{6, -1, 4}; break;
            case 3: c = new float[]{-2, 3, 3}; break;
            case 4: c = new float[]{3, 2, 1}; break;
            case 5: c = new float[]{2, 0, -1}; break;
            default: c = new float[]{0, 0, 0}; break;
        }
        float x = n <= 1 ? 0 : (float) band / (n - 1);
        float db = x <= .5 ? c[0] + (c[1] - c[0]) * (x / .5f) : c[1] + (c[2] - c[1]) * ((x - .5f) / .5f);
        return (short) (db * 100);
    }

    private void releaseEffects() {
        try { if (equalizer != null) equalizer.release(); } catch (Exception ignored) {}
        try { if (bassBoost != null) bassBoost.release(); } catch (Exception ignored) {}
        try { if (reverb != null) reverb.release(); } catch (Exception ignored) {}
        equalizer = null;
        bassBoost = null;
        reverb = null;
    }

    private int crossfadeDuration() {
        int i = prefs.getInt("pref_crossfade_dur", 1);
        return new int[]{3000, 5000, 8000, 10000, 12000, 15000}[Math.max(0, Math.min(5, i))];
    }

    private void startCrossfade(String path, int nextIdx, int duration) {
        if (crossfading) return;
        crossfading = true;
        MediaPlayer old = player;
        try {
            MediaPlayer np = new MediaPlayer();
            configurePlayer(np);
            np.setDataSource(path);
            np.prepare();
            np.setVolume(0, 0);
            np.start();
            nextPlayer = np;
            player = np;
            currentPath = path;
            currentIndex = nextIdx;
            playButton.setText("JEDA");
            songTitleView.setText(new File(path).getName());
            applyPlaybackParams();
            applyEffects();
            fade(old, np, duration);
            updateNotification();
        } catch (Exception e) {
            crossfading = false;
            releaseNext();
        }
    }

    private void fade(MediaPlayer old, MediaPlayer fresh, int dur) {
        final int steps = Math.max(1, dur / 50);
        final int[] i = {0};
        Runnable r = new Runnable() {
            public void run() {
                i[0]++;
                float x = Math.min(1f, i[0] / (float) steps);
                try { fresh.setVolume(x, x); } catch (Exception ignored) {}
                try { if (old != null) old.setVolume(1f - x, 1f - x); } catch (Exception ignored) {}
                if (i[0] < steps) {
                    handler.postDelayed(this, 50);
                } else {
                    try { if (old != null) { old.stop(); old.release(); } } catch (Exception ignored) {}
                    nextPlayer = null;
                    crossfading = false;
                    prepareNext();
                }
            }
        };
        handler.post(r);
    }

    private void prepareNext() {
        releaseNext();
        if (player == null || visibleSongs.size() < 2) return;
        int n = nextIndex();
        if (n < 0) return;
        try {
            nextPlayer = new MediaPlayer();
            nextPlayer.setDataSource(visibleSongs.get(n));
            nextPlayer.prepare();
        } catch (Exception e) {
            releaseNext();
        }
    }

    private void releaseNext() {
        if (nextPlayer != null) {
            try { nextPlayer.release(); } catch (Exception ignored) {}
            nextPlayer = null;
        }
    }

    private void updateUiAndTransitions() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                int p = player.getCurrentPosition();
                int d = player.getDuration();
                timerView.setText(formatTime(p) + " / " + formatTime(d));
                if (!userSeeking && d > 0) progress.setProgress((int) ((long) p * 1000 / d));
                if (loopAB && loopB > loopA && p >= loopB) player.seekTo(loopA);
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                    player.pause();
                    sleepAt = 0;
                    playButton.setText("PUTAR");
                    updateNotification();
                    Toast.makeText(this, "Sleep timer selesai.", Toast.LENGTH_SHORT).show();
                }
                int cf = crossfadeDuration();
                if (prefs.getBoolean("pref_crossfade", true) && !crossfading && d - p <= cf && nextIndex() >= 0) {
                    int n = nextIndex();
                    if (n >= 0 && n < visibleSongs.size()) {
                        startCrossfade(visibleSongs.get(n), n, Math.max(1000, Math.min(cf, d / 3)));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void finishAndStop() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIF_ID);
        }
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        releaseNext();
        releaseEffects();
        releasePlayer();
        scanExecutor.shutdownNow();
        finish();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (notificationManager != null && (player == null || !player.isPlaying())) {
            notificationManager.cancel(NOTIF_ID);
        }
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        releaseNext();
        releaseEffects();
        if (player != null) {
            try { prefs.edit().putInt(KEY_LAST_POS, player.getCurrentPosition()).apply(); } catch (Exception ignored) {}
        }
        scanExecutor.shutdownNow();
    }
}