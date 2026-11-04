package fr.dabi.c3twin;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local client for the TLINK 4.0.41.11 Fox video and input channels. */
final class ZlinkStream {
    interface Listener {
        /** First picture decoded: its size and the black margins ZLink says it leaves around it. */
        void picture(int width, int height, int left, int top, int right, int bottom);
        void failed();
    }
    // Fox ports of the installed ZLink engine (TLINK 4.0.41.11). If the head unit runs
    // another version, check `netstat -lnp | grep 518` on the device and update these.
    private static final int VIDEO_PORT = 0xca6a, TOUCH_PORT = 0xca88;
    // Physical keys (51838). Message format not confirmed on the head unit: see docs/zlink-fox.md.
    private static final int KEY_PORT = 0xca7e;
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final Pattern HEADER = Pattern.compile(
        "VIDEO#TYPE:H264#WIDTH:(\\d{1,4})#HEIGHT:(\\d{1,4})#LEN:(\\d{1,7})"
            + "#LM:(\\d{1,4})#TM:(\\d{1,4})#RM:(\\d{1,4})#BM:(\\d{1,4})");
    private final Socket video = new Socket(), touch = new Socket(), keys = new Socket();
    private volatile boolean keysReady;
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean closed;

    ZlinkStream(Listener listener) { this.listener = listener; }

    private static void connect(Socket s, int port) throws IOException {
        s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        s.setTcpNoDelay(true);
    }

    void start(Surface surface) {
        new Thread(() -> read(surface), "CarPlay-video").start();
    }

    private void read(Surface surface) {
        MediaCodec decoder = null;
        Thread output = null;
        try {
            Log.i("C3Twin", "Projection: connexion " + VIDEO_PORT + "/" + TOUCH_PORT);
            connect(video, VIDEO_PORT);
            video.getOutputStream().write(packet("REQUEST FRAME"));
            DataInputStream stream = new DataInputStream(video.getInputStream());
            byte[] header = new byte[128];
            int width = 0, height = 0;
            int[] margins = new int[4];
            while (!closed) {
                stream.readFully(header);
                int end = 0;
                while (end < header.length && header[end] != 0) end++;
                String line = new String(header, 0, end, StandardCharsets.US_ASCII);
                Matcher fields = HEADER.matcher(line);
                if (!fields.matches()) {
                    // The engine also sends short status lines on this socket; skip them.
                    Log.w("C3Twin", "Projection header ignoré: " + line);
                    continue;
                }
                int w = Integer.parseInt(fields.group(1)), h = Integer.parseInt(fields.group(2));
                int length = Integer.parseInt(fields.group(3));
                if (w < 1 || h < 1 || w > 4096 || h > 4096 || length < 1 || length > MAX_FRAME)
                    throw new IOException("Invalid frame size");
                for (int k = 0; k < 4; k++) margins[k] = Integer.parseInt(fields.group(4 + k));
                if (decoder == null) {
                    connect(touch, TOUCH_PORT);
                    connectKeys();
                    width = w;
                    height = h;
                    decoder = MediaCodec.createDecoderByType("video/avc");
                    MediaFormat format = MediaFormat.createVideoFormat("video/avc", w, h);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME);
                    decoder.configure(format, surface, null, 0);
                    decoder.start();
                    final MediaCodec codec = decoder;
                    final int[] m = margins.clone();
                    output = new Thread(() -> render(codec, w, h, m), "CarPlay-render");
                    output.start();
                } else if (w != width || h != height) {
                    throw new IOException("Projection size changed");
                }
                byte[] frame = new byte[length];
                stream.readFully(frame);
                int index;
                do { index = decoder.dequeueInputBuffer(50000); } while (index < 0 && !closed);
                if (closed) break;
                ByteBuffer buffer = decoder.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < length) throw new IOException("Frame too large");
                buffer.clear();
                buffer.put(frame);
                decoder.queueInputBuffer(index, 0, length, SystemClock.elapsedRealtimeNanos() / 1000, 0);
            }
        } catch (IOException | RuntimeException e) {
            fail(e);
        } finally {
            close();
            if (output != null) {
                try { output.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (decoder != null) decoder.release();
        }
    }

    private void render(MediaCodec codec, int width, int height, int[] m) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = true;
        try {
            while (!closed) {
                int index = codec.dequeueOutputBuffer(info, 50000);
                if (index < 0) continue;
                boolean picture = info.size > 0;
                codec.releaseOutputBuffer(index, picture);
                if (picture && first) {
                    first = false;
                    listener.picture(width, height, m[0], m[1], m[2], m[3]);
                }
            }
        } catch (RuntimeException e) { fail(e); }
    }

    private static byte[] packet(String message) {
        return Arrays.copyOf(message.getBytes(StandardCharsets.US_ASCII), 128);
    }

    synchronized void sendTouch(String state, int x, int y) {
        if (closed) return;
        input.execute(() -> {
            try { touch.getOutputStream().write(packet("STATE:" + state + "#" + x + ":" + y + "#")); }
            catch (IOException e) { fail(e); }
        });
    }

    /** The key channel is optional: without it the picture and touch still work. */
    private void connectKeys() {
        input.execute(() -> {
            try {
                connect(keys, KEY_PORT);
                keysReady = true;
                Log.i("C3Twin", "Projection: touches connectées (" + KEY_PORT + ")");
            } catch (IOException e) {
                Log.i("C3Twin", "Projection: pas de canal touches (" + e.getMessage() + ")");
            }
        });
    }

    /** One press of a head unit or steering wheel key, as an Android key code. */
    synchronized void sendKey(int keyCode) {
        if (closed || !keysReady) return;
        input.execute(() -> {
            try { keys.getOutputStream().write(packet("KEY VALUE:" + keyCode + "#")); }
            catch (IOException e) { keysReady = false; Log.i("C3Twin", "Projection: touches perdues"); }
        });
    }

    boolean keysReady() { return keysReady; }

    private synchronized void fail(Exception e) {
        if (!closed) {
            if (e instanceof java.io.EOFException || e instanceof java.net.SocketException) {
                Log.i("C3Twin", "Projection: " + e.getMessage());
            } else {
                Log.w("C3Twin", "Projection Zlink", e);
            }
            close();
            listener.failed();
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { video.close(); } catch (IOException ignored) {}
        try { touch.close(); } catch (IOException ignored) {}
        try { keys.close(); } catch (IOException ignored) {}
        input.shutdownNow();
    }
}
