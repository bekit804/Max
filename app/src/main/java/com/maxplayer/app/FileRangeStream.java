package com.maxplayer.app;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Читает кусок файла: с позиции start, не больше length байт (-1 значит до конца). */
final class FileRangeStream extends InputStream {
    private final FileInputStream in;
    private long remaining;

    FileRangeStream(ParcelFileDescriptor pfd, long start, long length) throws IOException {
        this.in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        this.remaining = length;
        if (start > 0) {
            try {
                in.getChannel().position(start);
            } catch (IOException e) {
                skipFully(start);
            }
        }
    }

    private void skipFully(long count) throws IOException {
        long left = count;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) return;
                n = 1;
            }
            left -= n;
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining == 0) return -1;
        int want = remaining < 0 ? len : (int) Math.min((long) len, remaining);
        int n = in.read(b, off, want);
        if (n > 0 && remaining > 0) remaining -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        int a = in.available();
        return remaining < 0 ? a : (int) Math.min((long) a, remaining);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
