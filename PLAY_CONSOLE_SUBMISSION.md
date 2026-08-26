# Google Play submission checklist

## App content answers for the public 1.3.0 release

- **Target audience:** adults; the app is not directed at children.
- **Ads:** no ads.
- **App access:** all features are available locally after the user creates or enters a database passphrase; no developer account or demo credentials are required.
- **Sensitive permissions:** public release declares `VIBRATE` only. It does not declare SMS, call-log, contacts, location, camera, microphone, or broad storage permissions.
- **Financial features:** personal finance tracking only; the app does not provide loans, trading execution, brokerage, investment advice, banking, payment initiation, or cryptocurrency exchange services.

## Data safety draft

Complete the form against the exact AAB uploaded to Play Console.

- **Data collected by the developer:** No. Financial information remains on device and is not transmitted to the developer.
- **Data shared:** The optional Nirmalam AI BYOL feature sends a user-triggered, minimised aggregate finance summary to the provider selected by the user. It excludes raw transactions, payees, descriptions, and account identifiers. Complete the Data safety form against the exact chosen provider SDK/API behaviour before release.
- **Security:** financial records are encrypted at rest in SQLCipher. The optional BYOL provider API key is protected with Android Keystore and is excluded from database and backup files.
- **Deletion:** users can delete individual records, remove starter data, or uninstall the app to remove local app data. There is no online account.
- **Optional exports:** users explicitly select a file destination via the Android Storage Access Framework. JSON is plaintext; `.ndf` is encrypted. These user-directed exports are not developer collection.

## Manual Play Console steps

1. Create the app and enroll in **Play App Signing**.
2. Generate a private upload key outside this repository; configure its four `NIRMALAM_UPLOAD_*` secrets for the release build.
3. Upload the signed AAB, then check the automatically generated permissions and Data safety prompts against this document.
4. Set the privacy-policy URL to the publicly accessible policy page. The repository includes `docs/privacy-policy.html`; host it through GitHub Pages or another stable HTTPS host before production submission.
5. Complete the IARC content-rating questionnaire truthfully and submit the required store listing assets.
