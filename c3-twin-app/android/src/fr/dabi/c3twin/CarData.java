package fr.dabi.c3twin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Collects the car's state on the QF001 head unit (Jitu firmware) and hands it to the page.
 *
 * Sources, best first:
 *  - the MCU service ("mcu_service"): every message the head unit's MCU sends, among them
 *    the CAN box frames (cmd 65), the illumination wire (cmd 34) and the handbrake wire (cmd 35);
 *  - the car app com.qf.vehicle: its shared body data (speed, lamps, belts), its door bits,
 *    the reverse gear and the steering angle, as broadcasts;
 *  - GPS, for the speed when the car gives none.
 *
 * The interfaces and byte layouts come from the community's reverse engineering of the
 * firmware (XDA threads, open projects such as Dashwheel); nothing here is a vendor API.
 * Every MCU message is also written to files/mcu.log so the car's own frames can be worked out.
 */
final class CarData {
    interface Listener { void onCarChanged(JSONObject state); }

    private static final String TAG = "C3Twin.car";

    // MCU service, as the firmware's framework registers it
    private static final String MCU_SERVICE = "mcu_service";
    private static final String MCU_MANAGER = "android.qf.mcu.IMcuManager";
    private static final String MCU_LISTENER = "android.qf.mcu.IMcuListener";
    private static final int TX_REGISTER = 2;
    private static final int TX_ON_INFO = 1;
    private static final int TX_SEND = 29;                    // send(byte cmd, byte[] data, int len)
    private static final int CMD_HEADLIGHT = 34;
    private static final int CMD_HANDBRAKE = 35;
    private static final int CMD_CAN = 65;                    // MCU -> Android: bytes from the CAN box
    private static final int CMD_TO_CAN = 0x40;               // Android -> MCU: bytes for the CAN box

    // CAN box frames inside cmd 65. Raise (RZC): FD|FC len type data… sum.
    // Hiworld: 5A A5 len cmd data… sum.
    private static final int RZC_DOORS = 0x38;
    private static final int RZC_WARNINGS = 0x3A;
    private static final int RZC_QUERY = 0x8F;
    // trip computer pages (layout from canbox-core's Raise PSA spec; distance in tenths of a km there)
    private static final int RZC_TRIP_NOW = 0x33;             // instant L/100 km, range km, km to destination
    private static final int RZC_TRIP1 = 0x34;                // average L/100 km, average km/h, distance
    private static final int RZC_TRIP2 = 0x35;
    private static final int RZC_OUTSIDE_TEMP = 0x36;
    private static final long TRIP_ASK_EVERY_MS = 30_000;
    private static final int HW_WARNINGS = 0x42;
    private static final int HW_ALERT = 0xEA;
    private static final long ASK_FIRST_MS = 15_000;          // the CAN box wakes up with the car
    private static final long ASK_EVERY_MS = 5 * 60_000;
    private static final long SINGLE_ALERT_MS = 5 * 60_000;   // an alert sent once, without an end, is kept this long

    // com.qf.vehicle broadcasts
    private static final String VEHICLE = "com.qf.vehicle";
    private static final String ACTION_SHARE = "com.qf.vehicle.action.DATA_SHARE";
    private static final String EXTRA_SHARE = "extra_DATA_SHARE";
    private static final String ACTION_STEERING = "com.qf.vehicle.action.swa";
    private static final String ACTION_REVERSE_ON = "com.qf.action.BACKCAR_START";
    private static final String ACTION_REVERSE_OFF = "com.qf.action.BACKCAR_STOP";
    private static final String REVERSE_PROP = "sys.qf.backcar_state";

    private static final long FRESH_MS = 3_000;
    private static final long BODY_FRESH_MS = 30_000;
    private static final float STEERING_RATIO = 15f;          // steering wheel degrees per road wheel degree
    private static final long LOG_MAX_BYTES = 8L << 20;

    private final Context context;
    /** Every screen showing the car: the home screen, and the side panel next to CarPlay. */
    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static CarData shared;
    private final HandlerThread thread = new HandlerThread("car");
    private Handler handler;
    private IBinder mcu;
    private FileWriter log;
    private File logFile;
    private String lastSent = "";

