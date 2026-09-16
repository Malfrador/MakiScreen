Paper plugin to play back video (and audio) in-game without the use of clientside mods.

## Features
* Play local MP4/MKV/AVI/WEBM/MOV files or download videos directly from YouTube (optional)
* Automatic resource pack generation and hosting for synchronized audio playback
* True 1080p or even 4K playback with efficient tile-based rendering
    * Only limit is the server and player bandwidth.
* Significantly improved visual quality
    * Floyd-Steinberg, Stucki or Atkinson dithering with temporal noise reduction
* Full playback controls: pause, resume, seek, and skip
    * Audio is sliced into seamless chunks to work around Minecraft's audio limitations
* Various compression techniques are used to reduce bandwidth usage as much as possible by only updating whats actually needed
    * Interframe compression, downsampling, dirty region detection and more

## Basic usage
_All commands have helpful tab-completion available_
1) Use `/mcc create <screen id> <aspect ratio> [x] [y]` while looking at the lower left corner to create a screen
    * Tip: Make sure your screen is evenly lit, e.g. using light blocks.
2) Place your `.mp4`, `.mkv`, `.avi`, `.webm`, or `.mov` files in `/plugins/MCCinema/videos/`
3) Use `/mcc play <screen id> <video>` to start playback. Optionally include the `--audio <chunk size>` parameter to play with audio.
    * For audio, this plugin will use [mcpacks.dev](https://mcpacks.dev/) for Resourcepack hosting by default.
    * Alternatively, it can automatically host a resourcepack server if audio is enabled. On some hosts this may fail.
    * To allow for resuming and pausing of playback, audio data is split into chunks of `<chunk size>` seconds in length. The default value of 10s is usually good.
4) You can use `/mcc quality <screen> <performance/balanced/quality>` to change playback quality settings. This will have a significant effect on bandwidth and CPU usage.
5) Use `/mcc crop <screen>` while a local video is playing to adjust its viewport. Scroll to zoom, use W/A/S/D to pan, and hold sneak for precise adjustments. The saved crop is reused whenever that video is played again.

To download videos from YouTube, run `/mcc download`.
On first use, MCCinema asks for permission to install its private, self-contained yt-dlp and Deno tools. The requested download or livestream continues automatically after setup; no system Python, Node.js, or ffmpeg installation is required. Administrators can inspect or retry the media toolchain with `/mcc media`.
But of course you can also always download YouTube videos manually and place them in /videos/ yourself!

YouTube cookies are optional and should only be used for media that requires an account. Export a fresh private/incognito YouTube session in Netscape format to `plugins/MCCinema/youtube_cookies.txt`, then close that private session so YouTube does not rotate it. MCCinema validates the file and only retries with it after an authentication-specific failure; no reload is needed. Using an account with third-party downloaders can cause YouTube to restrict it, so avoid a primary account.

---

**A note on bandwidth usage:**

This plugin will inevitably create a lot of traffic. Minecraft isn't really a great video player.

This is optimized as best as possible (and likely better than many similar plugins), however it can still be a lot.

The actual amount of traffic scales with two factors: Screen size, and how dynamic the video is. Videos with very little movement such as slideshows will barely generate any traffic.
You can see live metrics, including bandwidth usage, using `/mcc debug <screen id>`.

**One example on a reasonably sized (7x4 blocks, 896x512) screen:**
- [Server Trailer](https://www.youtube.com/watch?v=R-HWAiNj9Nw): Average 3 MB/s
    * This screen size gives you good enough resolution for most content

**Two examples on a very large (17x7 blocks, 2176x896) screen:**
- [Bad Apple](https://www.youtube.com/watch?v=FtutLA63Cp8): Average 5 MB/s
    * Only two colors, pillarboxing on both sides, many large same-color areas -> lower bandwidth usage
- [Bite Marks](https://www.youtube.com/watch?v=I76wvt0aEE4): Average 30 MB/s
    * Many colors, full CS format, very quick scenes -> very high bandwidth usage

---

## Configuration
You can find a full, commented configuration file [here](https://github.com/Malfrador/MakiScreen/blob/master/src/main/resources/config.yml)

### Additional notes:
- This plugin makes heavy use of multi-threading and benefits from more and faster CPU cores significantly.
- Video and audio are streamed, so RAM usage should not be a meaningful concern. However, this plugin will create considerable GC pressure.
- It is recommended to use a Velocity proxy in front of your server, and to then disable `network-compression-threshold` in `server.properties`. Doing so will offload the work of compressing and encrypting packets to the proxy, which can significantly improve performance and throughput. If that is not possible, increasing `netty-threads` in spigot.yml might be worth considering.
- Packets are bundled per frame, so packets-per-second should not be a concern here.
- You can tweak most config settings during video playback with `/mcc debug <screen> <setting> <value>`. Depending on the video content, tweaking settings might have a big effect (e.g. anime style vs real life videos).
---

## Showcase (sound on!)

### Very large screen (17x7 blocks, 2176x896 CS) playing at 24FPS

https://github.com/user-attachments/assets/beb5a6a0-8234-48bf-a9e6-be4311302585

### Even larger screen sizes are supported, but you will run into bandwidth limitations
<img alt="javaw_UbWieGAgKk" src="https://github.com/user-attachments/assets/1d6b7c9b-2c33-4d27-baaf-055825bf9114" />




