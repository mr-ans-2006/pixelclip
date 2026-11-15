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
