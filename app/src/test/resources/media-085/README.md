# PDF reader UI fixture

`tanzania-checklist.pdf` is a synthetic two-page Tanzania small-business stock/customer-record checklist created for this test. It contains no user data, scripts, links or credentials. `generate_fixture.py` recreates it with ReportLab and an embedded DejaVu Sans subset to avoid platform font substitution; this generator is not a runtime dependency.

`checklist-page-1.png` and `checklist-page-2.png` were locally rendered from that exact PDF using Poppler (`pdftoppm -scale-to-x 1200 -scale-to-y -1 -png`). The native Compose screenshot demonstrates the actual reader shell, fit/zoom/page controls, state restoration and cleanup with these injected test pages. It is **not evidence that Android PdfRenderer decoded the PDF on a device**.

Host limitations were established with a direct native-decoder probe: Robolectric SDK35 fails with missing `FileDescriptor.getOwnerId$`; SDK34 reports zero pages for this valid two-page file (independently confirmed by `pdfinfo`). Production retains the bounded Android PdfRenderer decoder. Real-device PDF decoding and playback acceptance remain required.