    // state
    private final boolean[] doors = new boolean[4];           // FL, FR, RL, RR
    private boolean hatch, bonnet;
    private String beam = "off";
    private boolean fogFront, fogRear, turnLeft, turnRight, hazard, reverse, handbrake, beltDriver;
    private double steer;
    private double canSpeed = -1, gpsSpeed = -1;
    private long canSpeedAt, gpsAt, carHeardAt, bodyAt;
    private boolean wireLights;
    private RaiseLights raiseLights;

    // trip computer; -1 = not said by the car
    private double instantL100 = -1, rangeKm = -1, odometerKm = -1, fuelLeft = -1, outsideTemp = Double.NaN;
    private final double[] trip1 = { -1, -1, -1 };            // km, average km/h, average L/100 km
    private final double[] trip2 = { -1, -1, -1 };
    private long nextTripAskAt;

    /** The car's warnings now, keyed "proto:code". */
    private final java.util.LinkedHashMap<String, Warning> warnings = new java.util.LinkedHashMap<>();
    private final byte[] stream = new byte[512];
    private int streamLen;
    private long nextAskAt;

    private static final class Warning {
        final String proto;
        final int code;
        int detail;
        long until;
        Warning(String proto, int code, int detail, long until) { this.proto = proto; this.code = code; this.detail = detail; this.until = until; }
    }

