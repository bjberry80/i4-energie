# i4 Energie – Android Auto App (Spec v1)

Mockup: `mockup-v1.png` (Referenz-Rendering: `mockup-v1.html`, Canvas-Code mit Beispieldaten).

## 1. Ziel

Eine kleine, sideloaded Android-Auto-App, die während der Fahrt auf dem Display des BMW i4 ein Diagramm „verbleibende Energie über Strecke“ zeigt. Die Daten kommen aus Home Assistant (HA). Die App rechnet nichts Fahrzeugspezifisches selbst aus. Sie liest HA-Sensoren und zeichnet sie.

Die App registriert sich regulär als **Navigations-App** über die Android for Cars App Library. Diese Kategorie darf während der Fahrt eine eigene Fläche (Surface) zeichnen. Es werden keine Sperren umgangen. Die App nutzt keine WebView, keine Bildschirmspiegelung und keine Root- oder Xposed-Hacks.

## 2. Rahmen

| Punkt | Vorgabe |
|---|---|
| Sprache | Kotlin |
| Bibliothek | `androidx.car.app:app` (aktuelle stabile Version prüfen), zusätzlich `androidx.car.app:app-projected` |
| Kategorie | `androidx.car.app.category.NAVIGATION` |
| minSdk / targetSdk | minSdk 29, targetSdk = aktuelles stabiles SDK |
| Handy | Android 15, sideload als APK (siehe §10: fürs echte Auto über Google Play) |
| Android Auto | Entwicklermodus mit „Unbekannte Quellen“ ist bereits aktiv |
| Build | Gradle. Zusätzlich ein GitHub-Actions-Workflow, der eine Debug- oder Release-APK als Artifact baut |
| Sprache der UI | Deutsch |

## 3. Architektur

```
Phone-App (Activity)        Car-App (CarAppService)
  Einstellungen               Session -> NavigationScreen
  HA-URL + Token  ─┐            SurfaceCallback -> ChartRenderer (Canvas)
                   └─> HaRepository (Polling, Cache, StateFlow<TripData>)
```

- `HaRepository`: Holt Daten per HA-REST-API. Wird von Phone-UI und Car-Session gemeinsam genutzt (Singleton, Coroutines).
- `ForecastCalc`: Reine Kotlin-Funktionen ohne Android-Abhängigkeit, mit Unit-Tests (Abschnitt 6).
- `ChartRenderer`: Zeichnet auf `android.graphics.Canvas`. Zeichnet nur in die **stable area** (`onStableAreaChanged`), damit die Action-Strip-Buttons nichts verdecken.
- `NavigationScreen`: Liefert ein `NavigationTemplate` (oder `MapWithContentTemplate`, falls die Bibliotheksversion es empfiehlt) mit Action Strip. Es wird keine echte Zielführung gestartet und kein `NavigationManager.navigationStarted()` aufgerufen.

### Manifest (Kernpunkte)

- `CarAppService` mit Intent-Filter `androidx.car.app.CarAppService` und Category `androidx.car.app.category.NAVIGATION`.
- Permissions: `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`, `INTERNET`.
- `meta-data` `androidx.car.app.minCarApiLevel` = 1 (oder was die gewählte Bibliotheksversion verlangt).
- `HostValidator`: Für den Sideload-Betrieb ist `ALLOW_ALL_HOSTS_VALIDATOR` in Debug-Builds akzeptabel. In Release die bekannten Android-Auto-Hosts eintragen.

## 4. Datenquelle Home Assistant

Authentifizierung: Long-Lived Access Token als `Authorization: Bearer <token>`. URL und Token gibt der Nutzer in der Phone-App ein. Speichern in `EncryptedSharedPreferences` (oder DataStore mit Verschlüsselung). Token nie loggen.

Abruf: `GET {base}/api/states/{entity_id}` für jede Entität, **alle 30 s** solange die Car-Session aktiv ist, plus einmal sofort beim Start und bei Tipp auf „Aktualisieren“. Optional später WebSocket `subscribe_entities`. Timeout 10 s. Bei Fehler: letzte Daten behalten und einen Hinweis „Stand hh:mm“ zeigen.

