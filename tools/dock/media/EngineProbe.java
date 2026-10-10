package dock.media;

import dock.core.config.Site;
import dock.core.config.Sites;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.secrets.CredentialManager;
import dock.smb.SmbSessions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The app's own playback stack end to end against the real NAS: the real
 *  MediaBridge over the real SmbFs, driven by the real VlcEngine with real
 *  seeks and (optionally) the real preview engine storming the same bridge
 *  the way scrub-tip hovers do. The media clock freezing while the engine
 *  believes it is playing is the reported failure. */
public class EngineProbe {

    static final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile boolean wedged;

    public static void main(String[] args) throws Exception {
        String siteName = arg(args, 0, "OMV");
        String goodDir = arg(args, 1, "/media/Anime/3D Kanojo/Season 01");
        String badDir = arg(args, 2, "/media/Anime/A Wild Last Boss Appeared!/Season 01");
        Thread.ofVirtual().start(() -> hardExit(480_000));
        Site site = Sites.load().stream()
                .filter(s -> s.name().equalsIgnoreCase(siteName))
                .findFirst().orElseThrow(() -> new IllegalStateException("no site " + siteName));
        String stored = CredentialManager.load(site.secretTarget());
        char[] pw = stored == null ? null : stored.toCharArray();
        System.out.println("site=" + site.name() + " user=" + site.user()
                + " password=" + (pw == null ? "MISSING" : "loaded"));
        SmbSessions.SmbSpec spec = new SmbSessions.SmbSpec(
                site.host(), site.port(), site.domain(), site.user(), pw, site.guest(), null);
        FileSystem fs = SmbSessions.connect(spec).fs();
        try {
            String goodFile = firstOf(fs.list(goodDir), goodDir, fs);
            String badFile = firstOf(fs.list(badDir), badDir, fs);
            System.out.println("GOOD=" + goodFile);
            System.out.println("BAD =" + badFile);
            System.out.println("vlc available=" + VlcEngine.available());

            run(fs, goodFile, "GOOD", false);
            run(fs, badFile, "BAD", false);
            run(fs, badFile, "BAD+PREVIEW", true);
        } finally {
            fs.close();
        }
        System.out.println(wedged ? "RESULT: WEDGED" : "RESULT: completed");
        System.exit(wedged ? 3 : 0);
    }

    static String arg(String[] args, int i, String dflt) {
        return args.length > i && !args[i].isBlank() ? args[i] : dflt;
    }

    static String firstOf(List<FileEntry> rows, String dir, FileSystem fs) {
        return rows.stream().filter(e -> !e.directory())
                .findFirst()
                .map(e -> fs.child(dir, e.name()))
                .orElseThrow(() -> new IllegalStateException("no files in " + dir));
    }

    static void run(FileSystem fs, String path, String tag, boolean storm) throws Exception {
        System.out.println();
        System.out.println("[" + tag + "] === " + path);
        events.clear();
        LoggingFs wrapped = new LoggingFs(fs);
        MediaBridge.Registration reg = MediaBridge.serve(wrapped, path);
        System.out.println("[" + tag + "] url=" + reg.url());
        MediaEngine engine = new VlcEngine.Factory().create();
        if (engine == null) {
            System.out.println("[" + tag + "] no engine - skipping");
            reg.close();
            return;
        }
        MediaPreview preview = storm ? new VlcPreviewEngine.Factory().create() : null;
        if (storm) preview.open(reg.url());
        engine.setListener(new MediaEngine.Listener() {
            @Override public void playing() { events.add(at() + " playing"); }
            @Override public void paused() { events.add(at() + " paused"); }
            @Override public void finished() { events.add(at() + " finished"); }
            @Override public void error(String message) { events.add(at() + " ERROR " + message); }
            @Override public void buffering(float percent) {
                events.add(at() + " buffering " + Math.round(percent * 100) + "%");
            }
            @Override public void time(long ms) {}
            @Override public void duration(long ms) { events.add(at() + " duration " + ms); }
        });
        long t0 = System.currentTimeMillis();
        engine.open(reg.url());
        long[] seekAt = {6_000, 16_000, 26_000, 36_000, 46_000};
        long[] seekTo = {600_000, 120_000, 900_000, 300_000, 1_500_000};
        int seekIdx = 0;
        long lastTime = -1;
        long lastAdvance = t0;
        try {
            while (System.currentTimeMillis() - t0 < 56_000) {
                Thread.sleep(500);
                long e = System.currentTimeMillis() - t0;
                long t = engine.timeMs();
                boolean playing = engine.isPlaying();
                if (t != lastTime && t > 0) {
                    lastTime = t;
                    lastAdvance = System.currentTimeMillis();
                }
                if (e % 10_000 < 525)
                    System.out.printf("    %s clock=%d ms playing=%s%n", at(), t, playing);
                if (playing && t > 0
                        && System.currentTimeMillis() - lastAdvance > 20_000) {
                    System.out.println("[" + tag + "] CLOCK FROZEN at " + t
                            + " ms while playing - the reported wedge");
                    wedged = true;
                    dumpThreads();
                    break;
                }
                if (seekIdx < seekAt.length && e >= seekAt[seekIdx]) {
                    System.out.println("[" + tag + "] @" + e + " seek -> " + seekTo[seekIdx]);
                    engine.seekMs(seekTo[seekIdx]);
                    seekIdx++;
                }
                if (storm && e % 3_000 < 525 && preview != null) {
                    long target = (long) (Math.random() * 1_400_000);
                    preview.request(target, image -> {});
                }
            }
        } finally {
            try { engine.close(); } catch (Throwable ignored) {}
            if (preview != null) try { preview.close(); } catch (Throwable ignored) {}
            reg.close();
        }
        System.out.println("[" + tag + "] events:");
        events.forEach(ev -> System.out.println("      " + ev));
        wrapped.report();
    }

