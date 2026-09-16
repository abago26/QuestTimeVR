# Images

| file | what it is |
|---|---|
| `app-icon.png` | **the app's icon**, 512x512. The single source for every launcher density — run `reference/make_icons.sh` after changing it, and do not edit `res/mipmap-*` by hand |
| `banner.jpg` | the header at the top of [DETAILS](../DETAILS.md) |
| `menu-files.png` | the file list, as it appears in the headset |
| `menu-scene.png` | the node list for a multi-node scene |
| `doorway-labels.png` | three doorway labels, including one long enough to be cut |
| `welcome.png` | the generated first-launch panorama, flattened out |
| `hero.jpg` | *wanted* — one still from inside a real panorama |
| `looking-around.gif` | *wanted* — a few seconds of turning, and the list opening |

**The four in the middle are regenerated, not screenshotted.** They come out of the
same Kotlin that draws them in the headset, via Robolectric, so they cannot drift from
what the app actually shows:

```bash
./build.sh testDebugUnitTest --tests '*MenuPreviewTest*' --tests '*WelcomeTest*'
cp android/app/build/preview/menu-list.png   docs/images/menu-files.png
cp android/app/build/preview/menu-nodes.png  docs/images/menu-scene.png
cp android/app/build/preview/gaze-label.png  docs/images/doorway-labels.png
cp android/app/build/preview/welcome.png     docs/images/welcome.png
```

The two still wanted are the two that cannot be produced this way: the panorama itself
is a composition layer, and `adb shell screencap` returns a black frame for the
immersive view. Those need a capture from inside the headset.

GitHub renders `.gif`, `.png` and `.jpg` inline. An `.mp4` will not play in a README —
upload it to a release or an issue and link it, or convert it to a gif.
