# Play Store artwork

These files are distribution metadata, not Android runtime resources. Keep them outside `app/src/main/res` so they are not packaged into the APK/AAB.

- `nirmalam_play_store_icon_512.png` — Google Play store listing icon source.
- `nirmalam_feature_graphic_1024x500.png` — Google Play feature graphic.

The installed app uses adaptive launcher-icon resources under `app/src/main/res/mipmap-anydpi-v26` and `mipmap-anydpi-v33`, with density-specific foreground/monochrome layers under `drawable-*dpi`.
