# ReelSaverNative 1.6.0

Нативный Android/Cordova-модуль ReelSave отвечает только за:

- чтение ссылки из буфера обмена Android;
- получение данных публичного Reel через собственные запросы к Instagram;
- разбор встроенных Instagram `data-sjs` данных;
- fallback через полноразмерный скрытый Android WebView;
- обнаружение URL MP4;
- сохранение MP4 через Android MediaStore;
- приём Instagram-ссылки из системного меню «Поделиться»;
- открытие папки загрузок.

## Реклама

Реклама **намеренно вынесена из этого плагина**. ReelSave 1.7 использует ту же схему, что проект AliasTop BUILD 30:

- GDevelop-расширение `YandexMobileAds 2.2.5`;
- Cordova-плагин `cordova-plugin-gdevelop-yandex-mobile-ads-sdk8`;
- репозиторий: `https://github.com/artemkz147-ship-it/cordova-plugin-gdevelop-yandex-mobile-ads-sdk8.git#main`;
- баннер ReelSave: `R-M-19692555-1`;
- rewarded ReelSave: `R-M-19692555-2`.

Так Yandex SDK не дублируется внутри ReelSaverNative и рекламная логика совпадает с рабочим Alias-проектом.
