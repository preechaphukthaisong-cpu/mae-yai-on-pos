# Release Signing — Maeyai-On-POS

สถานะ: เตรียม Release Signing สำหรับการตรวจสอบแบบ manual เท่านั้น ยังไม่ใช่การอนุมัติปล่อยแอป

## Release keystore

- File: `maeyai-pos-release.p12`
- Store type: PKCS12
- Alias: `maeyai-pos-release`
- Key: RSA 3072-bit
- Signature: SHA256withRSA
- Android application ID: `com.maeyaion.pos`
- Approved certificate SHA-1: `4A:17:CC:CA:25:DE:A7:BC:B7:CE:E2:EC:9B:78:74:2F:D1:BC:CB:47`
- Approved certificate SHA-256: `0C:C2:0A:95:BD:8C:C4:FC:4E:6B:F3:79:E9:69:45:C2:24:86:32:7C:C7:D9:C3:BD:5C:12:87:35:64:75:04:C2`

ห้าม commit keystore, passphrase, base64 ของ keystore หรือไฟล์สำรองเข้ารหัสลง repository

## GitHub Actions secrets

ตั้งค่าใน Repository → Settings → Secrets and variables → Actions → New repository secret:

- `MAEYAI_RELEASE_KEYSTORE_BASE64`: Base64 ของไฟล์ PKCS12
- `MAEYAI_RELEASE_STORE_PASSWORD`: รหัสผ่าน keystore
- `MAEYAI_RELEASE_KEY_ALIAS`: `maeyai-pos-release`
- `MAEYAI_RELEASE_KEY_PASSWORD`: รหัสผ่าน private key (หากตอนสร้างกด Enter ใช้รหัสผ่านเดียวกับ keystore)

ห้ามส่ง secret ผ่าน issue, pull request, commit, chat หรือ log

## Manual verification workflow

Workflow `.github/workflows/android-release.yml` ทำงานเฉพาะเมื่อสั่ง workflow_dispatch ด้วยตนเอง โดยจะ:
1. ตรวจว่ามี secrets ครบ
2. ถอด Base64 ไปยังไฟล์ชั่วคราวบน GitHub runner
3. ตรวจ alias และ SHA-1 ของ certificate กับค่าที่อนุมัติ
4. รัน unit tests และ assembleRelease
5. ตรวจลายเซ็น APK ว่าตรงกับ release certificate
6. เก็บ APK เป็น Actions artifact ชั่วคราว 14 วัน
7. ลบไฟล์ keystore ชั่วคราว

Workflow นี้ไม่สร้าง Git tag, GitHub Release, ไม่ publish และไม่ merge PR

## Google OAuth

เพิ่ม Android OAuth client สำหรับ package `com.maeyaion.pos` โดยใช้ Release SHA-1 ข้างต้น แยกจาก Debug SHA-1 เดิม เพื่อให้การลงชื่อเข้าใช้ Google Drive ทำงานกับ APK ที่เซ็นด้วย Release key ได้ ไม่ต้องเปิด scope เพิ่มจากที่แอปใช้สำหรับ Drive app data

## Versioning

ยังคง `versionCode 1` และ `versionName 0.1.0` ระหว่างการเตรียม signing gate; เปลี่ยนเป็น `versionName 1.0.0` หลัง Release Signing verification และเกณฑ์ Phase 3.1 ผ่านครบเท่านั้น การสร้าง Git tag หรือปล่อยแอปต้องได้รับอนุมัติชัดเจนจากผู้ใช้
