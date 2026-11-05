package fr.dabi.c3twin;

import android.app.*;
import android.os.*;
import android.content.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Local calibration draft; only the explicit Accept action changes the saved profile. */
final class SignalSetup {
    private final Activity activity;
    private final MtcSignals signals;
    private final JSONObject draft;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Map<String, byte[]> baseline, active;
    private int selected;
    private TextView status;
    private AlertDialog dialog;
    SignalSetup(Activity activity, MtcSignals signals) {
        this.activity = activity; this.signals = signals; this.draft = signals.configuration();
    }
    void close() { if (dialog != null) dialog.dismiss(); handler.removeCallbacks(refresh); }
    void show() {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(1); box.setPadding(24, 8, 24, 8);
        TextView help = new TextView(activity);
        help.setText("À l’arrêt. Choisis un élément, laisse les autres immobiles. Capture son état inactif, actif puis inactif. Les correspondances restent un brouillon jusqu’à validation.\nSi aucune source ne remonte, exporte le diagnostic. Aucun réglage du firmware n’est modifié."); box.addView(help);
        Spinner fields = new Spinner(activity);
        fields.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, MtcSignals.LABELS));
        fields.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int position, long id) { selected = position; baseline = active = null; }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        }); box.addView(fields);
        button(box, "1 · Capturer fermé / éteint", () -> { baseline = signals.capture(); active = null; toast(baseline.isEmpty() ? "Aucun signal reçu : exporter le diagnostic" : "État inactif capturé"); });
        button(box, "2 · Capturer ouvert / allumé", () -> {
            if (baseline == null || baseline.isEmpty()) { toast("Capture d’abord l’état inactif"); return; }
            active = signals.capture(); toast("État actif capturé. Reviens maintenant à l’état inactif.");
        });
        button(box, "3 · Capturer le retour et proposer", this::candidates);
        button(box, "Retirer ce signal du brouillon", () -> { draft.remove(MtcSignals.FIELDS[selected]); toast("Signal retiré du brouillon"); });
        button(box, "Exporter le diagnostic…", () -> ((MainActivity)activity).exportDiagnostic(signals.report(draft)));
        status = new TextView(activity); status.setTextSize(12); status.setTypeface(android.graphics.Typeface.MONOSPACE); box.addView(status);
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        dialog = new AlertDialog.Builder(activity).setTitle("Analyse MTCH · brouillon").setView(scroll)
            .setNegativeButton("Fermer sans accepter", null).setPositiveButton("Accepter la configuration", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            new AlertDialog.Builder(activity).setTitle("Enregistrer ce profil ?")
                .setMessage("Après avoir vérifié les valeurs de l’aperçu en manipulant les éléments, confirme. Les " + draft.length() + " signaux seront appliqués maintenant et à chaque ouverture de C3 Twin. Les champs non associés restent inconnus. Une configuration vide retire tous les signaux.")
                .setNegativeButton("Continuer l’analyse", null).setPositiveButton("Valider et enregistrer", (a,b) -> {
                    if (signals.accept(draft)) { toast("Configuration enregistrée"); dialog.dismiss(); }
                    else toast("Échec de l’enregistrement : configuration précédente conservée");
                }).show();
        }));
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refresh));
        dialog.show(); handler.post(refresh);
    }
    private final Runnable refresh = new Runnable() {
        public void run() { status.setText(signals.report(draft)); handler.postDelayed(this, 700); }
    };
    private void candidates() {
        if (baseline == null || active == null) { toast("Capture les deux premiers états"); return; }
        Map<String, byte[]> end = signals.capture();
        ArrayList<JSONObject> choices = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>();
        for (String source : baseline.keySet()) {
            byte[] first = baseline.get(source), middle = active.get(source), last = end.get(source);
            if (last == null || last.length != first.length) continue;
            for (int bit : SignalBits.changed(first, middle)) {
                if (SignalBits.read(first, bit, false) != SignalBits.read(last, bit, false)) continue;
                try {
                    boolean inverted = !SignalBits.read(middle, bit, false);
                    choices.add(new JSONObject().put("source", source).put("bit", bit).put("inverted", inverted));
                    labels.add(source + " · octet " + (bit / 8) + " · bit " + (bit % 8) + (inverted ? " · inversé" : ""));
                } catch (JSONException ignored) { }
            }
        }
        if (choices.isEmpty()) { toast("Aucune correspondance. Réessaye, ou exporte le diagnostic. Pour un clignotant, capture pendant sa phase allumée."); return; }
        new AlertDialog.Builder(activity).setTitle("Candidats — vérifier l’aperçu ensuite")
            .setItems(labels.toArray(new String[0]), (d, index) -> {
                try {
                    String field = MtcSignals.FIELDS[selected];
                    JSONObject mapping = choices.get(index);
                    draft.put(field, mapping);
                    if (field.startsWith("turn")) new AlertDialog.Builder(activity)
                        .setTitle("Comportement du signal clignotant")
                        .setMessage("Regarde l’aperçu pendant plusieurs flashes. Le signal alterne-t-il à chaque flash, ou reste-t-il actif tant que le commodo est enclenché ?")
                        .setNegativeButton("Actif en continu", (a,b) -> { try { mapping.put("phase", false); } catch (JSONException ignored) {} })
                        .setPositiveButton("Alter­ne à chaque flash", (a,b) -> { try { mapping.put("phase", true); } catch (JSONException ignored) {} }).show();
                    toast("Candidat ajouté : vérifie qu’il suit uniquement cet élément");
                }
                catch (JSONException ignored) { }
            }).setNegativeButton("Annuler", null).show();
    }
    private void button(LinearLayout box, String label, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setOnClickListener(v -> action.run()); box.addView(button);
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
