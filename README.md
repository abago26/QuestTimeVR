<img width="2000" height="732" alt="image" src="https://github.com/abago26/QuestTimeVR/blob/main/docs/images/QuestTimeVR%20Wide.jpg?raw=true" />

<small>Photo by <a href="https://unsplash.com">Tamara Gore</a> on <a href="https://unsplash.com">Unsplash</a></small>


**Stand inside a 1990s QuickTime VR panorama, on a Meta Quest 3.**

<!-- A still from inside a panorama goes here. -->
<!-- ![A panorama in the headset](docs/images/hero.jpg) -->

Before the Vision Pro, QuickTime VR was Apple's 1995 journey into virtual reality. 
You dragged a mouse inside a small window to look around a place someone had photographed. 
It was the closest a desktop of that era came.

Unfortunately, the current version of QuickTime will not preview these files,
and the panoramas inside them, many the only surviving record of a place at a
moment, became effectively unopenable.

That's where QuestTime VR comes in, as the original format was always describing a shape you can now simply
*stand in*. A QuickTime VR panorama is a cylinder: pixels wrapped around you, with one
number for how far round it goes and another for how tall it stands.

---

## See it

<img width="1920" height="1080" alt="Adobe Express - Casting_Video_1789617393991" src="https://github.com/user-attachments/assets/813e7462-629f-4515-b4bd-c7fe3b4b2219" />


 <img width="3926" height="2208" alt="Casting_Screenshot_1789617239270" src="https://github.com/user-attachments/assets/fdacd9f1-a2c6-4bcb-9ec7-0d2299e1c16a" />

---

## Things You'll Need

**1. Meta Quest 3/3S Headset w/ Right Controller.** As of now, it's recommended to use
the latest Horizon OS 2.7 update, however testing has not been made on earlier versions
and may be possible with earlier versions of the software.

**2. Computer/Phone/Tablet with Local Internet Connection.** When you open the app, you'll see 
a preview window that gives you an IP address to visit (example http://192.168.1.87:8080/).
Visiting this link will take you to a familiar Mac OS 9 - era landing page where you can
drop in your QTVR files...forgot to mention those...

**3. QTVR Files.** Due to copyright reasons, I cannot provide the QTVR files in this repo
as they are the property of the photographers that took them or belong to Apple from their
Authoring Studio demos from 1995-1997. It's easy to find these files though with a simple Google search,
just ensure that the file ends in a .mov format. 

---

## Getting started

**1. Install it.** Download the APK from [Releases](../../releases) and sideload it with
the Meta Quest Developer Hub, or:

```bash
adb install -r QuestTimeVR-*.apk
```

**NOTE** As I've yet to push it to the Meta Quest Store, this will show up in your library under
**Unknown Sources**. I will attempt to resolve this in a later update.

**2. Put it on.** The app opens a default panorama straight away and shows you what else is on
the headset. **A** or **X** opens that list, moving the thumbstick up or down navigates through each file, the
**trigger** chooses.

**3. Send it your own files.** The app will report a web address at the top of the file navigator. 
Open it in any browser on the same Wi-Fi and drop files in. 

<img width="775" height="584" alt="image" src="https://github.com/abago26/QuestTimeVR/blob/main/docs/images/WebServerpreview-screenshot.png?raw=true" />


You'll also have the option to load in a custom music track of your choice. I'm unable to provide one, 
however here is one that I believe fits this era of technology: 
<a href="https://youtu.be/Cz2YCRmDOFk">wake up! it's 2000s again - frutiger aero playlist</a>

**4. View the QTVR File.** You can move the thumbstick to the left or right to shift 45 degrees around
the panoramic.

---

## What it opens

QuickTime VR **1.0 and 2.x**, cylindrical and cubic, Cinepak and Photo-JPEG — including
**multi-node scenes**, where a file holds several panoramas, allowing you to switch between
various locations in a scene.

---

## More

- **NOTE:** The quality of QTVR files can vary, as some of these date back to as far back as 1995. Given that,
the resolution may seem uncomfortable in some panoramas. Running the QTVR files through Topaz Photo AI to upscale them
could potentially work, however you'd have to break the panorama up first, run the separate images through the software, then
stitch them back together in a format. This is a potential pipeline I will be testing in the future, with the possibility of creating an
Python script that can break apart the images and stitch the upscaled versions back together, re-applying the original metadata a
traditional QTVR file would require.

- **[The long version](docs/DETAILS.md)** — how it works, what is verified, and
  exactly what it refuses and why.
- **[Working notes](CLAUDE.md)** — the engineering log: every trap found, what it cost,
  and how it was settled. Written for whoever picks this up next.

## Licence

MIT. See [LICENSE](LICENSE).

Not affiliated with Apple or Meta. QuickTime and QuickTime VR are Apple trademarks;
Meta Quest is a Meta trademark. The sample panoramas and music used in development are other
people's photographs/music and are not in this repository.
