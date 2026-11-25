# French administrative geography — used to classify NER entities into
# city / department / region / country buckets.
#
# Sources: INSEE official divisions (101 departments, 18 regions incl. overseas).
# Cities are NOT listed here; any GPE entity not matched below is treated as a city.

# Maps department name -> region name
DEPARTMENTS: dict[str, str] = {
    # Auvergne-Rhône-Alpes
    "Ain": "Auvergne-Rhône-Alpes",
    "Allier": "Auvergne-Rhône-Alpes",
    "Ardèche": "Auvergne-Rhône-Alpes",
    "Cantal": "Auvergne-Rhône-Alpes",
    "Drôme": "Auvergne-Rhône-Alpes",
    "Isère": "Auvergne-Rhône-Alpes",
    "Loire": "Auvergne-Rhône-Alpes",
    "Haute-Loire": "Auvergne-Rhône-Alpes",
    "Puy-de-Dôme": "Auvergne-Rhône-Alpes",
    "Rhône": "Auvergne-Rhône-Alpes",
    "Savoie": "Auvergne-Rhône-Alpes",
    "Haute-Savoie": "Auvergne-Rhône-Alpes",
    # Bourgogne-Franche-Comté
    "Côte-d'Or": "Bourgogne-Franche-Comté",
    "Doubs": "Bourgogne-Franche-Comté",
    "Jura": "Bourgogne-Franche-Comté",
    "Nièvre": "Bourgogne-Franche-Comté",
    "Haute-Saône": "Bourgogne-Franche-Comté",
    "Saône-et-Loire": "Bourgogne-Franche-Comté",
    "Yonne": "Bourgogne-Franche-Comté",
    "Territoire de Belfort": "Bourgogne-Franche-Comté",
    # Bretagne
    "Côtes-d'Armor": "Bretagne",
    "Finistère": "Bretagne",
    "Ille-et-Vilaine": "Bretagne",
    "Morbihan": "Bretagne",
    # Centre-Val de Loire
    "Cher": "Centre-Val de Loire",
    "Eure-et-Loir": "Centre-Val de Loire",
    "Indre": "Centre-Val de Loire",
    "Indre-et-Loire": "Centre-Val de Loire",
    "Loir-et-Cher": "Centre-Val de Loire",
    "Loiret": "Centre-Val de Loire",
    # Corse
    "Corse-du-Sud": "Corse",
    "Haute-Corse": "Corse",
    # Grand Est
    "Ardennes": "Grand Est",
    "Aube": "Grand Est",
    "Marne": "Grand Est",
    "Haute-Marne": "Grand Est",
    "Meurthe-et-Moselle": "Grand Est",
    "Meuse": "Grand Est",
    "Moselle": "Grand Est",
    "Bas-Rhin": "Grand Est",
    "Haut-Rhin": "Grand Est",
    "Vosges": "Grand Est",
    # Hauts-de-France
    "Aisne": "Hauts-de-France",
    "Nord": "Hauts-de-France",
    "Oise": "Hauts-de-France",
    "Pas-de-Calais": "Hauts-de-France",
    "Somme": "Hauts-de-France",
    # Île-de-France
    "Paris": "Île-de-France",
    "Seine-et-Marne": "Île-de-France",
    "Yvelines": "Île-de-France",
    "Essonne": "Île-de-France",
    "Hauts-de-Seine": "Île-de-France",
    "Seine-Saint-Denis": "Île-de-France",
    "Val-de-Marne": "Île-de-France",
    "Val-d'Oise": "Île-de-France",
    # Normandie
    "Calvados": "Normandie",
    "Eure": "Normandie",
    "Manche": "Normandie",
    "Orne": "Normandie",
    "Seine-Maritime": "Normandie",
    # Nouvelle-Aquitaine
    "Charente": "Nouvelle-Aquitaine",
    "Charente-Maritime": "Nouvelle-Aquitaine",
    "Corrèze": "Nouvelle-Aquitaine",
    "Creuse": "Nouvelle-Aquitaine",
    "Dordogne": "Nouvelle-Aquitaine",
    "Gironde": "Nouvelle-Aquitaine",
    "Landes": "Nouvelle-Aquitaine",
    "Lot-et-Garonne": "Nouvelle-Aquitaine",
    "Pyrénées-Atlantiques": "Nouvelle-Aquitaine",
    "Deux-Sèvres": "Nouvelle-Aquitaine",
    "Vienne": "Nouvelle-Aquitaine",
    "Haute-Vienne": "Nouvelle-Aquitaine",
    # Occitanie
    "Ariège": "Occitanie",
    "Aude": "Occitanie",
    "Aveyron": "Occitanie",
    "Gard": "Occitanie",
    "Haute-Garonne": "Occitanie",
    "Gers": "Occitanie",
    "Hérault": "Occitanie",
    "Lot": "Occitanie",
    "Lozère": "Occitanie",
    "Hautes-Pyrénées": "Occitanie",
    "Pyrénées-Orientales": "Occitanie",
    "Tarn": "Occitanie",
    "Tarn-et-Garonne": "Occitanie",
    # Pays de la Loire
    "Loire-Atlantique": "Pays de la Loire",
    "Maine-et-Loire": "Pays de la Loire",
    "Mayenne": "Pays de la Loire",
    "Sarthe": "Pays de la Loire",
    "Vendée": "Pays de la Loire",
    # Provence-Alpes-Côte d'Azur
    "Alpes-de-Haute-Provence": "Provence-Alpes-Côte d'Azur",
    "Hautes-Alpes": "Provence-Alpes-Côte d'Azur",
    "Alpes-Maritimes": "Provence-Alpes-Côte d'Azur",
    "Bouches-du-Rhône": "Provence-Alpes-Côte d'Azur",
    "Var": "Provence-Alpes-Côte d'Azur",
    "Vaucluse": "Provence-Alpes-Côte d'Azur",
    # Overseas
    "Guadeloupe": "Guadeloupe",
    "Martinique": "Martinique",
    "Guyane": "Guyane",
    "La Réunion": "La Réunion",
    "Mayotte": "Mayotte",
}

