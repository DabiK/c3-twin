package fr.dabi.c3twin;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** Passive experimental receiver. Never opens serial ports or transmits MCU commands. */
final class MtcSignals {
    static final String[] FIELDS = {"FL", "FR", "RL", "RR", "hatch", "bonnet", "position", "lowBeam", "highBeam", "turnLeft", "turnRight"};
    static final String[] LABELS = {"Porte avant gauche", "Porte avant droite", "Porte arrière gauche", "Porte arrière droite", "Coffre", "Capot", "Feux de position", "Feux de croisement", "Feux de route", "Clignotant gauche", "Clignotant droit"};
    final Context context;
    private final Map<String, byte[]> values = new LinkedHashMap<>();
    private final Map<String, Long> times = new HashMap<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private JSONObject accepted;
    private final Runnable changed;
    private String error = "";
    private long frames;
    private final Handler main = new Handler(Looper.getMainLooper());
    MtcSignals(Context context, Runnable changed) {
        this.context = context; this.changed = changed;
        try { accepted = validate(new JSONObject(prefs().getString("accepted", "{}"))); }
        catch (Exception e) { accepted = new JSONObject(); error = "Configuration invalide : refaire l’analyse"; }
        IntentFilter filter = new IntentFilter("com.microntek.sync");
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
        } catch (RuntimeException e) { error = "Réception sync refusée : " + e.getClass().getSimpleName(); }
        main.post(poll);
    }
    void stop() {
        main.removeCallbacks(poll);
        try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
    }
    private SharedPreferences prefs() { return context.getSharedPreferences("mtch-signals-v1", 0); }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            try {
                byte[] data = intent.getByteArrayExtra("syncdata");
                if (data != null && data.length > 0 && data.length <= 128) record("sync", data);
            } catch (RuntimeException e) { synchronized (MtcSignals.this) { error = "Trame sync illisible"; } }
        }
    };
    private final Runnable poll = new Runnable() {
        public void run() {
            try {
                String value = Settings.System.getString(context.getContentResolver(), "com.microntek.controlinfo.door");
                if (value != null) {
                    long n = Long.parseLong(value.trim());
                    if (n >= Integer.MIN_VALUE && n <= 0xffffffffL)
                        record("door", new byte[]{(byte)n, (byte)(n >> 8), (byte)(n >> 16), (byte)(n >> 24)});
                }
            } catch (NumberFormatException e) { synchronized (MtcSignals.this) { error = "Clé portes présente mais format non numérique"; } }
            catch (RuntimeException e) { synchronized (MtcSignals.this) { error = "Lecture de la clé portes refusée"; } }
            main.postDelayed(this, 500);
        }
    };
    private synchronized void record(String source, byte[] bytes) {
        frames++;
        boolean different = !Arrays.equals(values.get(source), bytes);
        if (different) {
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02X ", b & 255));
            history.addLast(SystemClock.elapsedRealtime() + " " + source + " " + hex);
            while (history.size() > 120) history.removeFirst();
        }
        values.put(source, bytes.clone()); times.put(source, SystemClock.elapsedRealtime());
        if (different) changed.run();
    }
    synchronized Map<String, byte[]> capture() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long now = SystemClock.elapsedRealtime();
        for (String source : values.keySet()) if (now - times.get(source) < 10000) out.put(source, values.get(source).clone());
        return out;
    }
    synchronized JSONObject configuration() { try { return new JSONObject(accepted.toString()); } catch (Exception e) { return new JSONObject(); } }
    static JSONObject validate(JSONObject config) throws JSONException {
        Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
            String field = keys.next();
            if (!Arrays.asList(FIELDS).contains(field)) throw new JSONException("Champ inconnu");
            JSONObject m = config.getJSONObject(field);
            String source = m.getString("source"); int bit = m.getInt("bit");
            if (!(source.equals("sync") || source.equals("door")) || bit < 0 || bit >= (source.equals("door") ? 32 : 1024)) throw new JSONException("Mapping invalide");
            m.getBoolean("inverted");
        }
        return config;
    }
    synchronized boolean accept(JSONObject draft) {
        try {
            JSONObject copy = validate(new JSONObject(draft.toString()));
            if (!prefs().edit().putString("accepted", copy.toString()).commit()) return false;
            accepted = copy; changed.run(); return true;
        } catch (Exception e) { return false; }
    }
    synchronized JSONObject states(JSONObject config) {
        JSONObject out = new JSONObject(); Map<String, byte[]> live = capture();
        for (String field : FIELDS) try {
            JSONObject mapping = config.optJSONObject(field);
            if (mapping == null) { out.put(field, JSONObject.NULL); continue; }
            byte[] bytes = live.get(mapping.getString("source"));
            int bit = mapping.getInt("bit");
            out.put(field, bytes == null || bit >= bytes.length * 8 ? JSONObject.NULL : SignalBits.read(bytes, bit, mapping.getBoolean("inverted")));
        } catch (Exception ignored) { }
        return out;
    }
    synchronized JSONObject patch() {
        JSONObject state = states(accepted);
        JSONObject patch = new JSONObject(), doors = new JSONObject(), lights = new JSONObject();
        try {
            for (String field : new String[]{"FL", "FR", "RL", "RR"}) doors.put(field, state.opt(field));
            patch.put("doors", doors).put("hatch", state.opt("hatch")).put("bonnet", state.opt("bonnet"));
            for (String field : new String[]{"position", "lowBeam", "turnLeft", "turnRight"}) lights.put(field, state.opt(field));
            // The beam enum cannot express unknown; detailed flags and the diagnostic retain unknown status.
            lights.put("beam", state.optBoolean("highBeam") ? "high" : state.optBoolean("lowBeam") ? "low" : state.optBoolean("position") ? "position" : "off");
            boolean phase = false;
            for (String field : new String[]{"turnLeft", "turnRight"}) {
                JSONObject mapping = accepted.optJSONObject(field);
                if (state.optBoolean(field) && mapping != null && mapping.optBoolean("phase", false)) phase = true;
            }
            lights.put("turnPhase", phase).put("hazard", false);
            patch.put("lights", lights);
            patch.put("sources", new JSONObject().put("car", !capture().isEmpty()).put("experimental", true).put("configured", accepted.length() > 0));
        } catch (JSONException ignored) { }
        return patch;
    }
    private static String servicePresent(String name) {
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
            return binder == null ? "absent" : "présent";
        } catch (Exception e) { return "non accessible"; }
    }
    synchronized String report(JSONObject draft) {
        StringBuilder out = new StringBuilder("MTCH expérimental — analyse à l’arrêt\n");
        out.append("carservice : ").append(servicePresent("carservice")).append(" ; mcu_service : ").append(servicePresent("mcu_service")).append("\n");
        out.append("Réceptions/lectures : ").append(frames).append("\nConfiguration acceptée : ").append(accepted.length()).append(" signaux\n");
        out.append("Sources fraîches : ").append(capture().keySet()).append("\n");
        if (!error.isEmpty()) out.append(error).append('\n');
        out.append("\nAperçu du brouillon\n");
        JSONObject state = states(draft);
        for (int i = 0; i < FIELDS.length; i++) out.append(LABELS[i]).append(" : ").append(state.isNull(FIELDS[i]) ? "inconnu" : state.optBoolean(FIELDS[i]) ? "ACTIF" : "inactif").append("\n");
        out.append("\nConfiguration\n").append(draft).append("\n\nDerniers changements bruts\n");
        for (String line : history) out.append(line).append('\n');
        return out.toString();
    }
}
