package xenon.mobile.bridge;

import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.ResultReceiver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Small, dependency-free Bridge endpoint injected into source-built clones. */
public final class MindustryBridgeService extends Service {
    private static final int PROTOCOL_VERSION = 1;
    private static final int RESULT_OK = 0;
    private static final int RESULT_BAD_REQUEST = 1;
    private static final int RESULT_UNAUTHORIZED = 2;
    private static final int RESULT_BUSY = 3;
    private static final int RESULT_NOT_FOUND = 4;
    private static final int RESULT_IO_ERROR = 5;
    private static final int RESULT_TIMEOUT = 6;
    private static final int RESULT_UNSUPPORTED = 7;
    private static final long MAX_COMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100_000;
    private static final String HUB_PACKAGE_PREFIX = "com.xenon.mobile";
    private static final String BRIDGE_PERMISSION = "com.xenon.mobile.permission.MINDUSTRY_BRIDGE";
    private static final String ACTION_PREFIX = "com.xenon.mobile.bridge.";
    private static final String ACTION_LAUNCH = ACTION_PREFIX + "LAUNCH";
    private static final String ACTION_STATUS = ACTION_PREFIX + "STATUS";
    private static final String ACTION_SET_PROFILE = ACTION_PREFIX + "SET_PROFILE";
    private static final String ACTION_JOIN = ACTION_PREFIX + "JOIN";
    private static final String ACTION_IMPORT_ZIP = ACTION_PREFIX + "IMPORT_ZIP";
    private static final String ACTION_EXPORT_ZIP = ACTION_PREFIX + "EXPORT_ZIP";
    private static final String ACTION_EXPORT_DIAGNOSTICS = ACTION_PREFIX + "EXPORT_DIAGNOSTICS";
    private static final String ACTION_REQUEST_GRACEFUL_EXIT = ACTION_PREFIX + "REQUEST_GRACEFUL_EXIT";
    private static final String ACTION_RESET = ACTION_PREFIX + "RESET_WHITELISTED_DATA";
    private static final String EXTRA_PROTOCOL = "protocol_version";
    private static final String EXTRA_CALLER = "caller_package";
    private static final String EXTRA_RECEIVER = "result_receiver";
    private static final String EXTRA_URI = "zip_uri";
    private static final String EXTRA_VARIANT = "variant";
    private static final String EXTRA_BACKEND = "backend";
    private static final String EXTRA_SLOT = "slot";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_UUID = "uuid";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_PROFILE_ID = "profile_id";
    private static final String EXTRA_PACKAGE = "package_name";
    private static final String EXTRA_BUSY = "busy";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_ERROR = "error";
    private static final String SETTINGS_FILE = "settings.bin";
    private static final int TYPE_BOOL = 0;
    private static final int TYPE_INT = 1;
    private static final int TYPE_LONG = 2;
    private static final int TYPE_FLOAT = 3;
    private static final int TYPE_STRING = 4;
    private static final int TYPE_BINARY = 5;
    private static final Object EXIT_LOCK = new Object();
    private static Activity activeActivity;
    private static CountDownLatch exitLatch;

    public static void registerLauncher(Activity activity) {
        synchronized (EXIT_LOCK) {
            activeActivity = activity;
        }
    }

