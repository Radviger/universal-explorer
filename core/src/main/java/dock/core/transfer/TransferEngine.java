package dock.core.transfer;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * The transfer queue. Jobs run on virtual threads (up to {@value #WORKERS}
 * at a time), each opening private stream channels so transfers never share
 * a protocol client with the browsing panes. Directory jobs scan lazily and
 * enqueue their children — enqueueing a huge tree returns instantly.
 */
public final class TransferEngine {

    public static final TransferEngine GLOBAL = new TransferEngine();
    private static final int WORKERS = 3;
    private static final int BUFFER = 64 * 1024;

    private final List<TransferJob> jobs = new CopyOnWriteArrayList<>();
    private final Map<UUID, Control> controls = new ConcurrentHashMap<>();
    private final Map<UUID, ConflictDecision> batchDecisions = new ConcurrentHashMap<>();
    private final Semaphore permits = new Semaphore(WORKERS);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object conflictLock = new Object();

    private volatile ConflictResolver resolver = info -> ConflictDecision.once(ConflictRule.PROMPT);

    static final class Control {
        volatile boolean paused;
        volatile boolean cancelled;
        final Object monitor = new Object();
    }

    /** The app shell uses {@link #GLOBAL}; fresh engines exist for isolated runs (tests, tools). */
    public TransferEngine() {}

    public void setResolver(ConflictResolver resolver) {
        this.resolver = resolver == null
                ? info -> ConflictDecision.once(ConflictRule.PROMPT) : resolver;
    }

    public List<TransferJob> snapshot() {
        return List.copyOf(jobs);
    }

    /**
     * Aggregates the current burst: every job whose batch still has work in
     * flight, or settled within {@code recencyMillis} of {@code nowMillis} —
     * a just-finished burst keeps its final numbers so the footer bar can
     * linger on them instead of vanishing at ~97% the moment the last file
     * lands. Finished jobs of in-scope batches keep counting (a DONE file
     * contributes its full size), so the bar tracks the whole burst rather
     * than jumping each time a file completes. Batches older than the
     * window — including finished jobs from earlier bursts still listed in
     * the queue window — drop out entirely.
     */
    public static Progress overall(List<TransferJob> jobs, long nowMillis, long recencyMillis) {
        boolean active = false;
        Set<UUID> scope = new HashSet<>();
        for (TransferJob j : jobs) {
            if (j.isActive()) {
                active = true;
                scope.add(j.batchId());
            } else if (j.isFinished() && nowMillis - j.finishedAtMillis() <= recencyMillis) {
                scope.add(j.batchId());
            }
        }
        int running = 0, queued = 0, downloads = 0, uploads = 0;
        long speed = 0, done = 0, total = 0;
        for (TransferJob j : jobs) {
            if (!scope.contains(j.batchId())) continue;
            switch (j.state()) {
                case RUNNING -> { running++; speed += j.speedBytesPerSec(); }
                case QUEUED -> queued++;
                default -> { }
            }
            // Directory jobs carry a child count, not bytes; the burst turns
            // determinate once their children — real FILE jobs of the same
            // batch — enqueue.
            if (j.kind() == TransferJob.Kind.FILE && j.size() > 0) {
                done += j.transferred();
                total += j.size();
            }
            boolean fromRemote = j.src().remote();
            boolean toRemote = j.dst().remote();
            if (fromRemote && !toRemote) downloads++;
            else if (toRemote && !fromRemote) uploads++;
        }
        return new Progress(active, !scope.isEmpty(), running, queued, speed, done, total,
                downloads, uploads);
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /** Copies or moves the selected entries from srcDir into dstDir. */
    public void enqueue(FileSystem src, String srcDir, List<FileEntry> items,
                        FileSystem dst, String dstDir, boolean move) {
        UUID batch = UUID.randomUUID();
        for (FileEntry e : items) {
            String srcPath = src.child(srcDir, e.name());
            String dstPath = dst.child(dstDir, e.name());
            addJob(new TransferJob(batch, e.directory() ? TransferJob.Kind.DIRECTORY
                            : TransferJob.Kind.FILE,
                    src, srcPath, dst, dstPath, move, e.size()));
        }
    }

    // ---- controls ----

    public void pause(UUID id) {
        Control c = controls.get(id);
        if (c != null) c.paused = true;
        fireChanged();
    }

    public void resume(UUID id) {
        Control c = controls.get(id);
        if (c != null) {
            c.paused = false;
            synchronized (c.monitor) { c.monitor.notifyAll(); }
        }
        fireChanged();
    }

    public void cancel(UUID id) {
        TransferJob job = byId(id);
        if (job == null) return;
        if (job.state() == TransferJob.State.QUEUED) {
            job.settle(TransferJob.State.CANCELLED);
            controls.remove(id);
            fireChanged();
            return;
        }
        Control c = controls.get(id);
        if (c != null) {
            c.cancelled = true;
            synchronized (c.monitor) { c.monitor.notifyAll(); }
        }
    }

    public void retry(UUID id) {
        TransferJob job = byId(id);
        if (job == null || job.isActive()) return;
        job.setTransferred(0);
        job.setError(null);
        job.setState(TransferJob.State.QUEUED);
        controls.put(job.id(), new Control()); // the old one was removed at completion
        submit(job);
        fireChanged();
    }

    public void pauseAll() {
        for (TransferJob j : jobs) if (j.isActive()) pause(j.id());
    }

    public void resumeAll() {
        for (TransferJob j : jobs) if (j.state() == TransferJob.State.PAUSED) resume(j.id());
    }

    public void retryFailed() {
        for (TransferJob j : jobs) {
            if (j.state() == TransferJob.State.FAILED) retry(j.id());
        }
    }

    public void clearFinished() {
        jobs.removeIf(TransferJob::isFinished);
        controls.keySet().retainAll(jobs.stream().map(TransferJob::id).toList());
        fireChanged();
    }

    public boolean hasActive(FileSystem fs) {
        for (TransferJob j : jobs) {
            if (j.isActive() && (j.src() == fs || j.dst() == fs)) return true;
        }
        return false;
    }

    public void cancelAll(FileSystem fs) {
        for (TransferJob j : jobs) {
            if (j.src() == fs || j.dst() == fs) cancel(j.id());
        }
    }

    public boolean hasActive() {
        return jobs.stream().anyMatch(TransferJob::isActive);
    }

    public TransferJob byId(UUID id) {
        for (TransferJob j : jobs) if (j.id().equals(id)) return j;
        return null;
    }

    // ---- execution ----

    private void addJob(TransferJob job) {
        jobs.add(job);
        controls.put(job.id(), new Control());
        submit(job);
        fireChanged();
    }

    private void submit(TransferJob job) {
        Thread.ofVirtual().name("dock-transfer-" + job.name()).start(() -> {
            try {
                permits.acquire();
                try {
                    run(job);
                } finally {
                    permits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void run(TransferJob job) {
        Control ctl = controls.get(job.id());
        if (ctl == null || ctl.cancelled) {
            job.settle(TransferJob.State.CANCELLED);
            fireChanged();
            return;
        }
        job.setState(TransferJob.State.RUNNING);
        fireChanged();
        try {
            if (job.kind() == TransferJob.Kind.DIRECTORY) {
                runDirectory(job);
            } else {
                runFile(job, ctl);
            }
            job.settle(TransferJob.State.DONE);
        } catch (CancelException e) {
            job.settle(TransferJob.State.CANCELLED);
        } catch (IOException | RuntimeException e) {
            // Runtime included: SMB flags dead connections with unchecked
            // exceptions mid-stream; the job must fail, not vanish.
            job.setError(e.getMessage() == null ? e.toString() : e.getMessage());
            job.settle(TransferJob.State.FAILED);
        } finally {
            controls.remove(job.id());
            fireChanged();
        }
    }

    private void runDirectory(TransferJob job) throws IOException {
        // Directories merge; the tree is discovered while the batch runs.
        if (!job.dst().exists(job.dstPath())) {
            job.dst().mkdir(job.dstPath());
        }
        List<FileEntry> children = job.src().list(job.srcPath());
        job.setSize(children.size());
        for (FileEntry e : children) {
            addJob(new TransferJob(job.batchId(),
                    e.directory() ? TransferJob.Kind.DIRECTORY : TransferJob.Kind.FILE,
                    job.src(), job.src().child(job.srcPath(), e.name()),
                    job.dst(), job.dst().child(job.dstPath(), e.name()),
                    job.move(), e.size()));
        }
        if (job.move() && children.isEmpty()) {
            job.src().delete(job.srcPath());
        }
    }

    private void runFile(TransferJob job, Control ctl) throws IOException {
        FileSystem srcView = job.src().streamView();
        FileSystem dstView = job.dst().streamView();
        try {
            FileEntry srcStat = srcView.stat(job.srcPath());
            job.setSize(srcStat.size());

            // Resume an interrupted attempt when the partial target still
            // matches what we recorded; otherwise start the decision flow over.
            long resumeFrom = job.resumeFrom();
            boolean resumed = false;
            String target = job.dstPath();
            if (resumeFrom > 0) {
                if (dstView.exists(target)
                        && dstView.stat(target).size() == resumeFrom
                        && srcStat.size() > resumeFrom) {
                    resumed = true;
                    job.setTransferred(resumeFrom);
                } else {
                    job.setResumeFrom(0);
                }
            }

            if (!resumed) {
                ConflictRule rule = resolveRule(job, srcStat, target, dstView);
                if (rule == ConflictRule.SKIP) {
                    job.setState(TransferJob.State.SKIPPED);
                    return;
                }
                if (rule == ConflictRule.RENAME) {
                    target = uniquePath(dstView, job.dstPath());
                }
            }

            try (InputStream in = resumed ? srcView.read(job.srcPath(), resumeFrom)
                                          : srcView.read(job.srcPath());
                 OutputStream out = resumed ? dstView.write(target, true)
                                           : dstView.write(target, false)) {
                byte[] buf = new byte[BUFFER];
                long mark = System.nanoTime();
                long windowBytes = 0;
                int n;
                try {
                    while ((n = in.read(buf)) > 0) {
                        if (ctl.cancelled) throw new CancelException();
                        if (ctl.paused) {
                            job.setState(TransferJob.State.PAUSED);
                            fireChanged();
                            awaitResume(ctl);
                            if (ctl.cancelled) throw new CancelException();
                            job.setState(TransferJob.State.RUNNING);
                        }
                        out.write(buf, 0, n);
                        job.setTransferred(job.transferred() + n);
                        windowBytes += n;
                        long now = System.nanoTime();
                        long windowNanos = now - mark;
                        if (windowNanos >= 500_000_000L) {
                            job.setSpeed((long) (windowBytes * 1_000_000_000.0 / windowNanos));
                            mark = now;
                            windowBytes = 0;
                        }
                    }
                    job.setSpeed(0);
                } catch (IOException e) {
                    // Remember the partial target so a retry can append.
                    job.setResumeFrom(job.transferred());
                    throw e;
                }
            }

            // Preserve the modification time when the backend supports it.
            if (srcStat.mtimeMillis() > 0) {
                try {
                    dstView.setTimes(target, srcStat.mtimeMillis());
                } catch (IOException | UnsupportedOperationException ignored) {
                }
            }
            if (job.move()) {
                srcView.delete(job.srcPath());
            }
            fireChanged();
        } finally {
            if (srcView != job.src()) srcView.close();
            if (dstView != job.dst()) dstView.close();
        }
    }

    private ConflictRule resolveRule(TransferJob job, FileEntry srcStat, String target,
                                     FileSystem dstView) throws IOException {
        if (!dstView.exists(target)) return ConflictRule.OVERWRITE; // plain copy
        FileEntry dstStat = dstView.stat(target);
        if (dstStat.directory() != srcStat.directory()) {
            throw new IOException("target exists and is a different type: " + target);
        }
        // Serialized: parallel workers of the same batch must not both prompt
        // before an "apply to all" decision has been cached.
        ConflictDecision decision;
        synchronized (conflictLock) {
            ConflictDecision sticky = batchDecisions.get(job.batchId());
            decision = sticky != null
                    ? sticky
                    : resolver.resolve(new ConflictInfo(job.srcPath(), srcStat, target, dstStat,
                            countRemainingConflicts(job.batchId())));
            if (decision.applyToAll()) {
                batchDecisions.put(job.batchId(), decision);
            }
        }
        return switch (decision.rule()) {
            case OVERWRITE, SKIP, RENAME -> decision.rule();
            case OVERWRITE_IF_NEWER -> (srcStat.mtimeMillis() > dstStat.mtimeMillis())
                    ? ConflictRule.OVERWRITE : ConflictRule.SKIP;
            case PROMPT -> ConflictRule.OVERWRITE; // resolver already answered
        };
    }

    private int countRemainingConflicts(UUID batch) {
        return (int) jobs.stream().filter(j -> j.batchId().equals(batch)).count();
    }

    private static String uniquePath(FileSystem dst, String path) throws IOException {
        String parent = dst.parent(path);
        String name = path.substring(path.lastIndexOf(dst.separator()) + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = dst.child(parent, base + " (" + i + ")" + ext);
            if (!dst.exists(candidate)) return candidate;
        }
        throw new IOException("could not find a free name for " + name);
    }

    private static void awaitResume(Control ctl) {
        synchronized (ctl.monitor) {
            while (ctl.paused && !ctl.cancelled) {
                try {
                    ctl.monitor.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final class CancelException extends IOException {
        CancelException() { super("cancelled"); }
    }

    private void fireChanged() {
        for (Runnable l : listeners) l.run();
    }
}