REGIONS: set[str] = {
    "Auvergne-Rhône-Alpes",
    "Bourgogne-Franche-Comté",
    "Bretagne",
    "Centre-Val de Loire",
    "Corse",
    "Grand Est",
    "Hauts-de-France",
    "Île-de-France",
    "Normandie",
    "Nouvelle-Aquitaine",
    "Occitanie",
    "Pays de la Loire",
    "Provence-Alpes-Côte d'Azur",
    # Overseas regions
    "Guadeloupe",
    "Martinique",
    "Guyane",
    "La Réunion",
    "Mayotte",
}

# Country names in French (common subset for European/global enterprise docs)
COUNTRIES: set[str] = {
    "France",
    "Allemagne",
    "Espagne",
    "Italie",
    "Belgique",
    "Suisse",
    "Luxembourg",
    "Portugal",
    "Pays-Bas",
    "Royaume-Uni",
    "Irlande",
    "Danemark",
    "Suède",
    "Norvège",
    "Finlande",
    "Autriche",
    "Pologne",
    "Tchéquie",
    "Hongrie",
    "Roumanie",
    "États-Unis",
    "Canada",
    "Mexique",
    "Brésil",
    "Argentine",
    "Chine",
    "Japon",
    "Inde",
    "Australie",
    "Maroc",
    "Algérie",
    "Tunisie",
    "Sénégal",
    "Côte d'Ivoire",
}