    private CarData(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The one reader of the car for the whole app, started on first use. */
    static synchronized CarData get(Context context) {
        if (shared == null) {
            shared = new CarData(context);
            shared.start();
        }
        return shared;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    void start() {
        thread.start();
        handler = new Handler(thread.getLooper());
        openLog();
        askVehicleAppToShare();
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SHARE);
        f.addAction(ACTION_STEERING);
        f.addAction(ACTION_REVERSE_ON);
        f.addAction(ACTION_REVERSE_OFF);
        context.registerReceiver(receiver, f, null, handler);
        handler.post(this::listenToMcu);
        handler.post(tick);
        startGps();
    }

    void stop() {
        try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(gps);
        thread.quitSafely();
        try { if (log != null) log.close(); } catch (IOException ignored) { }
    }

    @SuppressLint("MissingPermission")
    void startGps() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return;
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, gps, thread.getLooper());
    }

    /** The whole state, in the page's shape. */
    synchronized JSONObject snapshot() {
        try {
            long now = SystemClock.elapsedRealtime();
            JSONObject d = new JSONObject()
                    .put("FL", doors[0]).put("FR", doors[1]).put("RL", doors[2]).put("RR", doors[3]);
            boolean bodyFresh = now - bodyAt < BODY_FRESH_MS;
            // These frames report changes, not a heartbeat. Keep them while the MCU is alive.
            RaiseLights raw = now - carHeardAt < 10_000 ? raiseLights : null;
            String shownBeam = raw != null ? raw.beam() : bodyFresh ? beam : (wireLights ? "position" : "off");
            JSONObject l = new JSONObject()
                    .put("beam", shownBeam)
                    .put("position", raw != null ? raw.position : JSONObject.NULL)
                    .put("lowBeam", raw != null ? raw.low : JSONObject.NULL)
                    .put("turnPhase", raw != null)
                    .put("fogFront", raw != null ? raw.fogFront : bodyFresh && fogFront)
                    .put("fogRear", raw != null ? raw.fogRear : bodyFresh && fogRear)
                    .put("reverse", reverse).put("turnLeft", raw != null ? raw.left : bodyFresh && turnLeft)
                    .put("turnRight", raw != null ? raw.right : bodyFresh && turnRight)
                    .put("hazard", raw == null && bodyFresh && hazard);
            double speed = now - canSpeedAt < FRESH_MS ? canSpeed : (now - gpsAt < FRESH_MS ? gpsSpeed : 0);
            JSONObject src = new JSONObject()
                    .put("car", now - carHeardAt < 10_000).put("gps", now - gpsAt < 5_000);
            org.json.JSONArray w = new org.json.JSONArray();
            for (Warning x : warnings.values()) {
                if (x.until < now) continue;
                w.put(new JSONObject().put("code", x.code).put("proto", x.proto).put("detail", x.detail));
            }
            JSONObject trip = new JSONObject()
                    .put("odometer", known(odometerKm)).put("range", known(rangeKm)).put("instant", known(instantL100))
                    .put("fuel", known(fuelLeft)).put("outsideTemp", Double.isNaN(outsideTemp) ? JSONObject.NULL : outsideTemp)
                    .put("trip1", tripJson(trip1)).put("trip2", tripJson(trip2));
            return new JSONObject()
                    .put("trip", trip)
                    .put("warnings", w)
                    .put("doors", d).put("hatch", hatch).put("bonnet", bonnet).put("lights", l)
                    .put("handbrake", handbrake).put("beltDriver", bodyFresh && beltDriver)
                    .put("speed", Math.max(0, Math.round(speed))).put("steer", Math.round(steer))
                    .put("sources", src);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static Object known(double v) { return v < 0 ? JSONObject.NULL : Math.round(v * 10) / 10.0; }

    private static JSONObject tripJson(double[] t) throws org.json.JSONException {
        return new JSONObject().put("km", known(t[0])).put("avgKmh", known(t[1])).put("avgL100", known(t[2]));
    }

    private void changed() {
        JSONObject s = snapshot();
        String text = s.toString();
        if (text.equals(lastSent)) return;
        lastSent = text;
        for (Listener l : listeners) l.onCarChanged(s);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String prop = systemProperty(REVERSE_PROP);
            if (prop != null && !prop.isEmpty()) synchronized (CarData.this) { reverse = "true".equals(prop); }
            long now = SystemClock.elapsedRealtime();
            if (mcu != null && now >= nextAskAt) {
                askCanBox(RZC_WARNINGS);
                nextAskAt = now + ASK_EVERY_MS;
            }
            if (mcu != null && now >= nextTripAskAt && now >= nextAskAt - ASK_EVERY_MS) {
                askCanBox(RZC_TRIP_NOW);
                askCanBox(RZC_TRIP1);
                askCanBox(RZC_TRIP2);
                nextTripAskAt = now + TRIP_ASK_EVERY_MS;
            }
            changed();      // also ages out stale speeds, sources and single alerts
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- MCU

    private final Binder mcuListener = new Binder() {
        {
            attachInterface(null, MCU_LISTENER);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TX_ON_INFO) {
                try { return super.onTransact(code, data, reply, flags); } catch (Exception e) { return false; }
            }
            byte[] msg = null;
            try {
                data.enforceInterface(MCU_LISTENER);
                msg = data.createByteArray();
            } catch (Exception e) {
                Log.w(TAG, "message MCU illisible", e);
            }
            if (reply != null) reply.writeNoException();
            // the service waits for each listener: hand the message to our own thread at once
            if (msg != null && msg.length > 0) {
                final byte[] m = msg;
                handler.post(() -> onMcu(m));
            }
            return true;
        }
    };

    private void listenToMcu() {
        IBinder b = service(MCU_SERVICE);
        if (b == null) {
            Log.i(TAG, "pas de service MCU (autoradio non QF ?)");
            return;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MCU_MANAGER);
            data.writeStrongBinder(mcuListener);
            data.writeString(context.getPackageName());
            b.transact(TX_REGISTER, data, reply, 0);
            reply.readException();
            b.linkToDeath(() -> { mcu = null; handler.postDelayed(this::listenToMcu, 3000); }, 0);
            mcu = b;
            nextAskAt = SystemClock.elapsedRealtime() + ASK_FIRST_MS;
            Log.i(TAG, "abonné au MCU");
        } catch (Exception e) {
            Log.w(TAG, "abonnement au MCU refusé", e);
            handler.postDelayed(this::listenToMcu, 10_000);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void onMcu(byte[] m) {
        writeLog("mcu", m);
        int cmd = m[0] & 0xFF;
        synchronized (this) {
            carHeardAt = SystemClock.elapsedRealtime();
            if (cmd == CMD_CAN) {
                feedCanBox(m);
            } else if (cmd == CMD_HEADLIGHT && m.length > 1) {
                wireLights = m[1] == 0;         // the framework's polarity: 0 = lamps on
            } else if (cmd == CMD_HANDBRAKE && m.length > 1) {
                handbrake = m[1] == 1;
            }
        }
        changed();
    }

    // ---------------------------------------------------------------- CAN box stream

    /** Bytes after the cmd byte; a frame may be split over messages, so they are buffered. */
    private void feedCanBox(byte[] m) {
        for (int i = 1; i < m.length; i++) {
            if (streamLen == stream.length) streamLen = 0;
            stream[streamLen++] = m[i];
        }
        int start = 0;
        while (true) {
            while (start < streamLen && !isHead(start)) start++;
            if (streamLen - start < 4) break;
            int used = (stream[start] & 0xFF) == 0x5A ? hiworldFrame(start) : raiseFrame(start);
            if (used == 0) break;           // incomplete: wait for more bytes
            start += used;
        }
        System.arraycopy(stream, start, stream, 0, streamLen - start);
        streamLen -= start;
    }

    private boolean isHead(int i) {
        int b = stream[i] & 0xFF;
        if (b == 0xFD || b == 0xFC) return true;
        return b == 0x5A && i + 1 < streamLen && (stream[i + 1] & 0xFF) == 0xA5;
    }

    /** FD len type data… sum, len counting itself, the type, the data and the sum. Returns bytes used, 0 = incomplete. */
    private int raiseFrame(int s) {
        int len = stream[s + 1] & 0xFF;
        if (len < 3 || len > 64) return 1;
        if (streamLen - s < len + 1) return 0;
        int sum = 0;
        for (int k = s + 1; k < s + len; k++) sum += stream[k] & 0xFF;
        if ((sum & 0xFF) != (stream[s + len] & 0xFF)) return 1;
        int type = stream[s + 2] & 0xFF;
        byte[] d = java.util.Arrays.copyOfRange(stream, s + 3, s + len);
        if (type == 0x7D && d.length >= 2 && d[0] == 1) {
            raiseLights = new RaiseLights(d[1] & 0xFF);
        } else if (type == RZC_DOORS && d.length > 0) {
            setDoorBits(d[0] & 0xFF);       // door byte, as seen on a C4 Picasso
        } else if (type == RZC_WARNINGS && d.length > 0) {
            int n = Math.min(d[0] & 0xFF, d.length - 1);
            java.util.List<Warning> now = new java.util.ArrayList<>();
            for (int k = 1; k <= n; k++) {
                int code = d[k] & 0xFF;
                if (code != 0x00 && code != 0xF0 && code != 0xFF) now.add(new Warning("raise", code, 0, Long.MAX_VALUE));
            }
            replaceWarnings("raise", now);
        } else if (type == RZC_TRIP_NOW && d.length >= 4) {
            int inst = be16(d, 0), range = be16(d, 2);
            instantL100 = inst <= 3000 ? inst / 10.0 : -1;
            rangeKm = range > 0 && range <= 2000 ? range : -1;
        } else if ((type == RZC_TRIP1 || type == RZC_TRIP2) && d.length >= 6) {
            double[] t = type == RZC_TRIP1 ? trip1 : trip2;
            int avgL = be16(d, 0), avgKmh = be16(d, 2), dist = be16(d, 4);
            t[0] = dist == 0xFFFF ? -1 : dist / 10.0;
            t[1] = avgKmh <= 250 ? avgKmh : -1;
            t[2] = avgL <= 300 ? avgL / 10.0 : -1;
        } else if (type == RZC_OUTSIDE_TEMP && d.length >= 1) {
            int b = d[0] & 0xFF;
            outsideTemp = (b & 0x80) != 0 ? -(b & 0x7F) : b;
        }
        return len + 1;
    }

    /** 5A A5 len cmd data… sum, len counting the data only. Returns bytes used, 0 = incomplete. */
    private int hiworldFrame(int s) {
        int len = stream[s + 2] & 0xFF;
        if (len > 64) return 1;
        if (streamLen - s < len + 5) return 0;
        int cmd = stream[s + 3] & 0xFF;
        byte[] d = java.util.Arrays.copyOfRange(stream, s + 4, s + 4 + len);
        long now = SystemClock.elapsedRealtime();
        if (cmd == HW_WARNINGS && len >= 4 && len == 0x18) {
            int n = Math.min(d[3] & 0xFF, 10);
            java.util.List<Warning> list = new java.util.ArrayList<>();
            for (int k = 0; k < n && 5 + 2 * k < d.length; k++) {
                int code = ((d[4 + 2 * k] & 0xFF) << 8) | (d[5 + 2 * k] & 0xFF);
                if (code != 0) list.add(new Warning("psa", code, 0, Long.MAX_VALUE));
            }
            replaceWarnings("psa", list);
        } else if (cmd == HW_WARNINGS && len == 2) {
            int code = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            warnings.put("psa:" + code, new Warning("psa", code, 0, now + SINGLE_ALERT_MS));
        } else if (cmd == HW_ALERT && len >= 5) {
            int code = ((d[0] & 0x7F) << 8) | (d[1] & 0xFF);
            if ((d[2] & 0x80) != 0) warnings.put("psa:" + code, new Warning("psa", code, d[4] & 0xFF, Long.MAX_VALUE));
            else warnings.remove("psa:" + code);
        }
        return len + 5;
    }

    private void replaceWarnings(String proto, java.util.List<Warning> list) {
        java.util.Iterator<String> it = warnings.keySet().iterator();
        while (it.hasNext()) if (it.next().startsWith(proto + ":")) it.remove();
        for (Warning w : list) warnings.put(proto + ":" + w.code, w);
    }

    private static int be16(byte[] d, int i) { return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF); }

    /**
     * Asks a Raise box for one of its pages (warnings, trip computer), as the car app does when
     * its own screens open (a Hiworld box sends unasked). A question only: nothing is set in the car.
     */
    private void askCanBox(int page) {
        IBinder b = mcu;
        if (b == null) return;
        byte[] f = { (byte) 0xFD, 0x04, (byte) RZC_QUERY, (byte) page, 0 };
        f[4] = (byte) ((f[1] + (f[2] & 0xFF) + f[3]) & 0xFF);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MCU_MANAGER);
            data.writeByte((byte) CMD_TO_CAN);
            data.writeByteArray(f);
            data.writeInt(f.length);
            b.transact(TX_SEND, data, reply, 0);
            reply.readException();
            writeLog("ask", f);
        } catch (Exception e) {
            Log.w(TAG, "question au boîtier CAN refusée", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void setDoorBits(int b) {
        doors[0] = (b & 0x80) != 0;
        doors[1] = (b & 0x40) != 0;
        doors[2] = (b & 0x20) != 0;
        doors[3] = (b & 0x10) != 0;
        hatch = (b & 0x08) != 0;
        bonnet = (b & 0x04) != 0;
    }

    // ---------------------------------------------------------------- com.qf.vehicle

    /** The car app only shares with apps listed in the global settings; needs WRITE_SECURE_SETTINGS (adb). */
    private void askVehicleAppToShare() {
        String me = context.getPackageName();
        try {
            for (String k : new String[] { "KeyShareCarbodyState", "KeyShareAcState", "KeyShareRadarState", "KeyShareDoorWindow" }) {
                Settings.Global.putInt(context.getContentResolver(), me + k, 1);
            }
            Log.i(TAG, "partage demandé à " + VEHICLE);
        } catch (SecurityException e) {
            Log.i(TAG, "pas d'autorisation WRITE_SECURE_SETTINGS : données partagées indisponibles");
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            synchronized (CarData.this) {
                carHeardAt = SystemClock.elapsedRealtime();
                switch (a) {
                    case ACTION_REVERSE_ON: reverse = true; break;
                    case ACTION_REVERSE_OFF: reverse = false; break;
                    case ACTION_STEERING: onSteering(i); break;
                    case ACTION_SHARE:
                        byte[] d = i.getByteArrayExtra(EXTRA_SHARE);
                        if (d != null) { writeLog("share", d); onShare(d); }
                        break;
                    default: break;
                }
            }
            changed();
        }
    };

    /** Steering wheel angle in tenths of a degree (right positive), full lock in the same unit. */
    private void onSteering(Intent i) {
        int angle = i.getIntExtra("extra_angle", 0);
        float rate = i.getFloatExtra("extra_rate", 0f);
        float wheel = (rate > 0 && (rate / 10f < 90f || rate / 10f > 900f)) ? angle / rate * 545f : angle / 10f;
        steer = Math.max(-35, Math.min(35, -wheel / STEERING_RATIO));      // the page counts left as positive
    }

    /** Messages are 0x2E, kind, data…; multi-byte values big-endian, all ones = not sent. */
    private void onShare(byte[] d) {
        if (d.length < 3 || (d[0] & 0xFF) != 0x2E) return;
        int kind = d[1] & 0xFF;
        if (kind == 3) {
            setDoorBits(d[2] & 0xFF);
        } else if (kind == 2 && d.length >= 20) {
            boolean empty = true;
            for (int k = 2; k < d.length; k++) if (d[k] != 0 && d[k] != (byte) 0xFF) { empty = false; break; }
            if (empty) return;          // the CAN box fills none of it on some cars
            long now = SystemClock.elapsedRealtime();
            bodyAt = now;
            int speed = u16(d, 7);
            if (speed >= 0 && speed < 300) { canSpeed = speed; canSpeedAt = now; }
            int odo = d.length > 17 ? ((d[15] & 0xFF) << 16) | ((d[16] & 0xFF) << 8) | (d[17] & 0xFF) : 0xFFFFFF;
            if (odo != 0xFFFFFF && odo > 0) odometerKm = odo / 10.0;
            int range = u16(d, 18), fuel = u16(d, 11), dist1 = u16(d, 20), inst = u16(d, 51);
            if (range > 0) rangeKm = range / 10.0;
            if (fuel >= 0) fuelLeft = fuel / 10.0;
            if (dist1 >= 0) trip1[0] = dist1 / 10.0;
            if (inst >= 0) instantL100 = inst / 10.0;
            boolean high = bit(d, 2, 7), low = bit(d, 2, 6), side = bit(d, 3, 7);
            beam = high ? "high" : low ? "low" : side ? "position" : "off";
            fogFront = bit(d, 2, 5);
            fogRear = bit(d, 2, 4);
            turnRight = bit(d, 2, 3);
            turnLeft = bit(d, 2, 2);
            hazard = bit(d, 2, 1);
            beltDriver = bit(d, 5, 3);
            handbrake = bit(d, 6, 4);
        }
    }

    private static boolean bit(byte[] d, int i, int b) { return i < d.length && ((d[i] >> b) & 1) == 1; }

    private static int u16(byte[] d, int i) {
        if (i + 1 >= d.length) return -1;
        int v = ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        return v == 0xFFFF ? -1 : v;
    }

    // ---------------------------------------------------------------- GPS

    private final LocationListener gps = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            synchronized (CarData.this) {
                gpsAt = SystemClock.elapsedRealtime();
                gpsSpeed = loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0;
            }
            changed();
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---------------------------------------------------------------- log of raw frames

    private void openLog() {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        logFile = new File(dir, "mcu.log");
        try {
            if (logFile.length() > LOG_MAX_BYTES) logFile.renameTo(new File(dir, "mcu.old.log"));
            log = new FileWriter(logFile, true);
        } catch (IOException e) {
            Log.w(TAG, "journal impossible", e);
        }
    }

    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private void writeLog(String source, byte[] m) {
        if (log == null) return;
        StringBuilder sb = new StringBuilder(m.length * 3 + 24);
        sb.append(stamp.format(new Date())).append(' ').append(source);
        for (byte b : m) sb.append(' ').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        try {
            log.write(sb.append('\n').toString());
            log.flush();
            if (logFile.length() > LOG_MAX_BYTES) { log.close(); openLog(); }
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- hidden platform calls

    private static IBinder service(String name) {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String systemProperty(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }
}