    static String at() {
        return String.format("%7d ms", System.currentTimeMillis() - start);
    }

    static final long start = System.currentTimeMillis();

    static void dumpThreads() {
        System.out.println("---------------- thread dump ----------------");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            System.out.println("\"" + t.getName() + "\" state=" + t.getState());
            for (StackTraceElement f : e.getValue()) System.out.println("    at " + f);
        }
        System.out.println("----------------------------------------------");
    }

    static void hardExit(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            return;
        }
        System.out.println("HARD EXIT after " + ms + " ms");
        dumpThreads();
        Runtime.getRuntime().halt(4);
    }

    static final class LoggingFs implements FileSystem {
        private final FileSystem inner;
        private final long t0 = System.currentTimeMillis();

        LoggingFs(FileSystem inner) { this.inner = inner; }

        private String at() {
            return String.format("%7d ms", System.currentTimeMillis() - t0);
        }

        private <T> T log(String op, SupplierEx<T> call) throws IOException {
            long t0 = System.nanoTime();
            try {
                T out = call.get();
                System.out.printf("    %s %-18s %5d ms ok%n",
                        at(), op, (System.nanoTime() - t0) / 1_000_000);
                return out;
            } catch (IOException | RuntimeException e) {
                System.out.printf("    %s %-18s %5d ms FAIL %s%n",
                        at(), op, (System.nanoTime() - t0) / 1_000_000, e);
                throw e;
            }
        }

        void report() {
            System.out.println("[fs] phase done");
        }

        interface SupplierEx<T> { T get() throws IOException; }

        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
        @Override public String separator() { return inner.separator(); }
        @Override public String home() { return inner.home(); }
        @Override public List<String> roots() { return inner.roots(); }
        @Override public String normalize(String path) { return inner.normalize(path); }
        @Override public String parent(String path) { return inner.parent(path); }
        @Override public String child(String dir, String name) { return inner.child(dir, name); }
        @Override public boolean exists(String path) throws IOException {
            return log("exists", () -> inner.exists(path));
        }
        @Override public List<FileEntry> list(String path) throws IOException {
            return log("list", () -> inner.list(path));
        }
        @Override public FileEntry stat(String path) throws IOException {
            return log("stat", () -> inner.stat(path));
        }
        @Override public void mkdir(String path) throws IOException {
            log("mkdir", () -> { inner.mkdir(path); return null; });
        }
        @Override public void delete(String path) throws IOException {
            log("delete", () -> { inner.delete(path); return null; });
        }
        @Override public void rename(String from, String to) throws IOException {
            log("rename", () -> { inner.rename(from, to); return null; });
        }
        @Override public InputStream read(String path) throws IOException {
            return log("read0", () -> counted("read0", inner.read(path)));
        }
        @Override public InputStream read(String path, long offset) throws IOException {
            return log("read@" + offset, () -> counted("read@" + offset, inner.read(path, offset)));
        }
        @Override public OutputStream write(String path, boolean append) throws IOException {
            return log("write", () -> inner.write(path, append));
        }
        @Override public void setTimes(String path, long mtimeMillis) throws IOException {
            log("setTimes", () -> { inner.setTimes(path, mtimeMillis); return null; });
        }
        @Override public void setPerms(String path, int posix) throws IOException {
            log("setPerms", () -> { inner.setPerms(path, posix); return null; });
        }
        @Override public FileSystem streamView() throws IOException {
            FileSystem view = inner.streamView();
            return view == inner ? this : new LoggingFs(view);
        }
        @Override public void close() { inner.close(); }

        InputStream counted(String label, InputStream in) {
            return new InputStream() {
                long served;
                boolean closed;

                @Override public int read() throws IOException {
                    try {
                        int n = in.read();
                        if (n >= 0) served++;
                        return n;
                    } catch (IOException | RuntimeException e) {
                        fail(e);
                        throw e;
                    }
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        int n = in.read(b, off, len);
                        if (n > 0) served += n;
                        return n;
                    } catch (IOException | RuntimeException e) {
                        fail(e);
                        throw e;
                    }
                }

                private void fail(Throwable t) {
                    System.out.printf("    %s %s READ FAILED after %d bytes: %s%n",
                            at(), label, served, t);
                }

                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try {
                        in.close();
                    } finally {
                        System.out.printf("    %s %s closed after %d bytes%n",
                                at(), label, served);
                    }
                }
            };
        }
    }
}
