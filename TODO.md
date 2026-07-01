# TODO

## Goal
رفع کرش هنگام زدن Connect و ساخت کانفیگ واقعی V2Ray/V2rayNG-like بر اساس rawConfig (vmess/vless/trojan) + تضمین اینکه VPN icon واقعی نمایش داده شود.

## Steps
- [x] (1) Fix mapping extras در `SimpleVpnService.extractConfigFromIntent()`
- [x] (2) جایگزینی `buildV2RayConfigJson()` از حالت JSON ثابت به حالت dynamic بر اساس `config.type` و وضعیت TLS/ SNI/ALPN
- [x] (3) اضافه کردن لاگ دقیق و rollback: اگر core/startLoop failure خورد، VPN interface/foreground/service را تمیز ببند و state را DISCONNECTED کند تا دوباره کرش نشود.
- [ ] (4) (در صورت نیاز) اصلاح مسیر inbound/outbound به مدلی نزدیک‌تر به عملکرد V2rayNG (بعد از تست لاگ‌ها).
- [ ] (5) Build + نصب روی گوشی و تست: Connect، بررسی crash-free بودن و مشاهده VPN icon + اعتبارسنجی ترافیک.