| Entität | Feld | Bedeutung |
|---|---|---|
| `sensor.i4_fahrt` | `attributes.start_km` | Kilometerstand bei Fahrtbeginn (float) |
| | `attributes.start_soc` | SOC bei Fahrtbeginn (%) |
| | `attributes.punkte` | `[[km_seit_start, soc], …]`, max. 300, chronologisch |
| | `attributes.fahrtverbrauch` | kWh/100 km dieser Fahrt oder `null` |
| `sensor.i4_route` | `state` | Routenlänge km |
| | `attributes.ziel` | Zielname (String) |
| | `attributes.aufstieg` / `abstieg` | Höhenmeter |
| | `attributes.start_km` | Kilometerstand bei Routenberechnung |
| | `attributes.profil` | `[[km, höhe_m, lat, lon], …]`, ca. 150 Punkte, km ab Routenstart |
| | `attributes.prognose` | `[soc, …]`, gleiche Länge wie `profil` |
| `sensor.i4_edrive40_battery_hv_state_of_charge` | `state` | aktueller SOC % |
| `sensor.i4_edrive40_trip_battery_charge_level_at_end_of_trip` | `state` | BMW-Ankunfts-SOC % (kann `unknown` sein) |

Robustheit: Attribute können fehlen, `null`, `"unknown"` oder `"unavailable"` sein. Zahlen können als String kommen. Alles defensiv parsen.

## 5. Darstellung (siehe Mockup)

Dunkles Theme, Hintergrund `#121212`. Keine Animationen.

- **Kopfzeile links:** Zielname fett; daneben „noch X km · ↑ A m ↓ B m · Ist S %“.
- **Kopfzeile rechts:** groß „Ankunft N %“ in `#a5d6a7`; darunter klein „Plan P % · BMW B % · Verbrauch ±V %“. Fehlende Werte weglassen.
- **Diagramm:**
  - X-Achse: km seit Fahrtbeginn. Bereich 0 bis max(Routenende, letzter Ist-Punkt, 10). Beschriftung alle 50 km (bei Routen unter 100 km alle 10 km).
  - Rechte Y-Achse: SOC 0–100 %, Gitter alle 20 %.
  - Linke Y-Achse: Höhe in m, Bereich automatisch, ohne eigene Gitterlinien.
- **Linien** (Zeichenreihenfolge von hinten nach vorn):
  1. Höhe: Fläche `rgba(158,158,158,0.18)` mit Kontur `rgba(158,158,158,0.6)`.
  2. BMW-Ankunft: horizontale Linie `#2196f3`, gepunktet, nur wenn eine Fahrt läuft und der Wert gültig ist.
  3. Plan (Routenprognose): `#ff9800`, gestrichelt, 4 px.
  4. Ist: `#4caf50`, 6 px, Treppenform (horizontal, dann vertikal).
  5. Live-Prognose: `#a5d6a7`, gepunktet, 5 px, vom letzten Ist-Punkt bis Routenende.
  6. Auto-Marker: Kreis am letzten Ist-Punkt.
- **Legende:** klein, rechts unter dem Action Strip.
- **Action Strip:** „Aktualisieren“ (⟳) und „Einstellungen“ (⚙, öffnet einen Hinweis, dass die Einstellungen am Handy erfolgen).
- **Leerzustände:**
  - Keine Route: Nur Ist-Linie und Text „Kein Ziel – Ziel in Home Assistant eingeben“.
  - Keine Fahrt: Nur Plan und Höhe, X ab 0, „Ankunft“ zeigt den Plan-Endwert.
  - Keine Daten oder Fehler: zentriert „Keine Verbindung zu Home Assistant“ plus „Stand hh:mm“.
- **Schriftgrößen:** in dp skalieren, mindestens 20 dp für Achsen, 32 dp für die Kopfzeile, 40 dp für den Ankunftswert (ursprünglich 56 dp, im DHU-Test zu groß). Neu zeichnen bei `onSurfaceAvailable`, `onStableAreaChanged`, `onVisibleAreaChanged` und bei neuen Daten.

## 6. Berechnungen (`ForecastCalc`)

Die Logik ist 1:1 aus der funktionierenden HA-Karte (plotly) übernommen.

**Offset Route ↔ Fahrt:**

```
last = letzter Ist-Punkt km (0 wenn keine Punkte)
routeGilt = route.profil vorhanden && !(route.start_km < fahrt.start_km - 1)
off = route.start_km - (fahrt.start_km ?: route.start_km)
if (off > last + 1) off = 0          // Route wurde vor der Fahrt geplant -> ab 0
X[i] = profil[i].km + off            // Route-Punkte auf Fahrt-km abbilden
```

Ist `routeGilt` false, Route ignorieren (alte Route einer früheren Fahrt).