# Maps city name -> department name.
# Covers all 101 department prefectures plus major non-prefecture cities.
# Extend this dict if a city referenced in your documents is missing.
CITIES: dict[str, str] = {
    # --- Auvergne-Rhône-Alpes ---
    "Bourg-en-Bresse": "Ain",
    "Moulins": "Allier",
    "Vichy": "Allier",
    "Privas": "Ardèche",
    "Aurillac": "Cantal",
    "Valence": "Drôme",
    "Romans-sur-Isère": "Drôme",
    "Grenoble": "Isère",
    "Bourgoin-Jallieu": "Isère",
    "Vienne": "Isère",
    "Saint-Étienne": "Loire",
    "Roanne": "Loire",
    "Le Puy-en-Velay": "Haute-Loire",
    "Clermont-Ferrand": "Puy-de-Dôme",
    "Thiers": "Puy-de-Dôme",
    "Riom": "Puy-de-Dôme",
    "Lyon": "Rhône",
    "Villeurbanne": "Rhône",
    "Vénissieux": "Rhône",
    "Bron": "Rhône",
    "Chambéry": "Savoie",
    "Annecy": "Haute-Savoie",
    "Thonon-les-Bains": "Haute-Savoie",
    # --- Bourgogne-Franche-Comté ---
    "Dijon": "Côte-d'Or",
    "Beaune": "Côte-d'Or",
    "Besançon": "Doubs",
    "Montbéliard": "Doubs",
    "Lons-le-Saunier": "Jura",
    "Nevers": "Nièvre",
    "Vesoul": "Haute-Saône",
    "Mâcon": "Saône-et-Loire",
    "Chalon-sur-Saône": "Saône-et-Loire",
    "Auxerre": "Yonne",
    "Belfort": "Territoire de Belfort",
    # --- Bretagne ---
    "Saint-Brieuc": "Côtes-d'Armor",
    "Quimper": "Finistère",
    "Brest": "Finistère",
    "Rennes": "Ille-et-Vilaine",
    "Saint-Malo": "Ille-et-Vilaine",
    "Vannes": "Morbihan",
    "Lorient": "Morbihan",
    # --- Centre-Val de Loire ---
    "Bourges": "Cher",
    "Chartres": "Eure-et-Loir",
    "Châteauroux": "Indre",
    "Tours": "Indre-et-Loire",
    "Blois": "Loir-et-Cher",
    "Orléans": "Loiret",
    # --- Corse ---
    "Ajaccio": "Corse-du-Sud",
    "Bastia": "Haute-Corse",
    # --- Grand Est ---
    "Charleville-Mézières": "Ardennes",
    "Troyes": "Aube",
    "Reims": "Marne",
    "Châlons-en-Champagne": "Marne",
    "Chaumont": "Haute-Marne",
    "Nancy": "Meurthe-et-Moselle",
    "Bar-le-Duc": "Meuse",
    "Metz": "Moselle",
    "Thionville": "Moselle",
    "Forbach": "Moselle",
    "Strasbourg": "Bas-Rhin",
    "Colmar": "Haut-Rhin",
    "Mulhouse": "Haut-Rhin",
    "Épinal": "Vosges",
    # --- Hauts-de-France ---
    "Laon": "Aisne",
    "Saint-Quentin": "Aisne",
    "Lille": "Nord",
    "Roubaix": "Nord",
    "Tourcoing": "Nord",
    "Dunkerque": "Nord",
    "Valenciennes": "Nord",
    "Beauvais": "Oise",
    "Compiègne": "Oise",
    "Arras": "Pas-de-Calais",
    "Calais": "Pas-de-Calais",
    "Boulogne-sur-Mer": "Pas-de-Calais",
    "Lens": "Pas-de-Calais",
    "Amiens": "Somme",
    # --- Île-de-France ---
    "Paris": "Paris",
    "Melun": "Seine-et-Marne",
    "Versailles": "Yvelines",
    "Évry-Courcouronnes": "Essonne",
    "Nanterre": "Hauts-de-Seine",
    "Boulogne-Billancourt": "Hauts-de-Seine",
    "Bobigny": "Seine-Saint-Denis",
    "Montreuil": "Seine-Saint-Denis",
    "Saint-Denis": "Seine-Saint-Denis",
    "Créteil": "Val-de-Marne",
    "Vincennes": "Val-de-Marne",
    "Cergy": "Val-d'Oise",
    "Argenteuil": "Val-d'Oise",
    # --- Normandie ---
    "Caen": "Calvados",
    "Évreux": "Eure",
    "Saint-Lô": "Manche",
    "Cherbourg-en-Cotentin": "Manche",
    "Alençon": "Orne",
    "Rouen": "Seine-Maritime",
    "Le Havre": "Seine-Maritime",
    "Dieppe": "Seine-Maritime",
    # --- Nouvelle-Aquitaine ---
    "Angoulême": "Charente",
    "La Rochelle": "Charente-Maritime",
    "Rochefort": "Charente-Maritime",
    "Saintes": "Charente-Maritime",
    "Tulle": "Corrèze",
    "Brive-la-Gaillarde": "Corrèze",
    "Guéret": "Creuse",
    "Périgueux": "Dordogne",
    "Bergerac": "Dordogne",
    "Bordeaux": "Gironde",
    "Mérignac": "Gironde",
    "Pessac": "Gironde",
    "Mont-de-Marsan": "Landes",
    "Dax": "Landes",
    "Agen": "Lot-et-Garonne",
    "Pau": "Pyrénées-Atlantiques",
    "Bayonne": "Pyrénées-Atlantiques",
    "Biarritz": "Pyrénées-Atlantiques",
    "Niort": "Deux-Sèvres",
    "Poitiers": "Vienne",
    "Limoges": "Haute-Vienne",
    # --- Occitanie ---
    "Foix": "Ariège",
    "Pamiers": "Ariège",
    "Carcassonne": "Aude",
    "Narbonne": "Aude",
    "Rodez": "Aveyron",
    "Nîmes": "Gard",
    "Alès": "Gard",
    "Toulouse": "Haute-Garonne",
    "Auch": "Gers",
    "Montpellier": "Hérault",
    "Béziers": "Hérault",
    "Sète": "Hérault",
    "Cahors": "Lot",
    "Mende": "Lozère",
    "Tarbes": "Hautes-Pyrénées",
    "Perpignan": "Pyrénées-Orientales",
    "Albi": "Tarn",
    "Castres": "Tarn",
    "Montauban": "Tarn-et-Garonne",
    # --- Pays de la Loire ---
    "Nantes": "Loire-Atlantique",
    "Saint-Nazaire": "Loire-Atlantique",
    "Angers": "Maine-et-Loire",
    "Laval": "Mayenne",
    "Le Mans": "Sarthe",
    "La Roche-sur-Yon": "Vendée",
    "Les Sables-d'Olonne": "Vendée",
    # --- Provence-Alpes-Côte d'Azur ---
    "Digne-les-Bains": "Alpes-de-Haute-Provence",
    "Gap": "Hautes-Alpes",
    "Nice": "Alpes-Maritimes",
    "Cannes": "Alpes-Maritimes",
    "Antibes": "Alpes-Maritimes",
    "Grasse": "Alpes-Maritimes",
    "Marseille": "Bouches-du-Rhône",
    "Aix-en-Provence": "Bouches-du-Rhône",
    "Arles": "Bouches-du-Rhône",
    "Toulon": "Var",
    "Fréjus": "Var",
    "Hyères": "Var",
    "Avignon": "Vaucluse",
    # --- Overseas ---
    "Basse-Terre": "Guadeloupe",
    "Pointe-à-Pitre": "Guadeloupe",
    "Fort-de-France": "Martinique",
    "Cayenne": "Guyane",
    "Saint-Denis-de-la-Réunion": "La Réunion",
    "Mamoudzou": "Mayotte",
}


