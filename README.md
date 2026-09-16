# QuestTime VR

**Stand inside a 1990s QuickTime VR panorama, on a Meta Quest 3.**

<!-- A still from inside a panorama goes here. -->
<!-- ![A panorama in the headset](docs/images/hero.jpg) -->

QuickTime VR was Apple's 1995 attempt at virtual reality. You dragged a mouse inside a
small window to look around a place someone had photographed. It was the closest a
desktop of that era came.

Then it was left behind. The current version of QuickTime will not preview these files,
and the panoramas inside them — many the only surviving record of a place at a
moment — became effectively unopenable.

The odd thing is that the format was always describing a shape you can now simply
*stand in*. A QuickTime VR panorama is a cylinder: pixels wrapped around you, with one
number for how far round it goes and another for how tall it stands. A Quest draws
cylinders natively. So this app converts almost nothing — it reads what the file
already says and hands it to the headset in those terms.

---

## See it

<!-- Drop a short capture here — opening a file, looking around, walking through a doorway. -->
<!-- ![Looking around](docs/images/looking-around.gif) -->

<!-- And a second one: the browser page, sending files to the headset. -->
<!-- ![Sending files](docs/images/sending-files.gif) -->

---

## Getting started

**1. Install it.** Download the APK from [Releases](../../releases) and sideload it with
SideQuest, or:

```bash
adb install -r QuestTimeVR-*.apk
```

**2. Put it on.** The app opens a panorama straight away and shows you what else is on
the headset. **A** or **X** opens that list, the thumbstick moves through it, the
**trigger** chooses.

**3. Send it your own files.** The app prints a web address. Open it in any browser on
the same Wi-Fi and drop files in — no cable, nothing to install.

> **On a Mac, zip them first.** Select the files in Finder, right-click, **Compress**,
> and send the zip. Many QuickTime VR files keep part of themselves in a place a browser
> cannot send on its own, and zipping brings it along.

---

## What it opens

QuickTime VR **1.0 and 2.x**, cylindrical and cubic, Cinepak and Photo-JPEG — including
**multi-node scenes**, where a file holds several places and you walk between them by
looking at a doorway and pulling the trigger.

Of one real 27-file archive from the 1990s, it opens 24. The three it refuses are
ordinary movies with no panorama in them at all.

Anything it cannot open, it says so by name rather than failing quietly. That is a
deliberate rule: **refusing clearly beats rendering something wrong.**

---

## More

- **[The long version](docs/DETAILS.md)** — how it works, what is verified, format
  coverage, known issues.
- **[Working notes](CLAUDE.md)** — the engineering log: every trap found, what it cost,
  and how it was settled. Written for whoever picks this up next.

## Licence

MIT. See [LICENSE](LICENSE).

Not affiliated with Apple or Meta. QuickTime and QuickTime VR are Apple trademarks;
Meta Quest is a Meta trademark. The sample panoramas used in development are other
people's photographs and are not in this repository.