**Plan-Linie:** `(X[i], prognose[i])`.

**Live-Prognose** (nur wenn Punkte, Route und Prognose vorhanden und `last < X.last`):

```
P(x) = lineare Interpolation von prognose über X (geklemmt an den Enden)
s0   = SOC des letzten Ist-Punkts mit km <= off (sonst erster Punkt)
socL = SOC des letzten Ist-Punkts
soll = prognose[0] - P(last)              // vorhergesagter Verbrauch seit Routenstart
f    = soll > 2 ? clamp((s0 - socL) / soll, 0.5, 2.0) : 1.0
live = [(last, socL)] + [(X[i], socL - (P(last) - prognose[i]) * f) for X[i] > last]
Ankunft = live.last.soc   (Fallback: prognose.last, sonst nichts)
Verbrauch ±V % = round((f - 1) * 100), nur anzeigen wenn soll > 2
```

**Unit-Tests (Pflicht):**
- Beispiel: Route 216 km ab Fahrt-km 125, `prognose = 66 - km*0.22` in 8-km-Schritten; Ist alle 10 km mit `98 - km*0.25` bis 230 km. Erwartet: Ankunft ≈ 11 %, 15 Live-Punkte.
- Ohne Ist-Punkte: Ankunft = letzter Prognosewert.
- Route älter als die Fahrt (`route.start_km < fahrt.start_km - 1`): keine Plan- und keine Live-Linie.
- `soll <= 2`: f = 1.
- Kaputte oder fehlende Attribute: keine Exception.

## 7. Phone-App

Eine Activity mit:
- Feldern für HA-URL (z. B. `https://xyz.ui.nabu.casa`) und Token.
- Button „Verbindung testen“ (`GET /api/` muss `{"message": "API running."}` liefern).
- Status der letzten Abfrage.
- Kurzer Anleitung zum Aktivieren der unbekannten Quellen in Android Auto.

## 8. Später (nicht Teil von v1)

- Ziel aus dem Auto setzen: `SearchTemplate` → `POST /api/services/input_text/set_value` für `input_text.i4_ziel`. Vorsicht: Die Tastatur ist während der Fahrt gesperrt. Das ist dann nur im Stand oder per Sprache möglich.
- Routentyp-Auswahl (`input_select.i4_routentyp`).
- WebSocket statt Polling.

## 9. Abnahme

1. APK lässt sich auf Android 15 installieren. Die App erscheint im Android-Auto-Launcher des i4 (Unbekannte Quellen an).
2. Mit gesetztem Ziel und laufender Fahrt zeigt das Display während der Fahrt das Diagramm wie im Mockup. Es wird nicht durch die Fahrsperre ausgeblendet.
3. Werte stimmen mit der HA-Karte `i4-dashboard-v7` überein: Plan-Endwert, Live-Ankunft auf ±1 %.
4. Verbindungsabbruch (Tunnel): Letzter Stand bleibt sichtbar, mit Zeitstempel. Kein Absturz.
5. Unit-Tests aus Abschnitt 6 grün. Test im Desktop Head Unit (DHU) vor dem Auto-Test.

## 10. Hinweise für die Umsetzung

- Google-Richtlinien für Navi-Apps sehen auf der Fläche eigentlich eine Karte vor. Für eine sideloaded App ohne Play-Store-Review ist das egal, eine Veröffentlichung im Play Store ist nicht geplant.
- Keine echte Zielführung (keine Turn-by-Turn-Daten, kein `navigationStarted`), damit die App nicht mit Google Maps oder BMW-Navi um die aktive Navigation konkurriert.
- Projektordner und Doku auf Deutsch. Code-Bezeichner auf Englisch.
- Erkenntnis aus dem Autotest (16.10.2026): Im echten Auto akzeptiert Android Auto nur Apps, die über Google Play bezogen wurden. „Unbekannte Quellen“ wirkt nur im DHU. Auch `adb install -i com.android.vending` hilft nicht, weil Android Auto die Eigentümerschaft bei Google Play abfragt. Damit gelten Abnahmepunkt 1 und §2 (Sideload als APK) so nicht. Weg: interner Test-Track in der Play Console, am 19.10.2026 im i4 erfolgreich getestet (Anleitung: `PLAY-TESTTRACK.md`). Die Fahrsperre selbst ist kein Problem: Solange die App lief, blieb sie auch in D und während der Fahrt sichtbar.
