# PixelClip

A pixel-art AI YouTube clipper for Android. No ads, no tracking, no account.

Paste a long YouTube link, pick a mode (Funny / Study / Motivational / Viral),
how many clips you want and how long each should be. PixelClip reads the
video's English captions, asks Gemini (with your own free API key from Google
AI Studio) to find the best moments, shows you the picks, then downloads only
those parts and cuts them into vertical 1080x1920 clips. Finished clips save
to Movies > PixelClip, each with a matching .srt caption file.

Built with Kotlin + Jetpack Compose, powered by the open-source
[yt-dlp](https://github.com/yt-dlp/yt-dlp) engine and the Gemini API.
The APK is built by GitHub Actions on every push and published to the
Releases page.

Made by Ali Nawaz.

## Development

PixelClip is an Android app written in **Kotlin** with **Jetpack Compose**, using a pixel-art UI theme.

- **Download engine:** yt-dlp running on-device through the youtubedl-boom library (Python 3.12). It downloads the video's captions, then downloads only the chosen sections of the video — not the whole file.
- **AI moment picking:** Google Gemini reads the caption text and picks the best moments. The app uses the user's own free Gemini API key, entered in the SETUP tab — no API key is stored in this repository.
- **Video cutting:** FFmpeg (bundled with the engine) cuts each section and converts it to vertical 1080x1920 (9:16). Finished clips are saved to Movies/PixelClip with matching .srt caption files.
- **Build and release:** GitHub Actions builds the APK on every push to `main` and publishes it to GitHub Releases. Builds are signed with a fixed keystore kept in the repo as `keystore.b64`, so every new release installs cleanly over the previous one.
- **Latest release:** v1.1 — fixes a YouTube HTTP 429 error when downloading captions.
