WIP-Fork of [MakiScreen](https://github.com/makifoxgirl/MakiScreen) that adds local playback, audio, and high framerate video. 

### Improvements
* Use local video files instead of reading a ffmpeg stream. Not needing to run FFMPEG improves performance as well.
* Framerate is independent from server tick-rate and can be higher than 20 FPS.
* Plays back audio using an automatically generated resource pack.
* Automatically up- or downscale video to fit the map screen.

See the original [MakiScreen](https://github.com/makifoxgirl/MakiScreen) repository for a video demo.
