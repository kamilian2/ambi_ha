# Ambi HA

Lampy podłączone do Home Assistanta (Zigbee, Wi-Fi) i taśmy WLED zmieniają kolor razem z obrazem
na telewizorze Philips z Ambilight – tak jak „Ambilight + Hue”, ale bez mostka Hue.

Aplikacja działa na telewizorze, czyta kolory Ambilight liczone przez sam telewizor (API JointSpace –
działa dla tunera, HDMI, Netflixa, YouTube) i wysyła je:

* do lamp w HA – przez WebSocket Home Assistanta (`light.turn_on` z kolorem i jasnością),
* do WLED – bezpośrednio, protokół UDP realtime (DNRGB), do 30 klatek/s; taśmę za telewizorem można rozłożyć na krawędzie ekranu.

## Instalacja

```
adb connect IP_TELEWIZORA:5555
adb install -r ambi-ha.apk
adb shell am start -n pl.kamil.ambiha/.MainActivity
```

Ustawienia: `http://IP_TELEWIZORA:5004` – parowanie z TV (PIN na ekranie), adres i token HA, wybór lamp i stref, taśmy WLED.

Włączanie z HA: `POST http://IP_TELEWIZORA:5004/on`, `/off`, `/toggle`.
