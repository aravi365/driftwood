# Driftwood

A quiet home screen for Android TV and Google TV. One row of your apps, a photo that slowly drifts behind them, the time and the weather. Nothing else.

I built it because the stock home screen felt slow and crowded with ads, and I wanted something closer to how the Apple TV home screen feels.

![Home screen](docs/screenshots/home.jpg)

## What you get

- **Your apps in a clean 5-wide grid.** TV apps show their full banner, phone-only apps get a tile coloured from their icon.
- **A new photo every two minutes.** Bing's photo of the day (8 a day) or Unsplash, with a slow zoom and drift. The caption sits small in the corner.
- **Weather** for where you are, worked out from your connection. You can type a city instead.
- **A simple focus style.** The selected app lifts, a white border wraps it, a light sweep passes over it and it gently sways.
- **A sticky top bar.** Scroll down and the time, date and weather stay at the top while the shelf fades away.
- **HDMI tiles** that switch the TV straight to that input.
- **A screensaver** with the same photos, plus a clock that moves every 30 seconds so nothing burns in.
- **Hold OK on any app** to move it, hide it or open its app info.
- **Light on the TV.** No Google Play Services, no tracking and no accounts. Everything stops animating when you open an app, so playback isn't slowed down.

| Scrolled down | All apps |
| --- | --- |
| ![Sticky bar](docs/screenshots/sticky-bar.jpg) | ![All apps](docs/screenshots/all-apps.jpg) |

| Hold OK on an app | Hold OK on Settings |
| --- | --- |
| ![App menu](docs/screenshots/app-menu.jpg) | ![Options menu](docs/screenshots/options-menu.jpg) |

![Screensaver](docs/screenshots/screensaver.jpg)

## Install

You need a Mac, Linux or Windows computer on the same Wi-Fi as the TV, with [adb](https://developer.android.com/tools/releases/platform-tools) installed (`brew install android-platform-tools` on a Mac).

### 1. Turn on network debugging on the TV

1. Open **Settings → System → About** (on some TVs, **Device Preferences → About**).
2. Select **Android TV OS build** seven times until it says you're a developer.
3. Go back and open **Developer options**. Turn on **USB debugging** (and **Network debugging** if it's listed).
4. Note the TV's IP address from **Settings → Network & Internet → your Wi-Fi**.

### 2. Connect from your computer

```bash
adb connect 192.168.1.49:5555
```

Use your TV's IP. A prompt appears on the TV the first time. Tick **Always allow from this computer** and choose **Allow**.

### 3. Install Driftwood

Download `driftwood.apk` from the [latest release](../../releases/latest), then:

```bash
adb install -r driftwood.apk
```

### 4. Make it the Home button

On Google TV the stock home screen takes over the Home button. Turn it off (nothing is deleted) and point Home at Driftwood:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.aravi365.driftwood/.MainActivity
```

On older Android TV (not Google TV), the stock launcher is `com.google.android.tvlauncher`. Disable that one instead.

### 5. Use the Driftwood screensaver (optional)

```bash
adb shell settings put secure screensaver_components com.aravi365.driftwood/.DriftwoodDream
```

Or pick **Driftwood** in **Settings → System → Ambient mode / Screen saver**.

### 6. Make the whole TV feel faster (optional)

```bash
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
```

## Using it

| Do this | To |
| --- | --- |
| **OK** on a tile | Open the app or switch to that HDMI input |
| **Hold OK** on a tile | Move it to the front, left or right, hide it, or open App info |
| **Hold OK** on the Settings tile | Set the weather city, choose wallpapers, bring back a hidden app, open screensaver settings |
| **Back** or **Home** | Jump back to the first app |

The first row is your dock. Move the apps you use most there.

### Weather location

By default the location comes from your internet connection (via [ipwho.is](https://ipwho.is)). To set it yourself, hold OK on **Settings → Weather location** and type a city, or from your computer:

```bash
adb shell am start -n com.aravi365.driftwood/.MainActivity --es city "Kochi"
```

Pass an empty city (`--es city ""`) to go back to automatic.

### Wallpapers

Choose **Bing photo of the day** or **Unsplash** under **Settings → Wallpapers**, or:

```bash
adb shell am start -n com.aravi365.driftwood/.MainActivity --es wallpapers unsplash
```

Photos are cached and refreshed at most every six hours.

## Casting from an iPhone

- **YouTube, YouTube Music, Hotstar and most video apps:** tap the Cast icon in the app on your phone and choose the TV. Google Cast is built into the TV.
- **Photos, screen mirroring, Safari and anything else:** install **AirScreen** on the TV from the Play Store and open it once. The TV then shows up under **Control Center → Screen Mirroring** on the iPhone, like an Apple TV.

## Removing pre-installed clutter (optional)

You can remove system apps you never use for your user. They stop running and disappear from the home screen, and any of them can be brought back. These were safe on a MediaTek-based Google TV (Android 11):

```bash
for p in com.google.android.videos com.google.android.play.games com.hisense.storemode \
         fusion.android.tv.demo com.hisense.tv.protector com.hisense.hibrowser com.vt.tv.aiot \
         com.iqqijni.tv1.keyboard com.google.android.feedback com.android.printspooler; do
  adb shell pm uninstall -k --user 0 $p
done
```

Bring one back with:

```bash
adb shell cmd package install-existing com.google.android.videos
```

Check your own TV's list with `adb shell pm list packages` first. Leave alone anything that handles updates, voice search, the Play Store, Live TV, or picture and sound.

## Going back to the stock home screen

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb uninstall com.aravi365.driftwood
```

## Build it yourself

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The code is plain Kotlin and Android views, with no libraries. Start with `MainActivity.kt` for the home screen, `Tile.kt` for the focus animation and `DriftwoodDream.kt` for the screensaver.

## Notes

- Tested on a MediaTek-based TV running Google TV (Android 11). It should work on any Android TV 9 or newer, but vendor skins vary.
- Rooting isn't needed for any of this, and I wouldn't recommend it. Most TVs have locked bootloaders, and Netflix and Hotstar drop to SD quality on rooted devices.
- On some TVs the default system theme is see-through, which can leave the last app frozen on screen when you press Home. Driftwood sets an opaque theme to avoid this. If you fork it, keep that.

## Credits

- [Google Sans Flex](https://github.com/googlefonts/googlesans-flex), SIL Open Font License ([licenses/OFL-GoogleSansFlex.txt](licenses/OFL-GoogleSansFlex.txt))
- Weather from [Open-Meteo](https://open-meteo.com), location from [ipwho.is](https://ipwho.is)
- Photos from [Bing](https://www.bing.com) and [Unsplash](https://unsplash.com) via [Picsum](https://picsum.photos). The screenshots use Unsplash photos.
- App names and logos in the screenshots belong to their owners

## License

MIT. See [LICENSE](LICENSE).
