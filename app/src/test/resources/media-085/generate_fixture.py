"""Manually regenerate the synthetic PDF with embedded fonts; not a runtime/test dependency."""
from pathlib import Path
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent
pdfmetrics.registerFont(TTFont('FixtureSans', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'))
pages = [
    ('Stock and sales', [
        ('Opening stock check', ['Count the items you plan to sell today.', 'Write each item, quantity and shelf location.', 'Mark low stock before you place another order.']),
        ('Record each sale', ['Note the date, item and number sold.', 'Keep cash and mobile-money receipts together.', 'Compare your sales notes with closing stock.']),
        ('End-of-day review', ['Check missing entries while the day is fresh.', 'Write one clear action for tomorrow.'])]),
    ('Customer records', [
        ('Keep a simple customer list', ["Ask before saving someone's contact details.", 'Record only what you need for the order.', 'Keep private notes away from shared screens.']),
        ('Track orders clearly', ['Write the item, quantity and agreed collection date.', 'Record whether the order is pending or complete.', 'Confirm any change with the customer.']),
        ('Weekly tidy-up', ['Remove duplicate entries and correct mistakes.', 'Store the notebook or file somewhere safe.'])]),
]
pdf = canvas.Canvas(str(ROOT / 'tanzania-checklist.pdf'), pagesize=(612, 792), invariant=1, pageCompression=1)
pdf.setTitle('Synthetic Tanzania small-business checklist — MeBot preview fixture')
pdf.setAuthor('MeBot UI test fixture')
for index, (name, sections) in enumerate(pages, 1):
    pdf.setFillColorRGB(.08, .25, .28); pdf.rect(0, 650, 612, 142, stroke=0, fill=1)
    pdf.setFillColorRGB(1, 1, 1); pdf.setFont('FixtureSans', 23)
    pdf.drawString(40, 735, 'Tanzania small-business checklist')
    pdf.setFont('FixtureSans', 18); pdf.drawString(40, 695, name)
    pdf.setFillColorRGB(.1, .15, .18); y = 610
    for heading, lines in sections:
        pdf.setFont('FixtureSans', 17); pdf.drawString(40, y, heading); y -= 32
        pdf.setFont('FixtureSans', 12)
        for line in lines:
            pdf.drawString(48, y, '- ' + line); y -= 25
        y -= 30
    pdf.setFillColorRGB(.35, .4, .43); pdf.setFont('FixtureSans', 10)
    pdf.drawString(40, 60, 'Synthetic local preview fixture - not a real business record.')
    pdf.drawString(40, 40, f'MeBot PDF fullscreen preview / Page {index} of 2')
    pdf.showPage()
pdf.save()