    public static void unregisterLauncher(Activity activity) {
        synchronized (EXIT_LOCK) {
            if (activeActivity == activity) {
                activeActivity = null;
                if (exitLatch != null) exitLatch.countDown();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = receiver(intent);
        if (!authorized(intent)) {
            send(receiver, RESULT_UNAUTHORIZED, "Caller is not authorized");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_STATUS.equals(action)) {
                if (!matchesTarget(intent)) {
                    send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
                } else {
                    status(receiver);
                }
            } else if (ACTION_SET_PROFILE.equals(action)) {
                setProfile(intent, receiver);
            } else if (ACTION_LAUNCH.equals(action) || ACTION_JOIN.equals(action)) {
                launch(intent, receiver, ACTION_JOIN.equals(action));
            } else if (ACTION_EXPORT_ZIP.equals(action)) {
                exportBackup(intent, receiver);
            } else if (ACTION_IMPORT_ZIP.equals(action)) {
                importBackup(intent, receiver);
            } else if (ACTION_EXPORT_DIAGNOSTICS.equals(action)) {
                exportDiagnostics(intent, receiver);
            } else if (ACTION_REQUEST_GRACEFUL_EXIT.equals(action)) {
                if (!matchesTarget(intent)) {
                    send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
                } else {
                    gracefulExit(receiver, startId);
                    return START_NOT_STICKY;
                }
            } else if (ACTION_RESET.equals(action)) {
                reset(intent, receiver);
            } else {
                send(receiver, RESULT_BAD_REQUEST, "Unsupported Bridge action");
            }
        } catch (Throwable error) {
            send(receiver, RESULT_IO_ERROR, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return START_NOT_STICKY;
    }

    private boolean authorized(Intent intent) {
        if (intent == null || intent.getIntExtra(EXTRA_PROTOCOL, -1) != PROTOCOL_VERSION) return false;
        String caller = intent.getStringExtra(EXTRA_CALLER);
        if (caller == null || !(caller.equals(HUB_PACKAGE_PREFIX) || caller.startsWith(HUB_PACKAGE_PREFIX + "."))) return false;
        try {
            int uid = getPackageManager().getPackageUid(caller, 0);
            int callingUid = Binder.getCallingUid();
            if (callingUid != Process.SYSTEM_UID && callingUid != Process.myUid() && callingUid != uid) return false;
            return getPackageManager().checkSignatures(getApplicationInfo().uid, uid) == PackageManager.SIGNATURE_MATCH;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matchesTarget(Intent intent) {
        ApplicationInfo info;
        try {
            info = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (Exception error) {
            return false;
        }
        Bundle metadata = info.metaData;
        if (metadata == null) return false;
        String variant = metadata.getString("xenon.variant");
        int slot = metadata.getInt("xenon.slot", -1);
        String requestedVariant = intent.getStringExtra(EXTRA_VARIANT);
        String backend = intent.getStringExtra(EXTRA_BACKEND);
        if (requestedVariant == null || backend == null || !intent.hasExtra(EXTRA_SLOT)) return false;
        int requestedSlot = intent.getIntExtra(EXTRA_SLOT, -1);
        return "apk".equalsIgnoreCase(backend)
            && variant != null
            && variant.equalsIgnoreCase(requestedVariant)
            && requestedSlot == slot;
    }

    private void status(ResultReceiver receiver) throws IOException {
        Bundle result = new Bundle();
        result.putString(EXTRA_PACKAGE, getPackageName());
        result.putString(EXTRA_STATUS, "ready");
        result.putBoolean(EXTRA_BUSY, isGameRunning());
        Profile profile = readProfile();
        if (profile != null) {
            result.putString(EXTRA_PROFILE_ID, profile.id);
            result.putString(EXTRA_UUID, profile.uuid);
            result.putString(EXTRA_NAME, profile.name);
        }
        send(receiver, RESULT_OK, null, result);
    }

    private void setProfile(Intent intent, ResultReceiver receiver) throws IOException {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        if (isGameRunning()) {
            send(receiver, RESULT_BUSY, "Game is running");
            return;
        }
        String uuid = trim(intent.getStringExtra(EXTRA_UUID));
        String name = trim(intent.getStringExtra(EXTRA_NAME));
        if (uuid == null || name == null || name.length() > 128) {
            send(receiver, RESULT_BAD_REQUEST, "A valid Mindustry Profile is required");
            return;
        }
        Settings settings = readSettings();
        settings.values.put("uuid", Setting.string(uuid));
        settings.values.put("name", Setting.string(name));
        writeSettings(settings);
        send(receiver, RESULT_OK, null);
    }

    private void gracefulExit(final ResultReceiver receiver, final int startId) {
        new Thread(() -> {
            CountDownLatch stopped = new CountDownLatch(1);
            Activity activity;
            synchronized (EXIT_LOCK) {
                activity = activeActivity;
                if (activity == null) {
                    send(receiver, RESULT_NOT_FOUND, "No Mindustry instance is running");
                    stopSelfResult(startId);
                    return;
                }
                exitLatch = stopped;
            }
            try {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) {
                        stopped.countDown();
                    } else {
                        activity.finish();
                    }
                });
                if (stopped.await(10, TimeUnit.SECONDS)) {
                    send(receiver, RESULT_OK, null);
                } else {
                    send(receiver, RESULT_TIMEOUT, "Graceful exit timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                send(receiver, RESULT_TIMEOUT, "Graceful exit was interrupted");
            } catch (Throwable error) {
                send(receiver, RESULT_IO_ERROR, error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                synchronized (EXIT_LOCK) {
                    if (exitLatch == stopped) exitLatch = null;
                }
                stopSelfResult(startId);
            }
        }, "xenon-graceful-exit").start();
    }

    private void launch(Intent intent, ResultReceiver receiver, boolean join) {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            send(receiver, RESULT_NOT_FOUND, "Clone launch activity was not found");
            return;
        }
        launch.setAction(join ? ACTION_JOIN : ACTION_LAUNCH);
        if (join) {
            launch.putExtra(EXTRA_HOST, intent.getStringExtra(EXTRA_HOST));
            launch.putExtra(EXTRA_PORT, intent.getIntExtra(EXTRA_PORT, 6567));
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(launch);
        send(receiver, RESULT_OK, null);
    }

    private void exportBackup(Intent intent, ResultReceiver receiver) throws IOException {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        Uri uri = uri(intent);
        if (uri == null) {
            send(receiver, RESULT_BAD_REQUEST, "Destination URI is required");
            return;
        }
        OutputStream output = getContentResolver().openOutputStream(uri, "w");
        if (output == null) throw new IOException("Could not open destination URI");
        try (CountingOutputStream counted = new CountingOutputStream(output, MAX_COMPRESSED_BYTES);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(counted))) {
            addBytes(zip, "manifest.json", "{\"schemaVersion\":1,\"kind\":\"backup\"}".getBytes(StandardCharsets.UTF_8));
            addTree(zip, dataRoot(), dataRoot(), "data", new Counter());
        }
        send(receiver, RESULT_OK, null);
    }

    private void importBackup(Intent intent, ResultReceiver receiver) throws IOException {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        Uri uri = uri(intent);
        if (uri == null) {
            send(receiver, RESULT_BAD_REQUEST, "Backup URI is required");
            return;
        }
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Could not open backup URI");
        File stage = new File(getCacheDir(), "bridge-import-" + UUID.randomUUID());
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("Could not create import staging directory");
        try (InputStream stream = new CountingInputStream(input, MAX_COMPRESSED_BYTES); ZipInputStream zip = new ZipInputStream(stream)) {
            int entries = 0;
            long expanded = 0;
            ZipEntry entry;
            boolean manifest = false;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Archive contains too many entries");
                String name = safeName(entry.getName());
                if (entry.isDirectory()) continue;
                if (name.equals("manifest.json")) manifest = true;
                File target = new File(stage, name);
                if (!target.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) throw new IOException("Archive path escapes staging directory");
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        expanded += read;
                        if (expanded > MAX_UNCOMPRESSED_BYTES) throw new IOException("Archive expands beyond the safety limit");
                        output.write(buffer, 0, read);
                    }
                }
            }
            if (!manifest) throw new IOException("Archive is missing manifest.json");
            copyTree(new File(stage, "data"), dataRoot());
        } finally {
            deleteTree(stage);
        }
        send(receiver, RESULT_OK, null);
    }

    private void exportDiagnostics(Intent intent, ResultReceiver receiver) throws IOException {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        Uri uri = uri(intent);
        if (uri == null) {
            send(receiver, RESULT_BAD_REQUEST, "Destination URI is required");
            return;
        }
        OutputStream output = getContentResolver().openOutputStream(uri, "w");
        if (output == null) throw new IOException("Could not open destination URI");
        try (CountingOutputStream counted = new CountingOutputStream(output, MAX_COMPRESSED_BYTES);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(counted))) {
            addBytes(zip, "manifest.json", "{\"schemaVersion\":1,\"kind\":\"diagnostics\"}".getBytes(StandardCharsets.UTF_8));
            addBytes(zip, "data/status.txt", (getPackageName() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        send(receiver, RESULT_OK, null);
    }

    private void reset(Intent intent, ResultReceiver receiver) {
        if (!matchesTarget(intent)) {
            send(receiver, RESULT_BAD_REQUEST, "Clone target does not match request");
            return;
        }
        if (isGameRunning()) {
            send(receiver, RESULT_BUSY, "Game is running");
            return;
        }
        deleteTree(new File(getCacheDir(), "bridge-state"));
        send(receiver, RESULT_OK, null);
    }

    private boolean isGameRunning() {
        synchronized (EXIT_LOCK) {
            return activeActivity != null && !activeActivity.isFinishing();
        }
    }

    private void copyTree(File source, File target) throws IOException {
        if (!source.isDirectory()) return;
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symbolic links are not allowed in backups");
            File destination = new File(target, file.getName());
            if (file.isDirectory()) copyTree(file, destination);
            else if (file.isFile()) {
                File parent = destination.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private File dataRoot() {
        File root = getExternalFilesDir(null);
        return root == null ? getFilesDir() : root;
    }

    private File settingsFile() {
        return new File(dataRoot(), SETTINGS_FILE);
    }

    private Profile readProfile() throws IOException {
        Settings settings = readSettings();
        String uuid = settings.string("uuid");
        String name = settings.string("name");
        return uuid == null || name == null ? null : new Profile("clone-" + uuid.hashCode(), uuid, name);
    }

    private Settings readSettings() throws IOException {
        File file = settingsFile();
        if (!file.isFile() || file.length() == 0) return new Settings();
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
        input.mark(2);
        int first = input.read();
        int second = input.read();
        input.reset();
        InputStream source = first == 0x78 && (second == 0x01 || second == 0x5e || second == 0x9c || second == 0xda)
            ? new InflaterInputStream(input) : input;
        try (DataInputStream stream = new DataInputStream(source)) {
            int count = stream.readInt();
            if (count < 0 || count > 100_000) throw new IOException("Invalid settings entry count");
            Settings settings = new Settings();
            for (int i = 0; i < count; i++) {
                String key = stream.readUTF();
                int type = stream.readByte();
                switch (type) {
                    case TYPE_BOOL: settings.values.put(key, Setting.bool(stream.readBoolean())); break;
                    case TYPE_INT: settings.values.put(key, Setting.intValue(stream.readInt())); break;
                    case TYPE_LONG: settings.values.put(key, Setting.longValue(stream.readLong())); break;
                    case TYPE_FLOAT: settings.values.put(key, Setting.floatValue(stream.readFloat())); break;
                    case TYPE_STRING: settings.values.put(key, Setting.string(stream.readUTF())); break;
                    case TYPE_BINARY:
                        int length = stream.readInt();
                        if (length < 0 || length > MAX_UNCOMPRESSED_BYTES) throw new IOException("Invalid settings binary length");
                        byte[] bytes = new byte[length];
                        stream.readFully(bytes);
                        settings.values.put(key, Setting.binary(bytes));
                        break;
                    default: throw new IOException("Unknown settings value type: " + type);
                }
            }
            return settings;
        }
    }

    private void writeSettings(Settings settings) throws IOException {
        File file = settingsFile();
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File temp = new File(parent, file.getName() + ".tmp");
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            stream.writeInt(settings.values.size());
            for (Map.Entry<String, Setting> entry : settings.values.entrySet()) {
                stream.writeUTF(entry.getKey());
                Setting value = entry.getValue();
                stream.writeByte(value.type);
                switch (value.type) {
                    case TYPE_BOOL: stream.writeBoolean((Boolean) value.value); break;
                    case TYPE_INT: stream.writeInt((Integer) value.value); break;
                    case TYPE_LONG: stream.writeLong((Long) value.value); break;
                    case TYPE_FLOAT: stream.writeFloat((Float) value.value); break;
                    case TYPE_STRING: stream.writeUTF((String) value.value); break;
                    case TYPE_BINARY:
                        byte[] bytes = (byte[]) value.value;
                        stream.writeInt(bytes.length);
                        stream.write(bytes);
                        break;
                    default: throw new IOException("Unknown settings value type: " + value.type);
                }
            }
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void addTree(ZipOutputStream zip, File root, File current, String prefix, Counter counter) throws IOException {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (++counter.entries > MAX_ENTRIES) throw new IOException("Archive contains too many entries");
            String name = prefix + "/" + file.getName();
            if (file.isDirectory()) addTree(zip, root, file, name, counter);
            else if (file.isFile()) {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private void addBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private String safeName(String raw) throws IOException {
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.contains(":") || name.isEmpty() || name.equals("manifest.json") == false && !name.startsWith("data/")) {
            throw new IOException("Archive entry is outside data/");
        }
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Archive path traversal is not allowed");
        }
        return name;
    }

    private void deleteTree(File root) {
        if (!root.exists()) return;
        File[] files = root.listFiles();
        if (files != null) for (File file : files) deleteTree(file);
        root.delete();
    }

    private Uri uri(Intent intent) {
        return intent == null ? null : intent.getParcelableExtra(EXTRA_URI);
    }

    private ResultReceiver receiver(Intent intent) {
        return intent == null ? null : intent.getParcelableExtra(EXTRA_RECEIVER);
    }

    private void send(ResultReceiver receiver, int code, String error) {
        send(receiver, code, error, new Bundle());
    }

    private void send(ResultReceiver receiver, int code, String error, Bundle result) {
        result.putInt(EXTRA_PROTOCOL, PROTOCOL_VERSION);
        result.putString(EXTRA_STATUS, code == RESULT_OK ? "ok" : "error");
        if (error != null) result.putString(EXTRA_ERROR, error);
        if (receiver != null) receiver.send(code, result);
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static final class Profile {
        final String id;
        final String uuid;
        final String name;

        Profile(String id, String uuid, String name) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static final class Settings {
        final LinkedHashMap<String, Setting> values = new LinkedHashMap<>();

        String string(String key) {
            Setting value = values.get(key);
            return value != null && value.type == TYPE_STRING ? (String) value.value : null;
        }
    }

    private static final class Setting {
        final int type;
        final Object value;

        Setting(int type, Object value) {
            this.type = type;
            this.value = value;
        }

        static Setting bool(boolean value) { return new Setting(TYPE_BOOL, value); }
        static Setting intValue(int value) { return new Setting(TYPE_INT, value); }
        static Setting longValue(long value) { return new Setting(TYPE_LONG, value); }
        static Setting floatValue(float value) { return new Setting(TYPE_FLOAT, value); }
        static Setting string(String value) { return new Setting(TYPE_STRING, value); }
        static Setting binary(byte[] value) { return new Setting(TYPE_BINARY, value); }
    }

    private static final class Counter {
        int entries;
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream output;
        private final long limit;
        private long count;

        CountingOutputStream(OutputStream output, long limit) {
            this.output = output;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            increment(1);
            output.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (length > 0) increment(length);
            output.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }

        private void increment(long value) throws IOException {
            count += value;
            if (count > limit) throw new IOException("Compressed archive exceeds the safety limit");
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        CountingInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = super.read(buffer, offset, length);
            if (value > 0) increment(value);
            return value;
        }

        private void increment(long value) throws IOException {
            count += value;
            if (count > limit) throw new IOException("Compressed archive exceeds the safety limit");
        }
    }
}
