#!/usr/bin/env python3
"""Reading copies of a guide: <name>.odt and <name>.pdf, generated from <name>.md.

    build_guide.py doc/INSTALL.md <output directory> [fr|en]

The language (default fr) is that of the guide: it sets the note at the top and the hyphenation.
Also used for the Keycloak lab guide (en).

INSTALL.md stays the one source (and ships too, as the copy-paste source: a command copied from a
word processor or a PDF can come out with curly quotes or broken lines). Nothing here is edited by
hand. Needs pandoc and LibreOffice; fails loudly when either is missing, so a release never goes out
silently without its guide.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

BODY_FONT = "Liberation Sans"
CODE_FONT = "Liberation Mono"

NOTES = {
    "fr": ("> Cette version est faite pour être lue. Pour copier des commandes sans risque (guillemets, "
           "tirets et retours à la ligne que Word ou un PDF peuvent altérer), utilisez le fichier "
           "`{md}` livré à côté.\n\n"),
    "en": ("> This copy is made for reading. To copy commands safely (quotes, dashes and line breaks "
           "that a word processor or a PDF can alter), use `{md}` next to it.\n\n"),
}


def die(message):
    sys.exit(f"build_guide: {message}")


def find_soffice():
    for candidate in (os.environ.get("SOFFICE"), shutil.which("soffice"), shutil.which("libreoffice"),
                      *sorted(glob.glob("/opt/libreoffice*/program/soffice"), reverse=True)):
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    die("LibreOffice not found (set SOFFICE=/path/to/soffice).")


def rewrite_zip(path, edits):
    """Apply {member name: function(text) -> text} to an ODT in place (mimetype stays first, stored)."""
    with zipfile.ZipFile(path) as src:
        items = [(info, src.read(info.filename)) for info in src.infolist()]
    with zipfile.ZipFile(path, "w") as out:
        for info, data in items:
            if info.filename in edits:
                data = edits[info.filename](data.decode("utf-8")).encode("utf-8")
            out.writestr(info, data, compress_type=zipfile.ZIP_STORED if info.filename == "mimetype"
                         else zipfile.ZIP_DEFLATED)


def patch_reference_styles(xml):
    # A4 with 20 mm margins instead of US Letter with 1 inch.
    xml = xml.replace('fo:page-width="8.5in"', 'fo:page-width="210mm"').replace('fo:page-height="11in"', 'fo:page-height="297mm"')
    xml = re.sub(r'fo:margin-(top|bottom|left|right)="1in"', r'fo:margin-\1="20mm"', xml)

    # Body text in a sans-serif face, code in a fixed face with a frame: a command is told apart at a glance.
    xml = re.sub(r'(<style:style style:name="Standard"[^>]*?)\s*/>',
                 r'\1><style:text-properties style:font-name="%s" fo:font-size="10pt" fo:language="fr" fo:country="FR"/></style:style>'
                 % BODY_FONT, xml, count=1)
    xml = xml.replace('<office:font-face-decls>',
                      f'<office:font-face-decls><style:font-face style:name="{BODY_FONT}" svg:font-family="&apos;{BODY_FONT}&apos;" style:font-family-generic="swiss"/>'
                      f'<style:font-face style:name="{CODE_FONT}" svg:font-family="&apos;{CODE_FONT}&apos;" style:font-family-generic="modern" style:font-pitch="fixed"/>', 1)

    def code_style(match):
        return (match.group(1)
                + '<style:paragraph-properties fo:margin-top="0.5mm" fo:margin-bottom="0.5mm" fo:background-color="#f2f2f2" '
                  'fo:padding="1.2mm" fo:border="0.3pt solid #c8c8c8" fo:hyphenate="false" fo:orphans="2" fo:widows="2" '
                  'style:contextual-spacing="false"/>'
                  f'<style:text-properties style:font-name="{CODE_FONT}" fo:font-size="8pt" fo:hyphenate="false" '
                  f'style:font-name-asian="{CODE_FONT}" style:font-name-complex="{CODE_FONT}"/></style:style>')
    xml = re.sub(r'(<style:style style:name="Preformatted_20_Text"[^>]*>).*?</style:style>', code_style, xml, count=1, flags=re.S)
    xml = re.sub(r'(<style:style style:name="Source_Text"[^>]*>).*?</style:style>',
                 lambda m: m.group(1) + f'<style:text-properties style:font-name="{CODE_FONT}" fo:font-size="9pt" fo:hyphenate="false"/></style:style>',
                 xml, count=1, flags=re.S)
    return xml


def patch_content(xml):
    # Table cells: pandoc leaves them borderless.
    cell = '<style:table-cell-properties fo:border="0.3pt solid #9a9a9a" fo:padding="1.2mm"/>'
    head = '<style:table-cell-properties fo:border="0.3pt solid #9a9a9a" fo:padding="1.2mm" fo:background-color="#e4e4e4"/>'
    xml = re.sub(r'(<style:style style:name="TableHeaderRowCell"[^>]*>)\s*<style:table-cell-properties[^>]*/>', r'\1' + head, xml)
    xml = re.sub(r'(<style:style style:name="TableRowCell"[^>]*>)\s*<style:table-cell-properties[^>]*/>', r'\1' + cell, xml)
    return xml


def main():
    if len(sys.argv) not in (3, 4):
        die("usage: build_guide.py <guide.md> <output directory> [fr|en]")
    source, outdir = sys.argv[1], sys.argv[2]
    lang = sys.argv[3] if len(sys.argv) == 4 else "fr"
    if lang not in NOTES:
        die("language must be fr or en")
    name = os.path.splitext(os.path.basename(source))[0]
    if not os.path.isfile(source):
        die(f"{source} not found")
    if not shutil.which("pandoc"):
        die("pandoc not found (apt install pandoc)")
    soffice = find_soffice()
    os.makedirs(outdir, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        reference = os.path.join(tmp, "reference.odt")
        with open(reference, "wb") as f:
            subprocess.run(["pandoc", "--print-default-data-file", "reference.odt"], check=True, stdout=f)
        rewrite_zip(reference, {"styles.xml": patch_reference_styles})

        # Said once at the top of the reading copies only: INSTALL.md itself does not carry it.
        note = NOTES[lang].format(md=os.path.basename(source))
        reading_copy = os.path.join(tmp, os.path.basename(source))
        with open(source, encoding="utf-8") as f, open(reading_copy, "w", encoding="utf-8") as g:
            text = f.read()
            # A YAML header (title, subtitle) must stay first: pandoc turns it into the title block.
            front = re.match(r"---\n.*?\n---\n", text, re.S)
            head = front.group(0) if front else ""
            g.write(head + "\n" + note + text[len(head):])

        odt = os.path.join(outdir, name + ".odt")
        subprocess.run(["pandoc", reading_copy, "-f", "gfm+yaml_metadata_block", "-t", "odt", "--reference-doc", reference,
                        "--lua-filter", os.path.join(HERE, "tablewidths.lua"),
                        "--metadata", f"lang={lang}", "--no-highlight", "-o", odt], check=True)
        rewrite_zip(odt, {"content.xml": patch_content})

        # A throwaway profile: a LibreOffice already open on this machine would otherwise take the job.
        subprocess.run([soffice, f"-env:UserInstallation=file://{tmp}/profile", "--headless",
                        "--convert-to", "pdf", "--outdir", outdir, odt],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    pdf = os.path.join(outdir, name + ".pdf")
    if not os.path.isfile(pdf) or os.path.getsize(pdf) < 10000:
        die("the PDF was not produced")
    print(f"build_guide: {outdir}/{name}.odt and {name}.pdf")


if __name__ == "__main__":
    main()
