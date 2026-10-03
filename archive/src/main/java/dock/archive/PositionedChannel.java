package dock.archive;

import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A seekable channel over a host backend's positioned reads — the random
 * access the zip and 7z indices need. Reads are served from an aligned
 * block cache (256 KiB, 32 blocks), so the many small seeks of a central
 * directory walk collapse into a handful of positioned reads and repeated
 * entry reads never touch the backend again. Backends with true positioned
 * reads (SFTP seek, WebDAV ranges, FTP REST, SMB2 READ) pay per block;
 * skip-loop backends pay per miss.
 */
final class PositionedChannel implements SeekableByteChannel {

    private static final int BLOCK = 256 * 1024;
    private static final int MAX_BLOCKS = 32;

    private final FileSystem host;
    private final String path;
    private final long size;
    private final Map<Long, byte[]> blocks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_BLOCKS;
        }
    };
    private long pos;
    private boolean open = true;

    PositionedChannel(FileSystem host, String path, long size) {
        this.host = host;
        this.path = path;
        this.size = size;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        if (!open) throw new ClosedChannelException();
        if (pos >= size || !dst.hasRemaining()) return -1;
        int total = 0;
        while (dst.hasRemaining() && pos < size) {
            byte[] block = blockAt(pos / BLOCK);
            int offsetInBlock = (int) (pos % BLOCK);
            if (offsetInBlock >= block.length) break;    // shrank underneath us
            int take = Math.min(block.length - offsetInBlock, dst.remaining());
            dst.put(block, offsetInBlock, take);
            pos += take;
            total += take;
        }
        return total == 0 ? -1 : total;
    }

    /** Fetches (and caches) the aligned block {@code index}. */
    private byte[] blockAt(long index) throws IOException {
        byte[] cached = blocks.get(index);
        if (cached != null) return cached;
        long start = index * BLOCK;
        int len = (int) Math.min(BLOCK, size - start);
        byte[] block = new byte[len];
        try (InputStream in = host.read(path, start)) {
            int done = 0;
            while (done < len) {
                int r = in.read(block, done, len - done);
                if (r < 0) break;
                done += r;
            }
            if (done < len) block = Arrays.copyOf(block, done);
        }
        blocks.put(index, block);
        return block;
    }

    @Override public synchronized long position() { return pos; }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0 || newPosition > size) throw new IllegalArgumentException();
        pos = newPosition;
        return this;
    }

    @Override public synchronized long size() { return size; }

    @Override public int write(ByteBuffer src) { throw new NonWritableChannelException(); }
    @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
    @Override public synchronized boolean isOpen() { return open; }

    @Override
    public synchronized void close() {
        open = false;
        blocks.clear();
    }
}
