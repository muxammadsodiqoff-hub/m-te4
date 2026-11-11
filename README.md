# Video Studio

Android uchun to'liq local (internetsiz) ishlaydigan sodda video montaj ilovasi.

## Imkoniyatlar (1-bosqich)

- Galereyadan bir nechta video va rasm tanlash
- Bo'laklarni tartiblash, o'chirish
- Videoni kesish (boshi/oxiri), rasm uchun ekranda turish vaqti
- Fon musiqasi qo'shish, videolarning asl ovozini o'chirish
- Butun video ustiga matn (tepa / o'rta / past)
- Format: 9:16, 16:9, 1:1; 720p yoki 1080p
- MP4 eksport, galereyaga saqlash (Movies/VideoStudio)

## APK olish

Har bir `main` ga push qilinganda GitHub Actions APK yig'adi va
**Releases** bo'limiga `VideoStudio.apk` qilib joylaydi.

## Texnologiya

Kotlin, Jetpack Compose, AndroidX Media3 Transformer. minSdk 29 (Android 10+).
