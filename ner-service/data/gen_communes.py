#!/usr/bin/env python3
"""Regenerate communes.txt as a tab-separated `name<TAB>department` table.

Data source: geo.api.gouv.fr (official French government commune list), which
provides each commune's name and INSEE department code. We map the code to the
canonical department name used by gazetteer.DEPARTMENTS so that downstream
region inference (department -> region) keeps working.

A commune name that occurs in more than one department is AMBIGUOUS: we keep the
name (so it still classifies as a city) but emit no department, rather than risk
inferring the wrong one. Names present in the previous communes.txt but absent
from the API (e.g. merged/historical communes) are preserved as membership-only
so nothing that matched before stops matching.

Output columns (tab-separated):
    <commune name>\t<department name>      # unambiguous
    <commune name>                          # ambiguous or unknown department

Run:  uv run python data/gen_communes.py   (from the ner-service directory)
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_FILE = os.path.join(HERE, "communes.txt")
API_URL = "https://geo.api.gouv.fr/communes?fields=nom,codeDepartement&format=json"

# INSEE department code -> canonical name. Values MUST match the keys of
# gazetteer.DEPARTMENTS exactly, or region inference will silently drop them
# (validated at the end of this script).
DEPT_CODE_TO_NAME = {
    "01": "Ain", "02": "Aisne", "03": "Allier", "04": "Alpes-de-Haute-Provence",
    "05": "Hautes-Alpes", "06": "Alpes-Maritimes", "07": "Ardèche", "08": "Ardennes",
    "09": "Ariège", "10": "Aube", "11": "Aude", "12": "Aveyron",
    "13": "Bouches-du-Rhône", "14": "Calvados", "15": "Cantal", "16": "Charente",
    "17": "Charente-Maritime", "18": "Cher", "19": "Corrèze",
    "2A": "Corse-du-Sud", "2B": "Haute-Corse",
    "21": "Côte-d'Or", "22": "Côtes-d'Armor", "23": "Creuse", "24": "Dordogne",
    "25": "Doubs", "26": "Drôme", "27": "Eure", "28": "Eure-et-Loir",
    "29": "Finistère", "30": "Gard", "31": "Haute-Garonne", "32": "Gers",
    "33": "Gironde", "34": "Hérault", "35": "Ille-et-Vilaine", "36": "Indre",
    "37": "Indre-et-Loire", "38": "Isère", "39": "Jura", "40": "Landes",
    "41": "Loir-et-Cher", "42": "Loire", "43": "Haute-Loire", "44": "Loire-Atlantique",
    "45": "Loiret", "46": "Lot", "47": "Lot-et-Garonne", "48": "Lozère",
    "49": "Maine-et-Loire", "50": "Manche", "51": "Marne", "52": "Haute-Marne",
    "53": "Mayenne", "54": "Meurthe-et-Moselle", "55": "Meuse", "56": "Morbihan",
    "57": "Moselle", "58": "Nièvre", "59": "Nord", "60": "Oise", "61": "Orne",
    "62": "Pas-de-Calais", "63": "Puy-de-Dôme", "64": "Pyrénées-Atlantiques",
    "65": "Hautes-Pyrénées", "66": "Pyrénées-Orientales", "67": "Bas-Rhin",
    "68": "Haut-Rhin", "69": "Rhône", "70": "Haute-Saône", "71": "Saône-et-Loire",
    "72": "Sarthe", "73": "Savoie", "74": "Haute-Savoie", "75": "Paris",
    "76": "Seine-Maritime", "77": "Seine-et-Marne", "78": "Yvelines",
    "79": "Deux-Sèvres", "80": "Somme", "81": "Tarn", "82": "Tarn-et-Garonne",
    "83": "Var", "84": "Vaucluse", "85": "Vendée", "86": "Vienne",
    "87": "Haute-Vienne", "88": "Vosges", "89": "Yonne", "90": "Territoire de Belfort",
    "91": "Essonne", "92": "Hauts-de-Seine", "93": "Seine-Saint-Denis",
    "94": "Val-de-Marne", "95": "Val-d'Oise",
    "971": "Guadeloupe", "972": "Martinique", "973": "Guyane",
    "974": "La Réunion", "976": "Mayotte",
}


def _accent_fold(text: str) -> str:
    import unicodedata
    return unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode().lower()


def main() -> int:
    # Allow an offline cached payload for reproducibility / no-network runs.
    cache = os.environ.get("COMMUNES_JSON")
    if cache and os.path.exists(cache):
        with open(cache, encoding="utf-8") as f:
            communes = json.load(f)
    else:
        with urllib.request.urlopen(API_URL, timeout=30) as resp:
            communes = json.load(resp)

    # folded name -> {dept names seen}; folded name -> display name (first wins)
    dept_by_name: dict[str, set[str]] = {}
    display: dict[str, str] = {}
    skipped_codes: set[str] = set()

    for c in communes:
        name = c.get("nom")
        code = c.get("codeDepartement")
        if not name:
            continue
        dept = DEPT_CODE_TO_NAME.get(code)
        if dept is None:
            skipped_codes.add(code)
            continue
        key = _accent_fold(name)
        display.setdefault(key, name)
        dept_by_name.setdefault(key, set()).add(dept)

    # Preserve any name from the existing file that the API no longer lists,
    # as membership-only (no department).
    preserved = 0
    if os.path.exists(OUT_FILE):
        with open(OUT_FILE, encoding="utf-8") as f:
            for line in f:
                old = line.split("\t", 1)[0].strip()
                if not old:
                    continue
                key = _accent_fold(old)
                if key not in display:
                    display[key] = old
                    dept_by_name.setdefault(key, set())  # empty -> no dept
                    preserved += 1

    unambiguous = ambiguous = 0
    rows: list[str] = []
    for key in sorted(display):
        name = display[key]
        depts = dept_by_name.get(key, set())
        if len(depts) == 1:
            rows.append(f"{name}\t{next(iter(depts))}")
            unambiguous += 1
        else:
            rows.append(name)  # 0 depts (preserved) or >1 (ambiguous)
            ambiguous += 1

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")

    # Validate every emitted department is a real gazetteer.DEPARTMENTS key.
    sys.path.insert(0, os.path.dirname(HERE))
    from gazetteer import DEPARTMENTS
    bad = {d for ds in dept_by_name.values() for d in ds} - set(DEPARTMENTS)
    if bad:
        print(f"ERROR: department names not in gazetteer.DEPARTMENTS: {sorted(bad)}", file=sys.stderr)
        return 1

    print(f"wrote {len(rows)} communes -> {OUT_FILE}")
    print(f"  with department:    {unambiguous}")
    print(f"  ambiguous/no dept:  {ambiguous}  (incl. {preserved} preserved from old file)")
    if skipped_codes:
        print(f"  skipped dept codes (not in table): {sorted(skipped_codes)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
