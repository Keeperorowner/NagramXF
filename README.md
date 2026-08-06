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

1. Clone the repository with its submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/Keeperorowner/NagramXF.git NagramXF
    ```

    If you already cloned the repository without submodules, run:

    ```bash
    git submodule update --init --recursive --depth=1
    ```

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

5. Replace project-specific metadata:

    - Set your Google Maps API key in the `com.google.android.maps.v2.API_KEY` meta-data entry in `TMessagesProj/src/main/AndroidManifest.xml`.
    - Set `BaseRemoteHelper.CHANNEL_METADATA_ID` in `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/remote/BaseRemoteHelper.java` to your metadata channel's numeric ID, without the `-100` prefix.

6. Open the project in Android Studio to start building.

## Notice

This project reverse-engineered and uses some code from closed-source projects.

For the functionality obtained through reverse engineering in this section, I listed the original developers as the contributors.

## Acknowledgments

- [NagramX](https://github.com/risin42/NagramX)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
