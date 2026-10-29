# Website assets and release data

The public site guides Android users through installation, source checking, AI connections, About You preferences, language, appearance, and local tools. Typography uses the exact Figtree variable font bundled by the app. `figtree.ttf` is copied without modification from `app/src/main/res/font/figtree.ttf`; its SIL Open Font License is retained in `../FONT-LICENSE.txt` and attribution is in the repository `NOTICE`.

The site is static HTML, CSS, and JavaScript. `site.css` loads the local font for every page, including guides under `guides/`. Captures are linked directly from the current showcase directory and are not cropped or retouched. The screenshot gallery's linked capture record keeps build, source, fixture, locale, theme, and date provenance; public-facing captions stay concise and do not imply a live provider account.

## Published download source

`release.json` is machine-readable publication evidence, not a customer-facing release announcement. The site and checks use the latest release only when it is confirmed published and its APK asset, checksums, source, and integrity receipt agree. If a newer candidate is pending, customer pages continue to describe the latest published release; they do not offer the candidate or an older download as an update fallback.

The primary button links directly to GitHub's latest-release asset path using the exact APK filename from the verified public record. Do not guess asset case or infer an APK from a local build. Run `scripts/check-showcase.py` after metadata or site changes; it verifies release identity and asset integrity against the preserved release receipts.

The top-level release record identifies the current public download, while `releases` retains the release history. Existing release manifests, checksum records, source archives, verification receipts, and dated capture records are historical evidence and should not be rewritten to match current copy. Update the gallery catalog only after capture review and keep the original screenshot bytes unchanged.

Guide pages are static HTML. Keep local links under the project-site root, preserve accessible navigation and headings, and make any current-facing feature description match the latest public app. Use specific actions rather than slogans. Avoid release numbers in current body copy; the version belongs in the site footer and technical release identity.

Do not put personal context, owner account details, API keys, signing secrets, or a personalized APK in this directory.