import os
import re
import unicodedata

# French prepositions/articles that spaCy sometimes includes in location spans:
# "de l'Ariège", "du Nord", "de la Loire", "en Occitanie", "au Maroc", …
_PREP = re.compile(
    r"^(?:de\s+l['']\s*|de\s+la\s+|du\s+|des\s+|de\s+|en\s+|au\s+|aux\s+|à\s+)",
    re.IGNORECASE,
)

_URL_SCHEME   = re.compile(r'https?://', re.IGNORECASE)  # http(s):// anywhere
_DOMAIN       = re.compile(r'\.\w{2,4}(/|$)')            # .fr .com .be at boundary
_ABBREV       = re.compile(r'^\w\.')                      # A.Bo  J.Cl  P.Ta
_LOWER_START  = re.compile(r'^[a-z]')                    # URL slug or common noun
_DIGIT_START  = re.compile(r'^\d')                       # "20 Minutes", "13 novembre"
_WHITESPACE   = re.compile(r'\s+')                        # collapse internal whitespace
_HAS_LETTER   = re.compile(r'[a-zA-ZÀ-ÿ]')              # at least one real letter

# Common French proper nouns that spaCy mis-tags as GPE/LOC on noisy pages.
_NON_PLACE_STOPLIST: frozenset[str] = frozenset({
    "Minutes", "Figaro", "Express", "Parisien", "Libre", "Monde",
    "Euronews", "Allah", "Akbar", "Bataclan",
})

