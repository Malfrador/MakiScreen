Paper plugin to play back video (and audio) in-game without the use of clientside mods. 

## Features
* Play local MP4 files or download videos directly from YouTube (optional)
* Automatic resource pack generation for synchronized audio playback
* True 1080p or even 4K playback with efficient tile-based rendering
  * Only limit is the server and player bandwidth.
* Significantly improved visual quality
  * Floyd-Steinberg dithering with temporal noise reduction
* Full playback controls: pause, resume, seek, and skip
  * Audio is sliced into seamless chunks to work around Minecraft's audio limitations
* Various compression techniques are used to reduce bandwidth usage as much as possible by only updating whats actually needed
  * Interframe compression, downsampling, dirty region detection and more
 
## Basic usage:
_All commands have helpful tab-completion available_
1) Use `/mcc create <screen id> <aspect ratio> [x] [y]` while looking at the lower left corner to create a screen
2) Place your video `.mp4` files in `/plugins/MCCinema/videos/`
3) Use `/mcc play <screen id> <video>` to start playback. Optionally include the `--audio <chunk size>` parameter to play with audio.
   * The plugin will attempt to automatically host a resourcepack server if audio is enabled. On some hosts this may fail. You can always use the RP from `/plugins/MCCinema/resourcepack/` manually though.
   * To allow for resuming and pausing of playback, audio data is split into chunks of `<chunk size>` seconds in length. The default value of 10s is usually good.

To download videos from YouTube, run `/mcc download`. 
There is some first-time setup required for this. You will be asked to confirm the yt-dlp download. Due to YTs anti-bot measures, you also may need to install a JS runtime on your machine for yt-dlp to work properly.

---

**A note on bandwidth usage:** 

This plugin will inevitably create a lot of traffic. Minecraft isn't really a great video player. The actual amount of traffic scales with two factors: Screen size, and how dynamic the video is. Videos with very little movement such as slideshows will barely generate any traffic. 
You can see live metrics, including bandwidth usage, using `/mcc debug <screen id>`. 

---

## Showcase (sound on!)

### Very large screen (17x7 blocks, 2176x896 CS) playing at 24FPS

https://github.com/user-attachments/assets/beb5a6a0-8234-48bf-a9e6-be4311302585

### Even larger screen sizes are supported, but you will run into bandwidth limitations
<img alt="javaw_UbWieGAgKk" src="https://github.com/user-attachments/assets/1d6b7c9b-2c33-4d27-baaf-055825bf9114" />




