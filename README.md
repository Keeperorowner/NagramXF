# Nagram XF

A fork of [Nagram X](https://github.com/risin42/NagramX) with additional features.
Includes most features from exteraGram and AyuGram.

## Sponsor

* [爱发电](https://ifdian.net/a/nagramxf)
* [Ko-fi](https://ko-fi.com/nagramxf)

## Download

* [Telegram Channel](https://t.me/NagramXF)
* [Telegram Beta Channel](https://t.me/NagramXFBetaAPKs)
* [GitHub Releases](https://github.com/Keeperorowner/NagramXF/releases)

## Compilation Guide

1. Clone with submodules (`--recursive`), or run `git submodule update --init` in an existing clone. The native libraries (`dav1d`, `ffmpeg`, `libvpx`) under `TMessagesProj/jni/third_party/` are required for native builds.

2. Obtain API credentials (`TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH`) from [Telegram Developer Portal](https://my.telegram.org/auth). Create `local.properties` in the project root with:

   ```properties
   TELEGRAM_APP_ID=<your_telegram_app_id>
   TELEGRAM_APP_HASH=<your_telegram_app_hash>
   ```

3. For APK signing: Replace `release.keystore` with your keystore and add signing configuration to `local.properties`:

   ```properties
   KEYSTORE_PASS=<your_keystore_password>
   ALIAS_NAME=<your_alias_name>
   ALIAS_PASS=<your_alias_password>
   ```

4. For FCM support: Replace `TMessagesProj/google-services.json` with your own configuration file.

5. Open the project in Android Studio to start building.

## Notice

This project reverse-engineered and uses some code from closed-source projects.

## Acknowledgments

- [NagramX](https://github.com/risin42/NagramX)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
