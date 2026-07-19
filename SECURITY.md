# Security notes

## مدل کلید

رمز تصادفی ۲۵۶ بیتی SQLCipher هرگز به‌صورت plaintext روی دیسک نوشته نمی‌شود. این رمز با یک کلید AES-GCM غیرقابل‌استخراج در Android Keystore بسته‌بندی شده است. Keystore key به احراز هویت بیومتریک در هر استفاده وابسته است و با تغییر enrollment بیومتریک invalid می‌شود.

کلیپ‌های دریافت‌شده در حالت قفل داخل دیتابیس staging به‌صورت AES-GCM ذخیره می‌شوند. metadata آن فقط شامل timestamp، IV، ciphertext و HMAC غیرقابل‌برگشت برای deduplication است. کلید staging نیز غیرقابل‌استخراج و داخل Android Keystore است، اما عمداً نیازمند biometric نیست تا ثبت پس‌زمینه در حالت قفل ممکن باشد.

## تصمیم‌های دفاعی

- `FLAG_SECURE` برای جلوگیری از screenshot و نمایش در recent-app previews
- `allowBackup=false` و exclusion کامل از device transfer/cloud backup
- SQLCipher `cipher_memory_security` و `secure_delete`
- SQLCipher logging خاموش است
- اعلان capture هیچ متن کلیپ‌بوردی را نمایش نمی‌دهد
- receiver داخلی `NOT_EXPORTED` و service برنامه `exported=false` است
- clipboardهای بازکپی‌شده با `EXTRA_IS_SENSITIVE` علامت‌گذاری می‌شوند
- C++ با RELRO/NOW، stack protector و hidden symbol visibility ساخته می‌شود
- تمام ELFهای بومی پروژه برای دستگاه‌های 16KB-page-size هم‌تراز می‌شوند

## مرز امنیت

Shizuku قدرت shell/root را به کد UserService می‌دهد. این قابلیت فقط برای خواندن متن Clipboard استفاده می‌شود و هیچ shell command دلخواه یا API نوشتن سیستم در برنامه وجود ندارد. کاربر باید مجوز Shizuku را صریحاً تأیید کند و هر لحظه می‌تواند سرویس ثبت را از داخل برنامه یا اعلان متوقف کند.

اگر enrollment اثر انگشت تغییر کند، کلید قدیمی طبق طراحی غیرقابل‌استفاده می‌شود. برنامه دادهٔ رمزگذاری‌شده را خودکار حذف یا reset نمی‌کند؛ بازیابی بدون کلید قبلی امکان‌پذیر نیست.
