package fr.dabi.c3twin;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plays one PCM channel of the ZLink Fox audio server (main or system audio). */
final class AudioStream {
    interface Listener {
        void failed();
    }

    // Fox ports of the installed ZLink engine (TLINK 4.0.41.11).
    static final int MAIN_PORT = 0xca92;    // 51858, music / podcasts / calls
    static final int SYSTEM_PORT = 0xcab0;  // 51888, Siri and navigation prompts

    private static final int MAX_FRAME = 1024 * 1024;
    private static final Pattern HEADER = Pattern.compile(
        "AUDIO#TYPE:PCM#SAMPLE:(\\d{1,6})#CHANNELS:(\\d)#BITS:(\\d{1,2})"
            + "#LEN:(\\d{1,7})(?:#AUDIOTYPE:(\\d{1,2}))?#");

    private final int port;
    private final int usage;
    private final Socket socket = new Socket();
    private final Listener listener;
    private volatile boolean closed;
    private AudioTrack track;
    /** When this channel last played something louder than silence (elapsedRealtime). */
    private volatile long soundAt;
    private volatile float volume = 1f;

    AudioStream(int port, int usage, Listener listener) {
        this.port = port;
        this.usage = usage;
        this.listener = listener;
    }

    void start() {
        new Thread(this::read, "CarPlay-audio-" + port).start();
    }

    private void read() {
        try {
            Log.i("C3Twin", "Audio: connexion " + port);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setTcpNoDelay(true);
            socket.getOutputStream().write(packet("REQUEST FRAME"));
            DataInputStream stream = new DataInputStream(socket.getInputStream());
            byte[] header = new byte[128];
            while (!closed) {
                stream.readFully(header);
                int end = 0;
                while (end < header.length && header[end] != 0) end++;
                String line = new String(header, 0, end, StandardCharsets.US_ASCII);
                Matcher fields = HEADER.matcher(line);
                if (!fields.matches()) {
                    // "AUDIO FRAME LOST#" and other status lines share this socket.
                    Log.w("C3Twin", "Audio header ignoré: " + line);
                    continue;
                }
                int sample = Integer.parseInt(fields.group(1));
                int channels = Integer.parseInt(fields.group(2));
                int bits = Integer.parseInt(fields.group(3));
                int length = Integer.parseInt(fields.group(4));
                if (bits != 16 || channels < 1 || channels > 2
                    || sample < 8000 || sample > 48000
                    || length < 1 || length > MAX_FRAME) {
                    throw new IOException("Format audio non supporté: " + line);
                }
                ensureTrack(sample, channels);
                byte[] frame = new byte[length];
                stream.readFully(frame);
                if (audible(frame)) soundAt = android.os.SystemClock.elapsedRealtime();
                int offset = 0;
                while (offset < length && !closed) {
                    int written = track.write(frame, offset, length - offset);
                    if (written < 0) throw new IOException("AudioTrack write " + written);
                    offset += written;
                }
            }
        } catch (IOException | RuntimeException e) {
            fail(e);
        } finally {
            close();
        }
    }

    private void ensureTrack(int sample, int channels) throws IOException {
        if (track != null && track.getSampleRate() == sample
            && track.getChannelCount() == channels) return;
        if (track != null) {
            track.stop();
            track.release();
            track = null;
        }
        int mask = channels == 1
            ? AudioFormat.CHANNEL_OUT_MONO
            : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sample, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("Tampon audio invalide: " + min);
        AudioFormat format = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sample)
            .setChannelMask(mask)
            .build();
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        // 200 ms of slack: enough to absorb the H.264/touch jitter without adding latency.
        int buffer = Math.max(min, sample * channels * 2 / 5);
        track = new AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        track.setVolume(volume);
        track.play();
        Log.i("C3Twin", "Audio " + port + ": " + sample + " Hz, " + channels + " ch");
    }

    /** Peak of a 16-bit PCM frame above roughly -40 dBFS, looking at every 8th sample. */
    private static boolean audible(byte[] pcm) {
        for (int i = 0; i + 1 < pcm.length; i += 16) {
            int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (v > 330 || v < -330) return true;
        }
        return false;
    }

    /** True while this channel is speaking (sound within the last [windowMs]). */
    boolean speaking(long windowMs) {
        return android.os.SystemClock.elapsedRealtime() - soundAt < windowMs;
    }

    /** Volume of this channel, 0..1; kept across format changes. */
    synchronized void setVolume(float v) {
        volume = v;
        if (track != null) try { track.setVolume(v); } catch (RuntimeException ignored) {}
    }

    private static byte[] packet(String message) {
        return Arrays.copyOf(message.getBytes(StandardCharsets.US_ASCII), 128);
    }

    private synchronized void fail(Exception e) {
        if (!closed) {
            if (e instanceof java.io.EOFException || e instanceof java.net.SocketException) {
                Log.i("C3Twin", "Audio " + port + ": " + e.getMessage());
            } else {
                Log.w("C3Twin", "Audio " + port, e);
            }
            close();
            listener.failed();
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
        if (track != null) {
            try { track.pause(); } catch (RuntimeException ignored) {}
            try { track.flush(); } catch (RuntimeException ignored) {}
            try { track.release(); } catch (RuntimeException ignored) {}
            track = null;
        }
    }
}
