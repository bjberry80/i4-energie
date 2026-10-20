# Home Assistant side

The Android Auto app only draws what Home Assistant provides. This folder contains everything needed on the Home Assistant side:

| File | What it is |
|---|---|
| [packages/i4_energie.yaml](packages/i4_energie.yaml) | Package with the trip recorder, the route sensor, helpers, OpenRouteService calls and the route automation |
| [dashboard-card.yaml](dashboard-card.yaml) | Optional dashboard card (Plotly) with the same chart plus a route map, for the phone/tablet |

## How it works

```
Enter destination (input_text.i4_ziel)
        │
        ▼
Automation "i4 Route berechnen"
  1. geocode destination via OpenRouteService (biased to the car's position)
  2. route car position → destination, with elevation
  3. resample to ~150 points: [km, elevation, lat, lon]
  4. SOC forecast per segment
        │  event i4_route
        ▼
sensor.i4_route   (length, destination, ascent/descent, profile, forecast)

Car moves / SOC or mileage changes
        ▼
sensor.i4_fahrt   (start km/SOC, recorded [km, soc] points)
```

**Trip detection** (`sensor.i4_fahrt`): a new trip starts when the car starts moving after more than 15 minutes of standstill, when the SOC rises by more than 1 % while parked (charging), or when you press "i4 Route neu" while parked. While moving, every SOC or mileage update adds a point (max. 300).

**Forecast** (`prognose`): starts at the current SOC. For each route segment:

```
energy (kWh) = average consumption (kWh/km) × distance
             + elevation gain (m) × 0.0057 kWh/m        uphill
             − elevation loss (m) × 0.0057 × 0.6        downhill (60 % recuperation)
SOC drop     = energy / battery capacity × 100
```

0.0057 kWh per metre is the potential energy of a ~2.1 t car (m·g·h). The average consumption comes from the car (`range_ev_average_electric_consumption`). The battery capacity comes from the car, a manual number entity, or falls back to 80.7 kWh (i4 eDrive40 net).

The app then corrects this plan live based on how your actual consumption compares to it (see [ForecastCalc.kt](../app/src/main/java/de/i4energie/calc/ForecastCalc.kt)). The dashboard card does the same in JavaScript.

## Requirements

1. **A BMW integration** that provides these entities. Mine come from a BMW CarData custom integration (`custom_components/cardata`, via HACS). The `i4_edrive40` part comes from the car's name in that integration.

   | Entity | Used for |
   |---|---|
   | `sensor.i4_edrive40_battery_hv_state_of_charge` | SOC % |
   | `sensor.i4_edrive40_vehicle_mileage` | odometer km |
   | `binary_sensor.i4_edrive40_vehicle_motion_state` | car is moving |
   | `device_tracker.i4_edrive40_location` | start of the route |
   | `sensor.i4_edrive40_range_ev_average_electric_consumption` | kWh/100 km |
   | `sensor.i4_edrive40_battery_hv_max_size` | battery capacity kWh (optional) |
   | `number.i4_edrive40_manual_battery_capacity` | battery capacity kWh (optional fallback) |
   | `sensor.i4_edrive40_trip_battery_charge_level_at_end_of_trip` | BMW arrival SOC (optional, only with active BMW navigation) |
   | `sensor.i4_edrive40_range_ev_remaining_range` | only for the dashboard card |

   **Different car or names?** Search and replace `i4_edrive40` in both files with your entity prefix. Any integration (or template sensor) delivering SOC, odometer, a "moving" state, position and average consumption works, not only BMW.

   Keep the names `sensor.i4_fahrt` and `sensor.i4_route`: the app reads exactly these.

2. **An OpenRouteService API key** (free, 2,000 routes/day): sign up at [openrouteservice.org](https://openrouteservice.org/dev/#/signup) and add to `secrets.yaml`:

   ```yaml
   ors_key: your-key-here
   ```

3. **Packages enabled** in `configuration.yaml` (skip if you already have this):

   ```yaml
   homeassistant:
     packages: !include_dir_named packages
   ```

4. For the dashboard card only: **Plotly Graph Card** from HACS (`custom:plotly-graph`).

## Installation

1. Copy `packages/i4_energie.yaml` to `/config/packages/i4_energie.yaml`.
2. Add `ors_key` to `secrets.yaml` (see above).
3. **Developer tools → YAML → Check configuration**, then restart Home Assistant.
4. Check that these entities exist: `sensor.i4_fahrt`, `sensor.i4_route`, `input_text.i4_ziel`, `input_select.i4_routentyp`, `input_button.i4_route_neu`, `input_text.i4_route_status`.

If you already created helpers with the same names in the UI, delete those first (or remove the corresponding section from the package). Otherwise Home Assistant reports duplicates.

## Usage

Put the helpers on a dashboard, e.g.:

```yaml
type: entities
title: i4 Route
entities:
  - input_text.i4_ziel
  - input_select.i4_routentyp
  - input_button.i4_route_neu
  - input_text.i4_route_status
```

- Type a destination (address, town or place). The route is calculated immediately, `i4_route_status` shows ✅ or the error.
- Changing the route type recalculates.
- **i4 Route neu** recalculates from the car's current position. When parked, it also starts a new trip.

Route type options: *Schnellste* (fastest), *Kürzeste* (shortest), *Ohne Autobahn* (avoid motorways).

## Dashboard card

[dashboard-card.yaml](dashboard-card.yaml): add a new card to a dashboard, open the code editor, paste the file content. It shows tiles for SOC, range, route length and trip km, the energy chart and the route on a dark map.
