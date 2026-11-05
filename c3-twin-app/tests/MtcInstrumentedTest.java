package fr.dabi.c3twin;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;

/** Synthetic transport/persistence tests: run only in an emulator, never vehicle proof. */
public final class MtcInstrumentedTest extends Instrumentation {
    private String phase;
    public void onCreate(Bundle args) { super.onCreate(args); phase = args.getString("phase", "save"); start(); }
    private void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    public void onStart() {
        Bundle result = new Bundle(); MtcSignals signals = null;
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("mtch-signals-v1", 0);
        try {
            if (phase.equals("save")) {
                prefs.edit().putString("testBackup", prefs.getString("accepted", "{}")).remove("accepted").commit();
            }
            signals = new MtcSignals(context, () -> {});
            if (phase.equals("save")) {
                check(signals.configuration().length() == 0, "starts unconfigured");
                JSONObject draft = new JSONObject().put("FL", new JSONObject().put("source", "sync").put("bit", 3).put("inverted", false));
                context.sendBroadcast(new Intent("com.microntek.sync").setPackage(context.getPackageName()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("syncdata", new byte[]{8}));
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (signals.capture().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                check(signals.states(draft).getBoolean("FL"), "receives byte array broadcast");
                check(signals.configuration().length() == 0, "draft does not auto-accept");
                check(signals.accept(draft), "accept commits");
                check(signals.patch().getJSONObject("doors").getBoolean("FL"), "accepted mapping drives twin");
                JSONObject invalid = new JSONObject().put("FL", new JSONObject().put("source", "sync").put("bit", -1).put("inverted", false));
                check(!signals.accept(invalid), "invalid profile rejected");
                check(signals.configuration().getJSONObject("FL").getInt("bit") == 3, "failed save preserves accepted profile");
                draft.remove("FL");
                check(signals.configuration().length() == 1, "draft mutation cannot alter accepted profile");
                SystemClock.sleep(10500);
                check(signals.states(signals.configuration()).isNull("FL"), "expired source becomes unknown");
            } else {
                check(signals.configuration().getJSONObject("FL").getInt("bit") == 3, "accepted profile survives process restart");
                check(signals.states(signals.configuration()).isNull("FL"), "no stale live state restored at boot");
                context.sendBroadcast(new Intent("com.microntek.sync").setPackage(context.getPackageName()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("syncdata", new byte[]{0}));
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (signals.capture().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                check(!signals.patch().getJSONObject("doors").getBoolean("FL"), "restored profile decodes fresh event");
                prefs.edit().putString("accepted", prefs.getString("testBackup", "{}")).remove("testBackup").commit();
            }
            result.putString("stream", "MTCH " + phase + " tests passed (synthetic emulator data)\n"); finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL " + phase + ": " + e.toString() + "\n" + (signals == null ? "no receiver" : signals.report(signals.configuration()))); finish(0, result);
        } finally { if (signals != null) signals.stop(); }
    }
}