# ---------------------------------------------------------------------------
# INSEE communes list
# ---------------------------------------------------------------------------

def _accent_fold(text: str) -> str:
    """Lowercase + strip diacritics for accent-tolerant commune lookup."""
    return unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode().lower()


def _load_insee_communes() -> dict[str, str | None]:
    """Load communes.txt into a {folded name -> department name | None} map.

    Each line is `name` or `name<TAB>department` (see data/gen_communes.py).
    A None value means the commune is known but its department is ambiguous
    (the name occurs in several departments) — membership only, no inference.
    """
    data_file = os.path.join(os.path.dirname(__file__), "data", "communes.txt")
    communes: dict[str, str | None] = {}
    try:
        with open(data_file, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line.strip():
                    continue
                name, _, dept = line.partition("\t")
                communes[_accent_fold(name.strip())] = dept.strip() or None
    except FileNotFoundError:
        return {}
    return communes


_INSEE_COMMUNES: dict[str, str | None] = _load_insee_communes()


def _in_insee(name: str) -> bool:
    return _accent_fold(name) in _INSEE_COMMUNES


def insee_department(name: str) -> str | None:
    """Department for an INSEE commune, or None if unknown/ambiguous."""
    return _INSEE_COMMUNES.get(_accent_fold(name))


def city_department(name: str) -> str | None:
    """Resolve a city to its department, preferring the curated CITIES dict and
    falling back to the full INSEE commune list. None when no unambiguous match."""
    return CITIES.get(name) or insee_department(name)


def is_plausible_place(text: str) -> bool:
    """Return False for strings that are obviously not place names.

    Catches URL fragments, domain names, dotted abbreviations, tokens starting
    with a lowercase letter or digit, strings that are too short or too long,
    strings with no letter characters, and known mis-tagged proper nouns.
    Known gazetteer entries always pass (uppercase start, no URL artefacts).
    """
    if len(text) < 3:               return False   # "A", "WC", "□"
    if len(text.split()) > 4:       return False   # multi-word product names
    if not _HAS_LETTER.search(text): return False  # pure symbols / digits
    if _URL_SCHEME.search(text):    return False
    if _DOMAIN.search(text):        return False
    if _ABBREV.match(text):         return False
    if _LOWER_START.match(text):    return False
    if _DIGIT_START.match(text):    return False
    if text in _NON_PLACE_STOPLIST: return False
    return True


def normalize(text: str) -> str:
    """Strip leading French prepositions/articles and collapse internal whitespace."""
    text = _WHITESPACE.sub(" ", text)
    return _PREP.sub("", text).strip()


def classify(text: str) -> str | None:
    """
    Returns one of "country", "region", "department", "city", or None.
    None means the entity was not recognised as a valid place name and should
    be discarded — this happens when the name is not in any known gazetteer
    (CITIES/DEPARTMENTS/REGIONS/COUNTRIES) and not in the INSEE communes list.

    Expects already-normalised text (see normalize()).

    CITIES is checked before DEPARTMENTS so that names belonging to both dicts
    (notably "Paris", which is both a commune and a department) are treated as a
    city. The city path then infers the department via CITIES[name], which in turn
    infers the region via DEPARTMENTS[dept], giving the full hierarchy.
    """
    if text in COUNTRIES:
        return "country"
    if text in REGIONS:
        return "region"
    if text in CITIES:
        return "city"
    if text in DEPARTMENTS:
        return "department"
    if _in_insee(text):
        return "city"
    return None
